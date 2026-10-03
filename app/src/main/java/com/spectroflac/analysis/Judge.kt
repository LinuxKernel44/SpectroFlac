package com.spectroflac.analysis

import com.spectroflac.flac.ContainerKind
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns raw measurements into a verdict.
 *
 * The core idea: a lossy encoder throws away everything above its cutoff in every single frame,
 * so the peak-hold spectrum of a transcode has a brick wall followed by a dead zone. Genuine
 * lossless audio that simply has little treble rolls off gradually and still shows content above
 * the roll-off somewhere in the track.
 */
object Judge {

    private const val MIN_WINDOWS = 4

    /** A wall has to drop at least this much to count as an encoder cut rather than a roll-off. */
    private const val WALL_DB = 26.0

    /**
     * The same test on the average spectrum, used only when the peak-hold spectrum shows no wall.
     * Sparse broadband bursts (clipping, decoder overshoot) can fill the dead zone above a cut in the
     * peak-hold spectrum, but they do not move a long-term average. On 20 genuine tracks from a real
     * lossless album the average-spectrum step never exceeded 12.6 dB; on 21 lossy transcodes of real
     * music it never fell below 25.7 dB. 20 sits in the middle of that gap.
     */
    private const val MEAN_WALL_DB = 20.0

    /** Where a spectrum stops: the edge of a brick wall, or else the top of the content. */
    class Edge(
        val cutoffBin: Int,
        val hasWall: Boolean,
        val dropDb: Double,
        val belowDb: Double,
        val aboveDb: Double,
        val peakDb: Double,
        val topFloorDb: Double,
    )

    /**
     * The step detector, shared by the verdict (on the mono mix) and the joint-stereo test (on the
     * side channel): for every bin, how much louder is the 1.2 kHz below it than the 1.2 kHz above?
     */
    fun findEdge(peakSpectrumDb: DoubleArray, binHz: Double, nyquist: Double, wallDb: Double = WALL_DB): Edge {
        val bins = peakSpectrumDb.size
        fun binOf(hz: Double) = (hz / binHz).toInt().coerceIn(0, bins - 1)
        val smooth = smoothDb(peakSpectrumDb, 2)

        val lowBin = binOf(100.0).coerceAtLeast(1)
        var peak = -200.0
        for (b in lowBin until bins) if (smooth[b] > peak) peak = smooth[b]

        val floorStart = (bins * 0.97).toInt().coerceIn(lowBin, bins - 1)
        val hfFloor = median(smooth, floorStart, bins - 1)

        val w = binOf(1200.0).coerceAtLeast(2)
        val searchFrom = maxOf(lowBin + w, binOf(nyquist * 0.08))
        val searchTo = bins - 1 - w / 2
        var wallBin = -1
        var wallDrop = 0.0
        var wallBelow = 0.0
        var wallAbove = 0.0
        var b = searchFrom
        while (b <= searchTo) {
            val below = mean(smooth, b - w, b - 1)
            val above = mean(smooth, b + 1, min(bins - 1, b + w))
            val score = below - above
            if (score > wallDrop) {
                wallDrop = score
                wallBin = b
                wallBelow = below
                wallAbove = above
            }
            b++
        }

        val hasWall = wallBin > 0 && wallDrop >= wallDb
        // With no wall to point at, report how far up meaningful content reaches: the highest bin
        // within 75 dB of the loudest one.
        var contentTop = lowBin
        val contentThreshold = peak - 75.0
        for (i in bins - 1 downTo lowBin) {
            if (smooth[i] > contentThreshold) {
                contentTop = i
                break
            }
        }

        // Place the edge at the half-way point of the step.
        val cutoffBin = if (hasWall) {
            val half = (wallBelow + wallAbove) / 2
            var edge = wallBin
            for (i in min(bins - 1, wallBin + w) downTo maxOf(lowBin, wallBin - w)) {
                if (smooth[i] >= half) {
                    edge = i
                    break
                }
            }
            edge
        } else contentTop

        return Edge(cutoffBin, hasWall, wallDrop, wallBelow, wallAbove, peak, hfFloor)
    }

    fun spectral(m: AnalysisMeasurements): SpectralInfo {
        val reasoning = mutableListOf<String>()
        val peakEdge = findEdge(m.peakSpectrumDb, m.binHz, m.nyquist)
        val meanEdge = findEdge(m.meanSpectrumDb, m.binHz, m.nyquist, MEAN_WALL_DB)
        // Fall back to the average spectrum when sparse bursts blur the peak-hold one.
        val fromMean = !peakEdge.hasWall && meanEdge.hasWall
        val edge = if (fromMean) {
            Edge(meanEdge.cutoffBin, true, meanEdge.dropDb, meanEdge.belowDb, meanEdge.aboveDb, peakEdge.peakDb, peakEdge.topFloorDb)
        } else peakEdge
        val hasWall = edge.hasWall
        val wallDrop = edge.dropDb
        val wallBelow = edge.belowDb
        val wallAbove = edge.aboveDb
        val peak = edge.peakDb
        val hfFloor = edge.topFloorDb
        val cutoffBin = edge.cutoffBin

        val cutoffHz = m.frequencyOf(cutoffBin)
        val ratio = cutoffHz / m.nyquist

        reasoning += "Analysed %d FFT windows of %d points (%.1f Hz per bin), keeping the loudest value seen in each bin across the whole track."
            .fmt(m.windowsAnalyzed, m.fftSize, m.binHz)
        reasoning += "Loudest bin %.1f dBFS; level in the top 3%% of the spectrum %.1f dBFS.".fmt(peak, hfFloor)
        reasoning += if (fromMean) {
            "The loudest-value spectrum shows only a %.1f dB step — occasional broadband bursts (clipping or decoder overshoot) fill the space above the cut — but the average spectrum drops %.1f dB at %.0f Hz (%.1f dBFS below it, %.1f dBFS above it)."
                .fmt(peakEdge.dropDb, wallDrop, cutoffHz, wallBelow, wallAbove)
        } else if (hasWall) {
            "Sharpest step in the spectrum: %.1f dB at %.0f Hz (%.1f dBFS below it, %.1f dBFS above it)."
                .fmt(wallDrop, cutoffHz, wallBelow, wallAbove)
        } else {
            "Sharpest step in the spectrum is only %.1f dB — no encoder cut. Content reaches %.0f Hz (%.0f%% of the %.1f kHz ceiling)."
                .fmt(wallDrop, cutoffHz, ratio * 100, m.nyquist / 1000)
        }

        val guess = if (m.windowsAnalyzed >= MIN_WINDOWS && hasWall) lossySourceGuess(cutoffHz) else null
        if (guess != null) reasoning += "A cut at this frequency is characteristic of: $guess."

        return SpectralInfo(
            cutoffHz = cutoffHz,
            nyquistHz = m.nyquist,
            cutoffRatio = ratio,
            rolloffDropDb = wallDrop,
            hasBrickWall = hasWall,
            noiseFloorDb = hfFloor,
            peakSpectrumDb = peak,
            sourceGuess = guess,
            windowsAnalyzed = m.windowsAnalyzed,
            fftSize = m.fftSize,
            reasoning = reasoning,
        )
    }

    private fun mean(src: DoubleArray, from: Int, to: Int): Double {
        if (from > to) return src.getOrElse(from) { -200.0 }
        var sum = 0.0
        for (i in from..to) sum += src[i]
        return sum / (to - from + 1)
    }

    /**
     * Maps a measured cut to the encoders known to produce it. The measured edge is the half-way
     * point of the step, and the bands below come from round-tripping full-band stereo noise through
     * libmp3lame (CBR and VBR), FFmpeg's native AAC, libvorbis and libopus at 44.1 and 48 kHz, then
     * measuring where each ended up. Several encoders share most cut frequencies, so a cut can only
     * ever suggest candidates, never name one. A very high bitrate leaves no cut at all on this test
     * (AAC 256+, LAME V0, Vorbis q6+), which is why a clean spectrum proves nothing about those.
     */
    internal fun lossySourceGuess(cutoffHz: Double): String? {
        val k = cutoffHz / 1000.0
        return when {
            k < 9.0 -> "a very low bitrate lossy source (MP3 V9 or 48 kbps and below)"
            k < 13.5 -> "MP3 64 kbps or AAC 64 kbps"
            k < 15.0 -> "MP3 80 kbps or a similar low-bitrate encode"
            k < 16.2 -> "MP3 96–112 kbps (or V7), Vorbis q0 or AAC ~80 kbps"
            k < 17.0 -> "MP3 128 kbps (LAME default or V5–V6), Vorbis q2 or AAC 96 kbps"
            k < 17.9 -> "MP3 160 kbps (or V4), Vorbis q3 or AAC 128 kbps"
            k < 19.25 -> "MP3 192 kbps (or V2) or Vorbis q4"
            k < 19.9 -> "MP3 224–256 kbps, AAC 160–192 kbps or Vorbis q4"
            k < 20.9 -> "Opus at any bitrate, MP3 320 kbps / LAME V0 or Vorbis q5"
            k < 21.8 -> "a high-bitrate lossy source (AAC 320, Vorbis q8+)"
            else -> null
        }
    }

    /** Detects a hi-res file whose content stops at the Nyquist limit of a lower sample rate. */
    private fun upsampledFrom(sampleRate: Int, cutoffHz: Double): Int? {
        if (sampleRate <= 50000) return null
        val candidates = intArrayOf(44100, 48000, 88200, 96000).filter { it < sampleRate }
        var best: Int? = null
        var bestDelta = Double.MAX_VALUE
        for (rate in candidates) {
            val expected = rate / 2.0
            val delta = abs(cutoffHz - expected) / expected
            // Resamplers put the transition band just above the source Nyquist, so allow a margin
            // on both sides rather than demanding an exact match.
            if (delta <= 0.20 && delta < bestDelta) {
                bestDelta = delta
                best = rate
            }
        }
        return best
    }

    fun assess(
        container: ContainerKind,
        technical: TechnicalInfo,
        spectral: SpectralInfo,
        integrity: IntegrityInfo,
        dynamics: DynamicsInfo,
        measurements: AnalysisMeasurements,
        jointStereo: JointStereoInfo? = null,
    ): Triple<Verdict, Int, List<Finding>> {
        val findings = mutableListOf<Finding>()
        var verdict = Verdict.AUTHENTIC
        var confidence = 70

        fun raise(target: Verdict, conf: Int) {
            if (target.ordinal > verdict.ordinal) verdict = target
            confidence = maxOf(confidence, conf)
        }

        // ---- integrity ------------------------------------------------------
        if (integrity.decodeError != null || integrity.truncated) {
            findings += Finding(
                Severity.CRITICAL,
                "Stream ends early",
                integrity.decodeError?.let { "Decoding stopped: $it" }
                    ?: "The audio data stops before the number of samples declared in STREAMINFO.",
            )
            raise(Verdict.CORRUPT, 95)
        }
        if (integrity.crcErrors > 0) {
            findings += Finding(
                Severity.CRITICAL,
                "${integrity.crcErrors} frame${if (integrity.crcErrors > 1) "s" else ""} failed the CRC check",
                "Frames carry a CRC-16 of their own contents. A mismatch means the audio data is damaged.",
            )
            raise(Verdict.CORRUPT, 97)
        }
        when {
            !integrity.md5Present -> findings += Finding(
                Severity.INFO,
                "No MD5 signature stored",
                "The encoder left the STREAMINFO MD5 field empty, so bit-perfect integrity cannot be confirmed.",
            )
            integrity.md5Verified && integrity.md5Matches -> findings += Finding(
                Severity.GOOD,
                "MD5 signature verified",
                "The decoded audio matches the fingerprint the encoder stored: the file is bit-perfect.",
            )
            integrity.md5Verified && !integrity.md5Matches -> {
                findings += Finding(
                    Severity.CRITICAL,
                    "MD5 signature does not match",
                    "The decoded audio differs from the fingerprint stored by the encoder. The file has been " +
                        "damaged or re-written by a tool that did not update it.",
                )
                raise(Verdict.CORRUPT, 90)
            }
        }

        // ---- lossy source ---------------------------------------------------
        val ratio = spectral.cutoffRatio
        val drop = spectral.rolloffDropDb
        val enoughData = spectral.windowsAnalyzed >= MIN_WINDOWS

        val upsampledFrom = upsampledFrom(technical.sampleRate, spectral.cutoffHz)

        val cutKhz = "%.1f kHz".fmt(spectral.cutoffHz / 1000)
        val ceilingKhz = "%.1f kHz".fmt(spectral.nyquistHz / 1000)
        val dropText = "%.0f dB".fmt(drop)
        val hiRes = technical.sampleRate > 50000
        // A genuine CD or 48 kHz master carries content (or at least dither noise) to within about
        // a kilohertz of Nyquist; the anti-alias filter of the converter sits right at the top.
        val genuineCeiling = spectral.nyquistHz - 1100
        val bandLimited = spectral.hasBrickWall &&
            (if (hiRes) spectral.cutoffHz < spectral.nyquistHz * 0.9 else spectral.cutoffHz < genuineCeiling)

        when {
            !enoughData -> findings += Finding(
                Severity.INFO,
                "File too short for a spectral verdict",
                "Fewer than $MIN_WINDOWS analysis windows could be taken, so the bandwidth test was skipped.",
            )

            bandLimited && hiRes -> {
                val source = upsampledFrom?.let { "the ceiling of a ${formatRate(it)} source" }
                    ?: "far below what this sample rate can carry"
                val alsoMatches = spectral.sourceGuess?.let { ", and the cut also matches $it" }.orEmpty()
                findings += Finding(
                    Severity.CRITICAL,
                    "Fake hi-res — nothing above $cutKhz",
                    "The file declares ${formatRate(technical.sampleRate)}, but the level falls $dropText " +
                        "at $cutKhz and nothing survives above it — $source. The audio was resampled up " +
                        "from a lower rate$alsoMatches; the extra sample rate carries no extra information.",
                )
                verdict = Verdict.FAKE
                confidence = maxOf(confidence, 88)
            }

            bandLimited -> {
                findings += Finding(
                    Severity.CRITICAL,
                    "Lossy source detected — cut at $cutKhz",
                    "Everything above $cutKhz is missing from every part of the track, and the level " +
                        "drops $dropText across that boundary. That brick wall is what a lossy encoder " +
                        "leaves behind: ${spectral.sourceGuess ?: "a lossy encoder"}.",
                )
                verdict = Verdict.FAKE
                confidence = (74 + ((drop - WALL_DB) / 2).toInt().coerceIn(0, 15) +
                    if (ratio < 0.85) 8 else 0).coerceAtMost(99)
            }

            spectral.hasBrickWall || ratio >= 0.85 -> {
                findings += Finding(
                    Severity.GOOD,
                    "Full-bandwidth spectrum",
                    "Content reaches $cutKhz, ${"%.0f".fmt(ratio * 100)} % of the $ceilingKhz ceiling for " +
                        "this sample rate. No lossy encoder leaves a spectrum like this.",
                )
                if (verdict == Verdict.AUTHENTIC) confidence = maxOf(confidence, if (integrity.md5Matches) 96 else 90)
            }

            else -> findings += Finding(
                Severity.INFO,
                "Treble rolls off gradually below $cutKhz",
                "Content fades out well below the $ceilingKhz ceiling, but there is no sharp cut (the " +
                    "steepest step measures only $dropText). That is what a dull, old or deliberately " +
                    "filtered master looks like, not what an encoder does.",
            )
        }

        // ---- bit depth ------------------------------------------------------
        // A silent file has no bits to look at, so the test would always "fail".
        val bitDepthTestable = dynamics.silentRatio < 0.95
        if (!bitDepthTestable) {
            findings += Finding(
                Severity.INFO,
                "Nothing to measure",
                "The file is digital silence from start to finish.",
            )
        } else if (dynamics.declaredBitDepth >= 24) {
            when {
                dynamics.unusedLowBits >= 8 -> {
                    findings += Finding(
                        Severity.CRITICAL,
                        "Padded ${dynamics.declaredBitDepth}-bit — really ${dynamics.effectiveBitDepth}-bit",
                        "The bottom ${dynamics.unusedLowBits} bits are zero in every single sample. The audio " +
                            "carries no more than ${dynamics.effectiveBitDepth} bits of real resolution; the file " +
                            "was simply padded out to ${dynamics.declaredBitDepth} bits.",
                    )
                    if (verdict != Verdict.FAKE) {
                        verdict = Verdict.FAKE
                        confidence = maxOf(confidence, 92)
                    }
                }
                dynamics.unusedLowBits in 4..7 -> {
                    findings += Finding(
                        Severity.WARNING,
                        "Only ${dynamics.effectiveBitDepth} of ${dynamics.declaredBitDepth} bits are used",
                        "The lowest ${dynamics.unusedLowBits} bits never carry any signal, which points to a " +
                            "conversion from a lower resolution master.",
                    )
                    if (verdict == Verdict.AUTHENTIC) {
                        verdict = Verdict.SUSPICIOUS
                        confidence = 65
                    }
                }
                else -> findings += Finding(
                    Severity.GOOD,
                    "Real ${dynamics.declaredBitDepth}-bit resolution",
                    "All ${dynamics.declaredBitDepth} bits carry signal, so the extra depth is genuine.",
                )
            }
        } else if (dynamics.unusedLowBits >= 4) {
            findings += Finding(
                Severity.WARNING,
                "Only ${dynamics.effectiveBitDepth} of ${dynamics.declaredBitDepth} bits are used",
                "The lowest ${dynamics.unusedLowBits} bits are always zero — unusual for genuine " +
                    "${dynamics.declaredBitDepth}-bit audio.",
            )
        }

        // ---- levels and dynamics -------------------------------------------
        if (dynamics.clippedRuns > 0) {
            val severity = if (dynamics.clippedRuns > 1000) Severity.WARNING else Severity.INFO
            findings += Finding(
                severity,
                "${dynamics.clippedRuns} clipped passages",
                "${dynamics.clippedSamples} samples sit at full scale, in ${dynamics.clippedRuns} runs of three " +
                    "or more in a row — the signature of a master pushed past 0 dBFS (or of a lossy decode " +
                    "that overshot).",
            )
        }
        dynamics.dynamicRangeDb?.let { dr ->
            val rounded = dr.roundToInt()
            val severity = when {
                dr < 6 -> Severity.WARNING
                dr < 9 -> Severity.INFO
                else -> Severity.GOOD
            }
            val comment = when {
                dr < 6 -> "Extremely compressed master (loudness war territory)."
                dr < 9 -> "Fairly compressed master, typical of modern pop and rock releases."
                dr < 14 -> "Healthy dynamics."
                else -> "Very open dynamics, typical of classical, jazz or an untouched master."
            }
            findings += Finding(severity, "Dynamic range DR$rounded", "$comment Peak %.1f dBFS, RMS %.1f dBFS, crest factor %.1f dB."
                .fmt(dynamics.peakDbfs, dynamics.rmsDbfs, dynamics.crestFactorDb))
        }
        if (dynamics.dualMono) {
            findings += Finding(
                Severity.INFO,
                "Dual mono",
                "Both channels are sample-for-sample identical: the file is mono stored as stereo.",
            )
        }
        measurements.stereo?.takeUnless { dynamics.dualMono }?.let { stereo ->
            findings += stereoFindings(stereo)
        }
        jointStereo?.takeIf { it.suspected }?.let { joint ->
            val lossyAlready = verdict == Verdict.FAKE && spectral.hasBrickWall
            // A side channel with its own brick wall is a specific symptom; a narrowing stereo image
            // alone is weak evidence (real music does it), so it never rates above INFO.
            findings += Finding(
                if (!lossyAlready && joint.sideBandLimited) Severity.WARNING else Severity.INFO,
                "Joint-stereo coding artifacts",
                buildString {
                    if (joint.sideBandLimited) {
                        append("The side channel stops at %.1f kHz while the mid channel reaches %.1f kHz. "
                            .fmt(joint.sideCutoffHz / 1000, joint.midCutoffHz / 1000))
                    }
                    joint.collapseDb?.takeIf { it >= StereoAnalysis.COLLAPSE_DB }?.let {
                        append("The stereo image narrows by %.0f dB between the mid range and the treble. ".fmt(it))
                    }
                    append(
                        if (lossyAlready) "That is how MP3 and AAC joint-stereo modes behave, and it fits the lossy source found above."
                        else "That is how MP3 and AAC joint-stereo modes behave. It is not proof on its own, but a genuine lossless master rarely does this.",
                    )
                },
            )
        }
        if (dynamics.silentRatio > 0.5) {
            findings += Finding(
                Severity.INFO,
                "${"%.0f".fmt(dynamics.silentRatio * 100)} % digital silence",
                "Most of the file contains no signal at all, which limits what the spectral test can see.",
            )
        }

        if (measurements.samplesAnalyzed != integrity.declaredSamples && integrity.declaredSamples > 0 &&
            !integrity.truncated
        ) {
            findings += Finding(
                Severity.WARNING,
                "Sample count differs from STREAMINFO",
                "Decoded ${measurements.samplesAnalyzed} samples, header declares ${integrity.declaredSamples}.",
            )
        }

        // Problems first, then the checks that passed, then neutral observations: on a clean file
        // the reader should see the confirmations before the small print.
        val order = listOf(Severity.CRITICAL, Severity.WARNING, Severity.GOOD, Severity.INFO)
        return Triple(verdict, confidence.coerceIn(0, 99), findings.sortedBy { order.indexOf(it.severity) })
    }

    private fun stereoFindings(stereo: StereoInfo): List<Finding> {
        val out = mutableListOf<Finding>()
        val silent = stereo.silentChannel
        if (silent != null) {
            val name = if (silent == 0) "left" else "right"
            out += Finding(
                Severity.WARNING,
                "The $name channel is silent",
                "Only one channel carries signal, so the file plays on one side only.",
            )
            return out
        }
        when {
            stereo.correlation < 0.0 -> out += Finding(
                Severity.WARNING,
                "Channels are mostly out of phase",
                "Left and right correlate at %.2f. Played in mono, much of the signal would cancel out."
                    .fmt(stereo.correlation),
            )
            stereo.negativeRatio > 0.25 -> out += Finding(
                Severity.INFO,
                "Out-of-phase passages",
                "%.0f %% of the track has left and right in opposite polarity, which hurts mono playback."
                    .fmt(stereo.negativeRatio * 100),
            )
            stereo.correlation > 0.98 -> out += Finding(
                Severity.INFO,
                "Nearly mono",
                "Left and right correlate at %.3f: the stereo image is almost non-existent.".fmt(stereo.correlation),
            )
        }
        if (abs(stereo.balanceDb) >= 6.0) {
            out += Finding(
                Severity.INFO,
                "Unbalanced channels",
                "The ${if (stereo.balanceDb > 0) "left" else "right"} channel is %.1f dB louder than the other."
                    .fmt(abs(stereo.balanceDb)),
            )
        }
        return out
    }

    fun summarise(verdict: Verdict, spectral: SpectralInfo?, integrity: IntegrityInfo?): String = when (verdict) {
        Verdict.AUTHENTIC -> buildString {
            append("Everything checks out: full-bandwidth audio")
            if (integrity?.md5Matches == true) append(" and a bit-perfect MD5 match")
            append(". Nothing suggests this came from a lossy source.")
        }
        Verdict.SUSPICIOUS -> "Some measurements are off, but not enough to call it a transcode. Look at the findings below."
        Verdict.FAKE -> spectral?.sourceGuess?.let {
            "This is not genuine lossless audio: the spectrum carries the fingerprint of $it."
        } ?: "This is not genuine lossless audio — the content does not fill the format it claims."
        Verdict.CORRUPT -> "The FLAC stream itself is damaged or has been altered since it was encoded."
        Verdict.NOT_FLAC -> "This file is not a FLAC stream, whatever its extension says."
        Verdict.ERROR -> "The file could not be analysed."
    }

    internal fun smoothDb(src: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(src.size)
        for (i in src.indices) {
            var sum = 0.0
            var n = 0
            for (j in (i - radius)..(i + radius)) {
                if (j in src.indices) {
                    sum += src[j]
                    n++
                }
            }
            out[i] = sum / n
        }
        return out
    }

    internal fun median(src: DoubleArray, from: Int, to: Int): Double {
        if (from > to) return src.getOrElse(from) { -200.0 }
        val slice = src.copyOfRange(from, to + 1)
        slice.sort()
        return slice[slice.size / 2]
    }

    fun formatRate(sampleRate: Int): String =
        if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz"
        else "%.1f kHz".fmt(sampleRate / 1000.0)
}
