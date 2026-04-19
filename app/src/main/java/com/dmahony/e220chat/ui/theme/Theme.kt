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
    primary = Color(0xFF2D9CDB),
    onPrimary = Color(0xFF04131D),
    primaryContainer = Color(0xFF123247),
    onPrimaryContainer = Color(0xFFD5EEFF),
    secondary = Color(0xFF8A9BAA),
    onSecondary = Color(0xFF111920),
    secondaryContainer = Color(0xFF1A2630),
    onSecondaryContainer = Color(0xFFD9E5EF),
    tertiary = Color(0xFF7E8DFF),
    onTertiary = Color(0xFF0E1224),
    tertiaryContainer = Color(0xFF212A4A),
    onTertiaryContainer = Color(0xFFE5E8FF),
    background = Color(0xFF0A131A),
    onBackground = Color(0xFFE4EDF4),
    surface = Color(0xFF101A22),
    onSurface = Color(0xFFE4EDF4),
    surfaceVariant = Color(0xFF18232D),
    onSurfaceVariant = Color(0xFF9AA8B4),
    surfaceContainerLowest = Color(0xFF060B10),
    surfaceContainerLow = Color(0xFF0E1820),
    surfaceContainer = Color(0xFF121D26),
    surfaceContainerHigh = Color(0xFF17222C),
    surfaceContainerHighest = Color(0xFF1C2833),
    outline = Color(0xFF2D3B47),
    outlineVariant = Color(0xFF22313C),
    inverseSurface = Color(0xFFE4EDF4),
    inverseOnSurface = Color(0xFF101A22),
    inversePrimary = Color(0xFF4CB4F2),
    surfaceTint = Color(0xFF2D9CDB),
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF2A0B10),
    errorContainer = Color(0xFF4A111A),
    onErrorContainer = Color(0xFFFFD9DE)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF176EA8),
    onPrimary = Color(0xFFF5FBFF),
    primaryContainer = Color(0xFFD9EFFF),
    onPrimaryContainer = Color(0xFF06263E),
    secondary = Color(0xFF5D6B77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EAF0),
    onSecondaryContainer = Color(0xFF1A2630),
    tertiary = Color(0xFF5D65C9),
    onTertiary = Color(0xFFF8F8FF),
    tertiaryContainer = Color(0xFFE3E5FF),
    onTertiaryContainer = Color(0xFF171B44),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF162029),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF162029),
    surfaceVariant = Color(0xFFE6ECF1),
    onSurfaceVariant = Color(0xFF5E6A75),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F8),
    surfaceContainerHigh = Color(0xFFE9EEF3),
    surfaceContainerHighest = Color(0xFFE1E7ED),
    outline = Color(0xFFCBD5DE),
    outlineVariant = Color(0xFFD9E2E9),
    inverseSurface = Color(0xFF22303A),
    inverseOnSurface = Color(0xFFF1F5F8),
    inversePrimary = Color(0xFF7BC3F7),
    surfaceTint = Color(0xFF176EA8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
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
