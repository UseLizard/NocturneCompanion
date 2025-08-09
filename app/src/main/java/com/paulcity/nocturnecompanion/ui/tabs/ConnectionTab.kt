package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager

@Composable
fun ConnectionTab(
    connectedDevices: List<EnhancedBleServerManager.DeviceInfo>
) {
    if (connectedDevices.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "No devices",
                    modifier = Modifier.size(64.dp),
                    tint = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No devices connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(connectedDevices) { device ->
                ConnectionCard(device)
            }
        }
    }
}

@Composable
fun ConnectionCard(device: EnhancedBleServerManager.DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Device header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        device.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Connected",
                    tint = infoColor(),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            
            // Connection parameters
            Text(
                "Connection Parameters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // MTU
            ConnectionParameter(
                label = "MTU (Maximum Transmission Unit)",
                value = "${device.mtu} bytes",
                icon = Icons.Default.Info
            )
            
            // PHY Settings
            ConnectionParameter(
                label = "TX PHY",
                value = device.currentTxPhy,
                icon = Icons.Default.KeyboardArrowUp
            )
            
            ConnectionParameter(
                label = "RX PHY",
                value = device.currentRxPhy,
                icon = Icons.Default.KeyboardArrowDown
            )
            
            // Connection duration
            ConnectionParameter(
                label = "Connection Duration",
                value = formatConnectionDuration(device.connectionDuration),
                icon = Icons.Default.DateRange
            )
            
            // Protocol support
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Protocol Support",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    label = "Binary Protocol",
                    enabled = device.supportsBinaryProtocol,
                    color = if (device.supportsBinaryProtocol) successColor() else errorColor()
                )
                StatusChip(
                    label = "2M PHY",
                    enabled = device.supports2MPHY,
                    color = if (device.supports2MPHY) successColor() else neutralColor()
                )
                if (device.requestHighPriority) {
                    StatusChip(
                        label = "High Priority",
                        enabled = true,
                        color = warningColor()
                    )
                }
            }
            
            // Subscriptions
            if (device.subscriptions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Active Subscriptions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    device.subscriptions.forEach { subscription ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = infoColor()
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                getCharacteristicName(subscription),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            
            // BLE Constants display
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Service Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    ServiceInfo("Service UUID", "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
                    ServiceInfo("Command RX", "6e400002")
                    ServiceInfo("Response TX", "6e400003")
                    ServiceInfo("Album Art TX", "6e400006")
                }
            }
        }
    }
}

@Composable
fun ConnectionParameter(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun StatusChip(
    label: String,
    enabled: Boolean,
    color: Color
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = color,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun ServiceInfo(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatConnectionDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = durationMs / (1000 * 60 * 60)
    
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        minutes > 0 -> String.format("%d:%02d", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}

private fun getCharacteristicName(uuid: String): String {
    return when {
        uuid.contains("6e400002", ignoreCase = true) -> "Command RX"
        uuid.contains("6e400003", ignoreCase = true) -> "Response TX"
        uuid.contains("6e400006", ignoreCase = true) -> "Album Art TX"
        else -> uuid.takeLast(8)
    }
}