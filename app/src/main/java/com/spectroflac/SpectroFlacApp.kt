package com.spectroflac

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.spectroflac.analysis.FlacAnalyzer
import com.spectroflac.data.AnalysisDao
import com.spectroflac.data.HistoryDatabase
import com.spectroflac.queue.AnalysisQueue
import com.spectroflac.queue.DeviceMonitor
import com.spectroflac.queue.NewFile
import com.spectroflac.queue.QueueController
import com.spectroflac.queue.QueueStore
import com.spectroflac.queue.ScanService
import com.spectroflac.settings.AppSettings
import com.spectroflac.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns what must outlive any screen: the settings, the history, the analyser and the scan queue.
 * The queue lives here (not in a ViewModel) so a scan keeps going when the screen changes and while
 * the foreground service keeps the process alive.
 */
class SpectroFlacApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(this) }
    val history: AnalysisDao by lazy { HistoryDatabase.get(this).analyses() }
    val analyzer by lazy { FlacAnalyzer(this) }
    val monitor by lazy { DeviceMonitor(this) }
    private val queueStore by lazy { QueueStore(File(filesDir, "queue.json")) }

    /** The current settings. Seeded synchronously so the very first frame already uses them. */
    val settings: StateFlow<AppSettings> by lazy {
        val initial = runBlocking { settingsRepository.current() }
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, initial)
    }

    /** One-line notices for the UI (e.g. the result of an auto-export). */
    val messages: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)

    /** Files left over from an interrupted scan, offered for resuming when the user opted in. */
    val restorable = MutableStateFlow<List<NewFile>>(emptyList())

    val queue: AnalysisQueue by lazy {
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(AppSettings.MAX_PARALLEL) { runnable ->
            Thread(runnable, "spectroflac-worker-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        AnalysisQueue(
            scope = appScope,
            workers = pool.asCoroutineDispatcher(),
            analyze = { uri, onProgress -> analyzer.analyze(android.net.Uri.parse(uri), onProgress) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        monitor.start()
        QueueController(this, appScope, queue, settings, monitor, history, queueStore, messages).start()

        if (settings.value.restoreQueueAfterRestart) restorable.value = queueStore.load() else queueStore.clear()

        // Run the scan in a foreground service (notification + wake lock) so it survives a locked screen.
        appScope.launch {
            queue.isActive.collect { active ->
                if (active && settings.value.backgroundScan) startScanService()
            }
        }
    }

    private fun startScanService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java)) }
    }

    fun discardRestorable() {
        restorable.value = emptyList()
        queueStore.clear()
    }
}

val Context.app: SpectroFlacApp get() = applicationContext as SpectroFlacApp
