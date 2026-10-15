package io.github.poporpepopo.photobackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.poporpepopo.photobackup.ui.HomeScreen
import io.github.poporpepopo.photobackup.ui.SettingsScreen
import io.github.poporpepopo.photobackup.ui.theme.PhotoBackupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoBackupTheme {
                MainScaffold()
            }
        }
    }
}

private enum class Tab(val label: Int, val title: Int, val icon: ImageVector) {
    Home(R.string.tab_home, R.string.app_name, Icons.Filled.Home),
    Settings(R.string.tab_settings, R.string.settings_title, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(tab.title)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            Tab.Home -> HomeScreen(Modifier.padding(padding))
            Tab.Settings -> SettingsScreen(snackbar, Modifier.padding(padding))
        }
    }
}
