package com.spectroflac.analysis

/**
 * How the two channels of a stereo file relate to each other, measured over the whole track.
 * The per-slice correlation timeline stays in memory only: like the spectrogram it is large and
 * the history screen offers a re-analysis instead.
 */
class StereoInfo(
    /** Pearson correlation of left and right, -1 (out of phase) .. +1 (identical). */
    val correlation: Double,
    /** Left RMS minus right RMS in dB: positive means the left channel is louder. */
    val balanceDb: Double,
    /** Side RMS relative to mid RMS in dB; very negative means close to mono. */
    val widthDb: Double,
    val midRmsDbfs: Double,
    val sideRmsDbfs: Double,
    /** Share of the time slices whose correlation is below zero. */
    val negativeRatio: Double,
    /** 0 = left, 1 = right, when that channel carries no signal at all. */
    val silentChannel: Int?,
    /** One correlation value per time slice, NaN where the slice is silent. */
    val timeline: FloatArray = FloatArray(0),
)

/**
 * Evidence of joint-stereo (mid/side) lossy coding: such encoders spend their bits on the mid
 * channel and starve the side channel, so the side channel runs out of treble before the mid does.
 */
class JointStereoInfo(
    val midCutoffHz: Double,
    val sideCutoffHz: Double,
    val sideWallDropDb: Double,
    /** The side channel has a brick wall that sits clearly below the mid channel's edge. */
    val sideBandLimited: Boolean,
    /** Side-to-mid level difference in the mid band and in the treble band, when measurable. */
    val sideToMidLowDb: Double?,
    val sideToMidHighDb: Double?,
    /** How much the stereo image narrows from the mid band to the treble band, in dB. */
    val collapseDb: Double?,
    val suspected: Boolean,
    val reasoning: List<String>,
)

/** Peak-hold and average spectrum of one channel, reduced to a fixed number of points. */
class SpectrumSeries(val name: String, val peakDb: FloatArray, val meanDb: FloatArray)

/** The spectra behind the spectrum chart: every series spans 0 Hz to Nyquist. */
class SpectrumCurves(val sampleRate: Int, val series: List<SpectrumSeries>) {
    companion object {
        const val POINTS = 512
    }
}

class SpectrogramLayer(val name: String, val data: ByteArray)

/**
 * The spectrogram behind the interactive view: one column per analysis window and
 * [bands] frequency bands up to Nyquist, stored column-major as 0..255 (-120..0 dBFS).
 */
class SpectrogramFull(
    val sampleRate: Int,
    val columns: Int,
    val bands: Int,
    /** Distance in samples between two columns. */
    val strideSamples: Long,
    val totalSamples: Long,
    val layers: List<SpectrogramLayer>,
) {
    val durationSeconds: Double
        get() = if (sampleRate > 0) totalSamples.toDouble() / sampleRate else 0.0

    val nyquistHz: Double get() = sampleRate / 2.0

    /** Time at which a column starts. */
    fun columnStartSeconds(column: Int): Double = column.toDouble() * strideSamples / sampleRate

    fun levelDb(layer: Int, column: Int, band: Int): Double {
        val data = layers[layer].data
        return (data[column * bands + band].toInt() and 0xFF) / 255.0 * 120.0 - 120.0
    }
}
