package io.github.poporpepopo.photobackup.smb

/**
 * Paths inside a share are kept "/"-separated throughout the app and only turned into
 * SMB's backslash form at the smbj boundary, in [toSmb].
 */
object RemotePath {

    /** "\\Backup//Pixel/ " -> "Backup/Pixel". Blank means the share root. */
    fun normalizeFolder(input: String): String =
        input.trim()
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    fun toSmb(path: String): String = path.replace('/', '\\')
}
