package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamesTest {
    private val plot = PlotSize(3840, 2160)

    @Test
    fun `the name carries the title and the size of the spectrogram`() {
        assertEquals("Lost Boys-spectrogram-3840x2160.png", ExportNames.imageName("Lost Boys", "track02.flac", plot))
    }

    @Test
    fun `without a title the file name is used, minus its extension`() {
        assertEquals("02 - Lost Boys-spectrogram-3840x2160.png", ExportNames.imageName(null, "02 - Lost Boys.flac", plot))
        assertEquals("a.b.c-spectrogram-3840x2160.png", ExportNames.imageName("  ", "a.b.c.flac", plot))
    }

    @Test
    fun `characters file systems reject are replaced`() {
        val name = ExportNames.safe("AC/DC: Back In Black? <live> \"remaster\" | 1980")
        assertFalse(name.any { it in "\\/:*?\"<>|" })
        assertTrue(name.startsWith("AC_DC_"))
    }

    @Test
    fun `an empty or dotted name falls back to a default`() {
        assertEquals("spectrogram", ExportNames.safe(""))
        assertEquals("spectrogram", ExportNames.safe("..."))
        assertEquals("spectrogram", ExportNames.safe("   "))
    }

    @Test
    fun `a very long title is cut`() {
        assertTrue(ExportNames.safe("x".repeat(500)).length <= 80)
    }

    @Test
    fun `unicode titles survive`() {
        assertEquals("Björk – Jóga-spectrogram-3840x2160.png", ExportNames.imageName("Björk – Jóga", "x.flac", plot))
    }
}
