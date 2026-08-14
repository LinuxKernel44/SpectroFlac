package com.spectroflac.analysis

import com.spectroflac.flac.FlacDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File

/**
 * Times a full decode-and-measure pass. Set SPECTROFLAC_PERF to a directory of large files.
 */
class PerformanceTest {

    @Test
    fun `times a full pass`() {
        val dir = System.getenv("SPECTROFLAC_PERF")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue("SPECTROFLAC_PERF not set", dir != null)
        val files = dir!!.listFiles { f -> f.extension.equals("flac", true) }.orEmpty()
        assumeTrue("no files", files.isNotEmpty())

        for (file in files) {
            val start = System.nanoTime()
            BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
                val decoder = FlacDecoder(input)
                val info = decoder.readMetadata().streamInfo
                val analyzer = AudioAnalyzer(info.sampleRate, info.channels, info.bitsPerSample, info.totalSamples)
                decoder.decodeFrames({ ch, n -> analyzer.onBlock(ch, n) })
                val m = analyzer.finish()
                val seconds = (System.nanoTime() - start) / 1e9
                val audioSeconds = info.totalSamples.toDouble() / info.sampleRate
                println(
                    "%s: %.1f MB, %.0f s of audio in %.2f s (%.0fx realtime), %d windows".format(
                        file.name, file.length() / 1e6, audioSeconds, seconds, audioSeconds / seconds,
                        m.windowsAnalyzed,
                    )
                )
            }
        }
    }
}
