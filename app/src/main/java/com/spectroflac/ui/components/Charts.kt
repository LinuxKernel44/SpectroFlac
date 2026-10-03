package com.spectroflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectroflac.analysis.SpectrumCurves
import com.spectroflac.ui.theme.SpectroColors
import java.util.Locale

/** Colour of a spectrum layer, shared by the chart and its legend chips. */
fun layerColor(name: String): Color = when (name) {
    "Mid", "Mono", "Mix" -> SpectroColors.BackdropCyan
    "Left" -> SpectroColors.BackdropMagenta
    "Right" -> SpectroColors.Suspicious
    "Side" -> SpectroColors.Genuine
    else -> SpectroColors.BackdropViolet
}

/** A small pill that can be switched on and off. */
@Composable
fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = SpectroColors.BackdropCyan,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (selected) accent else accent.copy(alpha = 0.35f)))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SpectroColors.TextPrimary else SpectroColors.TextSecondary,
        )
    }
}

private val axisStyle = TextStyle(fontSize = 9.sp, color = SpectroColors.TextTertiary)

/**
 * Peak-hold (line + soft fill) and average (thin line) spectrum of the selected channels, with the
 * measured cutoff marked. Layers can be overlaid, which makes a side channel that stops before the
 * mid channel easy to see.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumChart(curves: SpectrumCurves, cutoffHz: Double?, modifier: Modifier = Modifier) {
    val names = curves.series.map { it.name }
    var visible by remember(curves) { mutableStateOf(setOf(names.first())) }
    var showMean by remember(curves) { mutableStateOf(true) }
    val measurer = rememberTextMeasurer()
    val nyquist = curves.sampleRate / 2.0

    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            names.forEach { name ->
                ToggleChip(
                    label = name,
                    selected = name in visible,
                    accent = layerColor(name),
                    onClick = {
                        // At least one series always stays visible.
                        visible = if (name in visible) (visible - name).ifEmpty { visible } else visible + name
                    },
                )
            }
            ToggleChip("Average", showMean, { showMean = !showMean }, accent = SpectroColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            val left = 34.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            val top = 4.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val w = right - left
            val h = bottom - top
            val dbTop = 0f
            val dbBottom = -120f

            fun x(i: Int, count: Int) = left + w * i / (count - 1).coerceAtLeast(1)
            fun y(db: Float) = top + h * ((dbTop - db.coerceIn(dbBottom, dbTop)) / (dbTop - dbBottom))

            // Grid and dB labels.
            for (db in -120..0 step 20) {
                val yy = y(db.toFloat())
                drawLine(Color.White.copy(alpha = 0.07f), Offset(left, yy), Offset(right, yy), 1f)
                label(measurer, db.toString(), Offset(2.dp.toPx(), yy - 6.dp.toPx()))
            }
            // Frequency labels.
            val stepKhz = niceStep(nyquist / 1000.0, 6)
            var k = 0.0
            while (k <= nyquist / 1000.0 + 1e-6) {
                val xx = left + w * (k * 1000.0 / nyquist).toFloat()
                drawLine(Color.White.copy(alpha = 0.05f), Offset(xx, top), Offset(xx, bottom), 1f)
                label(measurer, formatAxisKhz(k), Offset(xx - 8.dp.toPx(), bottom + 2.dp.toPx()))
                k += stepKhz
            }

            curves.series.filter { it.name in visible }.forEach { series ->
                val color = layerColor(series.name)
                val count = series.peakDb.size
                val line = Path()
                val fill = Path()
                for (i in 0 until count) {
                    val xx = x(i, count)
                    val yy = y(series.peakDb[i])
                    if (i == 0) {
                        line.moveTo(xx, yy)
                        fill.moveTo(xx, bottom)
                        fill.lineTo(xx, yy)
                    } else {
                        line.lineTo(xx, yy)
                        fill.lineTo(xx, yy)
                    }
                }
                fill.lineTo(x(count - 1, count), bottom)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.10f))
                drawPath(line, color, style = Stroke(width = 1.6.dp.toPx()))
                if (showMean) {
                    val mean = Path()
                    for (i in 0 until count) {
                        val xx = x(i, count)
                        val yy = y(series.meanDb[i])
                        if (i == 0) mean.moveTo(xx, yy) else mean.lineTo(xx, yy)
                    }
                    drawPath(mean, color.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))
                }
            }

            cutoffHz?.takeIf { it in 1.0..nyquist }?.let { hz ->
                val xx = left + w * (hz / nyquist).toFloat()
                drawLine(
                    SpectroColors.Fake, Offset(xx, top), Offset(xx, bottom), 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                label(
                    measurer, formatAxisKhz(hz / 1000.0) + " kHz cut",
                    Offset((xx - 62.dp.toPx()).coerceAtLeast(left), top + 2.dp.toPx()),
                    SpectroColors.Fake,
                )
            }
        }
    }
}

/**
 * Left/right correlation per time slice, drawn as bars from the zero line: green above (in phase),
 * red below (out of phase, which would cancel in mono).
 */
@Composable
fun CorrelationChart(timeline: FloatArray, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(90.dp),
    ) {
        val left = 24.dp.toPx()
        val w = size.width - left
        val top = 2.dp.toPx()
        val h = size.height - 4.dp.toPx()
        val mid = top + h / 2
        drawLine(Color.White.copy(alpha = 0.18f), Offset(left, mid), Offset(size.width, mid), 1f)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(left, top), Offset(size.width, top), 1f)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(left, top + h), Offset(size.width, top + h), 1f)
        label(measurer, "+1", Offset(2.dp.toPx(), top - 2.dp.toPx()))
        label(measurer, "0", Offset(8.dp.toPx(), mid - 6.dp.toPx()))
        label(measurer, "−1", Offset(2.dp.toPx(), top + h - 10.dp.toPx()))
        if (timeline.isEmpty()) return@Canvas
        val barW = (w / timeline.size).coerceAtLeast(1f)
        timeline.forEachIndexed { i, c ->
            if (c.isNaN()) return@forEachIndexed
            val xx = left + w * i / timeline.size
            val yy = mid - c * (h / 2)
            drawLine(
                color = if (c >= 0f) SpectroColors.Genuine.copy(alpha = 0.85f) else SpectroColors.Fake.copy(alpha = 0.9f),
                start = Offset(xx + barW / 2, mid),
                end = Offset(xx + barW / 2, yy),
                strokeWidth = barW.coerceAtLeast(1.2f),
            )
        }
    }
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color = SpectroColors.TextTertiary,
) {
    drawText(measurer, text, at, style = axisStyle.copy(color = color))
}

internal fun formatAxisKhz(khz: Double): String =
    String.format(Locale.US, "%.1f", khz).removeSuffix(".0")

/** Picks a 1-2-5 step that gives roughly [target] ticks across [span]. */
internal fun niceStep(span: Double, target: Int): Double {
    val raw = span / target
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val fraction = raw / magnitude
    val nice = when {
        fraction < 1.5 -> 1.0
        fraction < 3.5 -> 2.0
        fraction < 7.5 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}
