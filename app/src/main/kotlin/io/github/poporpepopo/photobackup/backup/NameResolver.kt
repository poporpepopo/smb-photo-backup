package io.github.poporpepopo.photobackup.backup

/** The few remote operations name resolution needs, so it can be tested with a fake. */
interface RemoteDirectory {
    /** Size of the file [name] in this directory, -1 if it is a directory, null if absent. */
    fun sizeOf(name: String): Long?

    /** True if the existing file [name] has exactly the bytes of the local item. */
    fun sameContentAsLocal(name: String): Boolean
}

sealed interface NameResolution {
    /** Nothing is called [name] yet: upload under it. */
    data class Upload(val name: String) : NameResolution

    /** An identical file is already there, e.g. after the app was reinstalled. */
    data class AlreadyPresent(val name: String) : NameResolution
}

object NameResolver {
    const val MAX_ATTEMPTS = 1000

    /**
     * Walks "IMG.jpg", "IMG (1).jpg", "IMG (2).jpg", ... and stops at the first name that
     * is free or already holds the same content. Contents are only compared when the sizes
     * match, so a differing file costs one metadata lookup.
     */
    fun resolve(
        dir: RemoteDirectory,
        desiredName: String,
        localSize: Long,
    ): NameResolution {
        for (n in 0 until MAX_ATTEMPTS) {
            val candidate = Destination.candidateName(desiredName, n)
            val size = dir.sizeOf(candidate) ?: return NameResolution.Upload(candidate)
            if (size == localSize && dir.sameContentAsLocal(candidate)) {
                return NameResolution.AlreadyPresent(candidate)
            }
        }
        throw IllegalStateException("no free name for $desiredName after $MAX_ATTEMPTS tries")
    }
}
