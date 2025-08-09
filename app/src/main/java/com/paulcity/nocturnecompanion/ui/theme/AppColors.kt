package com.paulcity.nocturnecompanion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Status colors - softer, theme-aware
object AppColors {
    // Success/Connected - Green
    val success = Color(0xFF66BB6A)
    val successDark = Color(0xFF4CAF50)
    
    // Info/Advertising - Blue  
    val info = Color(0xFF42A5F5)
    val infoDark = Color(0xFF2196F3)
    
    // Warning/Running - Yellow/Orange
    val warning = Color(0xFFFFCA28)
    val warningDark = Color(0xFFFF9800)
    
    // Error/Stopped - Red
    val error = Color(0xFFEF5350)
    val errorDark = Color(0xFFE53935)
    
    // Neutral colors for light theme
    val neutralLight = Color(0xFF9E9E9E)
    val backgroundLight = Color(0xFFFFFFFF)
    val surfaceLight = Color(0xFFF5F5F5)
    val onSurfaceLight = Color(0xFF212121)
    
    // Neutral colors for dark theme
    val neutralDark = Color(0xFF757575)
    val backgroundDark = Color(0xFF121212)
    val surfaceDark = Color(0xFF1E1E1E)
    val onSurfaceDark = Color(0xFFE0E0E0)
}

// Composable functions to get theme-aware colors
@Composable
fun successColor() = if (MaterialTheme.colorScheme.isLight()) AppColors.success else AppColors.successDark

@Composable
fun infoColor() = if (MaterialTheme.colorScheme.isLight()) AppColors.info else AppColors.infoDark

@Composable
fun warningColor() = if (MaterialTheme.colorScheme.isLight()) AppColors.warning else AppColors.warningDark

@Composable
fun errorColor() = if (MaterialTheme.colorScheme.isLight()) AppColors.error else AppColors.errorDark

@Composable
fun neutralColor() = if (MaterialTheme.colorScheme.isLight()) AppColors.neutralLight else AppColors.neutralDark

// Extension to check if current theme is light
@Composable
fun androidx.compose.material3.ColorScheme.isLight(): Boolean {
    // Check if the background is closer to white than black
    return this.background.luminance() > 0.5
}

// Luminance calculation for Color
fun Color.luminance(): Float {
    val r = red
    val g = green  
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}