package com.spectroflac.analysis

import com.spectroflac.flac.ContainerKind
import com.spectroflac.flac.FlacDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File

/**
 * Runs the analysis engine over a directory of samples and prints the verdict for each, so the
 * detection thresholds can be checked against files whose provenance is known.
 *
 * Files whose name starts with `real_` must come out genuine, files starting with `fake_` must not.
 */
class JudgeTest {

    private val samplesDir: File?
        get() = System.getenv("SPECTROFLAC_SAMPLES")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `classifies known samples`() {
        val dir = samplesDir
        assumeTrue("SPECTROFLAC_SAMPLES not set", dir != null)
        val files = dir!!.listFiles { f -> f.extension.equals("flac", true) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no samples", files.isNotEmpty())

        val failures = mutableListOf<String>()
        for (file in files) {
            val outcome = runCatching { analyse(file) }.getOrElse {
                println("${file.name}: ${it.javaClass.simpleName} ${it.message}")
                if (file.name.startsWith("not_a_")) continue else throw it
            }
            val (verdict, confidence, spectral, findings) = outcome
            println(
                "%-28s %-11s %3d%%  cut %6.0f Hz (%3.0f%% of Nyq)  step %5.1f dB %-5s %s".format(
                    file.name, verdict.short, confidence, spectral.cutoffHz, spectral.cutoffRatio * 100,
                    spectral.rolloffDropDb, if (spectral.hasBrickWall) "WALL" else "-",
                    spectral.sourceGuess ?: "",
                )
            )
            findings.filter { it.severity == Severity.CRITICAL || it.severity == Severity.WARNING }
                .forEach { println("      ${it.severity}: ${it.title}") }

            when {
                file.name.startsWith("real_") && verdict != Verdict.AUTHENTIC ->
                    failures += "${file.name} should be AUTHENTIC but was $verdict"
                file.name.startsWith("fake_") && verdict != Verdict.FAKE ->
                    failures += "${file.name} should be FAKE but was $verdict"
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    private data class Outcome(
        val verdict: Verdict,
        val confidence: Int,
        val spectral: SpectralInfo,
        val findings: List<Finding>,
    )

    private fun analyse(file: File): Outcome {
        BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
            val decoder = FlacDecoder(input)
            val meta = decoder.readMetadata()
            val info = meta.streamInfo
            val analyzer = AudioAnalyzer(info.sampleRate, info.channels, info.bitsPerSample, info.totalSamples)
            val decoded = decoder.decodeFrames({ ch, n -> analyzer.onBlock(ch, n) })
            val m = analyzer.finish()

            val duration = info.totalSamples.toDouble() / info.sampleRate
            val bitrate = ((file.length() * 8.0) / duration / 1000.0).toInt()
            val technical = TechnicalInfo(
                info.bitsPerSample, info.sampleRate, info.channels, "test", duration, info.totalSamples,
                file.length(), bitrate, 0, 0.0, info.minBlockSize, info.maxBlockSize,
                info.minFrameSize, info.maxFrameSize, decoded.fixedBlockSize,
            )
            val integrity = IntegrityInfo(
                decoded.md5Expected, decoded.md5Actual, info.hasMd5, decoded.md5Verified, decoded.md5Matches,
                decoded.crcErrors, decoded.truncated, decoded.framesDecoded, decoded.samplesDecoded,
                info.totalSamples, decoded.error,
            )
            val dynamics = DynamicsInfo(
                m.peakSampleDbfs, m.rmsDbfs, m.crestFactorDb, m.dynamicRangeDb, m.clippedSamples,
                m.clippedRuns, m.silentSampleRatio, info.bitsPerSample, m.effectiveBitDepth,
                m.unusedLowBits, m.dualMono,
            )
            val spectral = Judge.spectral(m)
            val (verdict, confidence, findings) =
                Judge.assess(ContainerKind.FLAC, technical, spectral, integrity, dynamics, m)
            return Outcome(verdict, confidence, spectral, findings)
        }
    }
}
