package com.spectroflac.export.image

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Where the labels of the frequency and time axes go. Pure, so the logic is tested without rendering. */
object AxisTicks {

    class Tick(val value: Double, val label: String)

    /** A 1-2-5 step that gives roughly [target] ticks across [span]. */
    fun niceStep(span: Double, target: Int): Double {
        val raw = span / max(1, target)
        val magnitude = 10.0.pow(floor(log10(raw)))
        val fraction = raw / magnitude
        val nice = when {
            fraction < 1.5 -> 1.0
            fraction < 3.5 -> 2.0
            fraction < 7.5 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /** Frequency ticks (value in Hz, label in kHz) with about one label per [pxPerLabel] pixels. */
    fun frequency(nyquistHz: Double, plotHeightPx: Int, pxPerLabel: Double): List<Tick> {
        val target = max(2, (plotHeightPx / pxPerLabel).toInt())
        val step = niceStep(nyquistHz / 1000.0, target)
        val ticks = ArrayList<Tick>()
        var k = 0.0
        while (k <= nyquistHz / 1000.0 + 1e-9) {
            ticks += Tick(k * 1000.0, formatKhz(k))
            k += step
        }
        return ticks
    }

    private val timeSteps = doubleArrayOf(
        0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0,
    )

    /** Time ticks (value and label in seconds) with about one label per [pxPerLabel] pixels. */
    fun time(durationSeconds: Double, plotWidthPx: Int, pxPerLabel: Double): List<Tick> {
        val target = max(2, (plotWidthPx / pxPerLabel).toInt())
        val wanted = durationSeconds / target
        val step = timeSteps.firstOrNull { it >= wanted } ?: timeSteps.last()
        val ticks = ArrayList<Tick>()
        var t = 0.0
        while (t <= durationSeconds + 1e-9) {
            ticks += Tick(t, clock(t, step))
            t += step
        }
        return ticks
    }

    /** m:ss, h:mm:ss for long files, with tenths when the tick spacing is below a second. */
    fun clock(seconds: Double, step: Double): String {
        val total = max(0.0, seconds)
        val hours = (total / 3600).toInt()
        val minutes = ((total - hours * 3600) / 60).toInt()
        val rest = total - hours * 3600 - minutes * 60
        return when {
            step < 1.0 -> if (hours > 0) String.format(Locale.US, "%d:%02d:%04.1f", hours, minutes, rest)
            else String.format(Locale.US, "%d:%04.1f", minutes, rest)
            hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest.toInt())
            else -> String.format(Locale.US, "%d:%02d", minutes, rest.toInt())
        }
    }

    fun formatKhz(khz: Double): String = String.format(Locale.US, "%.1f", khz).removeSuffix(".0")

    /** dBFS labels of the colour legend, loudest first. */
    fun legendLevels(): List<Int> = (0 downTo -120 step 20).toList()
}
