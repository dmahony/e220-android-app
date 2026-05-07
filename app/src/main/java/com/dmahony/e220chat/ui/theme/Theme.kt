package com.dmahony.e220chat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF78D6C7),
    onPrimary = Color(0xFF07251F),
    secondary = Color(0xFF9DB7B2),
    onSecondary = Color(0xFF14211F),
    tertiary = Color(0xFF85A7FF),
    onTertiary = Color(0xFF101A3E),
    background = Color(0xFF0B0F10),
    onBackground = Color(0xFFE8F0EE),
    surface = Color(0xFF111718),
    onSurface = Color(0xFFE8F0EE),
    surfaceVariant = Color(0xFF1A2123),
    onSurfaceVariant = Color(0xFF9FB0AC),
    surfaceContainerLow = Color(0xFF141A1C),
    surfaceContainer = Color(0xFF171E20),
    surfaceContainerHigh = Color(0xFF1C2426),
    outline = Color(0xFF2B3537),
    outlineVariant = Color(0xFF20292B),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A1111)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF146356),
    onPrimary = Color(0xFFF4FBF9),
    secondary = Color(0xFF516663),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF375DA8),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF111716),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111716),
    surfaceVariant = Color(0xFFE7EEEC),
    onSurfaceVariant = Color(0xFF5A6B67),
    surfaceContainerLow = Color(0xFFF8FBFA),
    surfaceContainer = Color(0xFFF0F4F3),
    surfaceContainerHigh = Color(0xFFE8EFED),
    outline = Color(0xFFD2DCDA),
    outlineVariant = Color(0xFFDCE5E3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 23.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun E220ChatTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
