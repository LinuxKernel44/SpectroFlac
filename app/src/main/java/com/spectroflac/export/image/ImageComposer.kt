package com.spectroflac.export.image

import android.graphics.Bitmap
import android.graphics.Canvas

/** Reads the level of any pixel of the spectrogram from a [LevelStore], one strip of bands at a time. */
class PlotLevels(private val store: LevelStore) {
    private val buffer = ByteArray(store.width * store.stripRows)
    private var strip = -1
    var rows = 0
        private set

    /** Makes sure the strip holding [band] is loaded; returns the row of [band] inside it. */
    fun prepare(band: Int): Int {
        val wanted = band / store.stripRows
        if (wanted != strip) {
            store.readStrip(wanted, buffer)
            strip = wanted
            rows = store.rowsInStrip(wanted)
        }
        return band - wanted * store.stripRows
    }

    fun buffer(): ByteArray = buffer
}

/**
 * Produces the final image one row at a time: the overlays are painted on a small bitmap covering a
 * strip of rows, then the spectrogram pixels are slotted in from the level store and everything is
 * converted to RGB bytes for the PNG writer.
 */
class ImageComposer(
    private val layout: ImageLayout,
    private val painter: OverlayPainter,
    private val levels: PlotLevels,
    private val lut: IntArray,
) {
    private val width = layout.totalWidth
    private val height = layout.totalHeight
    private val stripRows = (4_000_000 / width).coerceIn(1, 64)
    private val bitmap = Bitmap.createBitmap(width, stripRows, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val pixels = IntArray(width * stripRows)
    private val row = ByteArray(width * 3)

    /** Calls [onRow] for every row of the image, top to bottom, with `width * 3` RGB bytes. */
    fun compose(onRow: (ByteArray) -> Unit, onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val plotL = layout.plotLeft
        val plotW = layout.plot.width
        val plotT = layout.plotTop
        val plotH = layout.plot.height
        val background = ExportColors.BACKGROUND
        var y0 = 0
        while (y0 < height) {
            if (isCancelled()) return
            val rows = minOf(stripRows, height - y0)
            bitmap.eraseColor(0)
            canvas.save()
            canvas.translate(0f, -y0.toFloat())
            painter.draw(canvas, y0, y0 + rows)
            canvas.restore()
            bitmap.getPixels(pixels, 0, width, 0, 0, width, rows)

            for (i in 0 until rows) {
                val y = y0 + i
                val base = i * width
                val plotRow = y - plotT
                if (plotRow in 0 until plotH) {
                    val band = plotH - 1 - plotRow
                    val inStrip = levels.prepare(band)
                    val data = levels.buffer()
                    val stride = levels.rows
                    var index = inStrip
                    for (x in 0 until width) {
                        val color = if (x >= plotL && x < plotL + plotW) {
                            val c = lut[data[index].toInt() and 0xFF]
                            index += stride
                            c
                        } else background
                        put(x, over(pixels[base + x], color))
                    }
                } else {
                    for (x in 0 until width) put(x, over(pixels[base + x], background))
                }
                onRow(row)
            }
            y0 += rows
            onProgress(y0.toFloat() / height)
        }
    }

    private fun put(x: Int, rgb: Int) {
        val o = x * 3
        row[o] = (rgb shr 16).toByte()
        row[o + 1] = (rgb shr 8).toByte()
        row[o + 2] = rgb.toByte()
    }

    /** [overlay] (straight alpha) painted over the opaque [base]. */
    private fun over(overlay: Int, base: Int): Int {
        val a = overlay ushr 24
        if (a == 0) return base
        if (a == 255) return overlay
        val inv = 255 - a
        val r = (((overlay shr 16) and 0xFF) * a + ((base shr 16) and 0xFF) * inv) / 255
        val g = (((overlay shr 8) and 0xFF) * a + ((base shr 8) and 0xFF) * inv) / 255
        val b = ((overlay and 0xFF) * a + (base and 0xFF) * inv) / 255
        return (r shl 16) or (g shl 8) or b
    }
}
