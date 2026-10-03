package com.spectroflac.export.image

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the exported image contains besides the spectrogram. */
data class ExportContent(
    val axes: Boolean = true,
    val header: Boolean = true,
    val cutoff: Boolean = true,
    val legend: Boolean = true,
    /** Track title, artist and a small cover in the header. */
    val trackInfo: Boolean = true,
)

/**
 * Where everything sits in the exported image. Sizes grow with the spectrogram, so a text label
 * stays readable whether the image is 1080p or hundreds of megapixels.
 */
class ImageLayout(val plot: PlotSize, val content: ExportContent) {
    /** 1 at 1280x720 and below; grows with the square root of the pixel count. */
    val scale: Float = max(1.0, sqrt(plot.pixels / (1280.0 * 720.0))).toFloat()

    private fun px(base: Float) = (base * scale).roundToInt()

    val headerHeight: Int = if (content.header || content.trackInfo) px(124f) else 0
    val topGap: Int = px(28f)
    val left: Int = if (content.axes) px(96f) else px(16f)
    val right: Int = if (content.legend) px(132f) else px(16f)
    val bottom: Int = if (content.axes) px(58f) else px(16f)

    val plotLeft: Int get() = left
    val plotTop: Int get() = headerHeight + topGap
    val totalWidth: Int get() = left + plot.width + right
    val totalHeight: Int get() = plotTop + plot.height + bottom
    val totalPixels: Long get() = totalWidth.toLong() * totalHeight

    /** Font sizes, in pixels. */
    val labelSize: Float get() = 16f * scale
    val titleSize: Float get() = 26f * scale
    val subtitleSize: Float get() = 17f * scale
    val smallSize: Float get() = 13f * scale
}
