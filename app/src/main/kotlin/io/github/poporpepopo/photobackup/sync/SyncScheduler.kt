package io.github.poporpepopo.photobackup.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC = "periodic-sync"
    private const val MANUAL = "manual-sync"

    /**
     * Every 15 minutes (the shortest WorkManager allows) on an unmetered network, and only
     * while charging if asked. UPDATE keeps the existing schedule when nothing changed.
     */
    fun schedulePeriodic(context: Context, chargingOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Runs a pass now, as a background job so it carries on when the app is closed. No
     * network constraint: if the router is out of reach, the pass says so immediately
     * instead of waiting silently.
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a "Sync now" request is waiting for WorkManager to start it. */
    fun manualQueued(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MANUAL)
            .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
}
