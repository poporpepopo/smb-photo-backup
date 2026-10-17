package io.github.poporpepopo.photobackup.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.poporpepopo.photobackup.PhotoBackupApp
import io.github.poporpepopo.photobackup.R
import io.github.poporpepopo.photobackup.smb.LocalNetworkAccess
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as PhotoBackupApp
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    app.settings,
                    localNetworkAllowed = { LocalNetworkAccess.granted(app) },
                    records = object : SettingsViewModel.UploadRecords {
                        override suspend fun count() = app.syncRunner.uploadedCount()
                        override suspend fun forget() = app.syncRunner.forgetUploads()
                    },
                )
            }
        },
    )
    // Asked at the moment it is needed; if refused, the test reports why.
    val askLocalNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.testConnection() }
    val form by vm.form.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.saved)
    val clearedText = stringResource(R.string.records_cleared)
    val busyText = stringResource(R.string.records_busy)
    val recordCount by vm.recordCount.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshRecordCount() }

    if (!form.loaded) return
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Field(
            value = form.host,
            label = R.string.field_host,
            keyboardType = KeyboardType.Uri,
            placeholder = "192.168.50.1",
        ) { v -> vm.edit { it.copy(host = v) } }
        Field(form.share, R.string.field_share) { v -> vm.edit { it.copy(share = v) } }
        Field(
            value = form.folder,
            label = R.string.field_folder,
            placeholder = stringResource(R.string.field_folder_hint),
        ) { v -> vm.edit { it.copy(folder = v) } }
        Field(form.username, R.string.field_user) { v -> vm.edit { it.copy(username = v) } }
        PasswordField(
            value = form.newPassword,
            hasSaved = form.hasSavedPassword,
        ) { v -> vm.edit { it.copy(newPassword = v) } }

        SwitchRow(R.string.switch_include_videos, form.includeVideos) { v ->
            vm.edit { it.copy(includeVideos = v) }
        }
        SwitchRow(R.string.switch_charging_only, form.chargingOnly) { v ->
            vm.edit { it.copy(chargingOnly = v) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                vm.save { scope.launch { snackbar.showSnackbar(savedText) } }
            }) { Text(stringResource(R.string.button_save)) }
            OutlinedButton(
                onClick = {
                    if (LocalNetworkAccess.granted(app)) {
                        vm.testConnection()
                    } else {
                        askLocalNetwork.launch(LocalNetworkAccess.PERMISSION)
                    }
                },
                enabled = test != TestState.Running,
            ) { Text(stringResource(R.string.button_test)) }
        }

        TestResult(test)

        HorizontalDivider(Modifier.padding(top = 12.dp))
        Records(
            count = recordCount,
            onForget = {
                vm.forgetRecords { done ->
                    scope.launch { snackbar.showSnackbar(if (done) clearedText else busyText) }
                }
            },
        )
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Records(count: Int?, onForget: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Text(stringResource(R.string.records_title), style = MaterialTheme.typography.titleMedium)
    if (count != null) Text(pluralStringResource(R.plurals.records_text, count, count))
    OutlinedButton(onClick = { confirming = true }, enabled = (count ?: 0) > 0) {
        Text(stringResource(R.string.records_clear))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.records_confirm_title)) },
            text = { Text(stringResource(R.string.records_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onForget()
                }) { Text(stringResource(R.string.records_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Field(
    value: String,
    label: Int,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, hasSaved: Boolean, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.field_password)) },
        // supportingText rather than placeholder: M3 hides the placeholder until focused.
        supportingText = if (hasSaved && value.isEmpty()) {
            { Text(stringResource(R.string.password_saved_hint)) }
        } else null,
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.password_hide else R.string.password_show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TestResult(state: TestState) {
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.test_running))
        }
        is TestState.Success -> ResultCard(error = false) {
            Text(
                stringResource(R.string.test_ok, state.host, state.share),
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.folder.isNotEmpty()) {
                Text(
                    stringResource(
                        if (state.folderExists) R.string.test_folder_exists
                        else R.string.test_folder_missing,
                        state.folder,
                    ),
                )
            }
            Text(stringResource(if (state.signed) R.string.test_signed else R.string.test_unsigned))
            state.space?.let { (free, total) ->
                val context = LocalContext.current
                Text(
                    stringResource(
                        R.string.test_free_space,
                        Formatter.formatShortFileSize(context, free),
                        Formatter.formatShortFileSize(context, total),
                    ),
                )
            }
            Text(stringResource(R.string.test_share_entries))
            if (state.folders.isEmpty()) {
                Text(stringResource(R.string.test_share_empty))
            } else {
                state.folders.forEach { Text("📁 $it") }
            }
        }
        is TestState.Failed -> ResultCard(error = true) {
            Text(errorText(state.error, state.host))
        }
    }
}

@Composable
private fun ResultCard(error: Boolean, content: @Composable () -> Unit) {
    val colors = if (error) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}
