package io.github.poporpepopo.photobackup.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.poporpepopo.photobackup.PhotoBackupApp

/** The notification's "Stop" action. Not exported: only our own notification sends it. */
class StopSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as PhotoBackupApp).syncRunner.stop()
    }
}
