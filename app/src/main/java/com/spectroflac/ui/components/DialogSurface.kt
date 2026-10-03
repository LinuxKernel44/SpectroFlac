package com.spectroflac.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors

/**
 * The panel of a dialog. A dialog is its own window, so the glass has nothing to refract and the
 * screen behind would show through the text: an almost opaque dark layer goes underneath, and the
 * glass sheen is painted on top of it.
 */
@Composable
fun DialogSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.clip(shape).background(SpectroColors.BackdropDeep.copy(alpha = 0.97f))) {
        GlassPanel(Modifier, cornerRadius = 28.dp, refraction = 20.dp, tint = 0.10f, glow = 0.06f, content = content)
    }
}
