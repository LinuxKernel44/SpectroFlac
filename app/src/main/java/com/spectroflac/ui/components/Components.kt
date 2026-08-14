package com.spectroflac.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectroflac.analysis.Finding
import com.spectroflac.analysis.Severity
import com.spectroflac.analysis.SpectrogramPreview
import com.spectroflac.analysis.Verdict
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors

fun Verdict.color(): Color = when (this) {
    Verdict.AUTHENTIC -> SpectroColors.Genuine
    Verdict.SUSPICIOUS -> SpectroColors.Suspicious
    Verdict.FAKE -> SpectroColors.Fake
    Verdict.CORRUPT -> SpectroColors.Damaged
    Verdict.NOT_FLAC -> SpectroColors.Fake
    Verdict.ERROR -> SpectroColors.Neutral
}

fun Severity.color(): Color = when (this) {
    Severity.CRITICAL -> SpectroColors.Fake
    Severity.WARNING -> SpectroColors.Suspicious
    Severity.INFO -> SpectroColors.Neutral
    Severity.GOOD -> SpectroColors.Genuine
}

fun Severity.icon(): ImageVector = when (this) {
    Severity.CRITICAL -> Icons.Filled.Error
    Severity.WARNING -> Icons.Filled.Warning
    Severity.INFO -> Icons.Filled.Info
    Severity.GOOD -> Icons.Filled.CheckCircle
}

@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = SpectroColors.BackdropCyan,
    prominent: Boolean = false,
    enabled: Boolean = true,
) {
    GlassPanel(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        cornerRadius = 22.dp,
        refraction = 14.dp,
        tint = if (prominent) 0.16f else 0.09f,
        glow = if (prominent) 0.10f else 0.03f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accent else SpectroColors.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) SpectroColors.TextPrimary else SpectroColors.TextTertiary,
            )
        }
    }
}

@Composable
fun VerdictBadge(verdict: Verdict, confidence: Int, modifier: Modifier = Modifier) {
    val color = verdict.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = verdict.short,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
        if (confidence > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$confidence%",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.75f),
            )
        }
    }
}

/** The line the whole app exists for: "24 BIT  44.1KHZ  1616 KBPS FLAC". */
@Composable
fun HeadlineSpec(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        letterSpacing = 1.2.sp,
        color = SpectroColors.TextPrimary,
    )
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    GlassPanel(modifier = modifier.fillMaxWidth(), cornerRadius = 26.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = SpectroColors.BackdropCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SpectroColors.TextTertiary,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = SpectroColors.TextPrimary) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SpectroColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
fun FindingRow(finding: Finding, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp)) {
        Icon(
            imageVector = finding.severity.icon(),
            contentDescription = finding.severity.name,
            tint = finding.severity.color(),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = finding.title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 15.sp,
                color = SpectroColors.TextPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = SpectroColors.TextSecondary,
            )
        }
    }
}

@Composable
fun GlassProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50)),
        color = SpectroColors.BackdropCyan,
        trackColor = Color.White.copy(alpha = 0.12f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * The compact spectrogram kept from the analysis pass: time across, frequency up.
 * It is a preview of the full interactive view planned for a later version.
 */
@Composable
fun SpectrogramStrip(preview: SpectrogramPreview, modifier: Modifier = Modifier) {
    val bitmap = remember(preview) { preview.toImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = "Spectrogram of the analysed track",
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .graphicsLayer { scaleY = -1f },
        contentScale = ContentScale.FillBounds,
    )
}

private fun SpectrogramPreview.toImageBitmap(): ImageBitmap {
    val pixels = IntArray(columns * bands)
    for (x in 0 until columns) {
        for (y in 0 until bands) {
            val level = data[x * bands + y].toInt() and 0xFF
            pixels[y * columns + x] = spectrogramColor(level)
        }
    }
    val bitmap = android.graphics.Bitmap.createBitmap(columns, bands, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, columns, 0, 0, columns, bands)
    return bitmap.asImageBitmap()
}

/** Black → indigo → violet → cyan → white, so a brick-wall cut is unmistakable. */
private fun spectrogramColor(level: Int): Int {
    val t = level / 255f
    val stops = intArrayOf(0x00040A, 0x1B1464, 0x6D5DF6, 0x22D3EE, 0xE8FBFF)
    val scaled = t * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    val f = scaled - index
    val a = stops[index]
    val b = stops[index + 1]
    val r = lerpChannel(a shr 16 and 0xFF, b shr 16 and 0xFF, f)
    val g = lerpChannel(a shr 8 and 0xFF, b shr 8 and 0xFF, f)
    val bl = lerpChannel(a and 0xFF, b and 0xFF, f)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun lerpChannel(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)
