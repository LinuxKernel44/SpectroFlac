package com.spectroflac.queue

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
import com.spectroflac.util.formatEta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake while the queue works, so a big folder finishes with the
 * screen off. It shows one progress notification and stops itself when the queue runs dry.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SpectroFlacApp
        ensureChannel()
        startForeground(
            NOTIFICATION_ID,
            build(0, 0, 0f, null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()

        if (watcher?.isActive != true) {
            watcher = scope.launch {
                // Stop when the queue has been idle for a moment. This is driven by the active/idle state
                // itself, not by progress updates (there are none once the last file is done), and the
                // grace period is cancelled if work shows up again.
                launch {
                    app.queue.isActive.collectLatest { active ->
                        if (!active) {
                            delay(IDLE_GRACE_MS)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                combine(app.queue.stats, app.queue.snapshot) { stats, snap -> stats to snap }.collect { (stats, snap) ->
                    if (snap.isActive) {
                        val status = snap.holdReason ?: if (snap.paused) "Paused" else null
                        val note = build(stats.completed, stats.total, stats.fraction, stats.etaMillis, status)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, note)
                        delay(1000) // at most one update a second
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits how long a data-sync service may run; the queue itself keeps going. */
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
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpectroFlac:scan").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scan progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while SpectroFlac analyses a batch of files"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun build(completed: Int, total: Int, fraction: Float, etaMillis: Long?, status: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            total == 0 -> "Starting…"
            status != null -> "$completed of $total · $status"
            else -> "$completed of $total · " + (etaMillis?.let { formatEta(it) + " left" } ?: "estimating time left…")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Analysing FLAC files")
            .setContentText(text)
            .setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "scan_progress"
        const val NOTIFICATION_ID = 4101
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val IDLE_GRACE_MS = 1500L
    }
}
