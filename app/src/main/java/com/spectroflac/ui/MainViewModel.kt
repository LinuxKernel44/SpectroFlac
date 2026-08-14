package com.spectroflac.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.FlacAnalyzer
import com.spectroflac.analysis.Verdict
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
    data object Batch : Screen
    data object History : Screen
}

data class Progress(
    val fileName: String,
    val fraction: Float,
    val index: Int = 0,
    val total: Int = 1,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val analyzer = FlacAnalyzer(app)
    private val dao = HistoryDatabase.get(app).analyses()

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val batchResults = mutableStateListOf<AnalysisReport>()

    private val _history = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val history: StateFlow<List<AnalysisRecord>> = _history.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            dao.observeAll().collect { _history.value = it }
        }
    }

    fun navigate(target: Screen) {
        screen = target
    }

    fun back() {
        screen = when (screen) {
            is Screen.Result -> if (batchResults.isNotEmpty()) Screen.Batch else Screen.Home
            else -> Screen.Home
        }
    }

    fun dismissMessage() {
        message = null
    }

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
            screen = Screen.Result(report)
        }
    }

    fun analyseFolder(treeUri: Uri) {
        persistTreePermission(treeUri)
        job?.cancel()
        batchResults.clear()
        screen = Screen.Batch
        job = viewModelScope.launch {
            progress = Progress("Listing files…", 0f)
            val files = withContext(Dispatchers.IO) { collectFlacFiles(treeUri) }
            if (files.isEmpty()) {
                progress = null
                message = "No .flac files found in that folder."
                return@launch
            }
            files.forEachIndexed { index, file ->
                progress = Progress(file.second, 0f, index + 1, files.size)
                val report = withContext(Dispatchers.Default) {
                    analyzer.analyze(file.first) { fraction ->
                        progress = progress?.copy(fraction = fraction)
                    }
                }
                batchResults += report
                store(report)
            }
            progress = null
        }
    }

    fun reanalyse(report: AnalysisReport) {
        analyseSingle(Uri.parse(report.uri))
    }

    fun openRecord(record: AnalysisRecord) {
        screen = Screen.Result(record.toReport())
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

    /** Walks the picked tree, depth first, keeping anything that ends in .flac. */
    private fun collectFlacFiles(treeUri: Uri): List<Pair<Uri, String>> {
        val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return emptyList()
        val out = mutableListOf<Pair<Uri, String>>()
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
            children.sortedBy { it.name?.lowercase() ?: "" }.forEach { child ->
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.name?.endsWith(".flac", ignoreCase = true) == true ->
                        out += child.uri to (child.name ?: "file.flac")
                }
            }
        }
        return out.sortedBy { it.second.lowercase() }
    }
}
