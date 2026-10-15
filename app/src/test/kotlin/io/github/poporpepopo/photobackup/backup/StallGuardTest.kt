package io.github.poporpepopo.photobackup.backup

import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StallGuardTest {

    @Test fun silentBlockTimesOut() = runTest {
        try {
            withStallGuard(60_000, now = { testScheduler.currentTime }) { awaitCancellation() }
            fail("expected a timeout")
        } catch (e: SmbFailureException) {
            assertEquals(SmbFailure.Timeout, e.failure)
        }
    }

    @Test fun slowButMovingBlockIsLeftAlone() = runTest {
        val result = withStallGuard(60_000, now = { testScheduler.currentTime }) { touch ->
            repeat(10) {
                delay(50_000) // 500 s in all, but never 60 s without a sign of life
                touch()
            }
            "done"
        }
        assertEquals("done", result)
    }

    @Test fun failureFromTheBlockPassesThrough() = runTest {
        try {
            withStallGuard(60_000, now = { testScheduler.currentTime }) {
                throw SmbFailureException(SmbFailure.ConnectionLost)
            }
            fail("expected the block's failure")
        } catch (e: SmbFailureException) {
            assertEquals(SmbFailure.ConnectionLost, e.failure)
        }
    }

    @Test fun outsideCancellationIsNotReportedAsTimeout() = runTest {
        var caught: Throwable? = null
        val job = launch {
            try {
                withStallGuard(60_000, now = { testScheduler.currentTime }) { awaitCancellation() }
            } catch (e: Throwable) {
                caught = e
                throw e
            }
        }
        delay(10_000)
        job.cancel()
        job.join()
        assertTrue(caught is CancellationException)
    }

    @Test fun blockingCallIsInterrupted() = runBlocking {
        // Real threads and clock: a thread stuck in a blocking call must be released.
        val started = System.nanoTime()
        try {
            withTimeout(5_000) {
                withStallGuard(300, checkEveryMs = 50) {
                    runInterruptible(Dispatchers.IO) { Thread.sleep(60_000) }
                }
            }
            fail("expected a timeout")
        } catch (e: SmbFailureException) {
            assertEquals(SmbFailure.Timeout, e.failure)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }
}
