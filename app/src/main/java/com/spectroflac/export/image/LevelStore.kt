package com.spectroflac.export.image

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Temporary storage for the spectrogram levels (one byte per pixel) of an image too big for memory.
 *
 * The analysis produces whole *columns* (one per time step, [height] bands each) but a PNG is written
 * *row by row*, so the data has to be transposed. The file is laid out in strips of [stripRows] bands:
 * strip `s` holds, for every column in order, that column's bytes for the bands of the strip. Writing
 * a chunk of columns then touches each strip once, and reading a strip is one contiguous read, with
 * `levelAt(column, band) = strip[column * rowsInStrip + (band - strip * stripRows)]`.
 */
class LevelStore(
    file: File,
    val width: Int,
    val height: Int,
    val stripRows: Int = 64,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val channel = raf.channel

    val stripCount: Int get() = (height + stripRows - 1) / stripRows

    fun rowsInStrip(strip: Int): Int = minOf(stripRows, height - strip * stripRows)

    init {
        require(width > 0 && height > 0 && stripRows > 0)
        raf.setLength(width.toLong() * height)
    }

    /**
     * Stores [columns] consecutive columns starting at [firstColumn]. [data] is column-major:
     * the column `i` of the chunk is `data[i * height until (i + 1) * height]`, band 0 first.
     */
    fun writeColumns(firstColumn: Int, columns: Int, data: ByteArray) {
        require(firstColumn >= 0 && firstColumn + columns <= width) { "columns out of range" }
        for (strip in 0 until stripCount) {
            val rows = rowsInStrip(strip)
            val block = ByteArray(columns * rows)
            for (i in 0 until columns) {
                System.arraycopy(data, i * height + strip * stripRows, block, i * rows, rows)
            }
            val position = strip.toLong() * width * stripRows + firstColumn.toLong() * rows
            writeFully(ByteBuffer.wrap(block), position)
        }
    }

    /** Reads the whole strip: `width * rowsInStrip(strip)` bytes, column after column. */
    fun readStrip(strip: Int, into: ByteArray) {
        val rows = rowsInStrip(strip)
        val size = width * rows
        require(into.size >= size) { "buffer too small" }
        readFully(ByteBuffer.wrap(into, 0, size), strip.toLong() * width * stripRows)
    }

    private fun writeFully(buffer: ByteBuffer, start: Long) {
        var position = start
        while (buffer.hasRemaining()) position += channel.write(buffer, position)
    }

    private fun readFully(buffer: ByteBuffer, start: Long) {
        var position = start
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n < 0) throw java.io.EOFException("level store is shorter than expected")
            position += n
        }
    }

    override fun close() {
        raf.close()
    }
}
