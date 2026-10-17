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
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
 * 1. pick a name: the original, or "name (n).ext" if a file of another size has it
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
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult {
        mkdirs(directory)
        val dir = ShareDirectory(share, directory)

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

    /**
     * Sends the file without waiting for each write's reply before the next. The router
     * takes at most 64 KB per write, and waiting a round trip after every one capped the
     * phone at about 5 MB/s where a Mac with the same router reached 13 MB/s.
     *
     * Progress counts only bytes the server has confirmed, so 100 % means it has them all.
     */
    private fun writeTemp(path: String, local: LocalFile, onProgress: (Long, Long) -> Unit): Long {
        var confirmed = 0L
        share.openFile(
            RemotePath.toSmb(path),
            setOf(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            local.open().use { input ->
                val pending = ArrayDeque<PendingWrite>()
                val spare = ArrayDeque<ByteArray>()

                fun settle(w: PendingWrite) {
                    val written = try {
                        w.result.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (e: ExecutionException) {
                        // A refused write comes back from the async path as a bare "wrote
                        // 0 bytes". Writing it again the plain way either succeeds or throws
                        // with the server's status, so a full disk is still told apart.
                        file.write(w.buffer, w.offset, 0, w.length)
                    }
                    if (written != w.length.toLong()) {
                        throw IOException("short write at ${w.offset}: $written of ${w.length}")
                    }
                    confirmed += w.length
                    spare += w.buffer
                    onActivity()
                    onProgress(confirmed, local.size)
                }

                onProgress(0, local.size)
                var queued = 0L
                while (true) {
                    val buf = spare.removeFirstOrNull() ?: ByteArray(CHUNK_BYTES)
                    val n = readFully(input, buf)
                    if (n == 0) break
                    pending += PendingWrite(buf, queued, n, file.writeAsync(buf, queued, 0, n))
                    queued += n
                    if (pending.size >= MAX_PENDING) settle(pending.removeFirst())
                }
                while (pending.isNotEmpty()) settle(pending.removeFirst())
            }
        }
        return confirmed
    }

    /** One chunk on its way; [buffer] must not be reused until [result] is done. */
    private class PendingWrite(
        val buffer: ByteArray,
        val offset: Long,
        val length: Int,
        val result: Future<Long>,
    )

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
        /** smbj splits each chunk into writes of the size the server allows (64 KB here). */
        const val CHUNK_BYTES = 1 shl 20

        /** Chunks in flight: up to 3 MB, about 48 writes, before waiting for the oldest. */
        const val MAX_PENDING = 3

        /** Longer than the router's pauses while it flushes to its disk (about 30 s). */
        const val WRITE_TIMEOUT_SECONDS = 90L
        const val RENAME_ATTEMPTS = 3

        fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    }
}

/** Fills [buf] as far as the stream allows; 0 at the end. */
private fun readFully(input: InputStream, buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = input.read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** [RemoteDirectory] backed by a directory on the share. */
private class ShareDirectory(
    private val share: DiskShare,
    private val directory: String,
) : RemoteDirectory {

    override fun sizeOf(name: String): Long? {
        val entry = share.stat(RemotePath.toSmb(if (directory.isEmpty()) name else "$directory/$name"))
            ?: return null
        return if (entry.isDirectory) -1L else entry.size
    }
}
