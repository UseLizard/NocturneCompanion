package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class OrganicBackgroundLayer(
    val brush: Brush,
    val centerX: Float,
    val centerY: Float,
    val baseAlpha: Float,
    val color: Color
)

@Composable
fun AnimatedFlowingBackground(
    modifier: Modifier = Modifier, 
    colors: List<Color>,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "organic_background_flow")

    // Multiple slow, organic animation cycles
    val flowOffset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 120000, easing = LinearEasing), // 2 minutes
            repeatMode = RepeatMode.Reverse
        ),
        label = "organic_flow_1"
    )

    val flowOffset2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 150000, easing = LinearEasing), // 2.5 minutes
            repeatMode = RepeatMode.Reverse
        ),
        label = "organic_flow_2"
    )

    val flowOffset3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200000, easing = LinearEasing), // 3.3 minutes
            repeatMode = RepeatMode.Reverse
        ),
        label = "organic_flow_3"
    )

    val flowOffset4 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 180000, easing = LinearEasing), // 3 minutes
            repeatMode = RepeatMode.Reverse
        ),
        label = "organic_flow_4"
    )

    // Create organic layers from the colors
    val organicLayers = remember(colors) {
        createOrganicBackgroundLayers(colors)
    }

    Box(modifier = modifier) {
        // Render each organic layer with flowing animation
        organicLayers.forEachIndexed { index, layer ->
            val currentOffset = when (index % 4) {
                0 -> flowOffset1
                1 -> flowOffset2
                2 -> flowOffset3
                else -> flowOffset4
            }
            
            // Create animated centers for organic movement
            val animatedCenterX = layer.centerX + sin(currentOffset * 2f) * 150f
            val animatedCenterY = layer.centerY + cos(currentOffset * 1.5f) * 100f
            
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = createAnimatedOrganicBrush(
                            layer = layer,
                            animatedCenterX = animatedCenterX,
                            animatedCenterY = animatedCenterY,
                            flowOffset = currentOffset
                        )
                    )
            )
        }
        
        content()
    }
}

private fun createOrganicBackgroundLayers(colors: List<Color>): List<OrganicBackgroundLayer> {
    val layers = mutableListOf<OrganicBackgroundLayer>()
    val random = Random(42) // Consistent seed for reproducible patterns
    
    colors.forEachIndexed { colorIndex, color ->
        // Create 3-4 layers per color for rich organic blending
        repeat(3 + random.nextInt(2)) { layerIndex ->
            val centerX = when ((colorIndex + layerIndex) % 6) {
                0 -> 100f + random.nextFloat() * 200f
                1 -> 400f + random.nextFloat() * 300f
                2 -> 700f + random.nextFloat() * 200f
                3 -> 200f + random.nextFloat() * 150f
                4 -> 600f + random.nextFloat() * 250f
                else -> 50f + random.nextFloat() * 800f
            }
            
            val centerY = when ((colorIndex + layerIndex) % 5) {
                0 -> 50f + random.nextFloat() * 150f
                1 -> 250f + random.nextFloat() * 200f
                2 -> 500f + random.nextFloat() * 150f
                3 -> 750f + random.nextFloat() * 200f
                else -> 100f + random.nextFloat() * 600f
            }
            
            val baseAlpha = 0.04f + random.nextFloat() * 0.08f // More visible but still elegant
            
            val brush = createStaticOrganicBrush(color, centerX, centerY, baseAlpha, random)
            
            layers.add(
                OrganicBackgroundLayer(
                    brush = brush,
                    centerX = centerX,
                    centerY = centerY,
                    baseAlpha = baseAlpha,
                    color = color
                )
            )
        }
    }
    
    return layers
}

private fun createStaticOrganicBrush(
    color: Color,
    centerX: Float,
    centerY: Float,
    baseAlpha: Float,
    random: Random
): Brush {
    val radius = 200f + random.nextFloat() * 400f
    
    return when (random.nextInt(6)) {
        0 -> {
            // Soft radial gradient with multiple stops
            Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = baseAlpha * 1.5f),
                    color.copy(alpha = baseAlpha * 1.0f),
                    color.copy(alpha = baseAlpha * 0.6f),
                    color.copy(alpha = baseAlpha * 0.3f),
                    color.copy(alpha = baseAlpha * 0.1f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = radius
            )
        }
        1 -> {
            // Gentle linear gradient
            Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = baseAlpha * 0.3f),
                    color.copy(alpha = baseAlpha * 0.8f),
                    color.copy(alpha = baseAlpha * 0.5f),
                    color.copy(alpha = baseAlpha * 0.2f),
                    Color.Transparent
                ),
                start = Offset(centerX - radius * 0.7f, centerY - radius * 0.5f),
                end = Offset(centerX + radius * 0.7f, centerY + radius * 0.5f)
            )
        }
        2 -> {
            // Eccentric radial for organic feel
            Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = baseAlpha * 1.2f),
                    color.copy(alpha = baseAlpha * 0.7f),
                    color.copy(alpha = baseAlpha * 0.3f),
                    Color.Transparent
                ),
                center = Offset(
                    centerX + random.nextFloat() * 80f - 40f,
                    centerY + random.nextFloat() * 60f - 30f
                ),
                radius = radius * (0.8f + random.nextFloat() * 0.5f)
            )
        }
        3 -> {
            // Subtle sweep gradient
            Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = baseAlpha * 0.4f),
                    color.copy(alpha = baseAlpha * 0.8f),
                    color.copy(alpha = baseAlpha * 0.3f),
                    Color.Transparent,
                    Color.Transparent
                ),
                center = Offset(centerX, centerY)
            )
        }
        4 -> {
            // Elongated elliptical gradient
            Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = baseAlpha * 0.6f),
                    color.copy(alpha = baseAlpha * 1.0f),
                    color.copy(alpha = baseAlpha * 0.4f),
                    Color.Transparent
                ),
                start = Offset(centerX, centerY - radius),
                end = Offset(centerX, centerY + radius)
            )
        }
        else -> {
            // Very soft radial with minimal alpha
            Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = baseAlpha * 0.8f),
                    color.copy(alpha = baseAlpha * 0.4f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = radius * 1.5f
            )
        }
    }
}

private fun createAnimatedOrganicBrush(
    layer: OrganicBackgroundLayer,
    animatedCenterX: Float,
    animatedCenterY: Float,
    flowOffset: Float
): Brush {
    val radius = 200f + sin(flowOffset * 3f) * 50f + 250f
    val alpha = layer.baseAlpha * (1.5f + sin(flowOffset * 2f) * 0.5f)
    
    // Use the actual color from the layer
    return Brush.radialGradient(
        colors = listOf(
            layer.color.copy(alpha = alpha * 2.0f),
            layer.color.copy(alpha = alpha * 1.5f),
            layer.color.copy(alpha = alpha * 1.0f),
            layer.color.copy(alpha = alpha * 0.6f),
            layer.color.copy(alpha = alpha * 0.2f),
            Color.Transparent
        ),
        center = Offset(animatedCenterX, animatedCenterY),
        radius = radius
    )
}
