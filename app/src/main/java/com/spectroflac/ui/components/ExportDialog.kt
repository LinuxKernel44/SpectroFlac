package com.spectroflac.ui.components

import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.export.image.ExportBenchmark
import com.spectroflac.export.image.ExportChannel
import com.spectroflac.export.image.ExportContent
import com.spectroflac.export.image.ExportPlanner
import com.spectroflac.export.image.ExportText
import com.spectroflac.export.image.ImageLayout
import com.spectroflac.export.image.PlotSize
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatBytes
import com.spectroflac.util.formatEta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object ExportDialogTags {
    const val DIALOG = "export-dialog"
    const val WIDTH = "export-width"
    const val HEIGHT = "export-height"
    const val SUMMARY = "export-summary"
    const val SIZE_LINE = "export-size-line"
    const val WARNING = "export-warning"
    const val SAVE = "export-save"
    const val SHARE = "export-share"
    const val CANCEL = "export-cancel"
    fun preset(label: String) = "export-preset-$label"
    fun content(label: String) = "export-content-$label"
    fun channel(label: String) = "export-channel-$label"
}

/** A choice made in the dialog. */
data class ExportChoice(val plot: PlotSize, val channel: ExportChannel, val content: ExportContent)

/**
 * Asks how the spectrogram image should be made. The size can be a preset, any width and height,
 * or the finest the file and the free storage allow; the dialog says what that means (detail per
 * pixel, file size, time) before anything is rendered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(
    report: AnalysisReport,
    onDismiss: () -> Unit,
    onSave: (ExportChoice) -> Unit,
    onShare: (ExportChoice) -> Unit,
    freeBytes: Long? = null,
) {
    val context = LocalContext.current
    val technical = report.technical ?: return
    val free = remember { freeBytes ?: runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE) }
    val channels = remember(technical.channels) { ExportChannel.forChannelCount(technical.channels) }
    val hasCutoff = report.spectral?.hasBrickWall == true

    var width by remember { mutableStateOf("3840") }
    var height by remember { mutableStateOf("2160") }
    var channel by remember { mutableStateOf(channels.first()) }
    var axes by remember { mutableStateOf(true) }
    var header by remember { mutableStateOf(true) }
    var trackInfo by remember { mutableStateOf(true) }
    var cutoff by remember { mutableStateOf(true) }
    var legend by remember { mutableStateOf(true) }

    val content = ExportContent(axes = axes, header = header, cutoff = cutoff && hasCutoff, legend = legend, trackInfo = trackInfo)
    val plot = PlotSize(width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0)
    val problem = ExportPlanner.validate(plot)
    val safePlot = if (problem == null) plot else PlotSize(ExportPlanner.MIN_SIDE, ExportPlanner.MIN_SIDE)
    val layout = ImageLayout(safePlot, content)
    val maximum = remember(technical.totalSamples, free, content) {
        ExportPlanner.maximum(technical.totalSamples, free) { ImageLayout(it, content).totalPixels }
    }

    val fftMillis by produceState(1.0, ExportPlanner.fftSize(safePlot.height)) {
        value = withContext(Dispatchers.Default) { ExportBenchmark.fftMillis(ExportPlanner.fftSize(safePlot.height)) }
    }
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val estimate = ExportPlanner.estimate(technical.totalSamples, technical.sampleRate, safePlot, layout.totalPixels, cores, fftMillis)
    val needed = estimate.tempBytes + estimate.pngBytes
    val notEnoughSpace = needed > free * 0.92
    val canExport = problem == null && !notEnoughSpace

    fun choose(p: PlotSize) { width = p.width.toString(); height = p.height.toString() }

    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(Modifier.fillMaxWidth().testTag(ExportDialogTags.DIALOG)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
                Text("Export spectrogram image", style = MaterialTheme.typography.titleMedium, color = SpectroColors.TextPrimary)
                Text(report.fileName, style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary, maxLines = 1)
                Spacer(Modifier.height(14.dp))

                Label("Size of the spectrogram")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportPlanner.presets.forEach { (name, size) ->
                        ToggleChip(name, plot == size, { choose(size) }, modifier = Modifier.testTag(ExportDialogTags.preset(name)))
                    }
                    ToggleChip(
                        "Maximum", plot == maximum, { choose(maximum) },
                        accent = SpectroColors.BackdropMagenta, modifier = Modifier.testTag(ExportDialogTags.preset("Maximum")),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberField("Width (px)", width, { width = it }, Modifier.weight(1f).testTag(ExportDialogTags.WIDTH))
                    NumberField("Height (px)", height, { height = it }, Modifier.weight(1f).testTag(ExportDialogTags.HEIGHT))
                }
                Spacer(Modifier.height(8.dp))
                if (problem != null) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = SpectroColors.Fake, modifier = Modifier.testTag(ExportDialogTags.WARNING))
                } else {
                    Text(
                        summaryLine(estimate.plot, estimate.fftSize, estimate.hzPerRow, estimate.msPerColumn),
                        style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextSecondary,
                        modifier = Modifier.testTag(ExportDialogTags.SUMMARY),
                    )
                    Text(
                        "Final image ${layout.totalWidth} × ${layout.totalHeight} px  ·  up to about ${formatBytes(estimate.pngBytes)} PNG  ·  " +
                            "${formatEta(estimate.seconds.toLong() * 1000).removePrefix("about ")} to render",
                        style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary,
                        modifier = Modifier.testTag(ExportDialogTags.SIZE_LINE),
                    )
                    if (notEnoughSpace) {
                        Text(
                            "Not enough free space: this needs about ${formatBytes(needed)} and ${formatBytes(free)} is free.",
                            style = MaterialTheme.typography.bodySmall, color = SpectroColors.Fake, modifier = Modifier.testTag(ExportDialogTags.WARNING),
                        )
                    } else if (layout.totalPixels > 100_000_000) {
                        Text(
                            "Very large image (${String.format(Locale.US, "%.0f", layout.totalPixels / 1e6)} megapixels). It can take " +
                                "a long time, and most viewers cannot open a file this big.",
                            style = MaterialTheme.typography.bodySmall, color = SpectroColors.Suspicious, modifier = Modifier.testTag(ExportDialogTags.WARNING),
                        )
                    } else if (layout.totalPixels > 25_000_000) {
                        Text(
                            "Large image: gallery apps may not open it, but image editors and browsers can.",
                            style = MaterialTheme.typography.bodySmall, color = SpectroColors.Suspicious, modifier = Modifier.testTag(ExportDialogTags.WARNING),
                        )
                    }
                }

                if (channels.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    Label("Channel")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        channels.forEach { c ->
                            ToggleChip(
                                c.label, channel == c, { channel = c }, accent = layerColor(c.label),
                                modifier = Modifier.testTag(ExportDialogTags.channel(c.label)),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Label("In the image")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip("Axes", axes, { axes = !axes }, modifier = Modifier.testTag(ExportDialogTags.content("Axes")))
                    ToggleChip("Header", header, { header = !header }, modifier = Modifier.testTag(ExportDialogTags.content("Header")))
                    ToggleChip("Title, artist, cover", trackInfo, { trackInfo = !trackInfo }, modifier = Modifier.testTag(ExportDialogTags.content("Track")))
                    ToggleChip(
                        if (hasCutoff) "Cutoff line" else "Cutoff line (none found)", cutoff && hasCutoff, { if (hasCutoff) cutoff = !cutoff },
                        accent = SpectroColors.Fake, modifier = Modifier.testTag(ExportDialogTags.content("Cutoff")),
                    )
                    ToggleChip("Colour legend", legend, { legend = !legend }, modifier = Modifier.testTag(ExportDialogTags.content("Legend")))
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(
                        "Save as…", { onSave(ExportChoice(plot, channel, content)) },
                        prominent = true, enabled = canExport,
                        modifier = Modifier.weight(1f).testTag(ExportDialogTags.SAVE),
                    )
                    GlassButton(
                        "Share", { onShare(ExportChoice(plot, channel, content)) },
                        accent = SpectroColors.BackdropMagenta, enabled = canExport,
                        modifier = Modifier.weight(1f).testTag(ExportDialogTags.SHARE),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassButton("Cancel", onDismiss, accent = SpectroColors.TextTertiary, modifier = Modifier.fillMaxWidth().testTag(ExportDialogTags.CANCEL))
            }
        }
    }
}

/** "8.3 MP  ·  FFT 8192  ·  2.7 Hz per row  ·  62 ms per column". */
fun summaryLine(plot: PlotSize, fftSize: Int, hzPerRow: Double, msPerColumn: Double): String =
    String.format(
        Locale.US, "%.1f MP  ·  FFT %d  ·  %s per row  ·  %s per column",
        plot.megapixels, fftSize, ExportText.hz(hzPerRow), ExportText.ms(msPerColumn),
    )


@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SpectroColors.TextPrimary, unfocusedTextColor = SpectroColors.TextPrimary,
            focusedBorderColor = SpectroColors.BackdropCyan, unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = SpectroColors.BackdropCyan, unfocusedLabelColor = SpectroColors.TextTertiary,
            cursorColor = SpectroColors.BackdropCyan,
        ),
        modifier = modifier,
    )
}
