package io.github.poporpepopo.photobackup.backup

import android.content.Context
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaItem
import io.github.poporpepopo.photobackup.media.MediaPermissions
import io.github.poporpepopo.photobackup.media.MediaStoreSource
import io.github.poporpepopo.photobackup.settings.SettingsRepository
import io.github.poporpepopo.photobackup.settings.TargetResult
import io.github.poporpepopo.photobackup.smb.HostProbe
import io.github.poporpepopo.photobackup.smb.LocalNetworkAccess
import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import io.github.poporpepopo.photobackup.smb.SmbGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class SyncProgress(
    /** 0-based index of the item in hand, out of [total]. */
    val index: Int,
    val total: Int,
    val name: String,
    val transfer: Transfer,
    /** Bytes of the item in hand compared or sent so far, out of [size]. */
    val sent: Long,
    val size: Long,
    /** Bytes of the items before this one, out of all items in the pass. */
    val doneBytes: Long,
    val totalBytes: Long,
    /** Time the rest of the pass should take; null until there is enough to go on. */
    val remainingMillis: Long?,
) {
    /** The item in hand, in 0..1. */
    val file: Float
        get() = if (size > 0) (sent.toFloat() / size).coerceIn(0f, 1f) else 1f

    /** The whole pass by bytes, in 0..1, so a few large videos are not one item each. */
    val overall: Float
        get() = if (totalBytes > 0) {
            ((doneBytes + sent).toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            (index + file) / total
        }
}

sealed interface RunState {
    data object Idle : RunState

    /** Checking settings, the router and what is pending. */
    data object Preparing : RunState

    data class Sending(val progress: SyncProgress) : RunState
}

data class ItemError(val name: String, val detail: String)

data class SyncSummary(
    val uploaded: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    val lastItemError: ItemError? = null,
)

sealed interface SyncOutcome {
    /** Every pending item was attempted (some may have failed individually). */
    data class Finished(val summary: SyncSummary) : SyncOutcome

    /** The router did not answer on 445: we are not at home. Nothing was attempted. */
    data object NotHome : SyncOutcome

    data class NotReady(val reason: NotReadyReason) : SyncOutcome

    /** The connection failed part-way; items before it are recorded. */
    data class Aborted(val failure: SmbFailure, val summary: SyncSummary) : SyncOutcome
}

enum class NotReadyReason { NoPermission, NoLocalNetwork, NotConfigured, PasswordUnreadable }

/** Thrown when the phone-side file cannot be read, so it can be told apart from SMB errors. */
class LocalReadException(cause: Throwable) : IOException(cause.message, cause)

/**
 * One backup pass: everything in MediaStore that is not recorded as uploaded is sent over a
 * single SMB connection, newest first, and recorded as soon as it is safely on the share.
 * An interrupted pass therefore loses at most the item in flight.
 */
class SyncRunner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val dao: UploadedDao,
    private val partials: PartialUploadDao,
    private val media: MediaStoreSource = MediaStoreSource(context),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // The periodic job and "Sync now" are separate WorkManager jobs and can overlap. Two
    // passes would race on the same temporary file names, so only one runs at a time.
    private val lock = Mutex()
    private val _state = MutableStateFlow<RunState>(RunState.Idle)

    /** What the pass in progress (if any) is doing, for the screen and the notification. */
    val state: StateFlow<RunState> = _state.asStateFlow()

    /** Items still to send under the current settings. Reads only; no network. */
    suspend fun pending(includeVideos: Boolean): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val items = media.query(includeVideos)
        val records = dao.all().associate { it.mediaId to it.version }
        val pending = SyncPlanner.pending(items, records, MediaItem::version)
        (items.size - pending.size) to pending.size
    }

    /** One backup pass, or null if another pass is already running. */
    suspend fun run(): SyncOutcome? {
        if (!lock.tryLock()) return null
        try {
            _state.value = RunState.Preparing
            return runPass()
        } finally {
            _state.value = RunState.Idle
            lock.unlock()
        }
    }

    private suspend fun runPass(): SyncOutcome {
        val ready = when (val t = settings.target()) {
            is TargetResult.Ready -> t
            TargetResult.NotConfigured -> return SyncOutcome.NotReady(NotReadyReason.NotConfigured)
            TargetResult.PasswordUnreadable ->
                return SyncOutcome.NotReady(NotReadyReason.PasswordUnreadable)
        }
        val s = ready.settings
        val permission = MediaPermissions.state(context, s.includeVideos)
        if (permission.access == MediaAccess.None || !permission.location) {
            return SyncOutcome.NotReady(NotReadyReason.NoPermission)
        }
        if (!LocalNetworkAccess.granted(context)) {
            return SyncOutcome.NotReady(NotReadyReason.NoLocalNetwork)
        }
        if (!HostProbe.isReachable(s.host)) return SyncOutcome.NotHome
        val target = ready.target

        val pending = withContext(Dispatchers.IO) {
            val items = media.query(s.includeVideos)
            val records = dao.all().associate { it.mediaId to it.version }
            SyncPlanner.pending(items, records, MediaItem::version)
        }
        val leftovers = withContext(Dispatchers.IO) { partials.all() }
        if (pending.isEmpty() && leftovers.isEmpty()) return SyncOutcome.Finished(SyncSummary())

        var summary = SyncSummary()
        var seenServerId: UUID? = null
        val totalBytes = pending.sumOf { it.size }
        var doneBytes = 0L
        // Progress made by reading back or writing, for the rate. A sampled comparison
        // counts as the whole file. doneBytes also jumps over items that failed.
        var moved = 0L
        val meter = RateMeter()
        try {
            withStallGuard(STALL_LIMIT_MS) { touch ->
                val job = currentCoroutineContext().job
                SmbGateway.withShare(target) { share ->
                    seenServerId = share.serverId
                    val uploader = Uploader(share.disk, onActivity = touch, temps = tempRecords)
                    // Leftovers of interrupted uploads. A retry of the same item would reuse
                    // its .part anyway, but not if the item was deleted from the phone,
                    // excluded (videos switched off) or now goes to another month folder.
                    for (path in leftovers) {
                        if (!job.isActive) break
                        touch()
                        if (runCatching { uploader.removeLeftover(path) }.getOrDefault(false)) {
                            partials.remove(path)
                        }
                    }
                    for ((index, item) in pending.withIndex()) {
                        if (!job.isActive) break
                        touch()
                        val name = Destination.sanitizeFileName(item.displayName, "media_${item.id}")
                        var last = 0L
                        try {
                            val result = sendOne(uploader, item, name, s.folder) { transfer, sent, size ->
                                // Each comparison and the upload count from 0 again.
                                moved += if (sent >= last) sent - last else sent
                                last = sent
                                val left = (totalBytes - doneBytes - sent).coerceAtLeast(0)
                                _state.value = RunState.Sending(
                                    SyncProgress(
                                        index, pending.size, name, transfer, sent, size,
                                        doneBytes, totalBytes,
                                        meter.remainingMillis(clock(), moved, left),
                                    ),
                                )
                            }
                            dao.upsert(
                                UploadedRecord(item.id, item.size, item.dateModifiedSeconds, result.path, clock()),
                            )
                            summary = when (result) {
                                is UploadResult.Uploaded -> summary.copy(uploaded = summary.uploaded + 1)
                                is UploadResult.AlreadyPresent ->
                                    summary.copy(alreadyPresent = summary.alreadyPresent + 1)
                            }
                        } catch (e: Exception) {
                            // Cancelled (interrupted mid-call) or a dead connection ends the
                            // pass; anything else is this file's problem (deleted meanwhile,
                            // a name the server rejects, ...).
                            if (!job.isActive) throw e
                            if (e !is LocalReadException && !share.isAlive) throw e
                            // A full disk fails every remaining item; stop and say so once.
                            if (SmbFailure.classify(e) == SmbFailure.DiskFull) {
                                throw SmbFailureException(SmbFailure.DiskFull, e)
                            }
                            summary = summary.copy(
                                failed = summary.failed + 1,
                                lastItemError = ItemError(name, describe(e)),
                            )
                        } finally {
                            doneBytes += item.size
                        }
                    }
                }
            }
        } catch (e: SmbFailureException) {
            return SyncOutcome.Aborted(e.failure, summary)
        } finally {
            // Settings saved before server IDs were remembered: trust the first server we
            // log in to, as the connection test would have.
            val id = seenServerId
            if (id != null && target.expectedServerId == null) {
                withContext(NonCancellable) { settings.rememberServer(s.host, id) }
            }
        }
        currentCoroutineContext().ensureActive()
        return SyncOutcome.Finished(summary)
    }

    private val tempRecords = object : TempFiles {
        override fun created(path: String) = partials.add(PartialUpload(path))
        override fun removed(path: String) = partials.remove(path)
    }

    private fun sendOne(
        uploader: Uploader,
        item: MediaItem,
        name: String,
        folder: String,
        onProgress: (Transfer, Long, Long) -> Unit,
    ): UploadResult {
        val size = local { media.originalSize(item) }
        val file = LocalFile(item.id, name, size) { local { media.openOriginal(item) } }
        val directory = Destination.directory(
            folder,
            item.dateTakenMillis,
            item.dateAddedSeconds,
            zone(),
            exifMonth = exifMonth(item),
        )
        return uploader.upload(file, directory, onProgress)
    }

    /** Only asked when MediaStore has no capture time; an unreadable EXIF just falls back. */
    private fun exifMonth(item: MediaItem): YearMonth? {
        if (item.isVideo || item.dateTakenMillis != null) return null
        return runCatching { Destination.exifYearMonth(media.exifCaptureDate(item)) }.getOrNull()
    }

    private inline fun <T> local(read: () -> T): T =
        try {
            read()
        } catch (e: Exception) {
            throw LocalReadException(e)
        }

    private fun describe(e: Throwable): String = when (val f = SmbFailure.classify(e)) {
        is SmbFailure.Other -> f.detail
        else -> f.toString()
    }

    private companion object {
        /** Longer than smbj's own request timeouts (30–60 s), so those fire first. */
        const val STALL_LIMIT_MS = 2 * 60_000L
    }
}

val MediaItem.version get() = MediaVersion(id, size, dateModifiedSeconds)
