package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {
    private val fourMinutes = 44_100L * 240

    @Test
    fun `the FFT follows the number of rows`() {
        assertEquals(1_024, ExportPlanner.fftSize(256))
        assertEquals(4_096, ExportPlanner.fftSize(1_080))
        assertEquals(8_192, ExportPlanner.fftSize(2_160))
        assertEquals(32_768, ExportPlanner.fftSize(16_384))
        assertEquals(65_536, ExportPlanner.fftSize(32_768))
    }

    @Test
    fun `columns that span more audio than a window take several windows`() {
        // 4 minutes over 1920 columns: 5.5k samples each, FFT 4096 (half = 2048): 3 windows.
        assertEquals(3, ExportPlanner.windowsPerColumn(fourMinutes, 1_920, 4_096))
        // Dense columns: one window is enough.
        assertEquals(1, ExportPlanner.windowsPerColumn(fourMinutes, 40_000, 32_768))
        // Never more than 16, however short the FFT.
        assertEquals(16, ExportPlanner.windowsPerColumn(fourMinutes * 100, 1_000, 1_024))
    }

    @Test
    fun `sizes outside the limits are explained`() {
        assertNull(ExportPlanner.validate(PlotSize(1920, 1080)))
        assertNotNull(ExportPlanner.validate(PlotSize(100, 1080)))
        assertNotNull(ExportPlanner.validate(PlotSize(1920, 100)))
        assertNotNull(ExportPlanner.validate(PlotSize(1920, ExportPlanner.MAX_HEIGHT + 1)))
        assertNotNull(ExportPlanner.validate(PlotSize(ExportPlanner.MAX_WIDTH + 1, 1080)))
        assertNull(ExportPlanner.validate(PlotSize(ExportPlanner.MAX_WIDTH, ExportPlanner.MAX_HEIGHT)))
    }

    @Test
    fun `the estimate describes the image`() {
        val plot = PlotSize(3_840, 2_160)
        val e = ExportPlanner.estimate(fourMinutes, 44_100, plot, totalPixels = 9_000_000, cores = 8, fftMillis = 0.5)
        assertEquals(8_192, e.fftSize)
        assertEquals(22_050.0 / 2_160, e.hzPerRow, 1e-9)
        assertEquals(240_000.0 / 3_840, e.msPerColumn, 1e-6)
        assertEquals((9_000_000 * 3 * 0.55).toLong(), e.pngBytes)
        assertEquals(fourMinutes * 4 + plot.pixels, e.tempBytes)
        assertTrue(e.valid && e.problem == null)
        assertTrue("some seconds, not hours: ${e.seconds}", e.seconds in 1.0..120.0)
    }

    @Test
    fun `an invalid size is flagged in the estimate`() {
        val e = ExportPlanner.estimate(fourMinutes, 44_100, PlotSize(50, 50), 10_000, 4, 1.0)
        assertFalse(e.valid)
        assertNotNull(e.problem)
    }

    @Test
    fun `more cores and faster FFTs shorten the estimate`() {
        val plot = PlotSize(7_680, 4_320)
        val slow = ExportPlanner.estimate(fourMinutes, 44_100, plot, 40_000_000, 1, 2.0).seconds
        val fast = ExportPlanner.estimate(fourMinutes, 44_100, plot, 40_000_000, 8, 2.0).seconds
        val faster = ExportPlanner.estimate(fourMinutes, 44_100, plot, 40_000_000, 8, 0.5).seconds
        assertTrue(slow > fast && fast > faster)
    }

    @Test
    fun `maximum goes as fine as the file allows when there is room`() {
        val max = ExportPlanner.maximum(fourMinutes, freeBytes = 50L * 1024 * 1024 * 1024)
        assertEquals(16_384, max.height)
        assertEquals(41_343, max.width) // one column every 256 samples
        assertNull(ExportPlanner.validate(max))
    }

    @Test
    fun `maximum shrinks to fit the free space`() {
        val roomy = ExportPlanner.maximum(fourMinutes, freeBytes = 50L * 1024 * 1024 * 1024)
        val tight = ExportPlanner.maximum(fourMinutes, freeBytes = 1L * 1024 * 1024 * 1024)
        assertTrue(tight.pixels < roomy.pixels)
        val cost = fourMinutes * 4 + tight.pixels + (tight.pixels * 3 * 0.55).toLong()
        assertTrue("cost $cost must fit 40 % of 1 GB", cost <= 0.4 * 1024 * 1024 * 1024)
        assertNull(ExportPlanner.validate(tight))
    }

    @Test
    fun `maximum never goes below a usable size`() {
        val none = ExportPlanner.maximum(fourMinutes, freeBytes = 0)
        assertEquals(1_920, none.width)
        assertEquals(2_048, none.height)
    }

    @Test
    fun `a very short file does not ask for absurdly few columns`() {
        assertEquals(1_920, ExportPlanner.maximum(44_100L * 3, freeBytes = 10L * 1024 * 1024 * 1024).width)
    }

    @Test
    fun `presets are the usual screen sizes`() {
        assertEquals(listOf("Screen", "4K", "8K"), ExportPlanner.presets.map { it.first })
        assertEquals(PlotSize(7_680, 4_320), ExportPlanner.presets.last().second)
    }
}
