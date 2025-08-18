package com.paulcity.nocturnecompanion.ui.tabs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
import com.paulcity.nocturnecompanion.ui.theme.*

@Composable
fun SettingsTab(
    currentBackgroundTheme: BackgroundTheme = BackgroundTheme.GRADIENT,
    onBackgroundThemeChanged: ((BackgroundTheme) -> Unit)? = null,
    // Status functionality
    serverStatus: String = "",
    isServerRunning: Boolean = false,
    notifications: List<String> = emptyList(),
    onStartServer: () -> Unit = {},
    onStopServer: () -> Unit = {},
    onClearNotifications: () -> Unit = {},
    isBluetoothEnabled: Boolean = true
) {
    val context = LocalContext.current
    var hasNotificationAccess by remember { 
        mutableStateOf(checkNotificationListenerAccess(context)) 
    }
    var selectedBackgroundTheme by remember { mutableStateOf(currentBackgroundTheme) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Status, 1 = Settings
    
    // Check permission on resume
    DisposableEffect(Unit) {
        onDispose {
            hasNotificationAccess = checkNotificationListenerAccess(context)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Selector
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledTonalButton(
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (selectedTab == 0) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Dashboard,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Status")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                FilledTonalButton(
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (selectedTab == 1) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Settings")
                }
            }
        }

        when (selectedTab) {
            0 -> StatusContent(
                serverStatus = serverStatus,
                isServerRunning = isServerRunning,
                notifications = notifications,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                onClearNotifications = onClearNotifications,
                isBluetoothEnabled = isBluetoothEnabled
            )
            1 -> SettingsContent(
                hasNotificationAccess = hasNotificationAccess,
                selectedBackgroundTheme = selectedBackgroundTheme,
                onBackgroundThemeChanged = { theme ->
                    selectedBackgroundTheme = theme
                    onBackgroundThemeChanged?.invoke(theme)
                },
                context = context
            )
        }
    }
}

@Composable
private fun StatusContent(
    serverStatus: String,
    isServerRunning: Boolean,
    notifications: List<String>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onClearNotifications: () -> Unit,
    isBluetoothEnabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server Status Card
        StatusCard(
            title = "Server Status",
            icon = if (isServerRunning) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle,
            isActive = isServerRunning,
            content = {
                ServerStatusContent(
                    serverStatus = serverStatus,
                    isServerRunning = isServerRunning,
                    isBluetoothEnabled = isBluetoothEnabled,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer
                )
            }
        )

        // Bluetooth Status Card (if relevant)
        if (!isBluetoothEnabled) {
            StatusCard(
                title = "Bluetooth",
                icon = Icons.Outlined.BluetoothDisabled,
                isActive = false,
                content = {
                    BluetoothWarningContent()
                }
            )
        }

        // Notifications Card
        StatusCard(
            title = "Notifications",
            icon = if (notifications.isNotEmpty()) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
            isActive = notifications.isNotEmpty(),
            trailing = {
                if (notifications.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = onClearNotifications
                    ) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = "Clear notifications",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            content = {
                NotificationsContent(notifications = notifications)
            }
        )
    }
}

@Composable
private fun SettingsContent(
    hasNotificationAccess: Boolean,
    selectedBackgroundTheme: BackgroundTheme,
    onBackgroundThemeChanged: (BackgroundTheme) -> Unit,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Notification Listener Permission
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
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
                            SuccessGreen 
                        else 
                            MaterialTheme.colorScheme.error
                    )
                }
                Icon(
                    imageVector = if (hasNotificationAccess) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (hasNotificationAccess) SuccessGreen else ErrorRed,
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant Permission")
                }
            }
        }
        
        // Background Theme Selection
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = "Background Theme",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Background Theme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Choose your preferred background style",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Background theme options
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackgroundTheme.values().forEach { theme ->
                    MinimalGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedBackgroundTheme == theme),
                                onClick = { 
                                    onBackgroundThemeChanged(theme)
                                },
                                role = Role.RadioButton
                            ),
                        contentPadding = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedBackgroundTheme == theme),
                                onClick = { 
                                    onBackgroundThemeChanged(theme)
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = getBackgroundThemeDisplayName(theme),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = getBackgroundThemeDescription(theme),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // About section
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Connection info
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
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

@Composable
private fun StatusCard(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    PrimaryGlassCard(
        modifier = modifier.fillMaxWidth(),
        isActive = isActive
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            trailing?.invoke()
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun ServerStatusContent(
    serverStatus: String,
    isServerRunning: Boolean,
    isBluetoothEnabled: Boolean,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isServerRunning) SuccessGreen else ErrorRed,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = serverStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
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
                containerColor = if (isServerRunning) ErrorRed else SuccessGreen,
                disabledContainerColor = NeutralGrey
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = if (isServerRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    !isBluetoothEnabled && !isServerRunning -> "Bluetooth Disabled"
                    isServerRunning -> "Stop Server"
                    else -> "Start Server"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun BluetoothWarningContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = WarningOrange,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Bluetooth is disabled on this device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NotificationsContent(notifications: List<String>) {
    if (notifications.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "No notifications",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notifications.forEach { notification ->
                MinimalGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = notification,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
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

private fun getBackgroundThemeDisplayName(theme: BackgroundTheme): String {
    return when (theme) {
        BackgroundTheme.GRADIENT -> "Gradient"
        BackgroundTheme.SOLID_LIGHT -> "Solid Light"
        BackgroundTheme.SOLID_DARK -> "Solid Dark"
        BackgroundTheme.MINIMAL_LIGHT -> "Minimal Light"
        BackgroundTheme.MINIMAL_DARK -> "Minimal Dark"
    }
}

private fun getBackgroundThemeDescription(theme: BackgroundTheme): String {
    return when (theme) {
        BackgroundTheme.GRADIENT -> "Animated flowing gradient background"
        BackgroundTheme.SOLID_LIGHT -> "Clean light background"
        BackgroundTheme.SOLID_DARK -> "Clean dark background"
        BackgroundTheme.MINIMAL_LIGHT -> "Minimal light grey background"
        BackgroundTheme.MINIMAL_DARK -> "Minimal dark grey background"
    }
}