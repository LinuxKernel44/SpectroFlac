package com.spectroflac.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SpectroFlac only ships a dark theme: the liquid glass needs a deep, saturated backdrop to
 * refract, and a light variant would read as a different app rather than the same one.
 */
object SpectroColors {
    val Background = Color(0xFF05060E)
    val BackdropViolet = Color(0xFF6D5DF6)
    val BackdropCyan = Color(0xFF22D3EE)
    val BackdropMagenta = Color(0xFFC46FF9)
    val BackdropDeep = Color(0xFF0B1030)

    val TextPrimary = Color(0xFFF5F7FF)
    val TextSecondary = Color(0xFFB9C0DC)
    val TextTertiary = Color(0xFF8188A8)

    val Genuine = Color(0xFF34D399)
    val Suspicious = Color(0xFFFBBF24)
    val Fake = Color(0xFFFB7185)
    val Damaged = Color(0xFFF97316)
    val Neutral = Color(0xFF8B93B8)

    val GlassTint = Color(0x14FFFFFF)
    val GlassBorder = Color(0x33FFFFFF)
    val GlassHighlight = Color(0x59FFFFFF)
}

private val SpectroDarkScheme = darkColorScheme(
    primary = SpectroColors.BackdropCyan,
    onPrimary = Color(0xFF04121A),
    secondary = SpectroColors.BackdropViolet,
    onSecondary = Color.White,
    background = SpectroColors.Background,
    onBackground = SpectroColors.TextPrimary,
    surface = SpectroColors.Background,
    onSurface = SpectroColors.TextPrimary,
    error = SpectroColors.Fake,
)

private val SpectroTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun SpectroFlacTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SpectroDarkScheme,
        typography = SpectroTypography,
        content = content,
    )
}
