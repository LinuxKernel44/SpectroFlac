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

    fun spectral(m: AnalysisMeasurements): SpectralInfo {
        val reasoning = mutableListOf<String>()
        val bins = m.peakSpectrumDb.size
        val smooth = smoothDb(m.peakSpectrumDb, 2)

        val lowBin = m.binOf(100.0).coerceAtLeast(1)
        var peak = -200.0
        for (b in lowBin until bins) if (smooth[b] > peak) peak = smooth[b]

        val floorStart = (bins * 0.97).toInt().coerceIn(lowBin, bins - 1)
        val hfFloor = median(smooth, floorStart, bins - 1)

        // Step detector: for every bin, how much louder is the 1.2 kHz below it than the 1.2 kHz
        // above it? A lossy cut is a step of 30 dB or more; a natural roll-off is a few dB.
        val w = m.binOf(1200.0).coerceAtLeast(2)
        val searchFrom = maxOf(lowBin + w, m.binOf(m.nyquist * 0.08))
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

        val hasWall = wallBin > 0 && wallDrop >= WALL_DB
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

        val cutoffHz = m.frequencyOf(cutoffBin)
        val ratio = cutoffHz / m.nyquist

        reasoning += "Analysed %d FFT windows of %d points (%.1f Hz per bin), keeping the loudest value seen in each bin across the whole track."
            .fmt(m.windowsAnalyzed, m.fftSize, m.binHz)
        reasoning += "Loudest bin %.1f dBFS; level in the top 3%% of the spectrum %.1f dBFS.".fmt(peak, hfFloor)
        reasoning += if (hasWall) {
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
     * Maps a measured cut to the encoder settings known to produce it. The measured edge sits a
     * few hundred hertz above the encoder's nominal cutoff (it is the half-way point of the step),
     * so these bands are shifted up to match.
     */
    private fun lossySourceGuess(cutoffHz: Double): String? {
        val k = cutoffHz / 1000.0
        return when {
            k < 11.7 -> "a very low bitrate lossy source (64 kbps or below)"
            k < 14.9 -> "MP3 80–96 kbps or AAC 64 kbps"
            k < 16.0 -> "MP3 112 kbps or AAC 96 kbps"
            k < 17.0 -> "MP3 128 kbps (LAME default) or Vorbis ~q3"
            k < 17.9 -> "MP3 160 kbps or AAC 128 kbps"
            k < 19.0 -> "MP3 192 kbps or AAC 160 kbps"
            k < 20.0 -> "MP3 224–256 kbps or AAC 192 kbps"
            k < 21.0 -> "MP3 320 kbps / LAME V0 or AAC 256 kbps"
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

    private fun smoothDb(src: DoubleArray, radius: Int): DoubleArray {
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

    private fun median(src: DoubleArray, from: Int, to: Int): Double {
        if (from > to) return src.getOrElse(from) { -200.0 }
        val slice = src.copyOfRange(from, to + 1)
        slice.sort()
        return slice[slice.size / 2]
    }

    fun formatRate(sampleRate: Int): String =
        if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz"
        else "%.1f kHz".fmt(sampleRate / 1000.0)
}
