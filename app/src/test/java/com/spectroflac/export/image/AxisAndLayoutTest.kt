package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisTicksTest {
    @Test
    fun `frequency ticks are round kilohertz values up to the ceiling`() {
        val ticks = AxisTicks.frequency(22_050.0, 1_080, 90.0)
        assertEquals("0", ticks.first().label)
        assertEquals(listOf(0.0, 2000.0, 4000.0, 6000.0, 8000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0, 22000.0), ticks.map { it.value })
        assertTrue(ticks.all { it.value <= 22_050.0 })
    }

    @Test
    fun `a taller image gets more frequency labels`() {
        val small = AxisTicks.frequency(22_050.0, 400, 90.0).size
        val big = AxisTicks.frequency(22_050.0, 8_000, 90.0).size
        assertTrue(big > small)
    }

    @Test
    fun `the high-resolution ceiling of a 96 kHz file`() {
        val ticks = AxisTicks.frequency(48_000.0, 2_000, 100.0)
        assertTrue(ticks.last().value <= 48_000.0 && ticks.last().value >= 40_000.0)
    }

    @Test
    fun `time ticks are minutes and seconds`() {
        val ticks = AxisTicks.time(240.0, 1_920, 160.0)
        assertEquals("0:00", ticks.first().label)
        assertEquals(240.0, ticks.last().value, 1e-9)
        assertEquals("4:00", ticks.last().label)
        assertTrue(ticks.size in 6..14)
    }

    @Test
    fun `a short clip gets sub-second ticks with tenths`() {
        val ticks = AxisTicks.time(2.5, 1_920, 160.0)
        assertTrue(ticks[1].label.contains("."))
    }

    @Test
    fun `a long file switches to hours`() {
        assertEquals("1:05:00", AxisTicks.clock(3_900.0, 300.0))
        assertEquals("0:09.5", AxisTicks.clock(9.5, 0.5))
        assertEquals("3:25", AxisTicks.clock(205.0, 5.0))
    }

    @Test
    fun `the legend runs from 0 to minus 120 dBFS`() {
        assertEquals(listOf(0, -20, -40, -60, -80, -100, -120), AxisTicks.legendLevels())
    }
}

class ImageLayoutTest {
    private val all = ExportContent()

    @Test
    fun `a small image keeps readable text`() {
        val layout = ImageLayout(PlotSize(1_280, 720), all)
        assertEquals(1f, layout.scale, 1e-6f)
        assertEquals(16f, layout.labelSize, 1e-6f)
    }

    @Test
    fun `text grows with the picture`() {
        val full = ImageLayout(PlotSize(1_920, 1_080), all).scale
        val eightK = ImageLayout(PlotSize(7_680, 4_320), all).scale
        val huge = ImageLayout(PlotSize(41_000, 16_384), all).scale
        assertTrue(1f < full && full < eightK && eightK < huge)
        assertEquals(6f, eightK, 1e-3f)
    }

    @Test
    fun `the total size is the spectrogram plus its surroundings`() {
        val layout = ImageLayout(PlotSize(2_000, 1_000), all)
        assertEquals(layout.left + 2_000 + layout.right, layout.totalWidth)
        assertEquals(layout.headerHeight + layout.topGap + 1_000 + layout.bottom, layout.totalHeight)
        assertEquals(layout.totalWidth.toLong() * layout.totalHeight, layout.totalPixels)
        assertEquals(layout.left, layout.plotLeft)
        assertEquals(layout.headerHeight + layout.topGap, layout.plotTop)
    }

    @Test
    fun `switching parts off shrinks the margins`() {
        val full = ImageLayout(PlotSize(2_000, 1_000), all)
        val bare = ImageLayout(PlotSize(2_000, 1_000), ExportContent(axes = false, header = false, cutoff = false, legend = false, trackInfo = false))
        assertEquals(0, bare.headerHeight)
        assertTrue(bare.left < full.left && bare.right < full.right && bare.bottom < full.bottom)
        assertTrue(bare.totalPixels < full.totalPixels)
    }

    @Test
    fun `the header exists when either header option is on`() {
        assertTrue(ImageLayout(PlotSize(2_000, 1_000), ExportContent(header = true, trackInfo = false)).headerHeight > 0)
        assertTrue(ImageLayout(PlotSize(2_000, 1_000), ExportContent(header = false, trackInfo = true)).headerHeight > 0)
        assertFalse(ImageLayout(PlotSize(2_000, 1_000), ExportContent(header = false, trackInfo = false)).headerHeight > 0)
    }
}
