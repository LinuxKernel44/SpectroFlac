package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Random

class LevelStoreTest {

    private fun temp(): File = File.createTempFile("levels", ".bin").also { it.deleteOnExit() }

    /** Writes a random matrix in irregular chunks, then reads every strip back and compares every pixel. */
    private fun roundTrip(width: Int, height: Int, stripRows: Int, chunks: List<Int>) {
        require(chunks.sum() == width)
        val rnd = Random(width * 31L + height)
        val matrix = Array(width) { ByteArray(height).also(rnd::nextBytes) }
        LevelStore(temp(), width, height, stripRows).use { store ->
            var column = 0
            for (size in chunks) {
                val data = ByteArray(size * height)
                for (i in 0 until size) System.arraycopy(matrix[column + i], 0, data, i * height, height)
                store.writeColumns(column, size, data)
                column += size
            }
            for (strip in 0 until store.stripCount) {
                val rows = store.rowsInStrip(strip)
                val buffer = ByteArray(width * rows)
                store.readStrip(strip, buffer)
                for (c in 0 until width) for (r in 0 until rows) {
                    val band = strip * stripRows + r
                    assertEquals("column $c band $band", matrix[c][band], buffer[c * rows + r])
                }
            }
        }
    }

    @Test
    fun `columns written in chunks come back transposed correctly`() = roundTrip(1000, 150, 64, listOf(300, 1, 450, 249))

    @Test
    fun `a height that is an exact multiple of the strip has no partial strip`() = roundTrip(77, 128, 64, listOf(77))

    @Test
    fun `a single column and a single strip`() = roundTrip(1, 10, 64, listOf(1))

    @Test
    fun `many one-column chunks`() = roundTrip(40, 33, 8, List(40) { 1 })

    @Test
    fun `strip sizes add up to the height`() {
        LevelStore(temp(), 10, 150, 64).use {
            assertEquals(3, it.stripCount)
            assertEquals(listOf(64, 64, 22), (0 until 3).map(it::rowsInStrip))
        }
    }
}
