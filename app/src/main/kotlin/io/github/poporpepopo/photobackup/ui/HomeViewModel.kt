package io.github.poporpepopo.photobackup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.backup.Destination
import io.github.poporpepopo.photobackup.backup.LocalFile
import io.github.poporpepopo.photobackup.backup.UploadResult
import io.github.poporpepopo.photobackup.backup.Uploader
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaPermissionState
import io.github.poporpepopo.photobackup.media.MediaPermissions
import io.github.poporpepopo.photobackup.media.MediaStoreSource
import io.github.poporpepopo.photobackup.settings.TargetResult
import io.github.poporpepopo.photobackup.smb.HostProbe
import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import io.github.poporpepopo.photobackup.smb.SmbGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class HomeState(
    val permission: MediaPermissionState? = null,
    val includeVideos: Boolean = true,
    val imageCount: Int? = null,
    val videoCount: Int? = null,
    val send: SendState = SendState.Idle,
)

sealed interface SendState {
    data object Idle : SendState
    data class Running(val name: String, val sent: Long, val total: Long) : SendState
    data class Done(val result: UploadResult) : SendState
    data class Failed(val error: UiError, val host: String) : SendState
}

class HomeViewModel(private val app: PhotoBackupApp) : ViewModel() {
    private val media = MediaStoreSource(app)

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
        val includeVideos = _state.value.includeVideos
        val permission = MediaPermissions.state(app, includeVideos)
        _state.update { it.copy(permission = permission) }
        if (permission.access == MediaAccess.None) {
            _state.update { it.copy(imageCount = null, videoCount = null) }
            return
        }
        viewModelScope.launch {
            val (images, videos) = withContext(Dispatchers.IO) {
                media.countImages() to media.countVideos()
            }
            _state.update { it.copy(imageCount = images, videoCount = videos) }
        }
    }

    /** Phase 2 test tool: uploads the most recently added item. */
    fun sendLatest() {
        if (_state.value.send is SendState.Running) return
        viewModelScope.launch {
            val result = runSendLatest()
            _state.update { it.copy(send = result) }
        }
    }

    private suspend fun runSendLatest(): SendState {
        val permission = MediaPermissions.state(app, _state.value.includeVideos)
        if (permission.access == MediaAccess.None || !permission.location) {
            return SendState.Failed(UiError.NoPermission, "")
        }
        val ready = when (val t = app.settings.target()) {
            is TargetResult.Ready -> t
            TargetResult.NotConfigured -> return SendState.Failed(UiError.NotConfigured, "")
            TargetResult.PasswordUnreadable -> return SendState.Failed(UiError.PasswordUnreadable, "")
        }
        val host = ready.settings.host
        fun fail(e: UiError) = SendState.Failed(e, host)

        val item = withContext(Dispatchers.IO) {
            media.query(ready.settings.includeVideos, limit = 1).firstOrNull()
        } ?: return fail(UiError.NoMedia)

        if (!HostProbe.isReachable(host)) return fail(UiError.Smb(SmbFailure.Unreachable))

        val name = Destination.sanitizeFileName(item.displayName, fallback = "media_${item.id}")
        _state.update { it.copy(send = SendState.Running(name, 0, item.size)) }
        return try {
            val local = withContext(Dispatchers.IO) {
                LocalFile(item.id, name, media.originalSize(item)) { media.openOriginal(item) }
            }
            val directory = Destination.directory(
                ready.settings.folder,
                item.dateTakenMillis,
                item.dateAddedSeconds,
                ZoneId.systemDefault(),
            )
            val result = SmbGateway.withShare(ready.target) { share ->
                Uploader(share).upload(local, directory) { sent, total ->
                    _state.update { it.copy(send = SendState.Running(name, sent, total)) }
                }
            }
            SendState.Done(result)
        } catch (e: SmbFailureException) {
            fail(UiError.Smb(e.failure))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Local read failures (e.g. the item vanished, or the original is not readable).
            fail(UiError.Smb(SmbFailure.classify(e)))
        }
    }
}
