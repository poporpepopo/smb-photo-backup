package io.github.poporpepopo.photobackup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.poporpepopo.photobackup.settings.BackupSettings
import io.github.poporpepopo.photobackup.settings.SettingsRepository
import io.github.poporpepopo.photobackup.settings.StoredPassword
import io.github.poporpepopo.photobackup.smb.HostProbe
import io.github.poporpepopo.photobackup.smb.RemotePath
import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import io.github.poporpepopo.photobackup.smb.SmbGateway
import io.github.poporpepopo.photobackup.smb.SmbTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsForm(
    val loaded: Boolean = false,
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val username: String = "",
    /** What the user typed. Empty means "keep the saved password". Never pre-filled. */
    val newPassword: String = "",
    val hasSavedPassword: Boolean = false,
    val includeVideos: Boolean = true,
    val chargingOnly: Boolean = false,
) {
    fun toSettings() = BackupSettings(
        host = host,
        share = share,
        folder = folder,
        username = username,
        includeVideos = includeVideos,
        chargingOnly = chargingOnly,
    )

    override fun toString() = "SettingsForm(//$host/$share/$folder as $username)"
}

sealed interface FormError {
    data object HostRequired : FormError
    data object ShareRequired : FormError
    data object PasswordUnreadable : FormError
    data class Smb(val failure: SmbFailure) : FormError
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(
        val host: String,
        val share: String,
        val folder: String,
        val folders: List<String>,
        val folderExists: Boolean,
    ) : TestState
    data class Failed(val error: FormError, val host: String) : TestState
}

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm())
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    init {
        viewModelScope.launch {
            val s = repo.current()
            _form.value = SettingsForm(
                loaded = true,
                host = s.host,
                share = s.share,
                folder = s.folder,
                username = s.username,
                hasSavedPassword = s.hasPassword,
                includeVideos = s.includeVideos,
                chargingOnly = s.chargingOnly,
            )
        }
    }

    fun edit(change: (SettingsForm) -> SettingsForm) = _form.update(change)

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        viewModelScope.launch {
            repo.save(f.toSettings(), f.newPassword)
            _form.update {
                it.copy(
                    folder = RemotePath.normalizeFolder(it.folder),
                    newPassword = "",
                    hasSavedPassword = it.hasSavedPassword || f.newPassword.isNotEmpty(),
                )
            }
            onSaved()
        }
    }

    /** Tests what is on screen, saved or not, so the user can check before saving. */
    fun testConnection() {
        if (_test.value == TestState.Running) return
        val f = _form.value
        viewModelScope.launch {
            _test.value = TestState.Running
            _test.value = runTest(f)
        }
    }

    private suspend fun runTest(f: SettingsForm): TestState {
        val host = f.host.trim()
        val share = f.share.trim()
        val folder = RemotePath.normalizeFolder(f.folder)
        fun fail(e: FormError) = TestState.Failed(e, host)

        if (host.isEmpty()) return fail(FormError.HostRequired)
        if (share.isEmpty()) return fail(FormError.ShareRequired)
        val password = resolvePassword(f) ?: return fail(FormError.PasswordUnreadable)
        if (!HostProbe.isReachable(host)) return fail(FormError.Smb(SmbFailure.Unreachable))
        return try {
            val target = SmbTarget(host, share, f.username.trim(), password)
            val listing = SmbGateway.probeShare(target, folder)
            TestState.Success(host, share, folder, listing.folders, listing.destinationExists)
        } catch (e: SmbFailureException) {
            fail(FormError.Smb(e.failure))
        }
    }

    /** The typed password, else the saved one. Null only when the saved one is unreadable. */
    private suspend fun resolvePassword(f: SettingsForm): String? {
        if (f.newPassword.isNotEmpty()) return f.newPassword
        return when (val p = repo.password()) {
            is StoredPassword.Available -> p.value
            StoredPassword.None -> ""
            StoredPassword.Unreadable -> if (f.username.isBlank()) "" else null
        }
    }
}
