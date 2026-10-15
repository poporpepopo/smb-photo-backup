package io.github.poporpepopo.photobackup.backup

import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [block] and cancels it if it stops calling `touch` for longer than [limitMs], then
 * throws [SmbFailure.Timeout]. smbj has its own per-request timeouts; this is the backstop
 * for hangs they do not cover, without capping how long a large but moving backup may take.
 *
 * Cancelling only helps if [block] notices: blocking code should run in `runInterruptible`
 * and close its socket on cancellation (as SmbGateway.withShare does).
 */
internal suspend fun <T> withStallGuard(
    limitMs: Long,
    checkEveryMs: Long = limitMs / 4,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    block: suspend (touch: () -> Unit) -> T,
): T = coroutineScope {
    val last = AtomicLong(now())
    val stalled = AtomicBoolean(false)
    val work = async { block { last.set(now()) } }
    val watchdog = launch {
        while (true) {
            delay(checkEveryMs)
            if (now() - last.get() > limitMs) {
                stalled.set(true)
                work.cancel()
                return@launch
            }
        }
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        if (!stalled.get()) throw e
        ensureActive() // cancelled from outside at the same moment: that wins
        throw SmbFailureException(SmbFailure.Timeout, e)
    } finally {
        watchdog.cancel()
    }
}
