package io.github.poporpepopo.photobackup.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare

class RemoteEntry(val isDirectory: Boolean, val size: Long)

/**
 * Type and size of [smbPath], or null if nothing is there — in one round trip, unlike
 * smbj's folderExists + fileExists + getFileInformation.
 *
 * Servers disagree on how to say "not found": Samba and Windows use OBJECT_NAME_NOT_FOUND
 * or OBJECT_PATH_NOT_FOUND, some others NO_SUCH_FILE. smbj's own exists() checks do not
 * accept the last one, so we classify it ourselves.
 */
fun DiskShare.stat(smbPath: String): RemoteEntry? = try {
    val info = getFileInformation(smbPath).standardInformation
    RemoteEntry(info.isDirectory, info.endOfFile)
} catch (e: SMBApiException) {
    if (e.status in NOT_FOUND) null else throw e
}

private val NOT_FOUND = setOf(
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
    NtStatus.STATUS_NO_SUCH_FILE,
)
