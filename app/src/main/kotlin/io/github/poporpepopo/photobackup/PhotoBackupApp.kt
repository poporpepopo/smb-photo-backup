package io.github.poporpepopo.photobackup

import android.app.Application
import io.github.poporpepopo.photobackup.backup.SyncRunner
import io.github.poporpepopo.photobackup.backup.UploadDatabase
import io.github.poporpepopo.photobackup.settings.SettingsRepository
import io.github.poporpepopo.photobackup.sync.SyncScheduler
import io.github.poporpepopo.photobackup.sync.SyncStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhotoBackupApp : Application() {
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val database: UploadDatabase by lazy { UploadDatabase.open(this) }
    val syncRunner: SyncRunner by lazy { SyncRunner(this, settings, database.uploaded()) }
    val syncStatus: SyncStatusStore by lazy { SyncStatusStore(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Keeps the periodic job registered and its charging constraint in step with the
        // setting. Runs on every process start, which also restores the job if it was lost.
        scope.launch {
            settings.settings.map { it.chargingOnly }.distinctUntilChanged().collect {
                SyncScheduler.schedulePeriodic(this@PhotoBackupApp, it)
            }
        }
    }
}
