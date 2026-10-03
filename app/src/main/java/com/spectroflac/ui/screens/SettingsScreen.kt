package com.spectroflac.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.spectroflac.BuildConfig
import com.spectroflac.queue.Parallelism
import com.spectroflac.settings.AppSettings
import com.spectroflac.settings.AutoExportFormat
import com.spectroflac.ui.components.GlassButton
import com.spectroflac.ui.components.SectionCard
import com.spectroflac.ui.components.SettingSwitch
import com.spectroflac.ui.components.ToggleChip
import com.spectroflac.ui.glass.supportsLiquidGlass
import com.spectroflac.ui.theme.SpectroColors

object SettingsTags {
    const val PARALLEL = "settings-parallel"
    fun parallelChip(label: String) = "settings-parallel-$label"
    const val BACKGROUND = "settings-background"
    const val THERMAL = "settings-thermal"
    const val GLASS = "settings-glass"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    cores: Int,
    exportFolderName: String?,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onPickExportFolder: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Settings", "Everything stays on this device", onBack) }

        item {
            SectionCard("Performance", icon = Icons.Filled.Memory) {
                SettingLabel("Files analysed at the same time")
                FlowRow(
                    Modifier.testTag(SettingsTags.PARALLEL),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleChip("Auto", settings.parallelFiles == AppSettings.AUTO, { onChange { it.copy(parallelFiles = AppSettings.AUTO) } }, modifier = Modifier.testTag(SettingsTags.parallelChip("Auto")))
                    for (n in 1..AppSettings.MAX_PARALLEL) {
                        ToggleChip("$n", settings.parallelFiles == n, { onChange { it.copy(parallelFiles = n) } }, modifier = Modifier.testTag(SettingsTags.parallelChip("$n")))
                    }
                }
                SettingNote(
                    "Auto runs ${Parallelism.auto(cores)} of this phone's $cores cores at once, fewer when it gets hot " +
                        "or Battery Saver is on. A single file is always decoded on one thread; the speed-up comes " +
                        "from analysing several files together.",
                )
                Spacer(Modifier.height(6.dp))
                SettingSwitch(
                    "Keep scanning in the background",
                    "A foreground service with a progress notification, so a big folder finishes with the screen off.",
                    settings.backgroundScan, { v -> onChange { it.copy(backgroundScan = v) } },
                    modifier = Modifier.testTag(SettingsTags.BACKGROUND),
                )
                SettingSwitch(
                    "Thermal protection",
                    "Use fewer threads when the phone warms up, and hold back when it is critically hot.",
                    settings.thermalProtection, { v -> onChange { it.copy(thermalProtection = v) } },
                    modifier = Modifier.testTag(SettingsTags.THERMAL),
                )
                SettingSwitch(
                    "Low-priority threads",
                    "Lets the interface stay smooth while the analysis threads run flat out.",
                    settings.lowPriorityThreads, { v -> onChange { it.copy(lowPriorityThreads = v) } },
                )
                SettingSwitch(
                    "Keep the screen on during a scan",
                    "Stops the screen from turning off while files are being analysed.",
                    settings.keepScreenOn, { v -> onChange { it.copy(keepScreenOn = v) } },
                )
                Spacer(Modifier.height(6.dp))
                SettingLabel("Pause below this battery level")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.BATTERY_THRESHOLDS.forEach { pct ->
                        ToggleChip(
                            if (pct == 0) "Never" else "$pct %", settings.pauseBelowBatteryPercent == pct,
                            { onChange { it.copy(pauseBelowBatteryPercent = pct) } },
                            accent = SpectroColors.Suspicious,
                        )
                    }
                }
                SettingNote("Only while the phone is not charging; the scan carries on by itself once it is plugged in.")
            }
        }

        item {
            SectionCard("Queue", icon = Icons.Filled.Tune) {
                SettingSwitch(
                    "Skip files already analysed",
                    "A re-scan only processes new or changed files. A result is reused only when the file is unchanged and the same app version analysed it.",
                    settings.skipKnownFiles, { v -> onChange { it.copy(skipKnownFiles = v) } },
                )
                SettingSwitch(
                    "Offer to resume after a restart",
                    "Remembers the files still waiting when the app is closed, and offers to continue next time. Off: an interrupted scan starts fresh.",
                    settings.restoreQueueAfterRestart, { v -> onChange { it.copy(restoreQueueAfterRestart = v) } },
                )
            }
        }

        item {
            SectionCard("Results and history", icon = Icons.Filled.History) {
                SettingLabel("Keep the latest analyses")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.HISTORY_LIMITS.forEach { n ->
                        ToggleChip("$n", settings.historyLimit == n, { onChange { it.copy(historyLimit = n) } }, accent = SpectroColors.BackdropViolet)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingLabel("Delete analyses older than")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.CLEAN_DAYS.forEach { d ->
                        ToggleChip(
                            when (d) { 0 -> "Never"; 365 -> "1 year"; else -> "$d days" },
                            settings.autoCleanDays == d, { onChange { it.copy(autoCleanDays = d) } }, accent = SpectroColors.BackdropViolet,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingLabel("Export automatically when a scan ends")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoExportFormat.entries.forEach { f ->
                        ToggleChip(f.label, settings.autoExport == f, { onChange { it.copy(autoExport = f) } }, accent = SpectroColors.BackdropMagenta)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = exportFolderName?.let { "Folder: $it" } ?: "No export folder chosen",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectroColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton(
                    if (exportFolderName == null) "Choose folder" else "Change folder",
                    onPickExportFolder, icon = Icons.Filled.Folder, accent = SpectroColors.BackdropMagenta,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (settings.autoExport != AutoExportFormat.OFF && exportFolderName == null) {
                    SettingNote("Choose a folder: without one, nothing is exported automatically.")
                }
            }
        }

        item {
            SectionCard("Appearance", icon = Icons.Filled.Palette) {
                SettingSwitch(
                    "Liquid glass effects",
                    if (supportsLiquidGlass) "Refracting glass panels. Turn off for a cooler, lighter interface during long scans."
                    else "This device (Android 12 or older) already uses the frosted look.",
                    settings.liquidGlass, { v -> onChange { it.copy(liquidGlass = v) } },
                    modifier = Modifier.testTag(SettingsTags.GLASS),
                )
                SettingSwitch(
                    "Animated backdrop",
                    "The slowly drifting background on the home screen.",
                    settings.animatedBackdrop, { v -> onChange { it.copy(animatedBackdrop = v) } },
                )
            }
        }

        item {
            SectionCard("About", icon = Icons.Filled.Info) {
                Text("SpectroFlac ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Licensed under the PolyForm Noncommercial License 1.0.0. The analysis runs entirely on this device; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary,
                )
                Spacer(Modifier.height(14.dp))
                GlassButton("Reset all settings", onReset, icon = Icons.Filled.RestartAlt, accent = SpectroColors.Fake, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
}
