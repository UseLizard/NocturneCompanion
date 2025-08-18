package com.paulcity.nocturnecompanion.ui.tabs

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.ui.AlbumArtInfo
import com.paulcity.nocturnecompanion.ui.components.InfoChip
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import com.paulcity.nocturnecompanion.ui.components.formatTime
import com.paulcity.nocturnecompanion.ui.theme.*
import com.paulcity.nocturnecompanion.utils.ExtractedColor
import com.paulcity.nocturnecompanion.utils.GradientInfo
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MediaTab(
    lastStateUpdate: StateUpdate?,
    albumArtInfo: AlbumArtInfo?,
    gradientInfo: GradientInfo? = null,
    isGenerating: Boolean = false,
    onGenerateGradient: () -> Unit = {},
    onSendGradient: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Album Art & Gradient Generation
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Album Art",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (albumArtInfo?.bitmap != null) {
                    Image(
                        bitmap = albumArtInfo.bitmap.asImageBitmap(),
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "No album art",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No album art available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Gradient Generation Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onGenerateGradient,
                    modifier = Modifier.weight(1f),
                    enabled = albumArtInfo?.bitmap != null && !isGenerating,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generating...")
                    } else {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = "Generate",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Colors")
                    }
                }
                
                if (gradientInfo != null) {
                    FilledTonalButton(
                        onClick = onSendGradient,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send")
                    }
                }
            }
        }

        // Media State Info
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            lastStateUpdate?.let { state ->
                Text(
                    text = state.track ?: "No track",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = state.album ?: "Unknown album",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Progress bar
                if (state.duration_ms > 0) {
                    val progress = (state.position_ms.toFloat() / state.duration_ms).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(state.position_ms),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            formatTime(state.duration_ms),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Play state and volume
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoChip(
                        if (state.is_playing) "Playing" else "Paused",
                        if (state.is_playing) SuccessGreen else WarningOrange
                    )
                    InfoChip("Volume: ${state.volume_percent}%")
                }
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No media state",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Gradient Showcase
        if (gradientInfo != null) {
            GradientShowcase(gradientInfo = gradientInfo)
            
            // Color Swatches
            SurfaceGlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Extracted Colors",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(gradientInfo.colors) { extractedColor ->
                        ColorSwatch(extractedColor = extractedColor)
                    }
                }
            }
        }
        
        // Album Art Transfer Info
        albumArtInfo?.let { info ->
            if (info.hasArt || info.size > 0) {
                SurfaceGlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Transfer Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (info.size > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Size:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${info.size} bytes",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    info.checksum?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Checksum:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${it.take(8)}...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    info.lastTransferTime?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Last Transfer:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(extractedColor: ExtractedColor) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(extractedColor.color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = extractedColor.hex,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (extractedColor.count > 0) {
            Text(
                text = "${extractedColor.count}px",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun GradientShowcase(gradientInfo: GradientInfo) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Organic Blend Gradient
        OrganicGradientCard(
            title = "Organic Blend",
            colors = gradientInfo.linearGradient
        )
        
        // Layered Transparent Gradient
        LayeredGradientCard(
            title = "Layered Colors", 
            colors = gradientInfo.radialGradient
        )
        
        // Subtle Variations Gradient
        SubtleVariationsCard(
            title = "Subtle Variations",
            colors = gradientInfo.vibrantGradient
        )
        
        // Animated Flowing Gradient
        AnimatedFlowingGradientCard(
            title = "Flowing Colors",
            colors = gradientInfo.sweepGradient
        )
    }
}

@Composable
private fun OrganicGradientCard(
    title: String,
    colors: List<Int>
) {
    SurfaceGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        if (colors.isNotEmpty()) {
            // Create organic gradient with intermediate steps
            val organicColors = remember(colors) { 
                com.paulcity.nocturnecompanion.utils.GradientUtils.createOrganicGradient(colors, 4)
                    .map { Color(it) }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = organicColors,
                            start = Offset(0f, 40f),
                            end = Offset(Float.POSITIVE_INFINITY, 40f)
                        )
                    )
            )
        }
    }
}

@Composable
private fun LayeredGradientCard(
    title: String,
    colors: List<Int>
) {
    SurfaceGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        if (colors.isNotEmpty()) {
            // Create multiple organic layers with unique positioning
            val organicLayers = remember(colors) {
                createOrganicLayers(colors)
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                // Solid background color derived from most vibrant color
                val backgroundSeed = remember(colors) { 
                    colors.map { it }.hashCode() 
                }
                val solidBackgroundColor = remember(colors) {
                    val vibrantColor = colors.maxByOrNull { 
                        com.paulcity.nocturnecompanion.utils.GradientUtils.getVibrance(it) 
                    } ?: colors.first()
                    Color(vibrantColor).copy(alpha = 0.12f)
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(solidBackgroundColor)
                )
                
                // Enhanced organic layers with more randomization
                val enhancedOrganicLayers = remember(colors, backgroundSeed) {
                    createEnhancedOrganicLayers(colors, backgroundSeed)
                }
                
                enhancedOrganicLayers.forEach { layer ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brush = layer.brush)
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtleVariationsCard(
    title: String,
    colors: List<Int>
) {
    SurfaceGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        if (colors.isNotEmpty()) {
            // Create subtle variations of each color
            val subtleColors = remember(colors) {
                colors.flatMap { color ->
                    com.paulcity.nocturnecompanion.utils.GradientUtils.createSubtleVariations(color, 3)
                }.map { Color(it) }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.sweepGradient(
                            colors = subtleColors
                        )
                    )
            )
        }
    }
}

@Composable
private fun AnimatedFlowingGradientCard(
    title: String,
    colors: List<Int>
) {
    // Multiple animation cycles for more organic movement
    val infiniteTransition = rememberInfiniteTransition(label = "flowing_gradient")
    
    val flowOffset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flow_1"
    )
    
    val flowOffset2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flow_2"
    )
    
    SurfaceGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        if (colors.isNotEmpty()) {
            // Create organic colors with variations
            val organicColors = remember(colors) {
                val baseColors = com.paulcity.nocturnecompanion.utils.GradientUtils.createOrganicGradient(colors, 2)
                baseColors.map { Color(it) }
            }
            
            val alphaColors = remember(colors) {
                com.paulcity.nocturnecompanion.utils.GradientUtils.createAlphaVariations(colors)
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                // Rich background gradient using all colors
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = colors.map { Color(it).copy(alpha = 0.2f) },
                                start = Offset(0f, 0f),
                                end = Offset(Float.POSITIVE_INFINITY, 80f)
                            )
                        )
                )
                
                // Multiple fuzzy organic layers
                val organicLayers = remember(colors) {
                    createOrganicLayers(colors)
                }
                
                organicLayers.forEach { layer ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brush = layer.brush)
                    )
                }
                
                // Subtle flowing layer with blurred effect
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = organicColors.map { it.copy(alpha = 0.15f) },
                                start = Offset(
                                    x = flowOffset1 * 200f - 100f,
                                    y = sin(flowOffset1 * 2f) * 20f + 40f
                                ),
                                end = Offset(
                                    x = flowOffset1 * 200f + 200f,
                                    y = cos(flowOffset1 * 2f) * 20f + 40f
                                )
                            )
                        )
                )
                
                // Subtle secondary flowing layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = alphaColors.map { it.copy(alpha = it.alpha * 0.3f) },
                                center = Offset(
                                    x = flowOffset2 * 250f,
                                    y = 40f + sin(flowOffset2 * 1.5f) * 15f
                                ),
                                radius = 150f + flowOffset2 * 80f
                            )
                        )
                )
            }
        }
    }
}

data class OrganicLayer(
    val brush: Brush,
    val alpha: Float,
    val position: Offset
)

private fun createOrganicLayers(colors: List<Int>): List<OrganicLayer> {
    val layers = mutableListOf<OrganicLayer>()
    
    colors.forEachIndexed { index, color ->
        val baseColor = Color(color)
        
        // Create 2-3 unique layers per color for organic blending
        repeat(if (index % 2 == 0) 2 else 3) { layerIndex ->
            // Unique positioning for each layer
            val centerX = when ((index + layerIndex) % 4) {
                0 -> 50f + (Math.random() * 100).toFloat()
                1 -> 200f + (Math.random() * 80).toFloat()
                2 -> 350f + (Math.random() * 60).toFloat()
                else -> 150f + (Math.random() * 150).toFloat()
            }
            
            val centerY = when ((index + layerIndex) % 3) {
                0 -> 20f + (Math.random() * 40).toFloat()
                1 -> 60f + (Math.random() * 30).toFloat()
                else -> 10f + (Math.random() * 50).toFloat()
            }
            
            // Varying radius for fuzzy effect
            val radius = 80f + (Math.random() * 120).toFloat()
            
            // Multiple alpha levels for organic blending
            val alpha = when (layerIndex) {
                0 -> 0.15f + (Math.random() * 0.1).toFloat()
                1 -> 0.08f + (Math.random() * 0.12).toFloat()
                else -> 0.05f + (Math.random() * 0.08).toFloat()
            }
            
            // Create unique gradient patterns
            val brush = when ((index + layerIndex) % 5) {
                0 -> {
                    // Fuzzy radial gradient
                    Brush.radialGradient(
                        colors = listOf(
                            baseColor.copy(alpha = alpha),
                            baseColor.copy(alpha = alpha * 0.7f),
                            baseColor.copy(alpha = alpha * 0.3f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = radius
                    )
                }
                1 -> {
                    // Organic linear sweep
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            baseColor.copy(alpha = alpha * 0.5f),
                            baseColor.copy(alpha = alpha),
                            baseColor.copy(alpha = alpha * 0.6f),
                            Color.Transparent
                        ),
                        start = Offset(centerX - radius, centerY - 20f),
                        end = Offset(centerX + radius, centerY + 20f)
                    )
                }
                else -> {
                    // Soft circular blend
                    Brush.radialGradient(
                        colors = listOf(
                            baseColor.copy(alpha = alpha * 0.6f),
                            baseColor.copy(alpha = alpha * 0.9f),
                            baseColor.copy(alpha = alpha * 0.4f),
                            baseColor.copy(alpha = alpha * 0.1f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = radius * 0.8f
                    )
                }
            }
            
            layers.add(
                OrganicLayer(
                    brush = brush,
                    alpha = alpha,
                    position = Offset(centerX, centerY)
                )
            )
        }
    }
    
    return layers
}

private fun createEnhancedOrganicLayers(colors: List<Int>, seed: Int): List<OrganicLayer> {
    val layers = mutableListOf<OrganicLayer>()
    val random = kotlin.random.Random(seed)
    
    // Create more layers with enhanced randomization
    colors.forEachIndexed { index, color ->
        val baseColor = Color(color)
        
        // Create 3-5 layers per color for richer blending
        val layerCount = 3 + random.nextInt(3)
        repeat(layerCount) { layerIndex ->
            // More randomized positioning across the entire area
            val centerX = random.nextFloat() * 400f
            val centerY = random.nextFloat() * 80f
            
            // More varied radius sizes
            val radius = 40f + random.nextFloat() * 180f
            
            // More varied alpha levels
            val alpha = 0.03f + random.nextFloat() * 0.18f
            
            // Enhanced gradient patterns with more variety
            val brush = when (random.nextInt(8)) {
                0 -> {
                    // Multi-step radial gradient
                    Brush.radialGradient(
                        colors = listOf(
                            baseColor.copy(alpha = alpha * 1.2f),
                            baseColor.copy(alpha = alpha * 0.8f),
                            baseColor.copy(alpha = alpha * 0.4f),
                            baseColor.copy(alpha = alpha * 0.15f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = radius
                    )
                }
                1 -> {
                    // Asymmetric linear gradient
                    val angle = random.nextFloat() * 360f
                    val startOffset = random.nextFloat() * radius
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            baseColor.copy(alpha = alpha * 0.3f),
                            baseColor.copy(alpha = alpha),
                            baseColor.copy(alpha = alpha * 0.7f),
                            baseColor.copy(alpha = alpha * 0.2f),
                            Color.Transparent
                        ),
                        start = Offset(centerX - startOffset, centerY - startOffset * 0.5f),
                        end = Offset(centerX + radius, centerY + radius * 0.3f)
                    )
                }
                else -> {
                    // Soft halo effect
                    Brush.radialGradient(
                        colors = listOf(
                            baseColor.copy(alpha = alpha * 0.7f),
                            baseColor.copy(alpha = alpha * 0.9f),
                            baseColor.copy(alpha = alpha * 0.6f),
                            baseColor.copy(alpha = alpha * 0.3f),
                            baseColor.copy(alpha = alpha * 0.1f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = radius * 1.4f
                    )
                }
            }
            
            layers.add(
                OrganicLayer(
                    brush = brush,
                    alpha = alpha,
                    position = Offset(centerX, centerY)
                )
            )
        }
    }
    
    return layers.shuffled(random)
}