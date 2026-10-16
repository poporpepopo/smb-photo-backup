package io.github.poporpepopo.photobackup.backup

/**
 * Which bytes to compare when a file of the same name and size is already on the share.
 *
 * Reading a copy back costs as much Wi-Fi time as sending it, and after a reinstall every
 * item already sent is read back once. Photos are small, so they are compared in full.
 * Larger files (in practice videos) are compared at the start, the end and a few places
 * in between: a video's header and index, which hold its own timestamps and frame
 * layout, sit at the start or the end. Two different videos with the same name and the
 * same size that also agree on all of these bytes are not a case worth paying gigabytes
 * of reads for.
 */
object ContentSample {
    const val FULL_COMPARE_MAX = 16L shl 20
    const val EDGE_BYTES = 1L shl 20
    const val MIDDLE_COUNT = 8
    const val MIDDLE_BYTES = 256L shl 10

    /** True when [ranges] covers only part of a file of [size] bytes. */
    fun isPartial(size: Long) = size > FULL_COMPARE_MAX

    /** Byte ranges to compare, as [start, end) pairs in ascending, non-overlapping order. */
    fun ranges(size: Long): List<Pair<Long, Long>> {
        if (!isPartial(size)) return listOf(0L to size)
        val picked = buildList {
            add(0L to EDGE_BYTES)
            for (k in 1..MIDDLE_COUNT) {
                val start = size * k / (MIDDLE_COUNT + 1)
                add(start to start + MIDDLE_BYTES)
            }
            add(size - EDGE_BYTES to size)
        }
        // Spaced far apart for any size above FULL_COMPARE_MAX, but merge to be safe.
        return picked.sortedBy { it.first }.fold(mutableListOf()) { acc, (start, end) ->
            val last = acc.lastOrNull()
            if (last != null && start <= last.second) {
                acc[acc.size - 1] = last.first to maxOf(last.second, end)
            } else {
                acc += start to end
            }
            acc
        }
    }
}
