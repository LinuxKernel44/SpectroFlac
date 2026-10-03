package com.spectroflac.export.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import java.util.Locale

/** The colours of the exported image, matching the app. */
object ExportColors {
    const val BACKGROUND = 0xFF05060E.toInt()
    const val TEXT = 0xFFF5F7FF.toInt()
    const val TEXT_SECONDARY = 0xFFB9C0DC.toInt()
    const val TEXT_TERTIARY = 0xFF8188A8.toInt()
    const val CUTOFF = 0xFFFB7185.toInt()
}

/**
 * Draws everything of the image except the spectrogram pixels: header, axes, grid, cutoff mark and
 * colour legend. It draws in image coordinates onto a canvas that shows rows `[y0, y1)`, so the
 * composer can render the image one strip at a time.
 */
class OverlayPainter(
    private val layout: ImageLayout,
    private val subject: ExportSubject,
    private val nyquistHz: Double,
    private val durationSeconds: Double,
    private val infoLine: String,
    private val cover: Bitmap?,
    private val lut: IntArray,
) {
    private val content = layout.content
    private val s = layout.scale
    private val plotL = layout.plotLeft.toFloat()
    private val plotT = layout.plotTop.toFloat()
    private val plotW = layout.plot.width.toFloat()
    private val plotH = layout.plot.height.toFloat()
    private val plotR = plotL + plotW
    private val plotB = plotT + plotH

    private val frequencyTicks = AxisTicks.frequency(nyquistHz, layout.plot.height, 90.0 * s)
    private val timeTicks = AxisTicks.time(durationSeconds, layout.plot.width, 150.0 * s)

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    private val titlePaint = paint(layout.titleSize, ExportColors.TEXT, bold = true)
    private val subtitlePaint = paint(layout.subtitleSize, ExportColors.TEXT_SECONDARY)
    private val smallPaint = paint(layout.smallSize, ExportColors.TEXT_TERTIARY)
    private val smallRight = paint(layout.smallSize, ExportColors.TEXT_TERTIARY, align = Paint.Align.RIGHT)
    private val tickRight = paint(layout.labelSize, ExportColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
    private val tickCenter = paint(layout.labelSize, ExportColors.TEXT_SECONDARY, align = Paint.Align.CENTER)
    private val unitRight = paint(layout.smallSize, ExportColors.TEXT_TERTIARY, align = Paint.Align.RIGHT)
    private val legendLabel = paint(layout.labelSize, ExportColors.TEXT_SECONDARY)
    private val gridPaint = Paint().apply { color = 0x24FFFFFF; strokeWidth = maxOf(1f, s) }
    private val tickPaint = Paint().apply { color = 0x8CFFFFFF.toInt(); strokeWidth = maxOf(1f, 1.5f * s) }
    private val borderPaint = Paint().apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = maxOf(1f, 1.5f * s) }
    private val cutoffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ExportColors.CUTOFF; strokeWidth = maxOf(2f, 2.5f * s); pathEffect = DashPathEffect(floatArrayOf(16f * s, 11f * s), 0f)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC05060E.toInt() }
    private val cutoffText = paint(layout.labelSize, ExportColors.CUTOFF)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, 1.5f * s); color = subject.verdictColor }
    private val badgeText = paint(layout.subtitleSize, subject.verdictColor, bold = true, align = Paint.Align.RIGHT)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val legendBar = Paint().apply {
        val stops = 32
        shader = LinearGradient(
            0f, plotT, 0f, plotB,
            IntArray(stops) { lut[255 - it * 255 / (stops - 1)] },
            FloatArray(stops) { it / (stops - 1f) },
            Shader.TileMode.CLAMP,
        )
    }

    private val pad = 18f * s

    private fun visible(top: Float, bottom: Float, y0: Int, y1: Int) = bottom >= y0 && top <= y1

    fun draw(canvas: Canvas, y0: Int, y1: Int) {
        if (layout.headerHeight > 0 && visible(0f, layout.headerHeight.toFloat(), y0, y1)) drawHeader(canvas)
        if (content.axes) drawAxes(canvas, y0, y1)
        if (content.legend) drawLegend(canvas, y0, y1)
        if (content.cutoff && subject.cutoffHz != null) drawCutoff(canvas, y0, y1)
        if (visible(plotT, plotB, y0, y1)) canvas.drawRect(plotL, plotT, plotR, plotB, borderPaint)
    }

    // ---- header ----------------------------------------------------------------------------

    private fun drawHeader(canvas: Canvas) {
        val h = layout.headerHeight.toFloat()
        val showCover = content.trackInfo && cover != null
        val coverSize = 72f * s
        if (showCover) {
            val top = (h - coverSize) / 2
            canvas.drawBitmap(cover!!, null, RectF(pad, top, pad + coverSize, top + coverSize), bitmapPaint)
        }
        val textX = pad + if (showCover) coverSize + 16f * s else 0f
        val rightReserve = if (content.header) layout.totalWidth * 0.34f else 0f
        val maxWidth = (layout.totalWidth - textX - pad - rightReserve).coerceAtLeast(40f)

        val lines = ArrayList<Triple<String, Paint, Float>>()
        if (content.trackInfo && !subject.title.isNullOrBlank()) {
            lines += Triple(subject.title, titlePaint, 1.25f)
            if (!subject.artist.isNullOrBlank()) lines += Triple(subject.artist, subtitlePaint, 1.3f)
        } else if (content.header) {
            lines += Triple(subject.fileName, titlePaint, 1.25f)
        }
        if (content.header) {
            val detail = listOfNotNull(
                subject.fileName.takeIf { content.trackInfo && !subject.title.isNullOrBlank() }, subject.formatLine,
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) lines += Triple(detail, smallPaint, 1.3f)
        }
        val total = lines.sumOf { (_, paint, spacing) -> (paint.textSize * spacing).toDouble() }.toFloat()
        var y = (h - total) / 2
        for ((text, paint, spacing) in lines) {
            val line = TextUtils.ellipsize(text, android.text.TextPaint(paint), maxWidth, TextUtils.TruncateAt.END).toString()
            y += paint.textSize * spacing
            canvas.drawText(line, textX, y - paint.textSize * (spacing - 1f) - paint.descent() * 0.2f, paint)
        }

        if (content.header) {
            val right = layout.totalWidth - pad
            subject.verdictLabel?.let { label ->
                val text = String.format(Locale.US, "%s  %d %%", label, subject.confidence)
                val width = badgeText.measureText(text)
                val padding = 10f * s
                val boxTop = h / 2 - badgeText.textSize - padding * 0.6f
                val box = RectF(right - width - padding * 2, boxTop, right, boxTop + badgeText.textSize + padding * 1.4f)
                canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, badgeStroke)
                canvas.drawText(text, right - padding, box.bottom - padding * 0.75f, badgeText)
            }
            val info = TextUtils.ellipsize(infoLine, android.text.TextPaint(smallRight), layout.totalWidth * 0.34f, TextUtils.TruncateAt.START).toString()
            canvas.drawText(info, right, h / 2 + smallRight.textSize * 2.2f, smallRight)
        }
    }

    // ---- axes ------------------------------------------------------------------------------

    private fun yOfHz(hz: Double): Float = (plotB - (hz / nyquistHz) * plotH).toFloat()

    private fun drawAxes(canvas: Canvas, y0: Int, y1: Int) {
        val markLength = 8f * s
        for (tick in frequencyTicks) {
            val y = yOfHz(tick.value)
            if (!visible(y - layout.labelSize, y + layout.labelSize, y0, y1)) continue
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            canvas.drawLine(plotL - markLength, y, plotL, y, tickPaint)
            canvas.drawText(tick.label, plotL - markLength - 6f * s, y + layout.labelSize * 0.35f, tickRight)
        }
        if (visible(plotT - layout.smallSize * 3, plotT, y0, y1)) {
            canvas.drawText("kHz", plotL - 6f * s, plotT - 14f * s, unitRight)
        }
        if (visible(plotB, plotB + layout.bottom, y0, y1)) {
            for (tick in timeTicks) {
                val x = plotL + (tick.value / durationSeconds * plotW).toFloat()
                canvas.drawLine(x, plotB, x, plotB + markLength, tickPaint)
                canvas.drawText(tick.label, x, plotB + markLength + layout.labelSize * 1.15f, tickCenter)
            }
        }
        for (tick in timeTicks) {
            val x = plotL + (tick.value / durationSeconds * plotW).toFloat()
            if (visible(plotT, plotB, y0, y1)) canvas.drawLine(x, maxOf(plotT, y0.toFloat()), x, minOf(plotB, y1.toFloat()), gridPaint)
        }
    }

    // ---- legend ----------------------------------------------------------------------------

    private fun drawLegend(canvas: Canvas, y0: Int, y1: Int) {
        val x0 = plotR + 26f * s
        val barW = 26f * s
        if (visible(plotT, plotB, y0, y1)) canvas.drawRect(x0, plotT, x0 + barW, plotB, legendBar)
        for (level in AxisTicks.legendLevels()) {
            val y = plotT + (-level / 120f) * plotH
            if (!visible(y - layout.labelSize, y + layout.labelSize, y0, y1)) continue
            canvas.drawLine(x0 + barW, y, x0 + barW + 6f * s, y, tickPaint)
            val label = if (level == 0) "0" else String.format(Locale.US, "−%d", -level)
            canvas.drawText(label, x0 + barW + 10f * s, y + layout.labelSize * 0.35f, legendLabel)
        }
        if (visible(plotT - layout.smallSize * 3, plotT, y0, y1)) canvas.drawText("dBFS", x0, plotT - 14f * s, smallPaint)
    }

    // ---- cutoff ----------------------------------------------------------------------------

    private fun drawCutoff(canvas: Canvas, y0: Int, y1: Int) {
        val hz = subject.cutoffHz ?: return
        if (hz <= 0 || hz >= nyquistHz) return
        val y = yOfHz(hz)
        if (!visible(y - layout.labelSize * 3, y + layout.labelSize * 3, y0, y1)) return
        canvas.drawLine(plotL, y, plotR, y, cutoffPaint)
        val text = String.format(Locale.US, "measured cut %.2f kHz", hz / 1000.0)
        val padding = 7f * s
        val width = cutoffText.measureText(text)
        val above = y - plotT > layout.labelSize * 2.6f
        val baseline = if (above) y - 12f * s else y + 12f * s + layout.labelSize
        val left = plotL + 16f * s
        val box = RectF(left - padding, baseline - layout.labelSize - padding * 0.4f, left + width + padding, baseline + padding)
        canvas.drawRoundRect(box, padding, padding, pillPaint)
        canvas.drawText(text, left, baseline, cutoffText)
    }
}
