package com.paulcity.nocturnecompanion.ui.tabs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    var hasNotificationAccess by remember { 
        mutableStateOf(checkNotificationListenerAccess(context)) 
    }
    
    // Check permission on resume
    DisposableEffect(Unit) {
        onDispose {
            hasNotificationAccess = checkNotificationListenerAccess(context)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Notification Listener Permission
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Notification Listener Access",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (hasNotificationAccess) 
                                "Media controls are enabled" 
                            else 
                                "Required for media control functionality",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasNotificationAccess) 
                                successColor() 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Icon(
                        if (hasNotificationAccess) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (hasNotificationAccess) successColor() else errorColor(),
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                if (!hasNotificationAccess) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // About section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Nocturne Companion",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "BLE media control bridge for Spotify Car Thing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Version 2.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connection info
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Connection Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("Service UUID", "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
                InfoRow("Command RX", "6e400002")
                InfoRow("State TX", "6e400003")
                InfoRow("Album Art TX", "6e400006")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Nordic UART Service (NUS) compatible",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

fun checkNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledListeners.contains(context.packageName)
}