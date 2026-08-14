package com.spectroflac.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import com.spectroflac.data.AnalysisRecord
import com.spectroflac.ui.Progress
import com.spectroflac.ui.components.GlassButton
import com.spectroflac.ui.components.GlassProgress
import com.spectroflac.ui.components.color
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatTimestamp

@Composable
fun BatchScreen(
    reports: List<AnalysisReport>,
    progress: Progress?,
    onBack: () -> Unit,
    onOpen: (AnalysisReport) -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    val counts = reports.groupingBy { it.verdict }.eachCount()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Folder scan",
                subtitle = if (progress != null) {
                    "${progress.index} of ${progress.total} · ${progress.fileName}"
                } else {
                    "${reports.size} file${if (reports.size == 1) "" else "s"} analysed"
                },
                onBack = onBack,
            )
        }

        if (progress != null) {
            item {
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = 22.dp, refraction = 14.dp) {
                    Column(Modifier.padding(18.dp)) {
                        GlassProgress(
                            if (progress.total > 1) {
                                (progress.index - 1 + progress.fraction) / progress.total
                            } else progress.fraction,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = progress.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = SpectroColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (counts.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(Verdict.AUTHENTIC, Verdict.FAKE, Verdict.SUSPICIOUS, Verdict.CORRUPT).forEach { verdict ->
                        val count = counts[verdict] ?: 0
                        if (count > 0) {
                            CountTile(verdict, count, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (reports.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        label = "CSV",
                        onClick = onExportCsv,
                        icon = Icons.Filled.Description,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        label = "JSON",
                        onClick = onExportJson,
                        icon = Icons.Filled.DataObject,
                        accent = SpectroColors.BackdropMagenta,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        items(reports, key = { it.uri + it.analysedAtMillis }) { report ->
            ReportRow(
                report = report,
                subtitle = report.headline,
                onClick = { onOpen(report) },
            )
        }

        if (reports.isEmpty() && progress == null) {
            item { EmptyNote("Nothing analysed yet.") }
        }
    }
}

@Composable
fun HistoryScreen(
    records: List<AnalysisRecord>,
    onBack: () -> Unit,
    onOpen: (AnalysisRecord) -> Unit,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "History",
                subtitle = "${records.size} analysis${if (records.size == 1) "" else "es"} kept on this device",
                onBack = onBack,
            )
        }
        if (records.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        label = "Export CSV",
                        onClick = onExportCsv,
                        icon = Icons.Filled.Description,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        label = "Clear",
                        onClick = onClear,
                        icon = Icons.Filled.DeleteSweep,
                        accent = SpectroColors.Fake,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        items(records, key = { it.uri }) { record ->
            HistoryRow(record) { onOpen(record) }
        }
        if (records.isEmpty()) {
            item { EmptyNote("No analysis yet. Anything you check will be listed here.") }
        }
    }
}

@Composable
private fun HistoryRow(record: AnalysisRecord, onClick: () -> Unit) {
    val verdict = runCatching { Verdict.valueOf(record.verdict) }.getOrDefault(Verdict.ERROR)
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        cornerRadius = 22.dp,
        refraction = 14.dp,
        tint = 0.08f,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(verdict.color().copy(alpha = 0.75f)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(record.artist, record.title).takeIf { it.isNotEmpty() }
                        ?.joinToString(" — ") ?: record.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpectroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.headline,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = SpectroColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTimestamp(record.analysedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectroColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = verdict.short,
                style = MaterialTheme.typography.labelSmall,
                color = verdict.color(),
            )
        }
    }
}

@Composable
private fun CountTile(verdict: Verdict, count: Int, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier, cornerRadius = 18.dp, refraction = 12.dp, tint = 0.07f) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = verdict.color(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = verdict.short,
                style = MaterialTheme.typography.labelSmall,
                color = SpectroColors.TextTertiary,
            )
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = SpectroColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SpectroColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    GlassPanel(Modifier.fillMaxWidth(), cornerRadius = 22.dp, refraction = 14.dp, tint = 0.06f) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = SpectroColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
        )
    }
}

/** Modal shown while a single file is being analysed. */
@Composable
fun AnalysisOverlay(progress: Progress, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpectroColors.Background.copy(alpha = 0.72f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.86f),
            cornerRadius = 28.dp,
            refraction = 20.dp,
            tint = 0.14f,
            glow = 0.08f,
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Analysing",
                    style = MaterialTheme.typography.titleMedium,
                    color = SpectroColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progress.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectroColors.TextSecondary,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                GlassProgress(progress.fraction)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${(progress.fraction * 100).toInt()} % — decoding every sample",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpectroColors.TextTertiary,
                )
                Spacer(Modifier.height(18.dp))
                GlassButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
