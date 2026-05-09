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
import com.dmahony.e220chat.FontScale
import com.dmahony.e220chat.ThemeMode

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF7EC8FF),
    onPrimary = Color(0xFF081423),
    primaryContainer = Color(0xFF123149),
    onPrimaryContainer = Color(0xFFCAE9FF),
    secondary = Color(0xFF93B6CA),
    onSecondary = Color(0xFF10212E),
    secondaryContainer = Color(0xFF162836),
    onSecondaryContainer = Color(0xFFD8E9F7),
    tertiary = Color(0xFF7EC8FF),
    onTertiary = Color(0xFF081423),
    tertiaryContainer = Color(0xFF123149),
    onTertiaryContainer = Color(0xFFCAE9FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8F0EE),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFE8F0EE),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF9FB0AC),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF161616),
    outline = Color(0xFF242424),
    outlineVariant = Color(0xFF1A1A1A),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A1111)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF67B7FF),
    onPrimary = Color(0xFF062033),
    primaryContainer = Color(0xFF103048),
    onPrimaryContainer = Color(0xFFD3ECFF),
    secondary = Color(0xFF91ADBF),
    onSecondary = Color(0xFF13212C),
    secondaryContainer = Color(0xFF1A2732),
    onSecondaryContainer = Color(0xFFD8E6F3),
    tertiary = Color(0xFF67B7FF),
    onTertiary = Color(0xFF062033),
    tertiaryContainer = Color(0xFF103048),
    onTertiaryContainer = Color(0xFFD3ECFF),
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
    primary = Color(0xFF4A92D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE7FF),
    onPrimaryContainer = Color(0xFF0E2B44),
    secondary = Color(0xFF5E7382),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3EA),
    onSecondaryContainer = Color(0xFF18242C),
    tertiary = Color(0xFF67B7FF),
    onTertiary = Color(0xFF0D1B2A),
    tertiaryContainer = Color(0xFFD6EBFF),
    onTertiaryContainer = Color(0xFF0D2941),
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

private fun baseTypography(): Typography = Typography(
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

private fun scaledTypography(multiplier: Float): Typography {
    val base = baseTypography()
    return Typography(
        headlineSmall = base.headlineSmall.copy(
            fontSize = (23 * multiplier).sp,
            lineHeight = (28 * multiplier).sp
        ),
        titleLarge = base.titleLarge.copy(
            fontSize = (18 * multiplier).sp,
            lineHeight = (22 * multiplier).sp
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = (16 * multiplier).sp,
            lineHeight = (20 * multiplier).sp
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = (16 * multiplier).sp,
            lineHeight = (22 * multiplier).sp
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = (15 * multiplier).sp,
            lineHeight = (21 * multiplier).sp
        ),
        bodySmall = base.bodySmall.copy(
            fontSize = (13 * multiplier).sp,
            lineHeight = (18 * multiplier).sp
        ),
        labelLarge = base.labelLarge.copy(
            fontSize = (14 * multiplier).sp,
            lineHeight = (18 * multiplier).sp
        ),
        labelMedium = base.labelMedium.copy(
            fontSize = (12 * multiplier).sp,
            lineHeight = (16 * multiplier).sp
        )
    )
}

@Composable
fun E220ChatTheme(
    darkTheme: Boolean = true,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: FontScale = FontScale.NORMAL,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AMOLED -> true
    }
    val colors: ColorScheme = when (themeMode) {
        ThemeMode.AMOLED -> AmoledColors
        else -> if (useDarkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = scaledTypography(fontScale.multiplier),
        content = content
    )
}
