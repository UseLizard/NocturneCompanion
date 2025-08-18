package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A custom glass-effect card component used throughout the app for consistent styling.
 * This card provides a modern glass morphism effect with customizable properties.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    // Glass effect properties
    glassType: GlassType = GlassType.Primary,
    isActive: Boolean = true,
    // Animation properties
    enableScaleAnimation: Boolean = true,
    scaleValue: Float = 1.02f,
    animationDuration: Int = 200,
    // Shape properties
    cornerRadius: Dp = 16.dp,
    // Padding
    contentPadding: Dp = 20.dp,
    // Content
    content: @Composable ColumnScope.() -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (enableScaleAnimation && isActive) scaleValue else 1f,
        animationSpec = tween(durationMillis = animationDuration),
        label = "glass_card_scale"
    )

    Card(
        modifier = modifier
            .then(if (enableScaleAnimation) Modifier.scale(scale) else Modifier),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = when (glassType) {
                GlassType.Primary -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                GlassType.Surface -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                GlassType.Secondary -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                GlassType.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                GlassType.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
                GlassType.Success -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                GlassType.Minimal -> MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
                is GlassType.Custom -> glassType.color.copy(alpha = glassType.alpha)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * Different glass effect types with predefined styling
 */
sealed class GlassType {
    /** Primary glass effect - used for main content cards */
    object Primary : GlassType()
    
    /** Surface glass effect - used for secondary content */
    object Surface : GlassType()
    
    /** Secondary glass effect - used for supporting content */
    object Secondary : GlassType()
    
    /** Tertiary glass effect - used for accent content */
    object Tertiary : GlassType()
    
    /** Error glass effect - used for error states */
    object Error : GlassType()
    
    /** Success glass effect - used for success states */
    object Success : GlassType()
    
    /** Minimal glass effect - very subtle transparency */
    object Minimal : GlassType()
    
    /** Custom glass effect with specified color and alpha */
    data class Custom(val color: Color, val alpha: Float = 0.1f) : GlassType()
}

/**
 * Convenience composables for common glass card types
 */

@Composable
fun PrimaryGlassCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    enableScaleAnimation: Boolean = true,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        glassType = GlassType.Primary,
        isActive = isActive,
        enableScaleAnimation = enableScaleAnimation,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun SurfaceGlassCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    enableScaleAnimation: Boolean = true,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        glassType = GlassType.Surface,
        isActive = isActive,
        enableScaleAnimation = enableScaleAnimation,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun MinimalGlassCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    enableScaleAnimation: Boolean = false,
    contentPadding: Dp = 16.dp,
    cornerRadius: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        glassType = GlassType.Minimal,
        isActive = isActive,
        enableScaleAnimation = enableScaleAnimation,
        cornerRadius = cornerRadius,
        contentPadding = contentPadding,
        content = content
    )
}