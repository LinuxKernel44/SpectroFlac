package com.spectroflac.export.image

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes an 8-bit RGB PNG one row at a time, so an image far bigger than the available memory can
 * be produced: only the current row and the compressor's window are ever held.
 *
 * The compressed data is cut into IDAT chunks of at most [chunkSize] bytes as it is produced.
 */
class PngStreamWriter(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    compressionLevel: Int = 3,
    private val chunkSize: Int = 1 shl 20,
) {
    private val deflater = Deflater(compressionLevel)
    private val compressed = ByteArray(chunkSize)
    private var pending = 0
    private var rowsWritten = 0
    private val rowBuffer = ByteArray(1 + width * 3)
    private val crc = CRC32()
    private var finished = false

    init {
        require(width > 0 && height > 0) { "empty image" }
        out.write(SIGNATURE)
        val header = ByteArray(13)
        putInt(header, 0, width)
        putInt(header, 4, height)
        header[8] = 8      // bit depth
        header[9] = 2      // colour type: RGB
        // compression, filter and interlace methods stay 0
        writeChunk("IHDR", header, header.size)
    }

    /** Appends one row: [rgb] holds `width * 3` bytes starting at [offset]. */
    fun writeRow(rgb: ByteArray, offset: Int = 0) {
        check(rowsWritten < height) { "too many rows" }
        rowBuffer[0] = 0 // filter: none
        System.arraycopy(rgb, offset, rowBuffer, 1, width * 3)
        deflater.setInput(rowBuffer, 0, rowBuffer.size)
        drain(false)
        rowsWritten++
    }

    /** Completes the compressed stream and writes the end chunk. */
    fun finish() {
        if (finished) return
        check(rowsWritten == height) { "wrote $rowsWritten of $height rows" }
        deflater.finish()
        drain(true)
        if (pending > 0) flushIdat()
        writeChunk("IEND", ByteArray(0), 0)
        deflater.end()
        out.flush()
        finished = true
    }

    private fun drain(untilFinished: Boolean) {
        while (true) {
            if (pending == compressed.size) flushIdat()
            val n = deflater.deflate(compressed, pending, compressed.size - pending)
            pending += n
            if (n == 0) {
                if (untilFinished && !deflater.finished()) continue
                break
            }
        }
    }

    private fun flushIdat() {
        writeChunk("IDAT", compressed, pending)
        pending = 0
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int) {
        val head = ByteArray(8)
        putInt(head, 0, length)
        for (i in 0 until 4) head[4 + i] = type[i].code.toByte()
        out.write(head)
        out.write(data, 0, length)
        crc.reset()
        crc.update(head, 4, 4)
        crc.update(data, 0, length)
        val tail = ByteArray(4)
        putInt(tail, 0, crc.value.toInt())
        out.write(tail)
    }

    private fun putInt(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value ushr 24).toByte()
        dst[at + 1] = (value ushr 16).toByte()
        dst[at + 2] = (value ushr 8).toByte()
        dst[at + 3] = value.toByte()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
