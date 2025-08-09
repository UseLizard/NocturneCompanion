package com.paulcity.nocturnecompanion.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.ui.tabs.components.InfoChip
import com.paulcity.nocturnecompanion.ui.tabs.components.formatTime
import java.text.SimpleDateFormat
import java.util.*

data class AlbumArtInfo(
    val hasArt: Boolean,
    val checksum: String? = null,
    val size: Int = 0,
    val lastQuery: String? = null,
    val lastTransferTime: Long? = null,
    val bitmap: Bitmap? = null
)

@Composable
fun MediaTab(
    lastStateUpdate: StateUpdate?,
    albumArtInfo: AlbumArtInfo?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Album art
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No album art",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Media info
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
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
                            if (state.is_playing) successColor() else warningColor()
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
                        color = Color.Gray
                    )
                }
            }
        }
        
        // Album art info
        albumArtInfo?.let { info ->
            if (info.hasArt || info.size > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Album Art Info",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (info.size > 0) {
                            Text("Size: ${info.size} bytes", style = MaterialTheme.typography.bodySmall)
                        }
                        info.checksum?.let {
                            Text("Checksum: ${it.take(16)}...", style = MaterialTheme.typography.bodySmall)
                        }
                        info.lastTransferTime?.let {
                            Text(
                                "Last transfer: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}