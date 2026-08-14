package com.spectroflac.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.compose.runtime.collectAsState
import com.spectroflac.export.Exporter
import com.spectroflac.ui.glass.LiquidBackdrop
import com.spectroflac.ui.screens.AnalysisOverlay
import com.spectroflac.ui.screens.BatchScreen
import com.spectroflac.ui.screens.HistoryScreen
import com.spectroflac.ui.screens.HomeScreen
import com.spectroflac.ui.screens.ResultScreen
import com.spectroflac.ui.theme.SpectroFlacTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpectroFlacApp(viewModel) }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** "Open with" and "Share to" both land here. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) viewModel.analyseSingle(uri)
    }
}

/** Asks for a persistable grant so history entries stay re-analysable. */
private class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

private class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

@androidx.compose.runtime.Composable
fun SpectroFlacApp(viewModel: MainViewModel) {
    SpectroFlacTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val history by viewModel.history.collectAsState()

        val pickFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
            uri?.let(viewModel::analyseSingle)
        }
        val pickFolder = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
            uri?.let(viewModel::analyseFolder)
        }

        viewModel.message?.let { text ->
            LaunchedEffect(text) {
                snackbar.showSnackbar(text)
                viewModel.dismissMessage()
            }
        }

        // The backdrop only drifts on the home screen: no reason to keep a full-screen shader
        // redrawing while the CPU is busy decoding, or behind a long list of results.
        LiquidBackdrop(animated = viewModel.screen is Screen.Home && viewModel.progress == null) {
            when (val screen = viewModel.screen) {
                is Screen.Home -> HomeScreen(
                    historyCount = history.size,
                    // "*/*" rather than audio/flac: plenty of providers mislabel FLAC files, and
                    // spotting a renamed MP3 is part of the job.
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    onPickFolder = { pickFolder.launch(null) },
                    onHistory = { viewModel.navigate(Screen.History) },
                )

                is Screen.Result -> ResultScreen(
                    report = screen.report,
                    onBack = viewModel::back,
                    onShare = {
                        Exporter.shareText(
                            context,
                            Exporter.textReport(screen.report),
                            "SpectroFlac — ${screen.report.fileName}",
                        )
                    },
                    onReanalyse = { viewModel.reanalyse(screen.report) },
                )

                is Screen.Batch -> BatchScreen(
                    reports = viewModel.batchResults,
                    progress = viewModel.progress,
                    onBack = viewModel::back,
                    onOpen = { viewModel.navigate(Screen.Result(it)) },
                    onExportCsv = {
                        Exporter.shareFile(
                            context,
                            "spectroflac-scan.csv",
                            Exporter.csv(viewModel.batchResults.toList()),
                            "text/csv",
                        )
                    },
                    onExportJson = {
                        Exporter.shareFile(
                            context,
                            "spectroflac-scan.json",
                            Exporter.json(viewModel.batchResults.toList()),
                            "application/json",
                        )
                    },
                )

                is Screen.History -> HistoryScreen(
                    records = history,
                    onBack = viewModel::back,
                    onOpen = viewModel::openRecord,
                    onClear = viewModel::clearHistory,
                    onExportCsv = {
                        scope.launch {
                            Exporter.shareFile(
                                context,
                                "spectroflac-history.csv",
                                Exporter.csv(viewModel.historyReports()),
                                "text/csv",
                            )
                        }
                    },
                )
            }

            viewModel.progress?.let { progress ->
                if (viewModel.screen !is Screen.Batch) {
                    AnalysisOverlay(progress, viewModel::cancel)
                }
            }

            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }

        androidx.activity.compose.BackHandler(enabled = viewModel.screen !is Screen.Home) {
            viewModel.back()
        }
    }
}
