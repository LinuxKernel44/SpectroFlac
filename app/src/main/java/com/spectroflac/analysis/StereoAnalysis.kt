package com.spectroflac.analysis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Everything that looks at the relationship between channels, and at the per-channel spectra. */
object StereoAnalysis {

    private const val DB_FLOOR = -200.0

    /** The side channel must stop this far below the mid channel's own edge to count. */
    private const val SIDE_GAP_HZ = 1500.0

    /** A narrowing of the stereo image from the mid band to the treble band that is worth reporting. */
    const val COLLAPSE_DB = 12.0

    /**
     * Reduces the running sums of a stereo track (in normalised sample units, N samples per channel)
     * to the numbers shown to the user.
     */
    fun measure(
        samples: Long,
        sumLL: Double,
        sumRR: Double,
        sumLR: Double,
        sumMM: Double,
        sumSS: Double,
        timeline: FloatArray,
    ): StereoInfo {
        val denom = sumLL * sumRR
        val correlation = if (denom > 0.0) (sumLR / sqrt(denom)).coerceIn(-1.0, 1.0) else 0.0
        val balance = when {
            sumLL > 0.0 && sumRR > 0.0 -> 10.0 * log10(sumLL / sumRR)
            sumLL > 0.0 -> 60.0
            sumRR > 0.0 -> -60.0
            else -> 0.0
        }.coerceIn(-60.0, 60.0)
        val width = if (sumMM > 0.0 && sumSS > 0.0) 10.0 * log10(sumSS / sumMM) else if (sumMM > 0.0) -100.0 else 0.0
        val valid = timeline.filter { !it.isNaN() }
        return StereoInfo(
            correlation = correlation,
            balanceDb = balance,
            widthDb = width.coerceIn(-100.0, 40.0),
            midRmsDbfs = powerDb(sumMM / max(1L, samples)),
            sideRmsDbfs = powerDb(sumSS / max(1L, samples)),
            negativeRatio = if (valid.isEmpty()) 0.0 else valid.count { it < 0f }.toDouble() / valid.size,
            silentChannel = when {
                sumLL == 0.0 && sumRR > 0.0 -> 0
                sumRR == 0.0 && sumLL > 0.0 -> 1
                else -> null
            },
            timeline = timeline,
        )
    }

    /**
     * Joint-stereo coders (MP3 joint stereo, AAC M/S) code the mid channel carefully and the side
     * channel sparingly, so the side channel loses its treble first. Two symptoms: a brick wall in
     * the side spectrum that sits below the mid channel's edge, and a stereo image that narrows
     * sharply from the mid range to the treble.
     */
    fun jointStereo(m: AnalysisMeasurements): JointStereoInfo? {
        val stereo = m.stereo ?: return null
        if (m.layerPeakDb.size < 4 || m.windowsAnalyzed < 4) return null
        // Without a side signal there is nothing to compare.
        if (m.dualMono || stereo.widthDb < -60.0) return null

        val midPeak = m.layerPeakDb[0]
        val sidePeak = m.layerPeakDb[3]
        val midEdge = Judge.findEdge(midPeak, m.binHz, m.nyquist)
        val sideEdge = Judge.findEdge(sidePeak, m.binHz, m.nyquist)
        val midCut = m.frequencyOf(midEdge.cutoffBin)
        val sideCut = m.frequencyOf(sideEdge.cutoffBin)

        val sideBandLimited = sideEdge.hasWall && sideCut < midCut - SIDE_GAP_HZ

        val midMean = m.layerMeanDb[0]
        val sideMean = m.layerMeanDb[3]
        val low = ratioDb(midMean, sideMean, m.binOf(300.0), m.binOf(3000.0))
        val highFrom = m.binOf(8000.0)
        val highTo = m.binOf(midCut - 500.0)
        val high = if (m.nyquist > 12000.0 && highTo - highFrom >= m.binOf(2000.0)) {
            ratioDb(midMean, sideMean, highFrom, highTo)
        } else null
        val collapse = if (low != null && high != null) low - high else null

        val reasoning = mutableListOf<String>()
        reasoning += "Side channel reaches %.0f Hz, mid channel %.0f Hz.".fmt(sideCut, midCut)
        if (sideEdge.hasWall) {
            reasoning += "The side channel has a %.0f dB brick wall%s."
                .fmt(sideEdge.dropDb, if (sideBandLimited) ", well below the mid channel's edge" else "")
        }
        if (low != null && high != null) {
            reasoning += "Side level relative to mid: %.1f dB around 300 Hz–3 kHz, %.1f dB around %.0f–%.0f kHz."
                .fmt(low, high, m.frequencyOf(highFrom) / 1000, m.frequencyOf(highTo) / 1000)
        }

        val suspected = sideBandLimited || (collapse != null && collapse >= COLLAPSE_DB)
        return JointStereoInfo(
            midCutoffHz = midCut,
            sideCutoffHz = sideCut,
            sideWallDropDb = sideEdge.dropDb,
            sideBandLimited = sideBandLimited,
            sideToMidLowDb = low,
            sideToMidHighDb = high,
            collapseDb = collapse,
            suspected = suspected,
            reasoning = reasoning,
        )
    }

    /** Side level minus mid level in dB over a bin range, from the mean power of each. */
    private fun ratioDb(mid: DoubleArray, side: DoubleArray, from: Int, to: Int): Double? {
        if (to <= from) return null
        val m = meanPowerDb(mid, from, to)
        val s = meanPowerDb(side, from, to)
        if (m <= DB_FLOOR + 1) return null
        return s - m
    }

    private fun meanPowerDb(db: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from..min(to, db.size - 1)) sum += 10.0.pow(db[i] / 10.0)
        val mean = sum / (min(to, db.size - 1) - from + 1)
        return powerDb(mean)
    }

    private fun powerDb(power: Double) = if (power > 0.0) 10.0 * log10(power) else DB_FLOOR

    /** Reduces the per-layer spectra to a fixed number of points for the spectrum chart. */
    fun curves(m: AnalysisMeasurements): SpectrumCurves? {
        if (m.layerNames.isEmpty() || m.windowsAnalyzed == 0) return null
        val points = SpectrumCurves.POINTS
        val series = m.layerNames.indices.map { i ->
            val peak = m.layerPeakDb[i]
            val mean = m.layerMeanDb[i]
            val bins = peak.size
            val outPeak = FloatArray(points)
            val outMean = FloatArray(points)
            for (p in 0 until points) {
                val from = (p.toLong() * bins / points).toInt()
                val to = max(from + 1, ((p + 1).toLong() * bins / points).toInt())
                var pk = DB_FLOOR
                var mn = DB_FLOOR
                for (b in from until min(to, bins)) {
                    if (peak[b] > pk) pk = peak[b]
                    if (mean[b] > mn) mn = mean[b]
                }
                outPeak[p] = pk.toFloat()
                outMean[p] = mn.toFloat()
            }
            SpectrumSeries(m.layerNames[i], outPeak, outMean)
        }
        return SpectrumCurves(m.sampleRate, series)
    }

    /** Correlation between -1 and 1 reads best with a sign and two decimals. */
    fun describeCorrelation(c: Double): String = when {
        abs(c) < 0.05 -> "uncorrelated"
        c > 0.95 -> "nearly mono"
        c > 0.5 -> "mostly in phase"
        c > 0.0 -> "wide"
        c > -0.5 -> "partly out of phase"
        else -> "mostly out of phase"
    }
}
