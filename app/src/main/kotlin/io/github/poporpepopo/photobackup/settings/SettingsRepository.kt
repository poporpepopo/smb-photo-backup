package io.github.poporpepopo.photobackup.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.poporpepopo.photobackup.smb.RemotePath
import io.github.poporpepopo.photobackup.smb.SmbTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Everything except the password, which is never part of a plain settings object. */
data class BackupSettings(
    val host: String = "",
    val share: String = "",
    /** Normalised with [RemotePath.normalizeFolder]: no leading/trailing slash, "/" separated. */
    val folder: String = "",
    val username: String = "",
    val includeVideos: Boolean = true,
    val chargingOnly: Boolean = false,
    val hasPassword: Boolean = false,
)

sealed interface StoredPassword {
    data object None : StoredPassword
    /** The Keystore key is gone; the user has to type the password again. */
    data object Unreadable : StoredPassword
    class Available(val value: String) : StoredPassword {
        override fun toString() = "Available(***)"
    }
}

sealed interface TargetResult {
    class Ready(val settings: BackupSettings, val target: SmbTarget) : TargetResult
    data object NotConfigured : TargetResult
    data object PasswordUnreadable : TargetResult
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    context: Context,
    private val cipher: PasswordCipher = PasswordCipher(),
) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<BackupSettings> = store.data.map { p ->
        BackupSettings(
            host = p[HOST].orEmpty(),
            share = p[SHARE].orEmpty(),
            folder = p[FOLDER].orEmpty(),
            username = p[USERNAME].orEmpty(),
            includeVideos = p[INCLUDE_VIDEOS] ?: true,
            chargingOnly = p[CHARGING_ONLY] ?: false,
            hasPassword = p[PASSWORD_ENC] != null,
        )
    }

    suspend fun current(): BackupSettings = settings.first()

    suspend fun password(): StoredPassword {
        val enc = store.data.first()[PASSWORD_ENC] ?: return StoredPassword.None
        return cipher.decrypt(enc)?.let(StoredPassword::Available) ?: StoredPassword.Unreadable
    }

    /** The saved settings turned into something to connect to. */
    suspend fun target(): TargetResult {
        val s = current()
        if (s.host.isBlank() || s.share.isBlank()) return TargetResult.NotConfigured
        val password = when (val p = password()) {
            is StoredPassword.Available -> p.value
            StoredPassword.None -> ""
            StoredPassword.Unreadable ->
                if (s.username.isBlank()) "" else return TargetResult.PasswordUnreadable
        }
        return TargetResult.Ready(s, SmbTarget(s.host, s.share, s.username, password))
    }

    /** Saves [s]. A null or empty [newPassword] keeps the stored one. */
    suspend fun save(s: BackupSettings, newPassword: String?) {
        // Encrypt outside edit{}: the transform block may be retried.
        val encrypted = newPassword?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        store.edit { p ->
            p[HOST] = s.host.trim()
            p[SHARE] = s.share.trim()
            p[FOLDER] = RemotePath.normalizeFolder(s.folder)
            p[USERNAME] = s.username.trim()
            p[INCLUDE_VIDEOS] = s.includeVideos
            p[CHARGING_ONLY] = s.chargingOnly
            if (encrypted != null) p[PASSWORD_ENC] = encrypted
        }
    }

    private companion object {
        val HOST = stringPreferencesKey("host")
        val SHARE = stringPreferencesKey("share")
        val FOLDER = stringPreferencesKey("folder")
        val USERNAME = stringPreferencesKey("username")
        val INCLUDE_VIDEOS = booleanPreferencesKey("include_videos")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val PASSWORD_ENC = stringPreferencesKey("password_enc")
    }
}
