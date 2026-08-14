package com.spectroflac.ui.glass

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spectroflac.ui.theme.SpectroColors
import kotlin.math.sin

/** AGSL — and therefore true refraction — needs Android 13. */
val supportsLiquidGlass: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Keeps every RuntimeShader reference in one place that the rest of the file can null-check. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ShaderHolder(source: String) {
    private val shader = RuntimeShader(source)
    val brush = ShaderBrush(shader)

    fun set(name: String, value: Float) = shader.setFloatUniform(name, value)
    fun set(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)
}

/**
 * Compiling AGSL can only fail at runtime, and a driver that rejects the source must not take the
 * whole screen with it — a rejected shader simply drops the app onto the frosted fallback.
 */
@SuppressLint("NewApi")
private fun createHolder(source: String): ShaderHolder? =
    if (!supportsLiquidGlass) null else runCatching { ShaderHolder(source) }.getOrNull()

/** Shared state so every panel refracts the same backdrop at the same instant. */
class BackdropState {
    var sizePx by mutableStateOf(Size.Zero)
    var time by mutableFloatStateOf(0f)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

/** Root container: paints the animated backdrop and drives its clock. */
@SuppressLint("NewApi")
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { BackdropState() }
    val holder = remember { createHolder(GlassShaders.BACKDROP) }

    if (animated) {
        LaunchedEffect(Unit) {
            var previous = 0L
            while (true) {
                withFrameNanos { now ->
                    // ~24 fps is plenty for a slowly drifting gradient and cuts the GPU cost.
                    if (now - previous > 41_000_000L) {
                        previous = now
                        state.time = (now / 1_000_000_000.0 % 10_000.0).toFloat()
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalBackdrop provides state) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { state.sizePx = Size(it.width.toFloat(), it.height.toFloat()) }
                .drawBehind {
                    if (holder != null) {
                        holder.set("uResolution", size.width, size.height)
                        holder.set("uTime", state.time)
                        drawRect(holder.brush)
                    } else {
                        drawFallbackBackdrop(state.time)
                    }
                },
            content = content,
        )
    }
}

/**
 * A pane of liquid glass. On Android 13+ it refracts the backdrop through a rounded-rectangle
 * lens with chromatic dispersion; below that it falls back to a frosted translucent panel.
 */
@SuppressLint("NewApi")
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    refraction: Dp = 18.dp,
    dispersion: Float = 0.30f,
    tint: Float = 0.10f,
    glow: Float = 0.04f,
    clipContent: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val density = LocalDensity.current
    val holder = remember { createHolder(GlassShaders.GLASS) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    val radiusPx = with(density) { cornerRadius.toPx() }
    val refractionPx = with(density) { refraction.toPx() }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                if (holder != null && backdrop.sizePx.width > 0f) {
                    holder.set("uResolution", backdrop.sizePx.width, backdrop.sizePx.height)
                    holder.set("uTime", backdrop.time)
                    holder.set("uOrigin", origin.x, origin.y)
                    holder.set("uSize", size.width, size.height)
                    holder.set("uRadius", radiusPx)
                    holder.set("uRefraction", refractionPx)
                    holder.set("uDispersion", dispersion)
                    holder.set("uTint", tint)
                    holder.set("uGlow", glow)
                    drawRect(holder.brush)
                } else {
                    drawFallbackGlass(radiusPx, tint)
                }
            }
            .then(if (clipContent) Modifier.clip(shape) else Modifier),
        content = content,
    )
}

private fun DrawScope.drawFallbackBackdrop(time: Float) {
    drawRect(SpectroColors.Background)
    val blobs = listOf(
        Triple(SpectroColors.BackdropViolet, Offset(size.width * 0.14f, size.height * 0.10f), 0.85f),
        Triple(SpectroColors.BackdropCyan, Offset(size.width * 0.97f, size.height * 0.40f), 0.70f),
        Triple(SpectroColors.BackdropMagenta, Offset(size.width * 0.30f, size.height * 0.96f), 0.90f),
        Triple(SpectroColors.BackdropDeep, Offset(size.width * 0.88f, size.height * 0.76f), 1.0f),
    )
    blobs.forEachIndexed { index, (color, base, scale) ->
        val drift = sin((time * 0.25f) + index) * size.minDimension * 0.04f
        val center = Offset(base.x + drift, base.y - drift * 0.5f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.34f), Color.Transparent),
                center = center,
                radius = size.minDimension * scale,
            ),
        )
    }
}

private fun DrawScope.drawFallbackGlass(radiusPx: Float, tint: Float) {
    val corner = CornerRadius(radiusPx, radiusPx)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.06f + tint),
                Color.White.copy(alpha = 0.02f + tint * 0.5f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.2f),
    )
}
