package com.spectroflac.flac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File

/**
 * Decodes real FLAC files and checks the decoder against the MD5 the encoder stored in
 * STREAMINFO — the strongest correctness check available for a decoder.
 *
 * Point SPECTROFLAC_SAMPLES at a directory of .flac files to run it, for example:
 *
 *     ffmpeg -f lavfi -i "sine=frequency=440:duration=20" -c:a flac sample.flac
 *     SPECTROFLAC_SAMPLES=/path/to/dir ./gradlew :app:testDebugUnitTest
 */
class FlacDecoderTest {

    private val samplesDir: File?
        get() = System.getenv("SPECTROFLAC_SAMPLES")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `decodes every sample and matches the stored md5`() {
        val dir = samplesDir
        assumeTrue("SPECTROFLAC_SAMPLES not set", dir != null)

        val files = dir!!.listFiles { f -> f.extension.equals("flac", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no .flac files in $dir", files.isNotEmpty())

        var checked = 0
        for (file in files) {
            val head = ByteArray(8192)
            BufferedInputStream(file.inputStream()).use { it.read(head) }
            if (ContainerSniffer.sniff(head).kind != ContainerKind.FLAC) {
                println("${file.name}: not a FLAC container, skipped")
                continue
            }

            val result = BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
                val decoder = FlacDecoder(input)
                val info = decoder.readMetadata().streamInfo
                var peak = 0
                val decoded = decoder.decodeFrames({ channels, count ->
                    for (c in channels.indices) {
                        for (i in 0 until count) {
                            val v = kotlin.math.abs(channels[c][i])
                            if (v > peak) peak = v
                        }
                    }
                })
                println(
                    "${file.name}: ${info.bitsPerSample} bit / ${info.sampleRate} Hz / " +
                        "${info.channels} ch, ${decoded.samplesDecoded} samples, " +
                        "${decoded.framesDecoded} frames, crcErrors=${decoded.crcErrors}, " +
                        "md5 ${if (decoded.md5Matches) "OK" else "MISMATCH"}, peak=$peak"
                )
                assertEquals("${file.name}: sample count", info.totalSamples, decoded.samplesDecoded)
                assertEquals("${file.name}: CRC errors", 0, decoded.crcErrors)
                assertTrue("${file.name}: not truncated", !decoded.truncated)
                assertEquals("${file.name}: MD5", decoded.md5Expected, decoded.md5Actual)
                decoded
            }
            assertTrue(result.framesDecoded > 0)
            checked++
        }
        assertTrue("no FLAC file was actually checked", checked > 0)
    }
}
