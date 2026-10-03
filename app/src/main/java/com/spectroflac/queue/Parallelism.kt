package com.spectroflac.queue

import com.spectroflac.settings.AppSettings
import kotlin.math.max

/**
 * How many files the queue analyses at once. A FLAC file decodes sequentially, so parallelism means
 * several files at the same time; one file always stays on one thread.
 */
object Parallelism {

    /** Thermal status values, as in `PowerManager.THERMAL_STATUS_*`. */
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4

    /** Auto: half of the cores (4 on an 8-core phone), leaving the rest for the system and the UI. */
    fun auto(cores: Int): Int = (cores / 2).coerceIn(1, AppSettings.MAX_PARALLEL)

    /** What the user asked for: their number, or Auto. */
    fun requested(setting: Int, cores: Int): Int =
        if (setting == AppSettings.AUTO) auto(cores) else setting.coerceIn(1, AppSettings.MAX_PARALLEL)

    /**
     * What actually runs right now. Heat lowers it for both Auto and a fixed number (unless thermal
     * protection is off); Battery Saver only lowers Auto, so a number the user picked is respected.
     * 0 means "hold new files back": the phone is critically hot.
     */
    fun effective(
        setting: Int,
        cores: Int,
        thermalProtection: Boolean,
        thermalStatus: Int,
        batterySaver: Boolean,
    ): Int {
        var n = requested(setting, cores)
        if (setting == AppSettings.AUTO && batterySaver) n = max(1, n / 2)
        if (thermalProtection) {
            n = when {
                thermalStatus >= THERMAL_CRITICAL -> 0
                thermalStatus == THERMAL_SEVERE -> 1
                thermalStatus == THERMAL_MODERATE -> max(1, n / 2)
                else -> n
            }
        }
        return n
    }

    /** Why the queue holds back, if the battery is the reason. */
    fun batteryPauseReason(percent: Int, charging: Boolean, threshold: Int): String? =
        if (threshold > 0 && !charging && percent in 0 until threshold) "Battery below $threshold %" else null

    fun thermalPauseReason(thermalProtection: Boolean, thermalStatus: Int): String? =
        if (thermalProtection && thermalStatus >= THERMAL_CRITICAL) "Phone is too hot" else null
}
