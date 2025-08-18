package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ui.components.InfoChip
import com.paulcity.nocturnecompanion.ui.components.SubscriptionChip

@Composable
fun DevicesTab(
    connectedDevices: List<EnhancedBleServerManager.DeviceInfo>,
    onRequestPhyUpdate: (String) -> Unit
) {
    if (connectedDevices.isEmpty()) {
        // Empty state - no devices connected
        Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "No devices",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No devices connected",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Waiting for Car Thing to connect...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
    } else {
        // Show connected devices
        LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        items(connectedDevices) { device ->
            ConnectedDeviceCard(
                device = device,
                onRequestPhyUpdate = onRequestPhyUpdate
            )
        }
        }
    }
}

@Composable
fun ConnectedDeviceCard(
    device: EnhancedBleServerManager.DeviceInfo,
    onRequestPhyUpdate: (String) -> Unit
) {
    PrimaryGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Device header
        Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
        ) {
        Column {
            Text(
                text = device.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Connection indicator
        Surface(
            shape = CircleShape,
            color = SuccessGreen,
            modifier = Modifier.size(40.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Connected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connection metrics
        Text(
        "Connection Metrics",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        // MTU and PHY info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCard(
                label = "MTU",
                value = "${device.mtu} bytes",
                icon = Icons.Default.Info,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard(
                label = "TX PHY",
                value = device.currentTxPhy,
                icon = Icons.Default.KeyboardArrowUp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard(
                label = "RX PHY",
                value = device.currentRxPhy,
                icon = Icons.Default.KeyboardArrowDown,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Connection duration
        MetricCard(
            label = "Connected for",
            value = formatDuration(device.connectionDuration),
            icon = Icons.Default.DateRange,
            modifier = Modifier.fillMaxWidth()
        )
        
        // Protocol support indicators
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (device.supportsBinaryProtocol) {
                StatusChip(
                    label = "Binary Protocol",
                    color = SuccessGreen,
                    icon = Icons.Default.Check
                )
            }
            if (device.supports2MPHY) {
                StatusChip(
                    label = "2M PHY",
                    color = InfoBlue,
                    icon = Icons.Default.PlayArrow
                )
            }
            if (device.requestHighPriority) {
                StatusChip(
                    label = "High Priority",
                    color = WarningOrange,
                    icon = Icons.Default.Star
                )
            }
        }
        
        // Request PHY update button (if needed)
        if (!device.supports2MPHY && !device.phyUpdateAttempted) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onRequestPhyUpdate(device.address) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Request 2M PHY for Faster Transfer")
            }
        }
        
        // Active subscriptions
        if (device.subscriptions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Active Subscriptions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                device.subscriptions.forEach { sub ->
                    SubscriptionChip(sub)
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    MinimalGlassCard(
        modifier = modifier
    ) {
        Row(
        verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
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
}

@Composable
fun StatusChip(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / 60000) % 60
    val hours = durationMs / 3600000
    
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}