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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** Bytes of the item in hand sent so far, out of [size]. */
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

    /** Ended by [SyncRunner.stop]; items before it are recorded. */
    data class Stopped(val summary: SyncSummary) : SyncOutcome
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

    /** The pass in progress, so [stop] can end it without touching the worker running it. */
    @Volatile private var pass: Job? = null
    @Volatile private var stopRequested = false

    /** One backup pass, or null if another pass is already running. */
    suspend fun run(): SyncOutcome? {
        if (!lock.tryLock()) return null
        try {
            _state.value = RunState.Preparing
            stopRequested = false
            val tally = Tally()
            return coroutineScope {
                val job = async { runPass(tally) }
                pass = job
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // Cancelling the child leaves this scope running. If the worker itself
                    // is being cancelled (WorkManager stopping it), let that through.
                    ensureActive()
                    if (!stopRequested) throw e
                    SyncOutcome.Stopped(tally.summary)
                } finally {
                    pass = null
                }
            }
        } finally {
            _state.value = RunState.Idle
            lock.unlock()
        }
    }

    /**
     * Ends the pass in progress, if any, as promptly as a cancellation does (the item in
     * flight is dropped; its temporary file is cleaned up later). Only this pass: the
     * periodic schedule is left alone, so the next period runs as usual.
     */
    fun stop() {
        val job = pass ?: return
        stopRequested = true
        job.cancel()
    }

    /** Uploaded records, for the settings screen. */
    suspend fun uploadedCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    /**
     * Forgets every upload, e.g. after the share's disk was replaced. Refused (false)
     * while a pass runs, since it would record items again as it goes.
     */
    suspend fun forgetUploads(): Boolean {
        if (!lock.tryLock()) return false
        try {
            withContext(Dispatchers.IO) { dao.clear() }
            return true
        } finally {
            lock.unlock()
        }
    }

    /** What a pass has done so far, readable after it was stopped. */
    private class Tally {
        @Volatile var summary = SyncSummary()
    }

    private suspend fun runPass(tally: Tally): SyncOutcome {
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

        var seenServerId: UUID? = null
        val totalBytes = pending.sumOf { it.size }
        var doneBytes = 0L
        // Bytes actually written, for the rate. doneBytes also jumps over items that were
        // already on the share or failed.
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
                        fun report(sent: Long, size: Long) {
                            moved += sent - last
                            last = sent
                            val left = (totalBytes - doneBytes - sent).coerceAtLeast(0)
                            _state.value = RunState.Sending(
                                SyncProgress(
                                    index, pending.size, name, sent, size, doneBytes, totalBytes,
                                    meter.remainingMillis(clock(), moved, left),
                                ),
                            )
                        }
                        // Shown even when the item turns out to be on the share already.
                        report(0, item.size)
                        try {
                            val result = sendOne(uploader, item, name, s.folder, ::report)
                            dao.upsert(
                                UploadedRecord(item.id, item.size, item.dateModifiedSeconds, result.path, clock()),
                            )
                            val done = tally.summary
                            tally.summary = when (result) {
                                is UploadResult.Uploaded -> done.copy(uploaded = done.uploaded + 1)
                                is UploadResult.AlreadyPresent -> done.copy(alreadyPresent = done.alreadyPresent + 1)
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
                            tally.summary = tally.summary.copy(
                                failed = tally.summary.failed + 1,
                                lastItemError = ItemError(name, describe(e)),
                            )
                        } finally {
                            doneBytes += item.size
                        }
                    }
                }
            }
        } catch (e: SmbFailureException) {
            return SyncOutcome.Aborted(e.failure, tally.summary)
        } finally {
            // Settings saved before server IDs were remembered: trust the first server we
            // log in to, as the connection test would have.
            val id = seenServerId
            if (id != null && target.expectedServerId == null) {
                withContext(NonCancellable) { settings.rememberServer(s.host, id) }
            }
        }
        currentCoroutineContext().ensureActive()
        return SyncOutcome.Finished(tally.summary)
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
        onProgress: (Long, Long) -> Unit,
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
