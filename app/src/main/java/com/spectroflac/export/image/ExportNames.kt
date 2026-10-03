package com.spectroflac.export.image

/** File names for exported images. Pure, so the rules are unit-tested. */
object ExportNames {
    private val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** "Artist - Track" style names stay readable; anything a file system dislikes becomes an underscore. */
    fun safe(text: String, maxLength: Int = 80): String {
        val cleaned = text.replace(unsafe, "_").replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.take(maxLength).trim().ifEmpty { "spectrogram" }
    }

    /** "Track title-spectrogram-3840x2160.png"; the size is the spectrogram's, not the whole image's. */
    fun imageName(title: String?, fileName: String, plot: PlotSize): String {
        val base = safe(title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.', fileName))
        return "$base-spectrogram-${plot.width}x${plot.height}.png"
    }
}
