package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
        val headerScale by animateFloatAsState(
            targetValue = 1.02f,
            animationSpec = tween(200),
            label = "header_card_scale"
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(headerScale),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
            modifier = Modifier.fillMaxSize(),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        AudioEventType.AUDIO_STARTED -> SuccessGreen
        AudioEventType.AUDIO_STOPPED -> ErrorRed
        AudioEventType.VOLUME_CHANGED -> MaterialTheme.colorScheme.tertiary
        AudioEventType.AUDIO_FOCUS_CHANGED -> InfoBlue
        AudioEventType.MEDIA_SESSION_CREATED -> SuccessGreen
        AudioEventType.MEDIA_SESSION_DESTROYED -> ErrorRed
        AudioEventType.METADATA_CHANGED -> MaterialTheme.colorScheme.secondary
        AudioEventType.PLAYBACK_STATE_CHANGED -> NeutralGrey
        AudioEventType.PLAYBACK_CONFIG_CHANGED -> NeutralGrey
        AudioEventType.AUDIO_DEVICE_CONNECTED -> InfoBlue
        AudioEventType.AUDIO_DEVICE_DISCONNECTED -> WarningOrange
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = eventColor.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}