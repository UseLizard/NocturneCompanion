package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StatusTab(
    serverStatus: String,
    isServerRunning: Boolean,
    notifications: List<String>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onClearNotifications: () -> Unit,
    isBluetoothEnabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Server controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Server Status",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (isServerRunning) successColor() else errorColor(),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        serverStatus,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Bluetooth status indicator
                if (!isBluetoothEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = warningColor().copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Bluetooth disabled",
                                tint = warningColor(),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Bluetooth is disabled on this device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = warningColor()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                Button(
                    onClick = {
                        if (isServerRunning) {
                            onStopServer()
                        } else {
                            onStartServer()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isBluetoothEnabled || isServerRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServerRunning) errorColor() else successColor(),
                        disabledContainerColor = neutralColor()
                    )
                ) {
                    Text(
                        when {
                            !isBluetoothEnabled && !isServerRunning -> "Bluetooth Disabled"
                            isServerRunning -> "Stop Server"
                            else -> "Start Server"
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Notifications
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
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onClearNotifications
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (notifications.isEmpty()) {
                    Text(
                        "No notifications",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                } else {
                    notifications.forEach { notification ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                notification,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}