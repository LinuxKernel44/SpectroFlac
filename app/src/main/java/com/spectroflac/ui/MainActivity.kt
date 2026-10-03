package com.spectroflac.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.spectroflac.export.image.ExportState
import com.spectroflac.export.image.ExportTarget
import com.spectroflac.settings.AppSettings
import com.spectroflac.ui.components.ExportDialog
import com.spectroflac.ui.components.ExportProgressOverlay
import com.spectroflac.util.formatBytes
import com.spectroflac.ui.components.PermissionPrompt
import com.spectroflac.ui.glass.LocalGlassEnabled
import com.spectroflac.ui.screens.QueueScreen
import com.spectroflac.ui.screens.SettingsScreen
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
import com.spectroflac.ui.screens.HistoryScreen
import com.spectroflac.ui.screens.HomeScreen
import com.spectroflac.ui.screens.ResultScreen
import com.spectroflac.ui.screens.SpectrogramScreen
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

    /** "Open with", "Share to" (one or several files) and a tap on the scan notification all land here. */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            viewModel.openQueue()
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(viewModel::analyseSingle)
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(viewModel::analyseSingle)
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                viewModel.analyseFiles(uris)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "com.spectroflac.OPEN_QUEUE"
    }
}

/** Asks for a persistable grant so history entries stay re-analysable. */
private class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

private class OpenPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
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

/** The auto-export folder must be writable as well as persistable. */
private class OpenWritableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
}

@androidx.compose.runtime.Composable
fun SpectroFlacApp(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    CompositionLocalProvider(LocalGlassEnabled provides settings.liquidGlass) {
        SpectroFlacTheme {
            AppContent(viewModel, settings)
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppContent(viewModel: MainViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val history by viewModel.history.collectAsState()
    val queueSnapshot by viewModel.queue.snapshot.collectAsState()
    val queueStats by viewModel.queue.stats.collectAsState()
    val queueProgress = viewModel.queue.progress.collectAsState()
    val queueActive by viewModel.queue.isActive.collectAsState()
    val restorable by viewModel.restorable.collectAsState()

    val pickFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        uri?.let(viewModel::analyseSingle)
    }
    val pickFiles = rememberLauncherForActivityResult(OpenPersistableDocuments()) { uris ->
        viewModel.analyseFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        uri?.let(viewModel::analyseFolder)
    }
    val pickExportFolder = rememberLauncherForActivityResult(OpenWritableTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.updateSettings { it.copy(autoExportFolder = uri.toString()) }
        }
    }
    // ---- background permissions: asked once, when the first scan starts -------------------------
    val permissions by rememberPermissionStatus()
    val prefs = remember { context.getSharedPreferences("spectroflac_ui", Context.MODE_PRIVATE) }
    var showPermissionPrompt by remember { mutableStateOf(false) }
    var batteryAfterNotifications by remember { mutableStateOf(false) }
    val createImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) viewModel.startSave(uri) else viewModel.cancelPendingSave()
    }
    val exportState by viewModel.exportJob.state.collectAsState()

    // The outcome of an export: share the finished image, or say where it went.
    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is ExportState.Done -> {
                val size = formatBytes(state.result.bytes)
                when (val target = state.target) {
                    is ExportTarget.Share -> Exporter.shareImage(context, target.file, target.displayName)
                    is ExportTarget.Document -> viewModel.showMessage(
                        "Saved ${target.displayName} (${state.result.width} \u00D7 ${state.result.height}, $size)" +
                            if (state.result.partial) " \u2014 the file was damaged, so only part of it is drawn" else "",
                    )
                }
                viewModel.exportJob.acknowledge()
            }
            is ExportState.Failed -> {
                viewModel.showMessage("Export failed: ${state.message}")
                viewModel.exportJob.acknowledge()
            }
            else -> Unit
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Remember a refusal: Android will not show the dialog again, so Settings must open the system page instead.
        if (!granted) prefs.edit().putBoolean("notifications_denied", true).apply()
        if (batteryAfterNotifications) {
            batteryAfterNotifications = false
            if (!Permissions.batteryUnrestricted(context)) Permissions.requestBatteryExemption(context)
        }
    }

    fun requestNotifications() {
        if (Permissions.needsRuntimeNotificationPermission && !prefs.getBoolean("notifications_denied", false)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Permissions.openNotificationSettings(context)
        }
    }

    LaunchedEffect(queueActive) {
        if (queueActive && !prefs.getBoolean("permissions_prompted", false) && !Permissions.status(context).allGranted) {
            prefs.edit().putBoolean("permissions_prompted", true).apply()
            showPermissionPrompt = true
        }
    }

    if (showPermissionPrompt) {
        PermissionPrompt(
            notificationsMissing = !permissions.notifications,
            batteryMissing = !permissions.battery,
            onSkip = { showPermissionPrompt = false },
            onContinue = {
                showPermissionPrompt = false
                if (!permissions.notifications && Permissions.needsRuntimeNotificationPermission) {
                    batteryAfterNotifications = !permissions.battery
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!permissions.battery) {
                    Permissions.requestBatteryExemption(context)
                }
            },
        )
    }

    val activity = context as? Activity
    DisposableEffect(settings.keepScreenOn, queueActive) {
        val keep = settings.keepScreenOn && queueActive
        if (keep) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val exportFolderName = remember(settings.autoExportFolder) {
        settings.autoExportFolder?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
    }

    // The backdrop only drifts on the home screen: no reason to keep a full-screen shader
    // redrawing while the CPU is busy decoding, or behind a long list of results.
    LiquidBackdrop(animated = viewModel.screen is Screen.Home && viewModel.progress == null && settings.animatedBackdrop) {
        when (val screen = viewModel.screen) {
            is Screen.Home -> HomeScreen(
                historyCount = history.size,
                queueCount = queueSnapshot.items.size,
                restorableCount = restorable.size,
                // "*/*" rather than audio/flac: plenty of providers mislabel FLAC files, and
                // spotting a renamed MP3 is part of the job.
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onPickFolder = { pickFolder.launch(null) },
                onHistory = { viewModel.navigate(Screen.History) },
                onOpenQueue = viewModel::openQueue,
                onSettings = { viewModel.navigate(Screen.Settings) },
                onResumeRestored = viewModel::resumeRestored,
                onDiscardRestored = viewModel::discardRestored,
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
                onOpenSpectrogram = { viewModel.navigate(Screen.Spectrogram(screen.report)) },
                onExportImage = { viewModel.openExportDialog(screen.report) },
            )

            is Screen.Spectrogram -> SpectrogramScreen(
                report = screen.report,
                onBack = viewModel::back,
                onExport = { viewModel.openExportDialog(screen.report) },
            )

            is Screen.Queue -> QueueScreen(
                snapshot = queueSnapshot,
                progress = queueProgress,
                stats = queueStats,
                filter = settings.queueFilter,
                sort = settings.queueSort,
                listing = viewModel.listing,
                onBack = viewModel::back,
                onSettings = { viewModel.navigate(Screen.Settings) },
                onPause = viewModel::pauseQueue,
                onResume = viewModel::resumeQueue,
                onCancelAll = viewModel::cancelAll,
                onCancel = viewModel::cancelItem,
                onMoveToTop = viewModel::moveToTop,
                onRetry = viewModel::retry,
                onRetryFailed = viewModel::retryFailed,
                onClearFinished = viewModel::clearFinished,
                onOpen = viewModel::openQueueItem,
                onExportCsv = {
                    Exporter.shareFile(context, "spectroflac-scan.csv", Exporter.csv(viewModel.exportReports()), "text/csv")
                },
                onExportJson = {
                    Exporter.shareFile(context, "spectroflac-scan.json", Exporter.json(viewModel.exportReports()), "application/json")
                },
                onFilter = { f -> viewModel.updateSettings { it.copy(queueFilter = f) } },
                onSort = { so -> viewModel.updateSettings { it.copy(queueSort = so) } },
            )

            is Screen.Settings -> SettingsScreen(
                settings = settings,
                cores = Runtime.getRuntime().availableProcessors(),
                exportFolderName = exportFolderName,
                permissions = permissions,
                onRequestNotifications = ::requestNotifications,
                onRequestBattery = { Permissions.requestBatteryExemption(context) },
                onChange = viewModel::updateSettings,
                onPickExportFolder = { pickExportFolder.launch(null) },
                onReset = viewModel::resetSettings,
                onBack = viewModel::back,
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
            AnalysisOverlay(progress, viewModel::cancel)
        }

        viewModel.exportDialogReport?.let { report ->
            ExportDialog(
                report = report,
                onDismiss = viewModel::closeExportDialog,
                onSave = { choice -> createImage.launch(viewModel.prepareSave(report, choice)) },
                onShare = { choice -> viewModel.startShare(report, choice) },
            )
        }

        (exportState as? ExportState.Running)?.let { running ->
            ExportProgressOverlay(running, onCancel = viewModel.exportJob::cancel)
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    androidx.activity.compose.BackHandler(enabled = viewModel.screen !is Screen.Home) {
        viewModel.back()
    }
}
