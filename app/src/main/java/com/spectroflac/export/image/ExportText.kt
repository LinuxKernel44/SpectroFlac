package com.spectroflac.export.image

import java.util.Locale

/** Wording shared by the export dialog and the text printed on the image, so they never disagree. */
object ExportText {
    /** "10 Hz", "5.1 Hz": one decimal below 10 Hz. */
    fun hz(hz: Double): String =
        if (hz >= 10) String.format(Locale.US, "%.0f Hz", hz) else String.format(Locale.US, "%.1f Hz", hz)

    /** "62 ms", "3.6 ms": one decimal below 10 ms. */
    fun ms(ms: Double): String =
        if (ms >= 10) String.format(Locale.US, "%.0f ms", ms) else String.format(Locale.US, "%.1f ms", ms)
}
