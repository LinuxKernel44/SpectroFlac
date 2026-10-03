package com.spectroflac.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.SpectrogramFull
import com.spectroflac.ui.components.SpectrogramLut
import com.spectroflac.ui.components.ToggleChip
import com.spectroflac.ui.components.formatAxisKhz
import com.spectroflac.ui.components.layerColor
import com.spectroflac.ui.components.niceStep
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatHz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The visible window onto the spectrogram, in unit coordinates: x is time (0 = start of the track,
 * 1 = end), y is frequency (0 = DC, 1 = Nyquist, growing upwards).
 */
@Stable
private class SpectrogramView {
    var x0 by mutableFloatStateOf(0f)
    var x1 by mutableFloatStateOf(1f)
    var y0 by mutableFloatStateOf(0f)
    var y1 by mutableFloatStateOf(1f)

    val zoomed: Boolean get() = (x1 - x0) < 0.999f || (y1 - y0) < 0.999f

    fun reset() {
        x0 = 0f; x1 = 1f; y0 = 0f; y1 = 1f
    }

    /** Zooms by [zx] / [zy] around the unit point ([fx], [fy]), keeping that point under the finger. */
    fun zoomAt(fx: Float, fy: Float, zx: Float, zy: Float) {
        val sx = ((x1 - x0) / zx).coerceIn(MIN_SPAN_X, 1f)
        val sy = ((y1 - y0) / zy).coerceIn(MIN_SPAN_Y, 1f)
        val nx0 = fx - (fx - x0) * (sx / (x1 - x0))
        val ny0 = fy - (fy - y0) * (sy / (y1 - y0))
        x0 = nx0.coerceIn(0f, 1f - sx); x1 = x0 + sx
        y0 = ny0.coerceIn(0f, 1f - sy); y1 = y0 + sy
    }

    /** Moves the window by a delta in unit coordinates. */
    fun panBy(dx: Float, dy: Float) {
        val sx = x1 - x0
        val sy = y1 - y0
        x0 = (x0 + dx).coerceIn(0f, 1f - sx); x1 = x0 + sx
        y0 = (y0 + dy).coerceIn(0f, 1f - sy); y1 = y0 + sy
    }

    /** Spoken (and tested) description of the visible window. */
    fun describe(full: SpectrogramFull): String {
        val duration = full.columns * full.strideSamples.toDouble() / full.sampleRate
        return String.format(
            Locale.US, "time %.1f to %.1f s, frequency %.0f to %.0f Hz",
            x0 * duration, x1 * duration, y0 * full.nyquistHz, y1 * full.nyquistHz,
        )
    }

    companion object {
        const val MIN_SPAN_X = 0.012f
        const val MIN_SPAN_Y = 0.012f
    }
}

/** Test tag of the interactive canvas. */
const val SPECTROGRAM_CANVAS_TAG = "spectrogram-canvas"

private val TickStyle = TextStyle(fontSize = 10.sp, color = SpectroColors.TextTertiary)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrogramScreen(report: AnalysisReport, onBack: () -> Unit, onExport: () -> Unit = {}) {
    val full = report.spectrogramFull
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (full == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "This analysis has no spectrogram data. Re-analyse the file to explore it.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpectroColors.TextSecondary,
            )
            return
        }

        val view = remember(full) { SpectrogramView() }
        var layer by remember(full) { mutableStateOf(0) }
        var cursorMode by remember(full) { mutableStateOf(false) }
        var showCutoff by remember(full) { mutableStateOf(report.spectral?.hasBrickWall == true) }
        var cursorX by remember(full) { mutableStateOf<Float?>(null) }
        var cursorY by remember(full) { mutableStateOf<Float?>(null) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Spectrogram",
                    style = MaterialTheme.typography.titleMedium,
                    color = SpectroColors.TextPrimary,
                )
                Text(
                    report.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectroColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (report.technical != null) {
                GlassIconButton(Icons.Filled.SaveAlt, "Export image", onExport)
                Spacer(Modifier.width(8.dp))
            }
            GlassIconButton(Icons.Filled.Refresh, "Reset zoom") {
                view.reset()
                cursorX = null
                cursorY = null
            }
        }
        Spacer(Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (full.layers.size > 1) {
                full.layers.forEachIndexed { index, l ->
                    ToggleChip(l.name, layer == index, { layer = index }, accent = layerColor(l.name))
                }
            }
            ToggleChip("Cursor", cursorMode, { cursorMode = !cursorMode }, accent = SpectroColors.BackdropCyan)
            if (report.spectral != null) {
                ToggleChip("Cutoff", showCutoff, { showCutoff = !showCutoff }, accent = SpectroColors.Fake)
            }
        }
        Spacer(Modifier.height(10.dp))

        val image by produceState<ImageBitmap?>(null, full, layer) {
            value = null
            value = withContext(Dispatchers.Default) { renderLayer(full, layer) }
        }

        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val axisLeft = with(density) { 44.dp.toPx() }
        val axisBottom = with(density) { 22.dp.toPx() }
        val cutoffHz = report.spectral?.cutoffHz
        val gestureHint = if (cursorMode) "Drag to move the cursor · pinch to zoom" else "Drag to pan · pinch to zoom · tap to place the cursor"

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(SPECTROGRAM_CANVAS_TAG)
                .semantics {
                    contentDescription = "Spectrogram of ${report.fileName}"
                    stateDescription = view.describe(full)
                }
                .pointerInput(full, cursorMode) {
                    val slop = viewConfiguration.touchSlop
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val plotW = size.width - axisLeft
                        val plotH = size.height - axisBottom
                        fun unitX(px: Float) = view.x0 + ((px - axisLeft) / plotW) * (view.x1 - view.x0)
                        fun unitY(py: Float) = view.y0 + ((plotH - py) / plotH) * (view.y1 - view.y0)

                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var moved = false
                        var multi = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                multi = true
                                moved = true
                                val a = pressed[0]
                                val b = pressed[1]
                                val prevDx = abs(a.previousPosition.x - b.previousPosition.x)
                                val prevDy = abs(a.previousPosition.y - b.previousPosition.y)
                                val dx = abs(a.position.x - b.position.x)
                                val dy = abs(a.position.y - b.position.y)
                                val prevDist = sqrt(prevDx * prevDx + prevDy * prevDy)
                                val dist = sqrt(dx * dx + dy * dy)
                                // Fingers spread along one axis zoom that axis only; fingers close
                                // together (or diagonal) zoom both, like a normal pinch.
                                val axisMin = 64.dp.toPx()
                                var zx = 1f
                                var zy = 1f
                                if (prevDist > 1f) {
                                    val uniform = dist / prevDist
                                    val xSpread = prevDx >= axisMin
                                    val ySpread = prevDy >= axisMin
                                    zx = when {
                                        xSpread -> dx / prevDx
                                        ySpread -> 1f
                                        else -> uniform
                                    }
                                    zy = when {
                                        ySpread -> dy / prevDy
                                        xSpread -> 1f
                                        else -> uniform
                                    }
                                }
                                val prevCentre = (a.previousPosition + b.previousPosition) / 2f
                                val centre = (a.position + b.position) / 2f
                                view.zoomAt(unitX(prevCentre.x), unitY(prevCentre.y), zx, zy)
                                // After zooming, drag the content with the centroid.
                                val pan = centre - prevCentre
                                view.panBy(
                                    -pan.x / plotW * (view.x1 - view.x0),
                                    pan.y / plotH * (view.y1 - view.y0),
                                )
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !multi) {
                                val c = pressed[0]
                                if (!moved && (c.position - startPos).getDistance() > slop) moved = true
                                if (moved) {
                                    if (cursorMode) {
                                        cursorX = unitX(c.position.x).coerceIn(0f, 1f)
                                        cursorY = unitY(c.position.y).coerceIn(0f, 1f)
                                    } else {
                                        val d = c.positionChange()
                                        view.panBy(
                                            -d.x / plotW * (view.x1 - view.x0),
                                            d.y / plotH * (view.y1 - view.y0),
                                        )
                                    }
                                    c.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!moved) {
                            val now = down.uptimeMillis
                            if (now - lastTapAt < 300 && (startPos - lastTapPos).getDistance() < 48.dp.toPx()) {
                                // Double tap: zoom in on the spot, or back out if already zoomed.
                                if (view.zoomed) view.reset()
                                else view.zoomAt(unitX(startPos.x), unitY(startPos.y), 4f, 4f)
                                lastTapAt = 0L
                            } else {
                                lastTapAt = now
                                lastTapPos = startPos
                                if (startPos.x >= axisLeft && startPos.y <= plotH) {
                                    cursorX = unitX(startPos.x).coerceIn(0f, 1f)
                                    cursorY = unitY(startPos.y).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                },
        ) {
            val plotW = size.width - axisLeft
            val plotH = size.height - axisBottom
            val x0 = view.x0
            val x1 = view.x1
            val y0 = view.y0
            val y1 = view.y1
            fun px(ux: Float) = axisLeft + (ux - x0) / (x1 - x0) * plotW
            fun py(uy: Float) = plotH - (uy - y0) / (y1 - y0) * plotH

            clipRect(axisLeft, 0f, size.width, plotH) {
                drawRect(Color(0xFF00040A), Offset(axisLeft, 0f), Size(plotW, plotH))
                val bitmap = image
                if (bitmap != null) {
                    val cols = full.columns.toFloat()
                    val bands = full.bands.toFloat()
                    val sx = plotW / ((x1 - x0) * cols)
                    val sy = plotH / ((y1 - y0) * bands)
                    translate(axisLeft - x0 * cols * sx, (y1 - 1f) / (y1 - y0) * plotH) {
                        scale(sx, sy, pivot = Offset.Zero) {
                            drawImage(
                                bitmap,
                                filterQuality = if (sx > 2f || sy > 2f) FilterQuality.None else FilterQuality.Medium,
                            )
                        }
                    }
                }
            }

            // Frequency axis (kHz) and gridlines.
            val nyquistKhz = full.nyquistHz / 1000.0
            val visibleKhz = (y1 - y0) * nyquistKhz
            val fStep = niceStep(visibleKhz, 7)
            var f = floor(y0 * nyquistKhz / fStep) * fStep
            while (f <= y1 * nyquistKhz + 1e-9) {
                val uy = (f / nyquistKhz).toFloat()
                if (uy in y0..y1) {
                    val yy = py(uy)
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(axisLeft, yy), Offset(size.width, yy), 1f)
                    drawText(
                        measurer, formatAxisKhz(f),
                        Offset(4.dp.toPx(), (yy - 7.dp.toPx()).coerceIn(0f, plotH - 12.dp.toPx())),
                        style = TickStyle,
                    )
                }
                f += fStep
            }
            drawText(measurer, "kHz", Offset(4.dp.toPx(), plotH + 5.dp.toPx()), style = TickStyle)

            // Time axis.
            val duration = full.columns * full.strideSamples.toDouble() / full.sampleRate
            val tStep = niceTimeStep((x1 - x0) * duration)
            var t = floor(x0 * duration / tStep) * tStep
            while (t <= x1 * duration + 1e-9) {
                val ux = (t / duration).toFloat()
                if (ux in x0..x1) {
                    val xx = px(ux)
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(xx, 0f), Offset(xx, plotH), 1f)
                    drawText(
                        measurer, formatClock(t, tStep),
                        Offset((xx - 12.dp.toPx()).coerceIn(axisLeft, size.width - 34.dp.toPx()), plotH + 5.dp.toPx()),
                        style = TickStyle,
                    )
                }
                t += tStep
            }

            clipRect(axisLeft, 0f, size.width, plotH) {
                if (showCutoff && cutoffHz != null) {
                    val uy = (cutoffHz / full.nyquistHz).toFloat()
                    if (uy in y0..y1) {
                        val yy = py(uy)
                        drawLine(
                            SpectroColors.Fake, Offset(axisLeft, yy), Offset(size.width, yy), 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                        )
                        drawText(
                            measurer, "measured cut ${formatHz(cutoffHz)}",
                            Offset(axisLeft + 6.dp.toPx(), yy - 14.dp.toPx()),
                            style = TickStyle.copy(color = SpectroColors.Fake),
                        )
                    }
                }
                val cx = cursorX
                val cy = cursorY
                if (cx != null && cy != null) {
                    val xx = px(cx)
                    val yy = py(cy)
                    val line = Color.White.copy(alpha = 0.85f)
                    drawLine(line, Offset(xx, 0f), Offset(xx, plotH), 1.2.dp.toPx())
                    drawLine(line, Offset(axisLeft, yy), Offset(size.width, yy), 1.2.dp.toPx())
                    drawCircle(SpectroColors.BackdropCyan, 6.dp.toPx(), Offset(xx, yy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        CursorReadout(full, layer, cursorX, cursorY, gestureHint)
    }
}

@Composable
private fun CursorReadout(full: SpectrogramFull, layer: Int, cx: Float?, cy: Float?, hint: String) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        refraction = 10.dp,
        tint = 0.08f,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (cx == null || cy == null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary)
            } else {
                val column = (cx * full.columns).toInt().coerceIn(0, full.columns - 1)
                val band = (cy * full.bands).toInt().coerceIn(0, full.bands - 1)
                val hz = cy * full.nyquistHz
                val seconds = min(cx * full.columns * full.strideSamples.toDouble() / full.sampleRate, full.durationSeconds)
                val db = full.levelDb(layer, column, band)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReadoutValue("Frequency", formatHz(hz))
                    ReadoutValue("Time", formatClock(seconds, 0.1))
                    ReadoutValue("Level", if (db <= -119.5) "< −120 dB" else String.format(Locale.US, "%.0f dBFS", db))
                }
                Spacer(Modifier.height(6.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun ReadoutValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SpectroColors.TextTertiary)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = SpectroColors.TextPrimary,
        )
    }
}

/** One pixel per cell, high frequencies on the top row. */
private fun renderLayer(full: SpectrogramFull, layer: Int): ImageBitmap {
    val columns = full.columns
    val bands = full.bands
    val data = full.layers[layer].data
    val pixels = IntArray(columns * bands)
    for (x in 0 until columns) {
        val base = x * bands
        for (band in 0 until bands) {
            pixels[(bands - 1 - band) * columns + x] = SpectrogramLut[data[base + band].toInt() and 0xFF]
        }
    }
    val bitmap = android.graphics.Bitmap.createBitmap(columns, bands, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, columns, 0, 0, columns, bands)
    return bitmap.asImageBitmap()
}

private val timeSteps = doubleArrayOf(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0)

private fun niceTimeStep(visibleSeconds: Double): Double {
    val target = visibleSeconds / 6.0
    return timeSteps.firstOrNull { it >= target } ?: timeSteps.last()
}

/** m:ss, with tenths when the tick spacing is below a second. */
private fun formatClock(seconds: Double, step: Double): String {
    val total = max(0.0, seconds)
    val minutes = (total / 60).toInt()
    val rest = total - minutes * 60
    return if (step < 1.0) String.format(Locale.US, "%d:%04.1f", minutes, rest)
    else String.format(Locale.US, "%d:%02d", minutes, rest.toInt())
}
