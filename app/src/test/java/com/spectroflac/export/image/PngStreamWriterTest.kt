package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class PngStreamWriterTest {

    private fun encode(width: Int, height: Int, level: Int = 3, chunk: Int = 1 shl 20, rgb: (Int, Int) -> Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = PngStreamWriter(out, width, height, level, chunk)
        val row = ByteArray(width * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = rgb(x, y)
                row[x * 3] = (c shr 16).toByte(); row[x * 3 + 1] = (c shr 8).toByte(); row[x * 3 + 2] = c.toByte()
            }
            writer.writeRow(row)
        }
        writer.finish()
        return out.toByteArray()
    }

    @Test
    fun `every chunk has a valid CRC and the file ends with IEND`() {
        val bytes = encode(64, 64, chunk = 200) { x, y -> x * y }
        TestPng.decode(bytes) // throws on a bad signature, CRC, or missing IEND
        assertEquals("IEND", String(bytes, bytes.size - 8, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `a decoder reads back exactly what was written`() {
        val bytes = encode(37, 23) { x, y -> (x * 6 shl 16) or (y * 11 shl 8) or ((x + y) * 3 and 0xFF) }
        val image = TestPng.decode(bytes)
        assertEquals(37, image.width); assertEquals(23, image.height)
        for (y in 0 until 23) for (x in 0 until 37) {
            val expected = (x * 6 shl 16) or (y * 11 shl 8) or ((x + y) * 3 and 0xFF)
            assertEquals("pixel $x,$y", expected, image.getRGB(x, y) and 0xFFFFFF)
        }
    }

    @Test
    fun `noise that needs many IDAT chunks survives intact`() {
        val rnd = Random(7)
        val noise = IntArray(500 * 120) { rnd.nextInt(0x1000000) }
        val bytes = encode(500, 120, level = 1, chunk = 512) { x, y -> noise[y * 500 + x] }
        val image = TestPng.decode(bytes)
        for (y in 0 until 120) for (x in 0 until 500) assertEquals(noise[y * 500 + x], image.getRGB(x, y) and 0xFFFFFF)
        // 180 kB of noise in 512-byte chunks: well over a hundred IDAT chunks.
        val idat = String(bytes, Charsets.ISO_8859_1).windowed(4).count { it == "IDAT" }
        assertTrue("expected many IDAT chunks, saw $idat", idat > 100)
    }

    @Test
    fun `one pixel wide and one pixel tall images work`() {
        val tall = TestPng.decode(encode(1, 50) { _, y -> y * 5 })
        assertEquals(1, tall.width); assertEquals(50, tall.height)
        assertEquals(49 * 5, tall.getRGB(0, 49) and 0xFFFFFF)
        val wide = TestPng.decode(encode(300, 1) { x, _ -> x })
        assertEquals(299, wide.getRGB(299, 0) and 0xFFFFFF)
    }

    @Test
    fun `flat areas compress hard`() {
        val bytes = encode(2000, 1000) { _, _ -> 0x05060E }
        assertTrue("a flat 2 MP image (6 MB raw) should shrink more than 100x, was ${bytes.size}", bytes.size < 60_000)
    }

    @Test
    fun `finishing early or writing too much is refused`() {
        val writer = PngStreamWriter(ByteArrayOutputStream(), 4, 2)
        val row = ByteArray(12)
        writer.writeRow(row)
        try { writer.finish(); throw AssertionError("finish with a missing row should fail") } catch (_: IllegalStateException) {}
        writer.writeRow(row)
        writer.finish()
        try { writer.writeRow(row); throw AssertionError("an extra row should fail") } catch (_: IllegalStateException) {}
    }
}
