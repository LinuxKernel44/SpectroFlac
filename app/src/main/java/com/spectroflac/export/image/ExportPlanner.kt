package com.spectroflac.export.image

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** The requested size of the spectrogram itself (without the header and the axes around it). */
data class PlotSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val megapixels: Double get() = pixels / 1_000_000.0
}

/** Everything the export dialog shows before the work starts. */
data class ExportEstimate(
    val plot: PlotSize,
    val fftSize: Int,
    /** Frequency resolution of one row, in Hz. */
    val hzPerRow: Double,
    /** Duration of audio behind one column, in milliseconds. */
    val msPerColumn: Double,
    val pngBytes: Long,
    /** Temporary space needed while rendering (the decoded audio and the level matrix). */
    val tempBytes: Long,
    val seconds: Double,
    val valid: Boolean,
    val problem: String?,
)

/**
 * Sizing rules and estimates for the spectrogram export. No Android types, so they are unit-tested.
 *
 * The export re-analyses the file at the resolution asked for: the number of rows decides the FFT
 * length (one bin per row or a little more), and the number of columns decides how far apart the
 * analysis windows sit. A bigger image therefore really shows more detail, up to what the audio holds.
 */
object ExportPlanner {
    const val MIN_SIDE = 256
    const val MAX_HEIGHT = 32_768
    const val MAX_WIDTH = 1_000_000
    const val MIN_FFT = 1_024
    const val MAX_FFT = 65_536

    /** Seen on typical spectrograms: noisy colour data compresses to roughly this share of raw RGB. */
    private const val PNG_RATIO = 0.55

    /** Rough speed of the PNG encoder, in megapixels per second per core. */
    private const val ENCODE_MPIXELS_PER_SECOND = 40.0

    /** A decode runs at tens of times real time. */
    private const val DECODE_REALTIME = 40.0

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n && p < (1 shl 30)) p = p shl 1
        return p
    }

    /** The FFT length for [height] rows: at least one bin per row. */
    fun fftSize(height: Int): Int = nextPowerOfTwo(2 * height).coerceIn(MIN_FFT, MAX_FFT)

    /** How many analysis windows are averaged (peak-held) into one column. */
    fun windowsPerColumn(totalSamples: Long, width: Int, fftSize: Int): Int {
        val span = totalSamples.toDouble() / width
        return ceil(span / (fftSize / 2.0)).toInt().coerceIn(1, 16)
    }

    fun estimate(
        totalSamples: Long,
        sampleRate: Int,
        plot: PlotSize,
        /** The image including header and axes, in pixels. */
        totalPixels: Long,
        cores: Int,
        /** Measured time of one FFT of [fftSize] points, in milliseconds. */
        fftMillis: Double,
    ): ExportEstimate {
        val problem = validate(plot)
        val fft = fftSize(max(plot.height, MIN_SIDE))
        val windows = plot.width.toLong() * windowsPerColumn(totalSamples, max(plot.width, 1), fft)
        val workers = max(1, min(cores, 8))
        val fftSeconds = windows * fftMillis / 1000.0 / workers
        val decodeSeconds = totalSamples.toDouble() / max(sampleRate, 1) / DECODE_REALTIME
        val encodeSeconds = totalPixels / 1_000_000.0 / ENCODE_MPIXELS_PER_SECOND
        return ExportEstimate(
            plot = plot,
            fftSize = fft,
            hzPerRow = if (plot.height > 0) sampleRate / 2.0 / plot.height else 0.0,
            msPerColumn = if (plot.width > 0 && sampleRate > 0) totalSamples.toDouble() / plot.width / sampleRate * 1000.0 else 0.0,
            pngBytes = (totalPixels * 3 * PNG_RATIO).roundToLong(),
            tempBytes = totalSamples * 4 + plot.pixels,
            seconds = fftSeconds + decodeSeconds + encodeSeconds + 0.5,
            valid = problem == null,
            problem = problem,
        )
    }

    fun validate(plot: PlotSize): String? = when {
        plot.width < MIN_SIDE || plot.height < MIN_SIDE -> "Each side must be at least $MIN_SIDE pixels."
        plot.height > MAX_HEIGHT -> "The height is limited to $MAX_HEIGHT pixels (the finest analysis there is)."
        plot.width > MAX_WIDTH -> "The width is limited to $MAX_WIDTH pixels."
        else -> null
    }

    /** The common sizes of the dialog. */
    val presets: List<Pair<String, PlotSize>> = listOf(
        "Screen" to PlotSize(1920, 1080),
        "4K" to PlotSize(3840, 2160),
        "8K" to PlotSize(7680, 4320),
    )

    /**
     * The finest image this file supports that still fits the space available: 16,384 rows (about
     * 1.3 Hz each at 44.1 kHz) and one column every ~256 samples, shrunk until the temporary files
     * and the PNG together stay under [budgetShare] of [freeBytes].
     */
    fun maximum(totalSamples: Long, freeBytes: Long, budgetShare: Double = 0.4, overheadPixels: (PlotSize) -> Long = { it.pixels }): PlotSize {
        var height = 16_384
        var width = (totalSamples / 256).coerceIn(1_920L, 65_536L).toInt()
        fun cost(w: Int, h: Int): Long {
            val plot = PlotSize(w, h)
            return totalSamples * 4 + plot.pixels + (overheadPixels(plot) * 3 * PNG_RATIO).roundToLong()
        }
        val budget = (freeBytes * budgetShare).toLong()
        while (cost(width, height) > budget && width > 1_920) width = max(1_920, width / 2)
        while (cost(width, height) > budget && height > 2_048) height /= 2
        return PlotSize(width, height)
    }

    /** log2 as an integer, for describing an FFT. */
    fun log2(n: Int): Int = (ln(n.toDouble()) / ln(2.0)).roundToLong().toInt()
}
