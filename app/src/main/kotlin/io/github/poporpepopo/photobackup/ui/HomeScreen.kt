package io.github.poporpepopo.photobackup.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.R
import io.github.poporpepopo.photobackup.backup.NotReadyReason
import io.github.poporpepopo.photobackup.backup.RunState
import io.github.poporpepopo.photobackup.backup.SyncOutcome
import io.github.poporpepopo.photobackup.backup.SyncSummary
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaPermissionState
import io.github.poporpepopo.photobackup.media.MediaPermissions
import io.github.poporpepopo.photobackup.smb.LocalNetworkAccess

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as PhotoBackupApp
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(app) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // The user can change permissions in system settings while we are in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cards that need the user come first; each disappears once it is dealt with.
        state.permission?.takeUnless { it.readyForBackup }?.let { PermissionCard(it, onResult = vm::refresh) }
        if (!state.localNetworkAllowed) LocalNetworkCard(onResult = vm::refresh)
        SyncCard(state, onSync = vm::syncNow, onStop = vm::stopSync)
        if (!state.batteryUnrestricted) BatteryCard()
        if (!state.notificationsAllowed) NotificationCard(onResult = vm::refresh)
    }
}

@Composable
private fun PermissionCard(p: MediaPermissionState, onResult: () -> Unit) {
    val context = LocalContext.current
    // Once the system has stopped showing the dialog (denied twice), only Settings helps.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        asked = true
        onResult()
    }
    val warn = !p.readyForBackup
    HomeCard(title = stringResource(R.string.media_access_title), warn = warn) {
        Text(
            stringResource(
                when (p.access) {
                    MediaAccess.Full -> R.string.media_access_full
                    MediaAccess.Partial -> R.string.media_access_partial
                    MediaAccess.None -> R.string.media_access_none
                },
            ),
        )
        if (p.access != MediaAccess.None && !p.location) {
            Text(stringResource(R.string.media_location_missing))
        }
        if (warn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(MediaPermissions.toRequest()) }) {
                    Text(
                        stringResource(
                            if (p.access == MediaAccess.Partial) R.string.button_grant_all
                            else R.string.button_grant,
                        ),
                    )
                }
                if (asked || p.access == MediaAccess.Partial) {
                    OutlinedButton(onClick = { openAppSettings(context) }) {
                        Text(stringResource(R.string.button_open_app_settings))
                    }
                }
            }
        }
    }
}

/** Android 17+: without it the router cannot be reached at all. */
@Composable
private fun LocalNetworkCard(onResult: () -> Unit) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        onResult()
    }
    HomeCard(title = stringResource(R.string.local_network_title), warn = true) {
        Text(stringResource(R.string.local_network_text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(LocalNetworkAccess.PERMISSION) }) {
                Text(stringResource(R.string.button_allow_local_network))
            }
            if (asked) {
                OutlinedButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.button_open_app_settings))
                }
            }
        }
    }
}

/** Optional: without it the pass still runs, but its progress notification is hidden. */
@Composable
private fun NotificationCard(onResult: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        onResult()
    }
    HomeCard(title = stringResource(R.string.notif_permission_title)) {
        Text(stringResource(R.string.notif_permission_text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.button_allow_notifications))
            }
            if (asked) {
                OutlinedButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.button_open_app_settings))
                }
            }
        }
    }
}

/**
 * Recommended: an optimized app's periodic job is rationed by app standby (as rarely as
 * once a day for a rarely opened app) and may not start a foreground service from the
 * background, so a long upload is cut off after about 10 minutes.
 */
@SuppressLint("BatteryLife") // see the permission's comment in the manifest
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    HomeCard(title = stringResource(R.string.battery_title)) {
        Text(stringResource(R.string.battery_text))
        Button(
            onClick = {
                val ask = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                )
                try {
                    context.startActivity(ask)
                } catch (_: ActivityNotFoundException) {
                    // Some builds drop the direct dialog; the list is always there.
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        ) { Text(stringResource(R.string.button_battery)) }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

@Composable
private fun SyncCard(state: HomeState, onSync: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    fun time(millis: Long) = DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE,
    )
    val run = state.run
    val status = state.status
    val lastRun = status.lastRun
    val failed = lastRun != null && run == RunState.Idle &&
        (lastRun.outcome is SyncOutcome.Aborted || lastRun.outcome is SyncOutcome.NotReady)

    HomeCard(title = stringResource(R.string.sync_title), warn = failed) {
        Text(
            status.lastSyncAt?.let { stringResource(R.string.status_last_sync, time(it)) }
                ?: stringResource(R.string.sync_never),
        )
        val uploaded = state.uploadedCount
        val pending = state.pendingCount
        if (uploaded != null && pending != null) {
            Text(
                stringResource(
                    if (state.includeVideos) R.string.sync_counts else R.string.sync_counts_photos_only,
                    uploaded,
                    pending,
                ),
            )
        }
        state.nextAutoSync?.let { next ->
            Text(
                when {
                    next > System.currentTimeMillis() -> stringResource(
                        if (state.chargingOnly) R.string.status_next_charging else R.string.status_next,
                        time(next),
                    )
                    state.chargingOnly -> stringResource(R.string.status_next_due_charging)
                    else -> stringResource(R.string.status_next_due)
                },
            )
        }
        // The latest error, unless it is the last run itself (shown below in full).
        val lastError = status.lastError
        when {
            lastError == null -> Text(stringResource(R.string.status_no_error))
            lastError != lastRun -> {
                Text(
                    stringResource(R.string.status_last_error, time(lastError.atMillis)),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutcomeText(lastError.outcome)
                if ((status.lastSyncAt ?: 0) > lastError.atMillis) {
                    Text(
                        stringResource(R.string.status_error_since_ok),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (run == RunState.Idle) {
            Button(
                onClick = onSync,
                enabled = state.permission?.access != MediaAccess.None && !state.queued,
            ) { Text(stringResource(R.string.button_sync_now)) }
        } else {
            // Ends this pass only; the next automatic one still runs.
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.button_stop_sync)) }
        }

        when (run) {
            RunState.Preparing -> {
                Text(stringResource(R.string.sync_preparing))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is RunState.Sending -> {
                val p = run.progress
                Text(ProgressText.overall(context, p))
                LinearProgressIndicator(progress = { p.overall }, modifier = Modifier.fillMaxWidth())
                Text(ProgressText.item(context, p), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { p.file }, modifier = Modifier.fillMaxWidth())
                Text(ProgressText.file(context, p), style = MaterialTheme.typography.bodySmall)
            }
            RunState.Idle -> when {
                state.queued -> Text(stringResource(R.string.sync_queued))
                lastRun != null -> {
                    Text(
                        stringResource(R.string.sync_last_run, time(lastRun.atMillis)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutcomeText(lastRun.outcome)
                }
            }
        }
    }
}

@Composable
private fun OutcomeText(outcome: SyncOutcome) {
    val summary = when (outcome) {
        is SyncOutcome.Finished -> outcome.summary
        is SyncOutcome.Aborted -> {
            Text(errorText(UiError.Smb(outcome.failure), host = ""))
            outcome.summary
        }
        is SyncOutcome.Stopped -> {
            Text(stringResource(R.string.sync_stopped))
            outcome.summary
        }
        SyncOutcome.NotHome -> {
            Text(stringResource(R.string.sync_not_home))
            null
        }
        is SyncOutcome.NotReady -> {
            Text(
                errorText(
                    when (outcome.reason) {
                        NotReadyReason.NoPermission -> UiError.NoPermission
                        NotReadyReason.NoLocalNetwork -> UiError.NoLocalNetwork
                        NotReadyReason.NotConfigured -> UiError.NotConfigured
                        NotReadyReason.PasswordUnreadable -> UiError.PasswordUnreadable
                    },
                    host = "",
                ),
            )
            null
        }
    } ?: return

    if (summary == SyncSummary()) {
        // An abort or stop before the first item has nothing to add to its message.
        if (outcome is SyncOutcome.Finished) Text(stringResource(R.string.sync_nothing_to_do))
        return
    }
    Text(stringResource(R.string.sync_summary, summary.uploaded, summary.alreadyPresent, summary.failed))
    summary.lastItemError?.let {
        Text(
            stringResource(R.string.sync_item_error, it.name, it.detail),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HomeCard(
    title: String? = null,
    warn: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (warn) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
