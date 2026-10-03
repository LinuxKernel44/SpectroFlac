package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class StftColumnsTest {
    private val sampleRate = 44_100

    private fun sine(hz: Double, seconds: Int, amplitude: Double = 0.5) =
        FloatArray(sampleRate * seconds) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

    private fun column(data: FloatArray, width: Int, height: Int, c: Int): ByteArray {
        val plan = StftPlan(sampleRate, data.size.toLong(), width, height)
        val out = ByteArray(height)
        StftColumns(plan).newWorker().column(ArrayPcm(data), c, out, 0)
        return out
    }

    private fun loudestRow(levels: ByteArray) = levels.indices.maxByOrNull { levels[it].toInt() and 0xFF }!!

    @Test
    fun `a pure tone lights the row of its frequency`() {
        val levels = column(sine(5_000.0, 2), 400, 512, 200)
        val expected = (5_000.0 / (sampleRate / 2.0) * 512).toInt()
        assertTrue("peak at ${loudestRow(levels)}, expected about $expected", kotlin.math.abs(loudestRow(levels) - expected) <= 2)
        val peak = levels[loudestRow(levels)].toInt() and 0xFF
        assertTrue("a half-scale tone should be bright, level $peak", peak > 200)
        // Far from the tone the spectrum is (nearly) empty.
        assertTrue((levels[400].toInt() and 0xFF) < peak - 100)
    }

    @Test
    fun `the tone moves with its frequency`() {
        val low = loudestRow(column(sine(1_000.0, 2), 300, 1024, 100))
        val high = loudestRow(column(sine(10_000.0, 2), 300, 1024, 100))
        assertEquals(1_000.0 / 22_050 * 1024, low.toDouble(), 3.0)
        assertEquals(10_000.0 / 22_050 * 1024, high.toDouble(), 3.0)
    }

    @Test
    fun `silence is black`() {
        assertTrue(column(FloatArray(sampleRate * 2), 300, 256, 50).all { it.toInt() == 0 })
    }

    @Test
    fun `a louder tone gives a higher level`() {
        val quiet = column(sine(3_000.0, 2, 0.01), 300, 512, 100).maxOf { it.toInt() and 0xFF }
        val loud = column(sine(3_000.0, 2, 0.5), 300, 512, 100).maxOf { it.toInt() and 0xFF }
        // 34 dB more is 34/120*255 = 72 levels more.
        assertEquals(72.0, (loud - quiet).toDouble(), 6.0)
    }

    @Test
    fun `finer images use longer FFTs and every row gets at least one bin`() {
        for (height in listOf(256, 540, 1080, 2160, 4320, 16384)) {
            val plan = StftPlan(sampleRate, sampleRate * 10L, 1000, height)
            assertTrue("fft ${plan.fftSize} for $height rows", plan.bins >= height)
            for (r in 0 until height) assertTrue("row $r has bins", plan.binStart[r + 1] > plan.binStart[r])
            assertEquals(plan.bins, plan.binStart[height])
        }
    }

    @Test
    fun `the first and last columns do not splatter where the audio starts and stops`() {
        // A tone that starts at full amplitude: zero-padding before sample 0 would be a step, which
        // smears energy across every frequency. Far from the tone the first column must stay dark.
        val data = FloatArray(sampleRate * 2) { (0.5 * kotlin.math.cos(2 * PI * 3_000.0 * it / sampleRate)).toFloat() }
        for (c in listOf(0, 399)) {
            val levels = column(data, 400, 512, c)
            val peak = levels.maxOf { it.toInt() and 0xFF }
            val farAway = (400 until 512).maxOf { levels[it].toInt() and 0xFF }
            assertTrue("column $c: peak $peak, far from the tone $farAway", farAway < peak - 100)
        }
    }

    @Test
    fun `columns past the ends read silence instead of failing`() {
        val data = sine(2_000.0, 1)
        val first = column(data, 50, 256, 0)
        val last = column(data, 50, 256, 49)
        assertTrue(first.any { it.toInt() != 0 } && last.any { it.toInt() != 0 })
    }

    @Test
    fun `the file-backed source reads exactly what the array source reads`() {
        val data = sine(4_321.0, 1) { _ -> }
        val file = File.createTempFile("pcm", ".f32").also { it.deleteOnExit() }
        RandomAccessFile(file, "rw").use { raf ->
            val buffer = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(data)
            raf.channel.write(buffer)
            val source = FilePcmSource(raf.channel, data.size.toLong())
            val reference = ArrayPcm(data)
            for (start in listOf(-3000L, -1L, 0L, 12_345L, data.size - 2048L, data.size - 5L, data.size + 100L)) {
                val a = FloatArray(4096); val b = FloatArray(4096)
                source.read(start, 4096, a); reference.read(start, 4096, b)
                assertTrue("window at $start", a.contentEquals(b))
            }
        }
    }

    private fun sine(hz: Double, seconds: Int, unused: (Int) -> Unit): FloatArray = sine(hz, seconds)
}
