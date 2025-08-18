package com.paulcity.nocturnecompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class BackgroundTheme {
    GRADIENT,
    SOLID_LIGHT,
    SOLID_DARK,
    MINIMAL_LIGHT,
    MINIMAL_DARK
}

// Modern color palette inspired by Material You
val NocturnePrimary = Color(0xFF6366F1)
val NocturnePrimaryVariant = Color(0xFF4F46E5)
val NocturneSecondary = Color(0xFF10B981)
val NocturneTertiary = Color(0xFFF59E0B)

// Dark theme colors
val NocturneDark80 = Color(0xFF8B5FBF)
val NocturneDarkGrey80 = Color(0xFFB8BCC8)
val NocturneDarkAccent80 = Color(0xFF6EE7B7)

// Light theme colors
val NocturneLight40 = Color(0xFF4338CA)
val NocturneLightGrey40 = Color(0xFF6B7280)
val NocturneLightAccent40 = Color(0xFF059669)

// Status colors with better contrast
val SuccessGreen = Color(0xFF10B981)
val WarningOrange = Color(0xFFF59E0B)
val ErrorRed = Color(0xFFEF4444)
val InfoBlue = Color(0xFF3B82F6)
val NeutralGrey = Color(0xFF6B7280)

// Surface colors for cards and containers
val SurfaceVariantLight = Color(0xFFF8FAFC)
val SurfaceVariantDark = Color(0xFF1E293B)
val OutlineLight = Color(0xFFE2E8F0)
val OutlineDark = Color(0xFF334155)

// High contrast text colors for better readability
val HighContrastTextDark = Color(0xFF000000)        // Pure black for light backgrounds
val HighContrastTextLight = Color(0xFFFFFFFF)       // Pure white for dark backgrounds
val MediumContrastTextDark = Color(0xFF1F2937)      // Very dark gray for light backgrounds
val MediumContrastTextLight = Color(0xFFF9FAFB)     // Very light gray for dark backgrounds
val SubtleTextDark = Color(0xFF374151)              // Dark gray for light backgrounds
val SubtleTextLight = Color(0xFFE5E7EB)             // Light gray for dark backgrounds

private val DarkColorScheme = darkColorScheme(
    primary = NocturneDark80,
    onPrimary = HighContrastTextLight,
    primaryContainer = NocturnePrimaryVariant,
    onPrimaryContainer = HighContrastTextLight,
    secondary = NocturneDarkAccent80,
    onSecondary = HighContrastTextDark,
    tertiary = NocturneTertiary,
    onTertiary = HighContrastTextDark,
    background = Color(0xFF0F172A),
    onBackground = HighContrastTextLight,
    surface = Color(0xFF1E293B),
    onSurface = HighContrastTextLight,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = MediumContrastTextLight,
    outline = OutlineDark,
    error = ErrorRed,
    onError = HighContrastTextLight
)

private val LightColorScheme = lightColorScheme(
    primary = NocturneLight40,
    onPrimary = HighContrastTextLight,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = NocturneLight40,
    secondary = NocturneLightAccent40,
    onSecondary = HighContrastTextLight,
    tertiary = NocturneTertiary,
    onTertiary = HighContrastTextLight,
    background = Color(0xFFFCFCFD),
    onBackground = HighContrastTextDark,
    surface = Color.White,
    onSurface = HighContrastTextDark,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = MediumContrastTextDark,
    outline = OutlineLight,
    error = ErrorRed,
    onError = HighContrastTextLight
)

@Composable
fun NocturneCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    backgroundTheme: BackgroundTheme = BackgroundTheme.GRADIENT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (backgroundTheme) {
        BackgroundTheme.SOLID_DARK, BackgroundTheme.MINIMAL_DARK -> DarkColorScheme
        BackgroundTheme.SOLID_LIGHT, BackgroundTheme.MINIMAL_LIGHT -> LightColorScheme
        BackgroundTheme.GRADIENT -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}