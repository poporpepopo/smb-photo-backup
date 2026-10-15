package io.github.poporpepopo.photobackup.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.backup.RunState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One backup pass, for both the periodic job and "Sync now". Always reports success:
 * not being at home, missing settings and dropped connections are all things the next
 * period handles, and a failure or retry would only make WorkManager back off.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as PhotoBackupApp
    private val notifier = SyncNotifier(context)

    override suspend fun doWork(): Result {
        val outcome = coroutineScope {
            val notifications = launch { followProgress() }
            try {
                app.syncRunner.run()
            } finally {
                notifications.cancel()
            }
        } ?: return Result.success() // the other job is already running a pass
        app.syncStatus.record(System.currentTimeMillis(), outcome)
        return Result.success()
    }

    /** Needed for expedited work ("Sync now") on Android 11 and lower. */
    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo(null)

    /**
     * Becomes a foreground service once there is something to send, then keeps the
     * notification current about once a second.
     */
    private suspend fun followProgress() {
        var refused = false
        app.syncRunner.state.collect { state ->
            if (state !is RunState.Sending || refused) return@collect
            try {
                setForeground(notifier.foregroundInfo(state.progress))
            } catch (e: IllegalStateException) {
                // Android 12+ refuses to start a foreground service from the background
                // unless an exemption applies. The pass carries on as ordinary work; if
                // WorkManager stops it at its time limit, the next period continues.
                refused = true
            }
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
