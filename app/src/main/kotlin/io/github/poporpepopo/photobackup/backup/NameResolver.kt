package io.github.poporpepopo.photobackup.backup

/** The few remote operations name resolution needs, so it can be tested with a fake. */
interface RemoteDirectory {
    /** Size of the file [name] in this directory, -1 if it is a directory, null if absent. */
    fun sizeOf(name: String): Long?
}

sealed interface NameResolution {
    /** Nothing is called [name] yet: upload under it. */
    data class Upload(val name: String) : NameResolution

    /** A file of the same name and size is already there, e.g. after the app was reinstalled. */
    data class AlreadyPresent(val name: String) : NameResolution
}

object NameResolver {
    const val MAX_ATTEMPTS = 1000

    /**
     * Walks "IMG.jpg", "IMG (1).jpg", "IMG (2).jpg", ... and stops at the first name that
     * is free or already holds a file of the same size, which is taken to be this item.
     *
     * Contents are not compared: reading a copy back costs as much Wi-Fi time as sending
     * it, and after a reinstall every item already sent would be read back once. Camera
     * file names carry the capture time to the millisecond, so a different file with the
     * same name and the same byte count is not a case worth that.
     */
    fun resolve(
        dir: RemoteDirectory,
        desiredName: String,
        localSize: Long,
    ): NameResolution {
        for (n in 0 until MAX_ATTEMPTS) {
            val candidate = Destination.candidateName(desiredName, n)
            val size = dir.sizeOf(candidate) ?: return NameResolution.Upload(candidate)
            if (size == localSize) {
                return NameResolution.AlreadyPresent(candidate)
            }
        }
        throw IllegalStateException("no free name for $desiredName after $MAX_ATTEMPTS tries")
    }
}
