package io.github.poporpepopo.photobackup.backup

import android.content.Context
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaItem
import io.github.poporpepopo.photobackup.media.MediaPermissions
import io.github.poporpepopo.photobackup.media.MediaStoreSource
import io.github.poporpepopo.photobackup.settings.SettingsRepository
import io.github.poporpepopo.photobackup.settings.TargetResult
import io.github.poporpepopo.photobackup.smb.HostProbe
import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import io.github.poporpepopo.photobackup.smb.SmbGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId

data class SyncProgress(
    /** 0-based index of the item being sent, out of [total]. */
    val index: Int,
    val total: Int,
    val name: String,
    val sent: Long,
    val size: Long,
)

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

enum class NotReadyReason { NoPermission, NotConfigured, PasswordUnreadable }

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
    private val media: MediaStoreSource = MediaStoreSource(context),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Items still to send under the current settings. Reads only; no network. */
    suspend fun pending(includeVideos: Boolean): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val items = media.query(includeVideos)
        val records = dao.all().associate { it.mediaId to it.version }
        val pending = SyncPlanner.pending(items, records, MediaItem::version)
        (items.size - pending.size) to pending.size
    }

    suspend fun run(onProgress: (SyncProgress) -> Unit = {}): SyncOutcome {
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
        if (!HostProbe.isReachable(s.host)) return SyncOutcome.NotHome

        val pending = withContext(Dispatchers.IO) {
            val items = media.query(s.includeVideos)
            val records = dao.all().associate { it.mediaId to it.version }
            SyncPlanner.pending(items, records, MediaItem::version)
        }
        if (pending.isEmpty()) return SyncOutcome.Finished(SyncSummary())

        val job = currentCoroutineContext().job
        var summary = SyncSummary()
        try {
            SmbGateway.withShare(ready.target) { share ->
                val uploader = Uploader(share.disk)
                for ((index, item) in pending.withIndex()) {
                    if (!job.isActive) break
                    val name = Destination.sanitizeFileName(item.displayName, "media_${item.id}")
                    try {
                        val result = sendOne(uploader, item, name, s.folder) { sent, size ->
                            onProgress(SyncProgress(index, pending.size, name, sent, size))
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
                        // A dead connection ends the pass; anything else is this file's
                        // problem (deleted meanwhile, a name the server rejects, ...).
                        if (e !is LocalReadException && !share.isAlive) throw e
                        summary = summary.copy(
                            failed = summary.failed + 1,
                            lastItemError = ItemError(name, describe(e)),
                        )
                    }
                }
            }
        } catch (e: SmbFailureException) {
            return SyncOutcome.Aborted(e.failure, summary)
        }
        currentCoroutineContext().ensureActive()
        return SyncOutcome.Finished(summary)
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
        val directory = Destination.directory(folder, item.dateTakenMillis, item.dateAddedSeconds, zone())
        return uploader.upload(file, directory, onProgress)
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
}

val MediaItem.version get() = MediaVersion(id, size, dateModifiedSeconds)
