package io.github.poporpepopo.photobackup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.backup.RunState
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaPermissionState
import io.github.poporpepopo.photobackup.media.MediaPermissions
import io.github.poporpepopo.photobackup.media.MediaStoreSource
import io.github.poporpepopo.photobackup.smb.LocalNetworkAccess
import io.github.poporpepopo.photobackup.sync.SyncScheduler
import io.github.poporpepopo.photobackup.sync.SyncStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val permission: MediaPermissionState? = null,
    val notificationsAllowed: Boolean = true,
    /** Android 17+: may this app reach the LAN (and so the router) at all. */
    val localNetworkAllowed: Boolean = true,
    /** Left out of battery optimization, so background passes are not rationed. */
    val batteryUnrestricted: Boolean = true,
    val includeVideos: Boolean = true,
    val chargingOnly: Boolean = false,
    val uploadedCount: Int? = null,
    val pendingCount: Int? = null,
    /** The pass in progress, whether started here or by the periodic job. */
    val run: RunState = RunState.Idle,
    /** "Sync now" was pressed and is waiting for WorkManager to start it. */
    val queued: Boolean = false,
    val status: SyncStatus = SyncStatus(),
    /** Earliest time the periodic job may run next. */
    val nextAutoSync: Long? = null,
)

class HomeViewModel(private val app: PhotoBackupApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    // Photos taken or deleted while the screen is open. Bursts (a camera burst, a gallery
    // deleting many) come as many calls, so they are collapsed into one recount.
    private val mediaChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mediaChanged.tryEmit(Unit)
        }
    }

    init {
        viewModelScope.launch {
            app.settings.settings.map { it.includeVideos }.distinctUntilChanged().collect { v ->
                _state.update { it.copy(includeVideos = v) }
                refresh()
            }
        }
        viewModelScope.launch {
            app.settings.settings.map { it.chargingOnly }.distinctUntilChanged().collect { c ->
                _state.update { it.copy(chargingOnly = c) }
            }
        }
        viewModelScope.launch {
            SyncScheduler.nextPeriodicRun(app).collect { t -> _state.update { it.copy(nextAutoSync = t) } }
        }
        listOf(MediaStoreSource.IMAGES, MediaStoreSource.VIDEOS).forEach {
            app.contentResolver.registerContentObserver(it, true, observer)
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            mediaChanged.debounce(1_000).collect { refreshCounts() }
        }
        viewModelScope.launch {
            app.syncRunner.state.collect { run ->
                val finished = run == RunState.Idle && _state.value.run != RunState.Idle
                _state.update { it.copy(run = run) }
                if (finished) refresh()
            }
        }
        viewModelScope.launch {
            app.syncStatus.status.collect { s -> _state.update { it.copy(status = s) } }
        }
        viewModelScope.launch {
            SyncScheduler.manualQueued(app).collect { q -> _state.update { it.copy(queued = q) } }
        }
    }

    /** Re-reads permissions and counts; called on resume and after a permission request. */
    fun refresh() {
        val permission = MediaPermissions.state(app, _state.value.includeVideos)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val battery = app.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(app.packageName)
        _state.update {
            it.copy(
                permission = permission,
                notificationsAllowed = notifications,
                localNetworkAllowed = LocalNetworkAccess.granted(app),
                batteryUnrestricted = battery,
            )
        }
        refreshCounts()
    }

    private fun refreshCounts() {
        if ((_state.value.permission?.access ?: MediaAccess.None) == MediaAccess.None) {
            _state.update { it.copy(uploadedCount = null, pendingCount = null) }
            return
        }
        viewModelScope.launch {
            val (uploaded, pending) = app.syncRunner.pending(_state.value.includeVideos)
            _state.update { it.copy(uploadedCount = uploaded, pendingCount = pending) }
        }
    }

    fun syncNow() {
        if (_state.value.run != RunState.Idle) return
        SyncScheduler.syncNow(app)
    }

    fun stopSync() = app.syncRunner.stop()

    override fun onCleared() {
        app.contentResolver.unregisterContentObserver(observer)
    }
}
