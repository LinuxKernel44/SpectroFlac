package com.spectroflac.queue

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spectroflac.R
import com.spectroflac.ui.MainActivity

/** The app's notifications: the ongoing progress ones live in the services, the "finished" ones here. */
object AppNotifications {
    const val CHANNEL_FINISHED = "task_finished"
    const val ID_SCAN_FINISHED = 4102
    const val ID_EXPORT_FINISHED = 4103

    /** True when a notification would actually be shown (permission granted and not blocked). */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureFinishedChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_FINISHED) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_FINISHED, "Finished scans and exports", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when a scan or a spectrogram export is done"
                },
            )
        }
    }

    /** Posts the outcome of a spectrogram export; a tap opens the app. */
    fun exportFinished(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        ensureFinishedChannel(context)
        val open = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_EXPORT_FINISHED, note) }
    }

    /** Posts "Scan complete: 123 files — 118 genuine, 4 not genuine, 1 damaged"; a tap opens the queue. */
    fun scanFinished(context: Context, summary: ScanSummary) {
        if (!canNotify(context)) return
        ensureFinishedChannel(context)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(summary.title)
            .setContentText(summary.text())
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.text()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_SCAN_FINISHED, note) }
    }
}
