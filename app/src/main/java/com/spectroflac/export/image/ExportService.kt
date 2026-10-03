package com.spectroflac.export.image

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.spectroflac.R
import com.spectroflac.SpectroFlacApp
import com.spectroflac.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps a long spectrogram export alive when the app is left: a foreground service with a progress
 * notification (and a Cancel action) and a wake lock. It stops by itself once the export is over.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SpectroFlacApp
        if (intent?.action == ACTION_CANCEL) {
            app.exportJob.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, build("Preparing…", 0f, indeterminate = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireWakeLock()

        if (watcher?.isActive != true) {
            watcher = scope.launch {
                launch {
                    app.exportJob.state.map { it is ExportState.Running }.distinctUntilChanged().collectLatest { running ->
                        if (!running) {
                            delay(IDLE_GRACE_MS)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                app.exportJob.state.collect { state ->
                    if (state is ExportState.Running) {
                        val text = String.format(Locale.US, "%s · %d %%", state.phase.label, (state.fraction * 100).toInt())
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, build(text, state.fraction, indeterminate = false, title = "Exporting spectrogram · ${state.width} × ${state.height}"))
                        delay(1000)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpectroFlac:export").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Export progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while SpectroFlac renders a spectrogram image"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun build(text: String, fraction: Float, indeterminate: Boolean, title: String = "Exporting spectrogram"): Notification {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), indeterminate)
            .addAction(0, "Cancel", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "export_progress"
        const val NOTIFICATION_ID = 4104
        const val ACTION_CANCEL = "com.spectroflac.EXPORT_CANCEL"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val IDLE_GRACE_MS = 1500L
    }
}
