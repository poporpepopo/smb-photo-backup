package io.github.poporpepopo.photobackup.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import io.github.poporpepopo.photobackup.MainActivity
import io.github.poporpepopo.photobackup.R
import io.github.poporpepopo.photobackup.backup.SyncProgress

/** The progress notification shown while a pass runs as a foreground service. */
class SyncNotifier(private val context: Context) {

    fun foregroundInfo(progress: SyncProgress?): ForegroundInfo {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(context.getString(R.string.notif_sync_title))
            .setContentText(
                progress?.let { context.getString(R.string.sync_progress, it.index + 1, it.total, it.name) }
                    ?: context.getString(R.string.sync_preparing),
            )
            .setProgress(PROGRESS_MAX, progress?.let { (it.overall * PROGRESS_MAX).toInt() } ?: 0, progress == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        // Android 12+ holds a foreground service's notification back for ~10 s, so a pass
        // that finishes quickly never shows one.
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notif_channel_sync))
                .setShowBadge(false)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL = "sync_progress"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_MAX = 1000
    }
}
