package io.github.poporpepopo.photobackup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.backup.SyncOutcome
import io.github.poporpepopo.photobackup.backup.SyncProgress
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaPermissionState
import io.github.poporpepopo.photobackup.media.MediaPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val permission: MediaPermissionState? = null,
    val includeVideos: Boolean = true,
    val uploadedCount: Int? = null,
    val pendingCount: Int? = null,
    val sync: SyncUiState = SyncUiState.Idle,
)

sealed interface SyncUiState {
    data object Idle : SyncUiState
    /** [progress] is null while connecting and listing. */
    data class Running(val progress: SyncProgress?) : SyncUiState
    data class Done(val outcome: SyncOutcome) : SyncUiState
}

class HomeViewModel(private val app: PhotoBackupApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.settings.settings.map { it.includeVideos }.distinctUntilChanged().collect { v ->
                _state.update { it.copy(includeVideos = v) }
                refresh()
            }
        }
    }

    /** Re-reads permissions and counts; called on resume and after a permission request. */
    fun refresh() {
        val permission = MediaPermissions.state(app, _state.value.includeVideos)
        _state.update { it.copy(permission = permission) }
        if (permission.access == MediaAccess.None) {
            _state.update { it.copy(uploadedCount = null, pendingCount = null) }
            return
        }
        viewModelScope.launch {
            val (uploaded, pending) = app.syncRunner.pending(_state.value.includeVideos)
            _state.update { it.copy(uploadedCount = uploaded, pendingCount = pending) }
        }
    }

    fun syncNow() {
        if (_state.value.sync is SyncUiState.Running) return
        _state.update { it.copy(sync = SyncUiState.Running(null)) }
        viewModelScope.launch {
            val outcome = app.syncRunner.run { p ->
                _state.update { it.copy(sync = SyncUiState.Running(p)) }
            }
            _state.update { it.copy(sync = SyncUiState.Done(outcome)) }
            refresh()
        }
    }
}
