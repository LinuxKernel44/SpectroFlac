package com.spectroflac.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectroflac.SpectroFlacApp
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import com.spectroflac.queue.FileInfo
import com.spectroflac.queue.NewFile
import com.spectroflac.queue.QueueItem
import com.spectroflac.settings.AppSettings
import com.spectroflac.data.AnalysisRecord
import com.spectroflac.data.HistoryDatabase
import com.spectroflac.data.toRecord
import com.spectroflac.data.toReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Result(val report: AnalysisReport) : Screen
    data class Spectrogram(val report: AnalysisReport) : Screen
    data object Queue : Screen
    data object Settings : Screen
    data object History : Screen
}

data class Progress(
    val fileName: String,
    val fraction: Float,
    val index: Int = 0,
    val total: Int = 1,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val spectroApp = app as SpectroFlacApp
    private val analyzer = spectroApp.analyzer
    private val dao = spectroApp.history
    val queue = spectroApp.queue
    val settings: StateFlow<AppSettings> = spectroApp.settings
    val restorable: StateFlow<List<NewFile>> = spectroApp.restorable

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** True while a folder is still being walked; files are queued as they are found. */
    var listing by mutableStateOf(false)
        private set

    private val _history = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val history: StateFlow<List<AnalysisRecord>> = _history.asStateFlow()

    private var job: Job? = null
    private var listingJob: Job? = null
    private val backStack = ArrayDeque<Screen>()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { _history.value = it }
        }
        viewModelScope.launch {
            spectroApp.messages.collect { message = it }
        }
    }

    // ---- navigation ------------------------------------------------------------------------

    /** Opens [target] on top of the current screen; Back returns to it. */
    fun navigate(target: Screen) {
        if (target == screen) return
        backStack.addLast(screen)
        screen = target
    }

    /** Swaps the current screen for [target] without leaving a Back entry behind. */
    private fun replace(target: Screen) {
        screen = target
    }

    fun back() {
        screen = backStack.removeLastOrNull() ?: Screen.Home
    }

    fun openQueue() {
        if (screen != Screen.Queue) navigate(Screen.Queue)
    }

    fun dismissMessage() {
        message = null
    }

    // ---- single file -----------------------------------------------------------------------

    fun analyseSingle(uri: Uri) {
        persistPermission(uri)
        job?.cancel()
        job = viewModelScope.launch {
            progress = Progress(displayNameOf(uri), 0f)
            val report = withContext(Dispatchers.Default) {
                analyzer.analyze(uri) { fraction ->
                    progress = progress?.copy(fraction = fraction)
                }
            }
            store(report)
            progress = null
            if (screen == Screen.Home) navigate(Screen.Result(report)) else replace(Screen.Result(report))
        }
    }

    // ---- several files and folders ---------------------------------------------------------

    /** One file goes straight to its result; two or more go through the queue. */
    fun analyseFiles(uris: List<Uri>) {
        val unique = uris.distinct()
        when {
            unique.isEmpty() -> return
            unique.size == 1 -> analyseSingle(unique.first())
            else -> {
                unique.forEach(::persistPermission)
                val context = getApplication<Application>()
                viewModelScope.launch {
                    val files = withContext(Dispatchers.IO) { unique.map { FileInfo.resolve(context, it) } }
                    queue.enqueue(files)
                    openQueue()
                }
            }
        }
    }

    fun analyseFolder(treeUri: Uri) {
        persistTreePermission(treeUri)
        listingJob?.cancel()
        listing = true
        openQueue()
        listingJob = viewModelScope.launch(Dispatchers.IO) {
            var found = 0
            try {
                found = walkTree(treeUri) { batch -> queue.enqueue(batch) }
            } finally {
                listing = false
            }
            if (found == 0) {
                message = "No .flac files found in that folder."
                if (queue.snapshot.value.items.isEmpty()) viewModelScope.launch { if (screen == Screen.Queue) back() }
            }
        }
    }

    fun resumeRestored() {
        val files = restorable.value
        spectroApp.discardRestorable()
        if (files.isNotEmpty()) {
            queue.enqueue(files)
            openQueue()
        }
    }

    fun discardRestored() = spectroApp.discardRestorable()

    // ---- queue controls --------------------------------------------------------------------

    fun pauseQueue() = queue.pause()
    fun resumeQueue() = queue.resume()
    fun cancelAll() = queue.cancelAll()
    fun cancelItem(id: Long) = queue.cancel(id)
    fun moveToTop(id: Long) = queue.moveToTop(id)
    fun retry(id: Long) = queue.retry(id)
    fun retryFailed() = queue.retryFailed()
    fun clearFinished() = queue.clearFinished()

    /** The queue keeps only light results, so opening a file analyses it again to rebuild the charts. */
    fun openQueueItem(item: QueueItem) {
        val uri = Uri.parse(item.uri)
        job?.cancel()
        job = viewModelScope.launch {
            progress = Progress(item.name, 0f)
            val report = withContext(Dispatchers.Default) {
                analyzer.analyze(uri) { fraction -> progress = progress?.copy(fraction = fraction) }
            }
            progress = null
            navigate(Screen.Result(report))
        }
    }

    fun exportReports(): List<AnalysisReport> = queue.reports()

    // ---- settings --------------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { spectroApp.settingsRepository.update(transform) }
    }

    fun resetSettings() {
        viewModelScope.launch { spectroApp.settingsRepository.reset() }
    }

    // ---- history ---------------------------------------------------------------------------

    fun reanalyse(report: AnalysisReport) {
        analyseSingle(Uri.parse(report.uri))
    }

    fun openRecord(record: AnalysisRecord) {
        navigate(Screen.Result(record.toReport()))
    }

    fun cancel() {
        job?.cancel()
        job = null
        progress = null
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clear() }
    }

    fun deleteRecord(record: AnalysisRecord) {
        viewModelScope.launch { dao.delete(record.uri) }
    }

    suspend fun historyReports(): List<AnalysisReport> = dao.all().map { it.toReport() }

    fun counts(reports: List<AnalysisReport>): Map<Verdict, Int> =
        reports.groupingBy { it.verdict }.eachCount()

    private suspend fun store(report: AnalysisReport) {
        runCatching { dao.upsert(report.toRecord()) }
    }

    private fun persistPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun persistTreePermission(uri: Uri) = persistPermission(uri)

    private fun displayNameOf(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /**
     * Walks the picked tree depth first and hands every .flac file to [onBatch] in groups, so the queue
     * starts working while a big library is still being listed. Returns how many files were found.
     */
    private fun walkTree(treeUri: Uri, onBatch: (List<NewFile>) -> Unit): Int {
        val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return 0
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        var batch = ArrayList<NewFile>()
        var found = 0
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
            val sorted = children.sortedBy { it.name?.lowercase() ?: "" }
            // Subfolders go on the stack in reverse so they are visited in alphabetical order.
            sorted.filter { it.isDirectory }.asReversed().forEach { stack.addLast(it) }
            sorted.filter { !it.isDirectory && it.name?.endsWith(".flac", ignoreCase = true) == true }.forEach { file ->
                batch += NewFile(file.uri.toString(), file.name ?: "file.flac", file.length(), file.lastModified())
                found++
                if (batch.size >= LIST_BATCH) {
                    onBatch(batch)
                    batch = ArrayList()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
        return found
    }

    private companion object {
        const val LIST_BATCH = 25
    }
}
