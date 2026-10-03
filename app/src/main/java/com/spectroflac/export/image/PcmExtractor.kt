package com.spectroflac.export.image

import com.spectroflac.flac.FlacDecoder
import com.spectroflac.flac.FlacMetadata
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the extraction learned about the file. */
class PcmInfo(
    val metadata: FlacMetadata,
    val sampleRate: Int,
    val channels: Int,
    val samples: Long,
    /** The stream ended early or had errors: the image shows what could be decoded. */
    val partial: Boolean,
)

/**
 * Decodes the file once and stores the chosen channel as little-endian 32-bit floats, so any number
 * of threads can then read analysis windows from it at random.
 */
object PcmExtractor {
    private const val BATCH = 1 shl 16

    fun extract(
        input: InputStream,
        fileSize: Long,
        channel: ExportChannel,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmInfo {
        val decoder = FlacDecoder(BufferedInputStream(input, 1 shl 16))
        val metadata = decoder.readMetadata()
        val info = metadata.streamInfo
        val channels = info.channels
        val fullScale = (1L shl (info.bitsPerSample - 1)).toFloat()
        val chosen = if (channel.id >= channels) ExportChannel.MIX else channel.id

        var written = 0L
        var blocks = 0
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            val out = raf.channel
            val buffer = ByteBuffer.allocate(BATCH * 4).order(ByteOrder.LITTLE_ENDIAN)
            val floats = buffer.asFloatBuffer()

            fun flush() {
                buffer.position(0)
                buffer.limit(floats.position() * 4)
                while (buffer.hasRemaining()) out.write(buffer)
                written += floats.position()
                buffer.clear()
                floats.clear()
            }

            val result = decoder.decodeFrames({ data, count ->
                for (i in 0 until count) {
                    val v = when {
                        chosen == ExportChannel.MIX -> {
                            var sum = 0L
                            for (c in 0 until channels) sum += data[c][i]
                            sum.toFloat() / (channels * fullScale)
                        }
                        chosen == ExportChannel.SIDE -> (data[0][i] - data[1][i]) * 0.5f / fullScale
                        else -> data[chosen][i] / fullScale
                    }
                    if (!floats.hasRemaining()) flush()
                    floats.put(v)
                }
                if (++blocks % 16 == 0 && fileSize > 0) onProgress((decoder.bytesRead.toFloat() / fileSize).coerceIn(0f, 1f))
            }, isCancelled = isCancelled)
            flush()
            onProgress(1f)
            return PcmInfo(metadata, info.sampleRate, channels, written, partial = result.error != null || result.truncated)
        }
    }
}
