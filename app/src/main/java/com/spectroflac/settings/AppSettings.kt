package com.spectroflac.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AutoExportFormat(val label: String) {
    OFF("Off"), CSV("CSV"), JSON("JSON"), BOTH("CSV + JSON");

    val csv: Boolean get() = this == CSV || this == BOTH
    val json: Boolean get() = this == JSON || this == BOTH
}

/** Which finished files the queue lists. */
enum class QueueFilter(val label: String) {
    ALL("All"), GENUINE("Genuine"), SUSPICIOUS("Suspicious"), FAKE("Not genuine"), DAMAGED("Damaged"), ERRORS("Errors")
}

enum class QueueSort(val label: String) {
    PROCESSING_ORDER("Latest first"), NAME("Name"), VERDICT("Worst first")
}

/** Everything the user can change in Settings. The defaults are what a fresh install uses. */
data class AppSettings(
    /** 0 = Auto, otherwise the number of files analysed at the same time (1..[MAX_PARALLEL]). */
    val parallelFiles: Int = AUTO,
    val backgroundScan: Boolean = true,
    val thermalProtection: Boolean = true,
    /** Pause scans below this battery percentage while not charging; 0 = never. */
    val pauseBelowBatteryPercent: Int = 0,
    val lowPriorityThreads: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** Post a notification with the verdict counts when a scan finishes. */
    val notifyOnFinish: Boolean = true,
    val restoreQueueAfterRestart: Boolean = false,
    val skipKnownFiles: Boolean = false,
    /** Newest analyses kept in the history. */
    val historyLimit: Int = 500,
    /** Analyses older than this many days are removed; 0 = keep. */
    val autoCleanDays: Int = 0,
    val autoExport: AutoExportFormat = AutoExportFormat.OFF,
    /** Tree URI of the folder auto-exports are written to. */
    val autoExportFolder: String? = null,
    val queueSort: QueueSort = QueueSort.PROCESSING_ORDER,
    val queueFilter: QueueFilter = QueueFilter.ALL,
    val liquidGlass: Boolean = true,
    val animatedBackdrop: Boolean = true,
) {
    companion object {
        const val AUTO = 0
        const val MAX_PARALLEL = 8
        val HISTORY_LIMITS = listOf(100, 250, 500)
        val CLEAN_DAYS = listOf(0, 30, 90, 365)
        val BATTERY_THRESHOLDS = listOf(0, 10, 15, 20, 30)
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [AppSettings] in a DataStore; reads never fail, a bad value falls back to its default. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val parallel = intPreferencesKey("parallel_files")
        val background = booleanPreferencesKey("background_scan")
        val thermal = booleanPreferencesKey("thermal_protection")
        val batteryPause = intPreferencesKey("pause_below_battery")
        val lowPriority = booleanPreferencesKey("low_priority_threads")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val notifyOnFinish = booleanPreferencesKey("notify_on_finish")
        val restoreQueue = booleanPreferencesKey("restore_queue")
        val skipKnown = booleanPreferencesKey("skip_known")
        val historyLimit = intPreferencesKey("history_limit")
        val cleanDays = intPreferencesKey("auto_clean_days")
        val autoExport = stringPreferencesKey("auto_export")
        val exportFolder = stringPreferencesKey("auto_export_folder")
        val sort = stringPreferencesKey("queue_sort")
        val filter = stringPreferencesKey("queue_filter")
        val glass = booleanPreferencesKey("liquid_glass")
        val backdrop = booleanPreferencesKey("animated_backdrop")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun reset() {
        context.settingsStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            parallelFiles = (this[Keys.parallel] ?: d.parallelFiles).coerceIn(0, AppSettings.MAX_PARALLEL),
            backgroundScan = this[Keys.background] ?: d.backgroundScan,
            thermalProtection = this[Keys.thermal] ?: d.thermalProtection,
            pauseBelowBatteryPercent = this[Keys.batteryPause] ?: d.pauseBelowBatteryPercent,
            lowPriorityThreads = this[Keys.lowPriority] ?: d.lowPriorityThreads,
            keepScreenOn = this[Keys.keepScreenOn] ?: d.keepScreenOn,
            notifyOnFinish = this[Keys.notifyOnFinish] ?: d.notifyOnFinish,
            restoreQueueAfterRestart = this[Keys.restoreQueue] ?: d.restoreQueueAfterRestart,
            skipKnownFiles = this[Keys.skipKnown] ?: d.skipKnownFiles,
            historyLimit = this[Keys.historyLimit] ?: d.historyLimit,
            autoCleanDays = this[Keys.cleanDays] ?: d.autoCleanDays,
            autoExport = enumOr(this[Keys.autoExport], d.autoExport),
            autoExportFolder = this[Keys.exportFolder]?.takeIf { it.isNotBlank() },
            queueSort = enumOr(this[Keys.sort], d.queueSort),
            queueFilter = enumOr(this[Keys.filter], d.queueFilter),
            liquidGlass = this[Keys.glass] ?: d.liquidGlass,
            animatedBackdrop = this[Keys.backdrop] ?: d.animatedBackdrop,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[Keys.parallel] = s.parallelFiles
        this[Keys.background] = s.backgroundScan
        this[Keys.thermal] = s.thermalProtection
        this[Keys.batteryPause] = s.pauseBelowBatteryPercent
        this[Keys.lowPriority] = s.lowPriorityThreads
        this[Keys.keepScreenOn] = s.keepScreenOn
        this[Keys.notifyOnFinish] = s.notifyOnFinish
        this[Keys.restoreQueue] = s.restoreQueueAfterRestart
        this[Keys.skipKnown] = s.skipKnownFiles
        this[Keys.historyLimit] = s.historyLimit
        this[Keys.cleanDays] = s.autoCleanDays
        this[Keys.autoExport] = s.autoExport.name
        if (s.autoExportFolder != null) this[Keys.exportFolder] = s.autoExportFolder else remove(Keys.exportFolder)
        this[Keys.sort] = s.queueSort.name
        this[Keys.filter] = s.queueFilter.name
        this[Keys.glass] = s.liquidGlass
        this[Keys.backdrop] = s.animatedBackdrop
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}
