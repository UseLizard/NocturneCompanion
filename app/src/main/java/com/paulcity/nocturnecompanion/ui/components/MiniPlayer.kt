package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paulcity.nocturnecompanion.data.StateUpdate

@Composable
fun MiniPlayer(
    currentTrack: StateUpdate?,
    albumArtUrl: String? = null,
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (currentTrack == null) return
    
    SurfaceGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpand() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        enableScaleAnimation = false,
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Album Art + Track Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Album Art
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (albumArtUrl != null) {
                        AsyncImage(
                            model = albumArtUrl,
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = "No album art",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Track Info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentTrack.track ?: "Unknown Track",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentTrack.artist ?: "Unknown Artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Control Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Button
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Play/Pause Button
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            if (currentTrack.is_playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (currentTrack.is_playing) "Pause" else "Play",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                
                // Next Button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        
        // Progress Bar
        if (currentTrack.duration_ms > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = (currentTrack.position_ms.toFloat() / currentTrack.duration_ms.toFloat()).coerceIn(0f, 1f)
            
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}