package io.github.poporpepopo.photobackup

import android.app.Application
import io.github.poporpepopo.photobackup.backup.SyncRunner
import io.github.poporpepopo.photobackup.backup.UploadDatabase
import io.github.poporpepopo.photobackup.settings.SettingsRepository

class PhotoBackupApp : Application() {
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val database: UploadDatabase by lazy { UploadDatabase.open(this) }
    val syncRunner: SyncRunner by lazy { SyncRunner(this, settings, database.uploaded()) }
}
