package io.github.poporpepopo.photobackup.backup

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import io.github.poporpepopo.photobackup.smb.RemotePath
import io.github.poporpepopo.photobackup.smb.stat
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

sealed interface UploadResult {
    /** Share-relative "/"-separated path of the file as it now exists on the share. */
    val path: String

    data class Uploaded(override val path: String, val bytes: Long) : UploadResult
    data class AlreadyPresent(override val path: String) : UploadResult
}

/** One local file to send. [open] is called each time the bytes are needed. */
class LocalFile(
    val mediaId: Long,
    val name: String,
    val size: Long,
    val open: () -> InputStream,
)

/** What the bytes reported by [Uploader.upload]'s progress are doing. */
enum class Transfer {
    /** Reading back a same-sized file on the share to see whether it is this item. */
    Comparing,

    /** As [Comparing], but only the parts [ContentSample] picks, for a large file. */
    Sampling,
    Sending,
}

/** Told when a temporary file may be left on the share and when it is gone again. */
interface TempFiles {
    fun created(path: String)
    fun removed(path: String)

    object Untracked : TempFiles {
        override fun created(path: String) {}
        override fun removed(path: String) {}
    }
}

/**
 * Writes one file to the share so that the final name only ever holds a complete copy:
 *
 * 1. pick a name: the original, or "name (n).ext" if a different file has it
 * 2. write everything to a hidden temporary file
 * 3. check the temporary file's size on the server against the bytes we sent
 * 4. rename it to the chosen name, refusing to overwrite
 *
 * If the connection drops mid-way, at worst a ".photobackup-<id>.part" file is left
 * behind. [temps] hears about it so a later pass can remove it with [removeLeftover].
 */
class Uploader(
    private val share: DiskShare,
    /** Called whenever bytes move, so a caller can tell a slow transfer from a hung one. */
    private val onActivity: () -> Unit = {},
    private val temps: TempFiles = TempFiles.Untracked,
) {

    fun upload(
        local: LocalFile,
        directory: String,
        onProgress: (transfer: Transfer, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ): UploadResult {
        mkdirs(directory)
        val dir = ShareDirectory(share, directory, local, onActivity, onProgress)

        var resolution = NameResolver.resolve(dir, local.name, local.size)
        if (resolution is NameResolution.AlreadyPresent) {
            return UploadResult.AlreadyPresent(join(directory, resolution.name))
        }

        val temp = join(directory, Destination.tempName(local.mediaId))
        temps.created(temp)
        try {
            val sent = writeTemp(temp, local, onProgress)
            val onServer = share.stat(RemotePath.toSmb(temp))?.size
            if (sent != local.size || onServer != sent) {
                throw IOException("size mismatch: expected ${local.size}, sent $sent, on server $onServer")
            }
            // The chosen name can be taken between resolving and renaming (another device
            // backing up to the same folder). Resolve again and retry a few times.
            repeat(RENAME_ATTEMPTS) {
                when (val r = resolution) {
                    is NameResolution.AlreadyPresent -> {
                        deleteTemp(temp)
                        return UploadResult.AlreadyPresent(join(directory, r.name))
                    }
                    is NameResolution.Upload -> {
                        val target = join(directory, r.name)
                        if (rename(temp, target)) {
                            temps.removed(temp)
                            return UploadResult.Uploaded(target, sent)
                        }
                    }
                }
                resolution = NameResolver.resolve(dir, local.name, local.size)
            }
            throw IOException("could not find a free name for ${local.name}")
        } catch (e: Exception) {
            deleteTemp(temp)
            throw e
        }
    }

    /**
     * Deletes a temporary file an earlier pass left behind. Refuses anything that is not
     * one of our ".part" names, so a bad record can never delete a real file. True when the
     * file is gone (deleted now, or already missing).
     */
    fun removeLeftover(path: String): Boolean {
        if (!Destination.isTempName(path.substringAfterLast('/'))) return true
        val smb = RemotePath.toSmb(path)
        return try {
            share.rm(smb)
            true
        } catch (e: SMBApiException) {
            // Servers word "it is not there" differently; ask the way that handles them all.
            share.stat(smb) == null
        }
    }

    /** Deletes [path] if the connection still allows; otherwise it stays recorded. */
    private fun deleteTemp(path: String) {
        if (runCatching { removeLeftover(path) }.getOrDefault(false)) temps.removed(path)
    }

    /** Creates [path] and its parents, tolerating ones that already exist. */
    private fun mkdirs(path: String) {
        if (path.isEmpty()) return
        var current = ""
        for (segment in path.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val smb = RemotePath.toSmb(current)
            val entry = share.stat(smb)
            if (entry != null) {
                if (entry.isDirectory) continue
                throw IOException("$current exists and is not a folder")
            }
            try {
                share.mkdir(smb)
            } catch (e: SMBApiException) {
                if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
            }
        }
    }

    private fun writeTemp(
        path: String,
        local: LocalFile,
        onProgress: (Transfer, Long, Long) -> Unit,
    ): Long {
        var sent = 0L
        share.openFile(
            RemotePath.toSmb(path),
            setOf(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            file.outputStream.use { out ->
                local.open().use { input ->
                    val buf = ByteArray(BUFFER_BYTES)
                    onProgress(Transfer.Sending, 0, local.size)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onActivity()
                        onProgress(Transfer.Sending, sent, local.size)
                    }
                }
            }
        }
        return sent
    }

    /** False if [to] already exists; any other failure is thrown. */
    private fun rename(from: String, to: String): Boolean {
        openForRename(from).use { file ->
            return try {
                file.rename(RemotePath.toSmb(to), false)
                true
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) false else throw e
            }
        }
    }

    private fun openForRename(path: String): File = share.openFile(
        RemotePath.toSmb(path),
        setOf(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
        const val RENAME_ATTEMPTS = 3

        fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    }
}

/** [RemoteDirectory] backed by a directory on the share, comparing against [local]. */
private class ShareDirectory(
    private val share: DiskShare,
    private val directory: String,
    private val local: LocalFile,
    private val onActivity: () -> Unit,
    private val onCompared: (Transfer, done: Long, total: Long) -> Unit,
) : RemoteDirectory {

    private fun smbPath(name: String) =
        RemotePath.toSmb(if (directory.isEmpty()) name else "$directory/$name")

    override fun sizeOf(name: String): Long? {
        val entry = share.stat(smbPath(name)) ?: return null
        return if (entry.isDirectory) -1L else entry.size
    }

    override fun sameContentAsLocal(name: String): Boolean =
        share.openFile(
            smbPath(name),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            val ranges = ContentSample.ranges(local.size)
            val total = ranges.sumOf { (start, end) -> end - start }
            val transfer = if (ContentSample.isPartial(local.size)) Transfer.Sampling else Transfer.Comparing
            var compared = 0L
            local.open().use { mine ->
                var at = 0L
                for ((start, end) in ranges) {
                    skipFully(mine, start - at)
                    // Not file.inputStream: it reads ahead past the end of the file, and
                    // some servers answer that read with a reply smbj cannot parse. Read
                    // exactly the range with positional reads.
                    val remote = BoundedInputStream(end - start, start) { buf, pos, off, len ->
                        file.read(buf, pos, off, len).also { n ->
                            onActivity()
                            if (n > 0) {
                                compared += n
                                // Reported against the whole file, so the pass's byte count
                                // moves on by the item's size when it is done.
                                onCompared(transfer, local.size * compared / total, local.size)
                            }
                        }
                    }
                    // [mine] is sequential and already at [start].
                    val ours = BoundedInputStream(end - start, start) { buf, _, off, len -> mine.read(buf, off, len) }
                    if (!streamsEqual(remote, ours)) return@use false
                    at = end
                }
                // The ranges end at local.size; a longer file here is not the same file.
                mine.read() < 0
            }
        }
}

/** Skips [n] bytes; a file stream does it by seeking, without reading them. */
private fun skipFully(input: InputStream, n: Long) {
    var left = n
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (input.read() < 0) throw EOFException("local file ended early")
            left--
        }
    }
}

/**
 * Exposes [size] bytes from [start] behind [readAt] (a positional read: buffer, file
 * offset, buffer offset, length) as an InputStream, never asking for anything past them.
 */
internal class BoundedInputStream(
    size: Long,
    start: Long = 0,
    private val readAt: (ByteArray, Long, Int, Int) -> Int,
) : InputStream() {
    private var position = start
    private val end = start + size

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= end) return -1
        val n = readAt(b, position, off, minOf(len.toLong(), end - position).toInt())
        if (n <= 0) return -1
        position += n
        return n
    }
}

internal fun streamsEqual(a: InputStream, b: InputStream): Boolean {
    val bufA = ByteArray(64 * 1024)
    val bufB = ByteArray(64 * 1024)
    while (true) {
        val n = readFully(a, bufA)
        val m = readFully(b, bufB)
        if (n != m) return false
        if (n == 0) return true
        for (i in 0 until n) if (bufA[i] != bufB[i]) return false
    }
}

/** Fills [buf] as far as the stream allows; SMB reads return short chunks. */
private fun readFully(input: InputStream, buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = input.read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
