package io.github.poporpepopo.photobackup

import android.app.Application
import io.github.poporpepopo.photobackup.settings.SettingsRepository

class PhotoBackupApp : Application() {
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
}
