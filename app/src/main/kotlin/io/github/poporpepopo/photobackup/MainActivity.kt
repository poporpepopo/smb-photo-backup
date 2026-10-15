package io.github.poporpepopo.photobackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.poporpepopo.photobackup.ui.SettingsScreen
import io.github.poporpepopo.photobackup.ui.theme.PhotoBackupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoBackupTheme {
                SettingsScreen()
            }
        }
    }
}
