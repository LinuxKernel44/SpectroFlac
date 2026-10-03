package com.spectroflac.export.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportTextTest {
    @Test
    fun `hertz get a decimal only below ten`() {
        assertEquals("5.1 Hz", ExportText.hz(5.07))
        assertEquals("10 Hz", ExportText.hz(10.2))
        assertEquals("21 Hz", ExportText.hz(20.7))
    }

    @Test
    fun `milliseconds get a decimal only below ten`() {
        assertEquals("1.8 ms", ExportText.ms(1.84))
        assertEquals("62 ms", ExportText.ms(62.4))
        assertEquals("9.9 ms", ExportText.ms(9.94))
    }
}
