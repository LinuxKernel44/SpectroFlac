package com.spectroflac.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Feeds synthetic signals through [AudioAnalyzer] and checks the stereo measurements. */
class StereoAnalysisTest {

    private val sampleRate = 44_100
    private val seconds = 12

    private fun analyse(left: (Int) -> Int, right: (Int) -> Int): AnalysisMeasurements {
        val total = sampleRate * seconds
        val analyzer = AudioAnalyzer(sampleRate, 2, 16, total.toLong())
        val block = 4096
        val l = IntArray(block)
        val r = IntArray(block)
        var i = 0
        while (i < total) {
            val n = minOf(block, total - i)
            for (k in 0 until n) {
                l[k] = left(i + k)
                r[k] = right(i + k)
            }
            analyzer.onBlock(arrayOf(l, r), n)
            i += n
        }
        return analyzer.finish()
    }

    private fun noise(seed: Long, amplitude: Int): (Int) -> Int {
        val rnd = Random(seed)
        val cache = IntArray(sampleRate * seconds) { ((rnd.nextDouble() * 2 - 1) * amplitude).toInt() }
        return { cache[it] }
    }

    @Test
    fun `identical channels correlate at one`() {
        val n = noise(1, 8000)
        val m = analyse(n, n)
        val s = m.stereo!!
        assertEquals(1.0, s.correlation, 1e-6)
        assertEquals(0.0, s.balanceDb, 1e-6)
        assertTrue(m.dualMono)
        assertNull(StereoAnalysis.jointStereo(m))
    }

    @Test
    fun `inverted channels correlate at minus one`() {
        val n = noise(2, 8000)
        val s = analyse(n) { -n(it) }.stereo!!
        assertEquals(-1.0, s.correlation, 1e-6)
        assertEquals(1.0, s.negativeRatio, 1e-9)
    }

    @Test
    fun `independent channels are uncorrelated and balanced`() {
        val s = analyse(noise(3, 8000), noise(4, 8000)).stereo!!
        assertEquals(0.0, s.correlation, 0.03)
        assertEquals(0.0, s.balanceDb, 0.3)
        assertTrue(s.timeline.isNotEmpty())
        assertTrue(s.widthDb > -1.0)
    }

    @Test
    fun `a louder left channel shows as positive balance`() {
        val n = noise(5, 4000)
        val s = analyse({ n(it) * 2 }, n).stereo!!
        assertEquals(6.0, s.balanceDb, 0.1)
        assertEquals(1.0, s.correlation, 1e-6)
    }

    @Test
    fun `a silent channel is reported`() {
        val s = analyse(noise(6, 8000)) { 0 }.stereo!!
        assertEquals(1, s.silentChannel)
        assertEquals(60.0, s.balanceDb, 1e-9)
    }

    @Test
    fun `per channel layers and a full resolution spectrogram are produced`() {
        val tone = { i: Int -> (sin(2 * PI * 1000 * i / sampleRate) * 10_000).toInt() }
        val m = analyse(tone, noise(7, 100))
        assertEquals(listOf("Mid", "Left", "Right", "Side"), m.layerNames)
        val full = m.fullSpectrogram
        assertNotNull(full)
        assertEquals(4, full!!.layers.size)
        assertEquals(AudioAnalyzer.FULL_BANDS, full.bands)
        assertEquals(full.columns * full.bands, full.layers[0].data.size)
        // The 1 kHz tone shows up in the left layer and not in the right one.
        val band = (1000.0 / (sampleRate / 2.0) * full.bands).toInt()
        val left = full.levelDb(1, full.columns / 2, band)
        val right = full.levelDb(2, full.columns / 2, band)
        assertTrue("left $left right $right", left > right + 30)
        // Time axis covers the file.
        assertEquals(seconds.toDouble(), full.durationSeconds, 0.01)
    }

    @Test
    fun `a side channel that stops early is flagged as joint stereo`() {
        // Both channels carry a shared full-band signal, but the diffuse part (what makes the side
        // channel) is low-passed far below it: exactly what a joint-stereo encoder leaves behind.
        val common = noise(8, 6000)
        val diffuseL = lowPass(noise(9, 6000), 0.08)
        val diffuseR = lowPass(noise(10, 6000), 0.08)
        val m = analyse({ common(it) + diffuseL(it) }, { common(it) + diffuseR(it) })
        val joint = StereoAnalysis.jointStereo(m)
        assertNotNull(joint)
        assertTrue("side ${joint!!.sideCutoffHz} mid ${joint.midCutoffHz}", joint.suspected)
    }

    /** Crude single-pole low-pass, enough to put a clear step in the spectrum. */
    private fun lowPass(source: (Int) -> Int, alpha: Double): (Int) -> Int {
        val out = IntArray(sampleRate * seconds)
        var y = 0.0
        for (i in out.indices) {
            y += alpha * (source(i) - y)
            out[i] = y.toInt()
        }
        return { out[it] }
    }

    @Test
    fun `spectrum curves have a fixed number of points per layer`() {
        val m = analyse(noise(11, 8000), noise(12, 8000))
        val curves = StereoAnalysis.curves(m)!!
        assertEquals(4, curves.series.size)
        curves.series.forEach {
            assertEquals(SpectrumCurves.POINTS, it.peakDb.size)
            assertEquals(SpectrumCurves.POINTS, it.meanDb.size)
        }
    }
}
