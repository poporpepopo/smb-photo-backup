package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateMeterTest {

    @Test fun noEstimateUntilTenSecondsHavePassed() {
        val meter = RateMeter()
        assertNull(meter.remainingMillis(0, 0, 1_000))
        assertNull(meter.remainingMillis(9_000, 9_000, 1_000))
    }

    @Test fun remainingBytesAtTheRateSoFar() {
        val meter = RateMeter()
        meter.remainingMillis(0, 0, 0)
        // 1 MB/s; 60 MB left is a minute.
        assertEquals(60_000L, meter.remainingMillis(10_000, 10_000_000, 60_000_000))
    }

    @Test fun followsTheRecentRateNotTheWholePass() {
        val meter = RateMeter(windowMillis = 60_000)
        // Ten minutes at 10 MB/s (reading back), then a minute at 1 MB/s (uploading).
        var t = 0L
        var moved = 0L
        while (t < 600_000) {
            meter.remainingMillis(t, moved, 0)
            t += 1_000
            moved += 10_000_000
        }
        while (t <= 661_000) {
            meter.remainingMillis(t, moved, 0)
            t += 1_000
            moved += 1_000_000
        }
        val left = meter.remainingMillis(t, moved, 100_000_000)!!
        // About 100 s at 1 MB/s, not 10 s at the old rate.
        assertEquals(100_000.0, left.toDouble(), 5_000.0)
    }

    @Test fun noEstimateWhileNothingMoves() {
        val meter = RateMeter()
        meter.remainingMillis(0, 500, 1_000)
        assertNull(meter.remainingMillis(30_000, 500, 1_000))
    }

    @Test fun overallProgressCountsBytesNotItems() {
        // One 1 KB photo done, a 99 KB video half sent: half of the items, but ~50 % of bytes.
        val p = SyncProgress(
            index = 1, total = 2, name = "v.mp4", transfer = Transfer.Sending,
            sent = 49_500, size = 99_000, doneBytes = 1_000, totalBytes = 100_000,
            remainingMillis = null,
        )
        assertEquals(0.505f, p.overall, 0.0001f)
        assertEquals(0.5f, p.file, 0.0001f)
    }
}
