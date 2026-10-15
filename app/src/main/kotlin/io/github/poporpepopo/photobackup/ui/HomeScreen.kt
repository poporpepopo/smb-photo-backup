package io.github.poporpepopo.photobackup.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
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
import io.github.poporpepopo.photobackup.backup.SyncOutcome
import io.github.poporpepopo.photobackup.backup.SyncSummary
import io.github.poporpepopo.photobackup.media.MediaAccess
import io.github.poporpepopo.photobackup.media.MediaPermissionState
import io.github.poporpepopo.photobackup.media.MediaPermissions

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
        state.permission?.let { PermissionCard(it, onResult = vm::refresh) }
        SyncCard(state, onSync = vm::syncNow)
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
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) { Text(stringResource(R.string.button_open_app_settings)) }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(state: HomeState, onSync: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val sync = state.sync
    val failed = sync is SyncUiState.Done && sync.outcome !is SyncOutcome.Finished

    HomeCard(title = stringResource(R.string.sync_title), warn = failed) {
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
        Button(
            onClick = onSync,
            enabled = state.permission?.access != MediaAccess.None && sync !is SyncUiState.Running,
        ) { Text(stringResource(R.string.button_sync_now)) }

        when (sync) {
            SyncUiState.Idle -> Unit
            is SyncUiState.Running -> {
                val p = sync.progress
                if (p == null) {
                    Text(stringResource(R.string.sync_preparing))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.sync_progress, p.index + 1, p.total, p.name))
                    LinearProgressIndicator(
                        progress = { (p.index + fraction(p.sent, p.size)) / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.sync_file_progress, size(p.sent), size(p.size)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is SyncUiState.Done -> OutcomeText(sync.outcome)
        }
    }
}

private fun fraction(sent: Long, size: Long) = if (size > 0) sent.toFloat() / size else 1f

@Composable
private fun OutcomeText(outcome: SyncOutcome) {
    val summary = when (outcome) {
        is SyncOutcome.Finished -> outcome.summary
        is SyncOutcome.Aborted -> {
            Text(errorText(UiError.Smb(outcome.failure), host = ""))
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
                        NotReadyReason.NotConfigured -> UiError.NotConfigured
                        NotReadyReason.PasswordUnreadable -> UiError.PasswordUnreadable
                    },
                    host = "",
                ),
            )
            null
        }
    } ?: return

    if (outcome is SyncOutcome.Finished && summary == SyncSummary()) {
        Text(stringResource(R.string.sync_nothing_to_do))
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
