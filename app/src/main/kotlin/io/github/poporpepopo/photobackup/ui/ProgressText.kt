package io.github.poporpepopo.photobackup.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import io.github.poporpepopo.photobackup.R
import io.github.poporpepopo.photobackup.backup.SyncProgress
import io.github.poporpepopo.photobackup.backup.Transfer
import java.text.NumberFormat

/** The progress lines shared by the home screen and the notification. */
object ProgressText {

    /** "n / N: name" */
    fun item(context: Context, p: SyncProgress): String =
        context.getString(R.string.sync_progress, p.index + 1, p.total, p.name)

    /** Whole pass: percent and time left. */
    fun overall(context: Context, p: SyncProgress, now: Long = System.currentTimeMillis()): String =
        context.getString(R.string.sync_overall, percent(p.overall, 1), left(context, p.remainingMillis, now))

    /** Item in hand: what is happening to it, percent and bytes. */
    fun file(context: Context, p: SyncProgress): String {
        val sent = Formatter.formatShortFileSize(context, p.sent)
        val size = Formatter.formatShortFileSize(context, p.size)
        return when (p.transfer) {
            Transfer.Comparing -> context.getString(R.string.sync_file_comparing, percent(p.file, 0), sent, size)
            // Only a few MB are read, so a byte count would be made up.
            Transfer.Sampling -> context.getString(R.string.sync_file_sampling, percent(p.file, 0), size)
            Transfer.Sending -> context.getString(R.string.sync_file_sending, percent(p.file, 0), sent, size)
        }
    }

    private fun percent(fraction: Float, digits: Int): String =
        NumberFormat.getPercentInstance().apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(fraction)

    private fun left(context: Context, millis: Long?, now: Long): String {
        if (millis == null) return context.getString(R.string.sync_left_unknown)
        val minutes = (millis + 59_999) / 60_000
        if (minutes <= 1) return context.getString(R.string.sync_left_soon)
        val end = now + millis
        val at = DateUtils.formatDateTime(
            context,
            end,
            DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(end)) 0 else DateUtils.FORMAT_SHOW_DATE,
        )
        return if (minutes < 60) {
            context.getString(R.string.sync_left_minutes, minutes.toInt(), at)
        } else {
            context.getString(R.string.sync_left_hours, (minutes / 60).toInt(), (minutes % 60).toInt(), at)
        }
    }
}
