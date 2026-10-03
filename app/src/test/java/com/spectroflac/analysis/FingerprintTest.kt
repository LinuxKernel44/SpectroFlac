package com.spectroflac.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the encoder-fingerprint table to the cut frequencies measured on real encoder output
 * (full-band stereo noise round-tripped through libmp3lame, FFmpeg AAC, libvorbis and libopus).
 */
class FingerprintTest {

    private fun guess(hz: Double) = Judge.lossySourceGuess(hz).orEmpty()

    @Test
    fun `measured MP3 cuts map to their bitrate`() {
        assertTrue(guess(16_764.0).contains("MP3 128"))   // CBR 128
        assertTrue(guess(17_442.0).contains("MP3 160"))   // CBR 160
        assertTrue(guess(18_831.0).contains("MP3 192"))   // CBR 192
        assertTrue(guess(19_520.0).contains("MP3 224"))   // CBR 224
        assertTrue(guess(20_230.0).contains("MP3 320"))   // CBR 320
        assertTrue(guess(15_385.0).contains("MP3 96"))    // CBR 96
        assertTrue(guess(11_251.0).contains("MP3 64"))    // CBR 64
    }

    @Test
    fun `measured vorbis opus and aac cuts list that encoder`() {
        assertTrue(guess(19_003.0).contains("Vorbis q4"))
        assertTrue(guess(19_184.0).contains("Vorbis q4"))  // same encode at 48 kHz
        assertTrue(guess(19_434.0).contains("Vorbis q4"))  // Vorbis q4 of real music
        assertTrue(guess(20_370.0).contains("Opus"))
        assertTrue(guess(20_380.0).contains("Opus"))
        assertTrue(guess(17_356.0).contains("AAC 128"))
        assertTrue(guess(19_423.0).contains("AAC 160"))
        assertTrue(guess(12_446.0).contains("AAC 64"))
    }

    @Test
    fun `a cut at the very top names nothing`() {
        assertNull(Judge.lossySourceGuess(22_039.0))
        assertNotNull(Judge.lossySourceGuess(21_400.0))
        assertEquals("", guess(22_039.0))
    }
}
