package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

data class ModernTabItem(
    val id: Int,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)
