package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paulcity.nocturnecompanion.data.StateUpdate

@Composable
fun FullScreenPlayer(
    currentTrack: StateUpdate?,
    albumArtUrl: String? = null,
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onMinimize: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with minimize button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                IconButton(onClick = onMinimize) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize player",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Album Art
            PrimaryGlassCard(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(24.dp)),
                contentPadding = 0.dp
            ) {
                if (albumArtUrl != null) {
                    AsyncImage(
                        model = albumArtUrl,
                        contentDescription = "Album Art",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = "No album art",
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Track Information
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = currentTrack?.track ?: "No track playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = currentTrack?.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = currentTrack?.album ?: "Unknown album",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Progress Bar
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                val progress = if (currentTrack?.duration_ms != null && currentTrack.duration_ms > 0) {
                    (currentTrack.position_ms.toFloat() / currentTrack.duration_ms.toFloat()).coerceIn(0f, 1f)
                } else 0f
                
                Slider(
                    value = progress,
                    onValueChange = { onSeek(it) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatPlayerTime(currentTrack?.position_ms ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatPlayerTime(currentTrack?.duration_ms ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Button
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.width(32.dp))
                
                // Play/Pause Button
                FloatingActionButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(80.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        if (currentTrack?.is_playing == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (currentTrack?.is_playing == true) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(32.dp))
                
                // Next Button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatPlayerTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}