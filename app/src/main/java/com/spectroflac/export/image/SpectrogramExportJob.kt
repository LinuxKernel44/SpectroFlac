package com.spectroflac.export.image

import android.content.Context
import android.provider.DocumentsContract
import com.spectroflac.queue.AppNotifications
import com.spectroflac.queue.FileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.OutputStream

sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val fileName: String,
        val phase: ExportPhase,
        val fraction: Float,
        val startedAtMillis: Long,
        val width: Int,
        val height: Int,
    ) : ExportState

    data class Done(val result: ExportResult, val target: ExportTarget, val fileName: String) : ExportState
    data class Failed(val message: String, val fileName: String) : ExportState
}

/**
 * Runs one spectrogram export at a time, off any screen: it lives in the application so leaving the
 * app does not stop it (a foreground service keeps the process alive). Progress and the outcome are
 * published as [state]; the UI acknowledges a finished state to return to idle.
 */
class SpectrogramExportJob(
    private val context: Context,
    private val scope: CoroutineScope,
    private val exporter: SpectrogramImageExporter,
    private val isAppInForeground: () -> Boolean,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> get() = _state

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is ExportState.Running

    /** Starts an export; ignored while another one is running. */
    fun start(request: ExportRequest, target: ExportTarget, name: String) {
        if (isRunning) return
        val started = System.currentTimeMillis()
        _state.value = ExportState.Running(name, ExportPhase.DECODING, 0f, started, request.plot.width, request.plot.height)
        job = scope.launch {
            try {
                val size = FileInfo.resolve(context, request.uri).sizeBytes
                val out = open(target)
                val result = out.use {
                    exporter.export(
                        request, size,
                        openInput = { context.contentResolver.openInputStream(request.uri) ?: error("Cannot open the audio file") },
                        out = it,
                        onProgress = { p ->
                            val current = _state.value
                            if (current is ExportState.Running) _state.value = current.copy(phase = p.phase, fraction = p.fraction)
                        },
                    )
                }
                _state.value = ExportState.Done(result, target, name)
                notifyFinished("Spectrogram ready", "$name · ${result.width} × ${result.height}")
            } catch (e: CancellationException) {
                discard(target)
                _state.value = ExportState.Idle
                throw e
            } catch (e: Throwable) {
                discard(target)
                val message = when (e) {
                    is OutOfMemoryError -> "Not enough memory for this size. Try a smaller image."
                    else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                }
                _state.value = ExportState.Failed(message, name)
                notifyFinished("Spectrogram export failed", message)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** The UI has shown the outcome: back to idle. */
    fun acknowledge() {
        if (_state.value is ExportState.Done || _state.value is ExportState.Failed) _state.value = ExportState.Idle
    }

    private fun open(target: ExportTarget): OutputStream = when (target) {
        is ExportTarget.Document -> context.contentResolver.openOutputStream(target.uri, "w") ?: error("Cannot write the chosen file")
        is ExportTarget.Share -> FileOutputStream(target.file.also { it.parentFile?.mkdirs() })
    }

    /** A cancelled or failed export must not leave a broken half-image behind. */
    private fun discard(target: ExportTarget) {
        runCatching {
            when (target) {
                is ExportTarget.Document -> DocumentsContract.deleteDocument(context.contentResolver, target.uri)
                is ExportTarget.Share -> target.file.delete()
            }
        }
    }

    private fun notifyFinished(title: String, text: String) {
        if (!isAppInForeground()) AppNotifications.exportFinished(context, title, text)
    }
}
