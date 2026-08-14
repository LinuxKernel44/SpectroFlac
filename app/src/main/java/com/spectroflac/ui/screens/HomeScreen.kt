package com.spectroflac.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectroflac.ui.components.GlassButton
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.glass.supportsLiquidGlass
import com.spectroflac.ui.theme.SpectroColors

@Composable
fun HomeScreen(
    historyCount: Int,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "SpectroFlac",
            style = MaterialTheme.typography.displaySmall,
            color = SpectroColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Is that FLAC the real thing?",
            style = MaterialTheme.typography.bodyMedium,
            color = SpectroColors.TextSecondary,
        )

        Spacer(Modifier.height(26.dp))

        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 32.dp,
            refraction = 26.dp,
            tint = 0.11f,
            glow = 0.06f,
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SpectrumMark()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "SpectroFlac decodes every sample of a .flac file, looks at the whole " +
                        "spectrum and tells you whether the audio really is lossless — or whether " +
                        "it was made from an MP3, upsampled, padded out to 24-bit, or damaged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpectroColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "24 BIT   44.1KHZ   1616 KBPS FLAC",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpectroColors.BackdropCyan,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        GlassButton(
            label = "Analyse a file",
            onClick = onPickFile,
            icon = Icons.Filled.InsertDriveFile,
            prominent = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        GlassButton(
            label = "Scan a folder",
            onClick = onPickFolder,
            icon = Icons.Filled.Folder,
            accent = SpectroColors.BackdropMagenta,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        GlassButton(
            label = if (historyCount > 0) "History ($historyCount)" else "History",
            onClick = onHistory,
            icon = Icons.Filled.History,
            accent = SpectroColors.BackdropViolet,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoTile(
                modifier = Modifier.weight(1f),
                title = "Spectral test",
                body = "Peak-hold FFT across the whole track finds the brick wall a lossy encoder leaves behind.",
            )
            InfoTile(
                modifier = Modifier.weight(1f),
                title = "Bit-perfect check",
                body = "The stored MD5 and every frame CRC are verified while decoding.",
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = if (supportsLiquidGlass) {
                "Nothing leaves your phone: the whole analysis runs on-device."
            } else {
                "Nothing leaves your phone. Liquid glass refraction needs Android 13 — " +
                    "this device gets the frosted look instead."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SpectroColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun InfoTile(title: String, body: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier, cornerRadius = 22.dp, refraction = 14.dp, tint = 0.07f) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SpectroColors.BackdropCyan,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = SpectroColors.TextSecondary,
            )
        }
    }
}

/** The bar mark from the launcher icon, rebuilt with the theme gradient. */
@Composable
private fun SpectrumMark() {
    val heights = listOf(0.30f, 0.55f, 0.85f, 1f, 0.72f, 0.45f, 0.24f)
    Row(
        modifier = Modifier.height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        heights.forEachIndexed { index, fraction ->
            Box(
                Modifier
                    .width(9.dp)
                    .height(64.dp * fraction)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                SpectroColors.BackdropCyan.copy(alpha = 0.95f - index * 0.06f),
                                SpectroColors.BackdropMagenta.copy(alpha = 0.75f),
                            ),
                        ),
                    ),
            )
        }
    }
}
