package com.spectroflac.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spectroflac.export.image.ExportState
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatDuration
import kotlinx.coroutines.delay
import java.util.Locale

object ExportProgressTags {
    const val OVERLAY = "export-progress"
    const val CANCEL = "export-progress-cancel"
}

/** Shown while a spectrogram is being rendered; the render goes on in the background if the app is left. */
@Composable
fun ExportProgressOverlay(state: ExportState.Running, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(SpectroColors.Background.copy(alpha = 0.72f))
            .clickable(enabled = false) {}
            .testTag(ExportProgressTags.OVERLAY),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.fillMaxWidth(0.88f), cornerRadius = 28.dp, refraction = 20.dp, tint = 0.14f, glow = 0.08f) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Exporting spectrogram", style = MaterialTheme.typography.titleMedium, color = SpectroColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.fileName}\n${state.width} × ${state.height}",
                    style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextSecondary,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                GlassProgress(state.fraction)
                Spacer(Modifier.height(10.dp))
                Text(
                    String.format(Locale.US, "%s — %d %%  ·  %s", state.phase.label, (state.fraction * 100).toInt(), formatDuration(((now - state.startedAtMillis) / 1000.0).coerceAtLeast(0.0))),
                    style = MaterialTheme.typography.labelSmall, color = SpectroColors.TextTertiary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "You can leave the app: the export keeps going and a notification shows its progress.",
                    style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                GlassButton("Cancel", onCancel, modifier = Modifier.fillMaxWidth().testTag(ExportProgressTags.CANCEL))
            }
        }
    }
}
