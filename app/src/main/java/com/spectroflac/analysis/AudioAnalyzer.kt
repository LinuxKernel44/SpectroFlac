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

        /** Frequency bands of the interactive spectrogram: two FFT bins each. */
        const val FULL_BANDS = FFT_SIZE / 4

        /** Time slices of the stereo correlation timeline. */
        private const val CORRELATION_SLICES = 256
        private const val DB_FLOOR = -200.0

        /** 10 * log10(2): converts a log2 of power into dB. */
        private const val DB_PER_LOG2 = 3.0102999566398120

        /**
         * log2 from the exponent bits and a cubic fit of the mantissa, accurate to about 0.005 —
         * far finer than the 0.47 dB steps of the 8-bit spectrogram it feeds.
         */
        private fun fastLog2(x: Double): Double {
            val bits = x.toRawBits()
            val exponent = ((bits ushr 52) and 0x7FF).toInt() - 1023
            val m = java.lang.Double.longBitsToDouble((bits and 0xFFFFFFFFFFFFFL) or 0x3FF0000000000000L)
            return exponent + ((-0.34484843 * m + 2.02466578) * m - 0.67487759)
        }
    }

    private val bins = FFT_SIZE / 2
    private val fft = Fft(FFT_SIZE)
    private val window = Fft.hannWindow(FFT_SIZE)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    /** dB of a power value of 1.0 once scaled to a single-sided amplitude spectrum. */
    private val dbOffset = 20.0 * log10(2.0 / (FFT_SIZE / 2.0))
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

    /**
     * One spectral view of the signal. Layer 0 is always the mono mix (for stereo that is the mid
     * channel) and drives the verdict; the others are the individual channels, plus the side
     * channel for stereo.
     */
    private class Layer(val name: String, columns: Int, bins: Int) {
        val ring = DoubleArray(FFT_SIZE)
        val peakPower = DoubleArray(bins)
        val sumPower = DoubleArray(bins)
        val spectrogram = ByteArray(columns * FULL_BANDS)
    }

    private val fullColumns = (expectedWindows + 8).toInt()
    private val layers: List<Layer> = buildList {
        add(Layer(if (channels == 1) "Mono" else if (channels == 2) "Mid" else "Mix", fullColumns, bins))
        when (channels) {
            1 -> Unit
            2 -> {
                add(Layer("Left", fullColumns, bins))
                add(Layer("Right", fullColumns, bins))
                add(Layer("Side", fullColumns, bins))
            }
            else -> for (c in 0 until channels) add(Layer("Ch ${c + 1}", fullColumns, bins))
        }
    }
    private val mix = layers[0]

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

    // Stereo relationship, in normalised sample units.
    private var sumLL = 0.0
    private var sumRR = 0.0
    private var sumLR = 0.0
    private var sumMM = 0.0
    private var sumSS = 0.0
    private val sliceLength = if (totalSamples > 0) max(1L, totalSamples / CORRELATION_SLICES) else sampleRate.toLong()
    private var sliceFill = 0L
    private var sliceLL = 0.0
    private var sliceRR = 0.0
    private var sliceLR = 0.0
    private val correlationTimeline = ArrayList<Float>()

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

        // Mono downmix feeds the verdict and the DR block boundaries; the other layers get the
        // individual channels (and mid/side for stereo).
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
            if (channels == 2) {
                // Mid and side are derived from the left/right spectra, so only those two are buffered.
                val l = channelData[0][i]
                val r = channelData[1][i]
                if (l != r) differingStereoSamples++
                val ln = l / fullScale
                val rn = r / fullScale
                layers[1].ring[ringPos] = ln
                layers[2].ring[ringPos] = rn
                accumulateStereo(ln, rn)
            } else {
                mix.ring[ringPos] = sum / (channels * fullScale)
                if (channels > 2) {
                    for (c in 0 until channels) layers[c + 1].ring[ringPos] = channelData[c][i] / fullScale
                }
            }
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

    private fun accumulateStereo(l: Double, r: Double) {
        val m = (l + r) * 0.5
        val s = (l - r) * 0.5
        sumLL += l * l
        sumRR += r * r
        sumLR += l * r
        sumMM += m * m
        sumSS += s * s
        sliceLL += l * l
        sliceRR += r * r
        sliceLR += l * r
        if (++sliceFill >= sliceLength) closeSlice()
    }

    private fun closeSlice() {
        val denom = sliceLL * sliceRR
        correlationTimeline += if (denom > 0.0) (sliceLR / sqrt(denom)).toFloat().coerceIn(-1f, 1f) else Float.NaN
        sliceFill = 0
        sliceLL = 0.0
        sliceRR = 0.0
        sliceLR = 0.0
    }

    private fun analyzeWindow() {
        val column = if (expectedWindows <= 1) 0
        else ((windowsAnalyzed.toLong() * (SPECTROGRAM_COLUMNS - 1)) / expectedWindows).toInt()
            .coerceIn(0, SPECTROGRAM_COLUMNS - 1)
        val fullColumn = if (windowsAnalyzed < fullColumns) windowsAnalyzed else -1

        if (channels == 2) analyzeStereoWindow(column, fullColumn) else analyzeLayers(column, fullColumn)
        columnWritten[column] = true
        windowsAnalyzed++
    }

    /** One FFT per layer: used for mono and for more than two channels. */
    private fun analyzeLayers(column: Int, fullColumn: Int) {
        for ((index, layer) in layers.withIndex()) {
            var p = ringPos
            for (n in 0 until FFT_SIZE) {
                re[n] = layer.ring[p] * window[n]
                im[n] = 0.0
                p = (p + 1) and ringMask
            }
            fft.transform(re, im)
            for (b in 0 until bins) {
                record(index, layer, b, re[b] * re[b] + im[b] * im[b], column, fullColumn)
            }
        }
    }

    /**
     * Left and right go through a single complex FFT (left as the real part, right as the
     * imaginary one) and are separated afterwards; mid and side are linear combinations of the two
     * spectra, so all four layers cost one transform.
     */
    private fun analyzeStereoWindow(column: Int, fullColumn: Int) {
        val left = layers[1].ring
        val right = layers[2].ring
        var p = ringPos
        for (n in 0 until FFT_SIZE) {
            re[n] = left[p] * window[n]
            im[n] = right[p] * window[n]
            p = (p + 1) and ringMask
        }
        fft.transform(re, im)

        for (b in 0 until bins) {
            val nb = (FFT_SIZE - b) and ringMask
            val zr = re[b]
            val zi = im[b]
            val cr = re[nb]
            val ci = im[nb]
            val lr = (zr + cr) * 0.5
            val li = (zi - ci) * 0.5
            val rr = (zi + ci) * 0.5
            val ri = (cr - zr) * 0.5
            val mr = (lr + rr) * 0.5
            val mi = (li + ri) * 0.5
            val sr = (lr - rr) * 0.5
            val si = (li - ri) * 0.5
            record(0, layers[0], b, mr * mr + mi * mi, column, fullColumn)
            record(1, layers[1], b, lr * lr + li * li, column, fullColumn)
            record(2, layers[2], b, rr * rr + ri * ri, column, fullColumn)
            record(3, layers[3], b, sr * sr + si * si, column, fullColumn)
        }
    }

    /** Folds one bin of one layer into its peak-hold, mean and spectrogram columns. */
    private fun record(index: Int, layer: Layer, b: Int, power: Double, column: Int, fullColumn: Int) {
        // Peak-hold in the power domain (monotonic in dB), converted once at the end.
        if (power > layer.peakPower[b]) layer.peakPower[b] = power
        layer.sumPower[b] += power
        if (fullColumn < 0 && index != 0) return
        val level = if (power > 0.0) {
            (((DB_PER_LOG2 * fastLog2(power) + dbOffset + 120.0) / 120.0) * 255.0).toInt().coerceIn(0, 255)
        } else 0
        if (fullColumn >= 0) {
            val idx = fullColumn * FULL_BANDS + (b shr 1)
            if (level > (layer.spectrogram[idx].toInt() and 0xFF)) layer.spectrogram[idx] = level.toByte()
        }
        if (index == 0) {
            val band = b / binsPerBand
            if (band < SPECTROGRAM_BANDS) {
                val idx = column * SPECTROGRAM_BANDS + band
                if (level > (spectrogram[idx].toInt() and 0xFF)) spectrogram[idx] = level.toByte()
            }
        }
    }

    private fun peakDb(layer: Layer) = DoubleArray(bins) {
        val power = layer.peakPower[it]
        val mag = sqrt(power) * (2.0 / (FFT_SIZE / 2.0))
        if (mag > 0) 20.0 * log10(mag) else DB_FLOOR
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
        if (channels == 2 && sliceFill >= sliceLength / 2) closeSlice()
        fillSpectrogramGaps()

        val meanDb = layers.map { meanSpectrumDb(it) }
        val peakDbs = layers.map { peakDb(it) }
        val peakOverall = peakSample.maxOrNull() ?: 0
        val peakDbfs = if (peakOverall > 0) 20.0 * log10(peakOverall / fullScale) else DB_FLOOR
        val totalSq = sumSquares.sum()
        val totalCount = sampleIndex * channels
        val rmsDbfs = if (totalCount > 0 && totalSq > 0) 10.0 * log10(totalSq / totalCount) else DB_FLOOR

        val trailingZeros = if (orAccumulator == 0) bitsPerSample else Integer.numberOfTrailingZeros(orAccumulator)
        val effectiveBits = (bitsPerSample - trailingZeros).coerceIn(0, bitsPerSample)

        val columns = windowsAnalyzed.coerceAtMost(fullColumns)
        val fullLayers = layers.map { SpectrogramLayer(it.name, it.spectrogram.copyOf(columns * FULL_BANDS)) }

        return AnalysisMeasurements(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            samplesAnalyzed = sampleIndex,
            windowsAnalyzed = windowsAnalyzed,
            fftSize = FFT_SIZE,
            peakSpectrumDb = peakDbs[0],
            meanSpectrumDb = meanDb[0],
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
            layerNames = layers.map { it.name },
            layerPeakDb = peakDbs,
            layerMeanDb = meanDb,
            fullSpectrogram = if (columns > 0) SpectrogramFull(
                sampleRate = sampleRate,
                columns = columns,
                bands = FULL_BANDS,
                strideSamples = stride,
                totalSamples = sampleIndex,
                layers = fullLayers,
            ) else null,
            stereo = if (channels == 2 && sampleIndex > 0) {
                StereoAnalysis.measure(
                    sampleIndex, sumLL, sumRR, sumLR, sumMM, sumSS, correlationTimeline.toFloatArray(),
                )
            } else null,
        )
    }

    private fun meanSpectrumDb(layer: Layer) = DoubleArray(bins) {
        val power = if (windowsAnalyzed > 0) layer.sumPower[it] / windowsAnalyzed else 0.0
        val mag = sqrt(power) * (2.0 / (FFT_SIZE / 2.0))
        if (mag > 0) 20.0 * log10(mag) else DB_FLOOR
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
    /** Names and spectra of every analysed layer; layer 0 is the mix that [peakSpectrumDb] holds. */
    val layerNames: List<String> = emptyList(),
    val layerPeakDb: List<DoubleArray> = emptyList(),
    val layerMeanDb: List<DoubleArray> = emptyList(),
    val fullSpectrogram: SpectrogramFull? = null,
    val stereo: StereoInfo? = null,
) {
    val binHz: Double get() = sampleRate.toDouble() / fftSize
    val nyquist: Double get() = sampleRate / 2.0
    fun frequencyOf(bin: Int): Double = bin * binHz
    fun binOf(hz: Double): Int = (hz / binHz).toInt().coerceIn(0, peakSpectrumDb.size - 1)
    val crestFactorDb: Double get() = peakSampleDbfs - rmsDbfs
    fun peakAbs(): Double = peakSpectrumDb.maxOrNull() ?: -200.0
}
