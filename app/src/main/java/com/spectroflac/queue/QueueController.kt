package com.spectroflac.queue

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.documentfile.provider.DocumentFile
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.BuildConfig
import com.spectroflac.data.AnalysisDao
import com.spectroflac.data.toRecord
import com.spectroflac.data.toReport
import com.spectroflac.export.Exporter
import com.spectroflac.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Connects the queue to the rest of the app: it feeds the user's settings and the phone's heat and
 * battery state into the queue, stores finished files in the history, saves the queue for a
 * restart, and writes the auto-export when a scan ends.
 */
class QueueController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val queue: AnalysisQueue,
    private val settings: StateFlow<AppSettings>,
    private val monitor: DeviceMonitor,
    private val dao: AnalysisDao,
    private val store: QueueStore,
    private val messages: MutableSharedFlow<String>,
) {
    fun start() {
        val cores = Runtime.getRuntime().availableProcessors()

        queue.onWorkerStart = {
            // Slightly lower than default: the UI stays smooth while analysis threads run flat out.
            Process.setThreadPriority(if (settings.value.lowPriorityThreads) LOW_PRIORITY_NICE else Process.THREAD_PRIORITY_DEFAULT)
        }
        queue.lookupKnown = { item -> lookupKnown(item) }
        queue.onItemFinished = { item ->
            // A skipped file is already in the history; a fresh result is stored as it finishes, so a
            // crash or a cancelled scan loses nothing.
            if (item.state != ItemState.SKIPPED) item.report?.let { runCatching { dao.upsert(it.toRecord()) } }
        }
        queue.onIdle = { snapshot -> scope.launch { afterScan(snapshot) } }

        scope.launch {
            combine(settings, monitor.state) { s, device -> s to device }.collect { (s, device) ->
                queue.skipKnown = s.skipKnownFiles
                queue.setParallelism(
                    Parallelism.effective(s.parallelFiles, cores, s.thermalProtection, device.thermalStatus, device.batterySaver),
                )
                queue.setHold(
                    Parallelism.thermalPauseReason(s.thermalProtection, device.thermalStatus)
                        ?: Parallelism.batteryPauseReason(device.batteryPercent, device.charging, s.pauseBelowBatteryPercent),
                )
            }
        }

        // Save the unfinished files about once a second while the queue changes (only when the user
        // wants restore). The state is read *after* the delay, so a busy scan is saved too.
        scope.launch {
            queue.snapshot.collect {
                if (!settings.value.restoreQueueAfterRestart) return@collect
                delay(SAVE_DELAY_MS)
                val unfinished = queue.snapshot.value.items.filter { !it.isFinished }
                    .map { NewFile(it.uri, it.name, it.sizeBytes, it.lastModified) }
                store.save(unfinished)
            }
        }
    }

    /** A file is "known" when the history holds a result for the same bytes from this app version. */
    private suspend fun lookupKnown(item: QueueItem): AnalysisReport? {
        if (item.lastModified <= 0L) return null
        val report = dao.get(item.uri)?.toReport() ?: return null
        val sameFile = report.technical?.fileSizeBytes == item.sizeBytes && report.sourceModifiedMillis == item.lastModified
        return report.takeIf { sameFile && it.analyzerVersion == BuildConfig.VERSION_CODE && it.verdict.name != "ERROR" }
    }

    private suspend fun afterScan(snapshot: QueueSnapshot) {
        val s = settings.value
        runCatching {
            if (s.autoCleanDays > 0) dao.deleteOlderThan(System.currentTimeMillis() - s.autoCleanDays * DAY_MS)
            dao.trimTo(s.historyLimit)
        }
        if (s.autoExport.csv || s.autoExport.json) autoExport(snapshot, s)
        if (s.notifyOnFinish) AppNotifications.scanFinished(context, ScanSummary.from(snapshot.items))
    }

    /** Writes the CSV and/or JSON of the finished scan into the folder the user chose. */
    private fun autoExport(snapshot: QueueSnapshot, s: AppSettings) {
        val folder = s.autoExportFolder
        val reports = snapshot.finished.mapNotNull { it.report }
        if (folder == null || reports.isEmpty()) return
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("export folder unavailable")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var written = 0
            if (s.autoExport.csv) {
                write(tree, "spectroflac-scan-$stamp.csv", "text/csv", Exporter.csv(reports)); written++
            }
            if (s.autoExport.json) {
                write(tree, "spectroflac-scan-$stamp.json", "application/json", Exporter.json(reports)); written++
            }
            messages.tryEmit("Exported ${reports.size} results ($written file${if (written == 1) "" else "s"}) to ${tree.name ?: "the export folder"}")
        }.onFailure { messages.tryEmit("Auto-export failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun write(tree: DocumentFile, name: String, mime: String, text: String) {
        val file = tree.createFile(mime, name) ?: error("cannot create $name")
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(text.toByteArray()) } ?: error("cannot write $name")
    }

    companion object {
        /** Android thread niceness: 0 is default, 10 and above is the restricted background group. */
        private const val LOW_PRIORITY_NICE = 5
        private const val SAVE_DELAY_MS = 1_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
