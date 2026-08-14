package com.spectroflac.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import com.spectroflac.ui.components.FindingRow
import com.spectroflac.ui.components.HeadlineSpec
import com.spectroflac.ui.components.KeyValueRow
import com.spectroflac.ui.components.SectionCard
import com.spectroflac.ui.components.SpectrogramStrip
import com.spectroflac.ui.components.VerdictBadge
import com.spectroflac.ui.components.color
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatBytes
import com.spectroflac.util.formatCount
import com.spectroflac.util.formatDb
import com.spectroflac.util.formatDbfs
import com.spectroflac.util.formatDuration
import com.spectroflac.util.formatHz
import com.spectroflac.util.formatTimestamp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ResultScreen(
    report: AnalysisReport,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onReanalyse: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = report.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SpectroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.Filled.Refresh, "Re-analyse", onReanalyse)
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Filled.Share, "Share report", onShare)
            }
        }

        item { VerdictHero(report) }

        report.spectrogram?.let { preview ->
            item {
                SectionCard("Spectrogram", icon = Icons.Filled.GraphicEq) {
                    SpectrogramStrip(preview)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Time runs left to right, frequency bottom to top, up to " +
                            "${formatHz(preview.sampleRate / 2.0)}. A flat dark band across the top " +
                            "is what a lossy encoder leaves behind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpectroColors.TextTertiary,
                    )
                }
            }
        }

        if (report.findings.isNotEmpty()) {
            item {
                SectionCard("What was found", icon = Icons.Filled.Info) {
                    report.findings.forEach { FindingRow(it) }
                }
            }
        }

        report.technical?.let { t ->
            item {
                SectionCard("Technical", icon = Icons.Filled.Memory) {
                    KeyValueRow("Bit depth", "${t.bitsPerSample} bit")
                    KeyValueRow("Sample rate", "${formatCount(t.sampleRate.toLong())} Hz")
                    KeyValueRow("Channels", "${t.channels} · ${t.channelLayout}")
                    KeyValueRow("Duration", formatDuration(t.durationSeconds))
                    KeyValueRow("File size", formatBytes(t.fileSizeBytes))
                    KeyValueRow("Bitrate", "${t.bitrateKbps} kbps")
                    KeyValueRow("Uncompressed", "${t.uncompressedBitrateKbps} kbps")
                    KeyValueRow(
                        "Compression",
                        String.format(Locale.US, "%.0f %% of raw", t.compressionRatio * 100),
                    )
                    KeyValueRow("Total samples", formatCount(t.totalSamples))
                    KeyValueRow(
                        "Block size",
                        "${t.minBlockSize}–${t.maxBlockSize} ${if (t.fixedBlockSize) "(fixed)" else "(variable)"}",
                    )
                    KeyValueRow("Frame size", "${formatCount(t.minFrameSize.toLong())}–${formatCount(t.maxFrameSize.toLong())} B")
                }
            }
        }

        report.spectral?.let { s ->
            item {
                SectionCard("Spectral analysis", icon = Icons.Filled.Tune) {
                    KeyValueRow(
                        "Content reaches",
                        formatHz(s.cutoffHz),
                        if (s.hasBrickWall) SpectroColors.Fake else SpectroColors.Genuine,
                    )
                    KeyValueRow("Ceiling for this rate", formatHz(s.nyquistHz))
                    KeyValueRow(
                        "Steepest step",
                        formatDb(s.rolloffDropDb) + if (s.hasBrickWall) "  (brick wall)" else "",
                    )
                    KeyValueRow("Level at the top", formatDbfs(s.noiseFloorDb))
                    KeyValueRow("Loudest bin", formatDbfs(s.peakSpectrumDb))
                    s.sourceGuess?.let { KeyValueRow("Profile matches", it, SpectroColors.Suspicious) }
                    KeyValueRow("Windows analysed", "${s.windowsAnalyzed} × ${s.fftSize}")
                    Spacer(Modifier.height(10.dp))
                    s.reasoning.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpectroColors.TextTertiary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        report.integrity?.let { i ->
            item {
                SectionCard("Integrity", icon = Icons.Filled.Shield) {
                    KeyValueRow("Stored MD5", if (i.md5Present) i.md5Expected else "none stored")
                    KeyValueRow("Decoded MD5", i.md5Actual)
                    KeyValueRow(
                        "Match",
                        when {
                            !i.md5Present -> "cannot be checked"
                            !i.md5Verified -> "not verified"
                            i.md5Matches -> "bit-perfect"
                            else -> "MISMATCH"
                        },
                        when {
                            !i.md5Verified -> SpectroColors.TextSecondary
                            i.md5Matches -> SpectroColors.Genuine
                            else -> SpectroColors.Fake
                        },
                    )
                    KeyValueRow("Frames decoded", formatCount(i.framesDecoded))
                    KeyValueRow("Samples decoded", formatCount(i.samplesDecoded))
                    KeyValueRow(
                        "Frame CRC errors",
                        i.crcErrors.toString(),
                        if (i.crcErrors > 0) SpectroColors.Fake else SpectroColors.Genuine,
                    )
                    i.decodeError?.let { KeyValueRow("Decoder", it, SpectroColors.Fake) }
                }
            }
        }

        report.dynamics?.let { d ->
            item {
                SectionCard("Levels and dynamics", icon = Icons.Filled.Bolt) {
                    KeyValueRow("Peak", formatDbfs(d.peakDbfs))
                    KeyValueRow("RMS", formatDbfs(d.rmsDbfs))
                    KeyValueRow("Crest factor", formatDb(d.crestFactorDb))
                    d.dynamicRangeDb?.let { KeyValueRow("Dynamic range", "DR${it.roundToInt()}") }
                    KeyValueRow("Clipped samples", formatCount(d.clippedSamples))
                    KeyValueRow("Clipped runs", formatCount(d.clippedRuns))
                    KeyValueRow(
                        "Real bit depth",
                        "${d.effectiveBitDepth} of ${d.declaredBitDepth} bits",
                        if (d.unusedLowBits >= 4) SpectroColors.Fake else SpectroColors.Genuine,
                    )
                    if (d.dualMono) KeyValueRow("Stereo", "dual mono (channels identical)")
                    if (d.silentRatio > 0.01) {
                        KeyValueRow("Digital silence", String.format(Locale.US, "%.1f %%", d.silentRatio * 100))
                    }
                }
            }
        }

        report.encoder?.let { e ->
            item {
                SectionCard("Encoder", icon = Icons.Filled.Memory) {
                    KeyValueRow("Vendor string", e.vendor ?: "not stated")
                    KeyValueRow("Compression", e.compressionGuess ?: "unknown")
                    KeyValueRow("Metadata blocks", e.blockTypes.joinToString(", "))
                    KeyValueRow("Seek table", if (e.hasSeekTable) "${e.seekPoints} points" else "none")
                    KeyValueRow("Padding", formatBytes(e.paddingBytes.toLong()))
                    KeyValueRow("Metadata size", formatBytes(e.metadataBytes))
                    if (e.applicationIds.isNotEmpty()) KeyValueRow("Application", e.applicationIds.joinToString(", "))
                    if (e.hasCueSheet) KeyValueRow("Cue sheet", "present")
                }
            }
        }

        if (report.tags.isNotEmpty()) {
            item {
                SectionCard("Tags", icon = Icons.Filled.Album) {
                    report.tags.forEach { (key, value) -> KeyValueRow(key, value) }
                }
            }
        }

        item {
            Text(
                text = "Analysed ${formatTimestamp(report.analysedAtMillis)} in " +
                    String.format(Locale.US, "%.1f s", report.analysisDurationMillis / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                color = SpectroColors.TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun VerdictHero(report: AnalysisReport) {
    val cover = remember(report.coverBytes) {
        report.coverBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 30.dp,
        refraction = 24.dp,
        tint = 0.12f,
        glow = 0.06f,
    ) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = "Album cover",
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(16.dp))
            }
            report.title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = SpectroColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            listOfNotNull(report.artist, report.album).takeIf { it.isNotEmpty() }?.let { parts ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectroColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            VerdictBadge(report.verdict, report.confidence)
            Spacer(Modifier.height(18.dp))
            HeadlineSpec(report.headline)
            Spacer(Modifier.height(14.dp))
            Text(
                text = report.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = SpectroColors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (report.verdict == Verdict.FAKE || report.verdict == Verdict.CORRUPT) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(report.verdict.color().copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    GlassPanel(
        modifier = Modifier
            .size(44.dp)
            .clickable { onClick() },
        cornerRadius = 22.dp,
        refraction = 12.dp,
        tint = 0.10f,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = SpectroColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(20.dp),
        )
    }
}

/** Shared by the batch and history lists. */
@Composable
fun ReportRow(
    report: AnalysisReport,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier
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
                    .height(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(report.verdict.color().copy(alpha = 0.7f)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = report.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpectroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = SpectroColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = report.verdict.short,
                style = MaterialTheme.typography.labelSmall,
                color = report.verdict.color(),
            )
        }
    }
}
