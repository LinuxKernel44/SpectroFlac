package com.spectroflac.analysis

import com.spectroflac.flac.FlacDecoder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Consumes decoded blocks once and derives everything the verdict needs: a long-term spectrum
 * (both peak-hold and average), per-channel level statistics, real bit depth and a compact
 * spectrogram preview.
 *
 * Peak-hold matters more than the average here: a lossy encoder removes high frequencies from
 * *every* frame, while a quiet passage of genuine lossless audio only lacks them some of the time.
 */
class AudioAnalyzer(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    totalSamples: Long,
) : FlacDecoder.SampleSink {

    companion object {
        const val FFT_SIZE = 4096
        const val SPECTROGRAM_COLUMNS = 512
        const val SPECTROGRAM_BANDS = 256
        const val TARGET_WINDOWS = 2600
        private const val DB_FLOOR = -200.0
    }

    private val bins = FFT_SIZE / 2
    private val fft = Fft(FFT_SIZE)
    private val window = Fft.hannWindow(FFT_SIZE)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    private val ring = DoubleArray(FFT_SIZE)
    private val ringMask = FFT_SIZE - 1
    private var ringPos = 0

    private val fullScale = (1L shl (bitsPerSample - 1)).toDouble()
    private val clipHigh = if (bitsPerSample >= 32) Int.MAX_VALUE else (1 shl (bitsPerSample - 1)) - 1
    private val clipLow = if (bitsPerSample >= 32) Int.MIN_VALUE else -(1 shl (bitsPerSample - 1))

    private val stride: Long = max(FFT_SIZE / 2L, if (totalSamples > 0) totalSamples / TARGET_WINDOWS else FFT_SIZE.toLong())
    private val expectedWindows = if (totalSamples > 0) max(1L, totalSamples / stride) else TARGET_WINDOWS.toLong()

    private var sampleIndex = 0L
    private var nextWindowAt = FFT_SIZE.toLong()
    private var windowsAnalyzed = 0

    private val peakDb = DoubleArray(bins) { DB_FLOOR }
    private val sumPower = DoubleArray(bins)

    /** Column-major dB values mapped to 0..255, ready to be drawn as a preview strip. */
    val spectrogram = ByteArray(SPECTROGRAM_COLUMNS * SPECTROGRAM_BANDS)
    private val columnWritten = BooleanArray(SPECTROGRAM_COLUMNS)
    private val binsPerBand = max(1, bins / SPECTROGRAM_BANDS)

    // Per-channel running statistics.
    private val peakSample = IntArray(channels)
    private val clippedSamples = LongArray(channels)
    private val clippedRuns = LongArray(channels)
    private val runLength = IntArray(channels)
    private val sumSquares = DoubleArray(channels)
    private var orAccumulator = 0
    private var nonZeroSamples = 0L
    private var differingStereoSamples = 0L

    // Dynamic-range blocks (3 s, as in the TT DR meter).
    private val drBlockLength = max(1, sampleRate * 3)
    private var drBlockFill = 0
    private val drBlockSum = DoubleArray(channels)
    private val drBlockPeak = DoubleArray(channels)
    private val drRms = Array(channels) { ArrayList<Double>() }
    private val drPeaks = Array(channels) { ArrayList<Double>() }

    override fun onBlock(channelData: Array<IntArray>, count: Int) {
        for (c in 0 until channels) {
            val data = channelData[c]
            var peak = peakSample[c]
            var clipped = clippedSamples[c]
            var runs = clippedRuns[c]
            var run = runLength[c]
            var sq = sumSquares[c]
            var orAcc = orAccumulator
            var blockSum = drBlockSum[c]
            var blockPeak = drBlockPeak[c]

            for (i in 0 until count) {
                val v = data[i]
                orAcc = orAcc or v
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                val norm = v / fullScale
                val p = norm * norm
                sq += p
                blockSum += p
                if (norm > blockPeak) blockPeak = norm else if (-norm > blockPeak) blockPeak = -norm
                if (v >= clipHigh || v <= clipLow) {
                    clipped++
                    run++
                    if (run == 3) runs++
                } else {
                    run = 0
                }
            }

            peakSample[c] = peak
            clippedSamples[c] = clipped
            clippedRuns[c] = runs
            runLength[c] = run
            sumSquares[c] = sq
            orAccumulator = orAcc
            drBlockSum[c] = blockSum
            drBlockPeak[c] = blockPeak
        }

        // Mono downmix feeds the spectrum and the DR block boundaries.
        var i = 0
        while (i < count) {
            var sum = 0L
            var anyNonZero = false
            for (c in 0 until channels) {
                val v = channelData[c][i]
                sum += v
                if (v != 0) anyNonZero = true
            }
            if (anyNonZero) nonZeroSamples++
            if (channels == 2 && channelData[0][i] != channelData[1][i]) differingStereoSamples++
            ring[ringPos] = sum / (channels * fullScale)
            ringPos = (ringPos + 1) and ringMask
            sampleIndex++

            if (sampleIndex >= nextWindowAt) {
                analyzeWindow()
                nextWindowAt += stride
            }

            drBlockFill++
            if (drBlockFill >= drBlockLength) {
                closeDrBlock()
            }
            i++
        }
    }

    private fun analyzeWindow() {
        var p = ringPos
        for (n in 0 until FFT_SIZE) {
            re[n] = ring[p] * window[n]
            im[n] = 0.0
            p = (p + 1) and ringMask
        }
        fft.transform(re, im)

        val scale = 2.0 / (FFT_SIZE / 2.0)
        val column = if (expectedWindows <= 1) 0
        else ((windowsAnalyzed.toLong() * (SPECTROGRAM_COLUMNS - 1)) / expectedWindows).toInt()
            .coerceIn(0, SPECTROGRAM_COLUMNS - 1)

        for (b in 0 until bins) {
            val power = re[b] * re[b] + im[b] * im[b]
            val mag = sqrt(power) * scale
            val db = if (mag > 0) 20.0 * log10(mag) else DB_FLOOR
            if (db > peakDb[b]) peakDb[b] = db
            sumPower[b] += power
            val band = b / binsPerBand
            if (band < SPECTROGRAM_BANDS) {
                val idx = column * SPECTROGRAM_BANDS + band
                val level = (((db + 120.0) / 120.0) * 255.0).toInt().coerceIn(0, 255)
                if (level > (spectrogram[idx].toInt() and 0xFF)) spectrogram[idx] = level.toByte()
            }
        }
        columnWritten[column] = true
        windowsAnalyzed++
    }

    /**
     * A short track produces fewer analysis windows than the strip has columns, which would leave
     * black gaps between them. Stretch each written column across the ones that follow it.
     */
    private fun fillSpectrogramGaps() {
        var source = -1
        for (column in 0 until SPECTROGRAM_COLUMNS) {
            if (columnWritten[column]) {
                source = column
            } else if (source >= 0) {
                System.arraycopy(
                    spectrogram, source * SPECTROGRAM_BANDS,
                    spectrogram, column * SPECTROGRAM_BANDS,
                    SPECTROGRAM_BANDS,
                )
            }
        }
        // Leading columns before the first window, if any.
        val first = columnWritten.indexOfFirst { it }
        if (first > 0) {
            for (column in 0 until first) {
                System.arraycopy(
                    spectrogram, first * SPECTROGRAM_BANDS,
                    spectrogram, column * SPECTROGRAM_BANDS,
                    SPECTROGRAM_BANDS,
                )
            }
        }
    }

    private fun closeDrBlock() {
        for (c in 0 until channels) {
            val mean = drBlockSum[c] / drBlockFill
            drRms[c].add(sqrt(2.0 * mean))
            drPeaks[c].add(drBlockPeak[c])
            drBlockSum[c] = 0.0
            drBlockPeak[c] = 0.0
        }
        drBlockFill = 0
    }

    fun finish(): AnalysisMeasurements {
        if (drBlockFill > sampleRate / 2) closeDrBlock()
        fillSpectrogramGaps()

        val meanDb = DoubleArray(bins) {
            val power = if (windowsAnalyzed > 0) sumPower[it] / windowsAnalyzed else 0.0
            val mag = sqrt(power) * (2.0 / (FFT_SIZE / 2.0))
            if (mag > 0) 20.0 * log10(mag) else DB_FLOOR
        }

        val peakOverall = peakSample.maxOrNull() ?: 0
        val peakDbfs = if (peakOverall > 0) 20.0 * log10(peakOverall / fullScale) else DB_FLOOR
        val totalSq = sumSquares.sum()
        val totalCount = sampleIndex * channels
        val rmsDbfs = if (totalCount > 0 && totalSq > 0) 10.0 * log10(totalSq / totalCount) else DB_FLOOR

        val trailingZeros = if (orAccumulator == 0) bitsPerSample else Integer.numberOfTrailingZeros(orAccumulator)
        val effectiveBits = (bitsPerSample - trailingZeros).coerceIn(0, bitsPerSample)

        return AnalysisMeasurements(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            samplesAnalyzed = sampleIndex,
            windowsAnalyzed = windowsAnalyzed,
            fftSize = FFT_SIZE,
            peakSpectrumDb = peakDb,
            meanSpectrumDb = meanDb,
            spectrogram = spectrogram,
            spectrogramColumns = SPECTROGRAM_COLUMNS,
            spectrogramBands = SPECTROGRAM_BANDS,
            peakSampleDbfs = peakDbfs,
            rmsDbfs = rmsDbfs,
            clippedSamples = clippedSamples.sum(),
            clippedRuns = clippedRuns.sum(),
            silentSampleRatio = if (sampleIndex > 0) 1.0 - nonZeroSamples.toDouble() / sampleIndex else 0.0,
            dualMono = channels == 2 && sampleIndex > 0 && differingStereoSamples == 0L,
            effectiveBitDepth = effectiveBits,
            unusedLowBits = trailingZeros,
            dynamicRangeDb = computeDr(),
        )
    }

    /** The TT DR meter: second-highest block peak over the RMS of the loudest 20 % of blocks. */
    private fun computeDr(): Double? {
        val perChannel = ArrayList<Double>(channels)
        for (c in 0 until channels) {
            val rms = drRms[c]
            val peaks = drPeaks[c]
            if (rms.size < 3) continue
            val sortedRms = rms.sortedDescending()
            val take = max(1, (sortedRms.size * 0.2).toInt())
            var sum = 0.0
            for (k in 0 until take) sum += sortedRms[k] * sortedRms[k]
            val loudRms = sqrt(sum / take)
            val sortedPeaks = peaks.sortedDescending()
            val secondPeak = sortedPeaks.getOrNull(1) ?: sortedPeaks[0]
            if (loudRms <= 0.0 || secondPeak <= 0.0) continue
            perChannel += 20.0 * log10(secondPeak / loudRms)
        }
        if (perChannel.isEmpty()) return null
        return perChannel.average()
    }
}

/** Raw measurements, before any interpretation. */
class AnalysisMeasurements(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val samplesAnalyzed: Long,
    val windowsAnalyzed: Int,
    val fftSize: Int,
    val peakSpectrumDb: DoubleArray,
    val meanSpectrumDb: DoubleArray,
    val spectrogram: ByteArray,
    val spectrogramColumns: Int,
    val spectrogramBands: Int,
    val peakSampleDbfs: Double,
    val rmsDbfs: Double,
    val clippedSamples: Long,
    val clippedRuns: Long,
    val silentSampleRatio: Double,
    val dualMono: Boolean,
    val effectiveBitDepth: Int,
    val unusedLowBits: Int,
    val dynamicRangeDb: Double?,
) {
    val binHz: Double get() = sampleRate.toDouble() / fftSize
    val nyquist: Double get() = sampleRate / 2.0
    fun frequencyOf(bin: Int): Double = bin * binHz
    fun binOf(hz: Double): Int = (hz / binHz).toInt().coerceIn(0, peakSpectrumDb.size - 1)
    val crestFactorDb: Double get() = peakSampleDbfs - rmsDbfs
    fun peakAbs(): Double = peakSpectrumDb.maxOrNull() ?: -200.0
}
