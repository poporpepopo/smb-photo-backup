package io.github.poporpepopo.photobackup.backup

/**
 * Estimates the time a pass has left from how fast bytes moved over roughly the last
 * [windowMillis]. A recent window rather than the whole pass, because reading back to
 * compare and uploading run at different speeds, and a pass can switch between them.
 */
class RateMeter(
    private val windowMillis: Long = 5 * 60_000L,
    private val minSpanMillis: Long = 10_000L,
) {
    /** (time, bytes moved so far), oldest first, at most one per [SAMPLE_EVERY_MS]. */
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /**
     * Records that [moved] bytes have moved so far at [atMillis] and returns how long
     * [remainingBytes] will take at the recent rate, or null until there is enough to go on.
     */
    fun remainingMillis(atMillis: Long, moved: Long, remainingBytes: Long): Long? {
        if (samples.isEmpty() || atMillis - samples.last().first >= SAMPLE_EVERY_MS) {
            samples.addLast(atMillis to moved)
        }
        // Keep the newest sample that is at least a window old, so the span covers it.
        while (samples.size > 1 && samples[1].first <= atMillis - windowMillis) samples.removeFirst()
        val (since, movedThen) = samples.first()
        val span = atMillis - since
        val bytes = moved - movedThen
        if (span < minSpanMillis || bytes <= 0) return null
        return (remainingBytes.toDouble() * span / bytes).toLong()
    }

    private companion object {
        const val SAMPLE_EVERY_MS = 1_000L
    }
}
