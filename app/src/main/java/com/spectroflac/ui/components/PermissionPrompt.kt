package com.spectroflac.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.spectroflac.ui.theme.SpectroColors

object PermissionPromptTags {
    const val CONTINUE = "permission-continue"
    const val SKIP = "permission-skip"
}

/**
 * Shown the first time a scan starts, before the two system prompts: says why they are asked, so
 * "Allow" is an informed choice. Only the missing permissions are listed.
 */
@Composable
fun PermissionPrompt(
    notificationsMissing: Boolean,
    batteryMissing: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Dialog(onDismissRequest = onSkip) {
        DialogSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                Text("Let SpectroFlac finish your scans", style = MaterialTheme.typography.titleMedium, color = SpectroColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A big folder can take a while. Two permissions keep it going when you leave the app:",
                    style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                if (notificationsMissing) {
                    Reason(
                        Icons.Filled.Notifications, "Notifications",
                        "Shows the progress of a scan and tells you when it is done.",
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (batteryMissing) {
                    Reason(
                        Icons.Filled.BatteryChargingFull, "No battery optimization",
                        "Stops Android from slowing down or pausing a long scan while the screen is off.",
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "You can change both later in Settings. Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton("Not now", onSkip, modifier = Modifier.weight(1f).testTag(PermissionPromptTags.SKIP))
                    GlassButton("Continue", onContinue, prominent = true, modifier = Modifier.weight(1f).testTag(PermissionPromptTags.CONTINUE))
                }
            }
        }
    }
}

@Composable
private fun Reason(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = SpectroColors.BackdropCyan, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary)
        }
    }
}
