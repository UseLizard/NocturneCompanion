package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcity.nocturnecompanion.data.AudioEvent
import com.paulcity.nocturnecompanion.data.AudioEventType
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AudioTab(
    audioEvents: List<AudioEvent>,
    onClearEvents: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header with clear button
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Audio Events",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${audioEvents.size} events",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClearEvents) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear events"
                    )
                }
            }
        }
        
        // Event list
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (audioEvents.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No audio events",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                items(audioEvents.reversed()) { event ->
                    AudioEventCard(event)
                }
            }
        }
    }
}

@Composable
fun AudioEventCard(event: AudioEvent) {
    val eventColor = when (event.eventType) {
        AudioEventType.AUDIO_STARTED -> successColor()
        AudioEventType.AUDIO_STOPPED -> errorColor()
        AudioEventType.VOLUME_CHANGED -> MaterialTheme.colorScheme.tertiary
        AudioEventType.AUDIO_FOCUS_CHANGED -> infoColor()
        AudioEventType.MEDIA_SESSION_CREATED -> successColor()
        AudioEventType.MEDIA_SESSION_DESTROYED -> errorColor()
        AudioEventType.METADATA_CHANGED -> MaterialTheme.colorScheme.secondary
        AudioEventType.PLAYBACK_STATE_CHANGED -> neutralColor()
        AudioEventType.PLAYBACK_CONFIG_CHANGED -> neutralColor()
        AudioEventType.AUDIO_DEVICE_CONNECTED -> infoColor()
        AudioEventType.AUDIO_DEVICE_DISCONNECTED -> warningColor()
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = eventColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = eventColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = event.eventType.name.replace('_', ' '),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = eventColor
                        )
                    }
                    // Show event type label only
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Text(
                text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray
            )
        }
    }
}