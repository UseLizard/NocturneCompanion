package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.DebugLogger
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
import com.paulcity.nocturnecompanion.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CommandsTab(
    lastCommand: String?,
    connectedDevicesCount: Int,
    onSendTestState: () -> Unit,
    onSendTestTimeSync: () -> Unit,
    onSendTestAlbumArt: () -> Unit,
    onSendTestWeather: () -> Unit,
    // Logs functionality
    debugLogs: List<DebugLogger.DebugLogEntry> = emptyList(),
    autoScroll: Boolean = true,
    logFilter: BleConstants.DebugLevel = BleConstants.DebugLevel.INFO,
    onAutoScrollToggle: (Boolean) -> Unit = {},
    onFilterChange: (BleConstants.DebugLevel) -> Unit = {},
    onClearLogs: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Commands, 1 = Logs
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-scroll effect for logs
    LaunchedEffect(debugLogs.size, autoScroll) {
        if (autoScroll && debugLogs.isNotEmpty() && selectedTab == 1) {
            listState.animateScrollToItem(debugLogs.size - 1)
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
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Commands")
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
                        Icons.Default.Article,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logs (${debugLogs.size})")
                }
            }
        }

        when (selectedTab) {
            0 -> CommandsContent(
                lastCommand = lastCommand,
                connectedDevicesCount = connectedDevicesCount,
                onSendTestState = onSendTestState,
                onSendTestTimeSync = onSendTestTimeSync,
                onSendTestAlbumArt = onSendTestAlbumArt,
                onSendTestWeather = onSendTestWeather
            )
            1 -> LogsContent(
                debugLogs = debugLogs,
                autoScroll = autoScroll,
                logFilter = logFilter,
                onAutoScrollToggle = onAutoScrollToggle,
                onFilterChange = onFilterChange,
                onClearLogs = onClearLogs,
                listState = listState
            )
        }
    }
}

@Composable
private fun CommandsContent(
    lastCommand: String?,
    connectedDevicesCount: Int,
    onSendTestState: () -> Unit,
    onSendTestTimeSync: () -> Unit,
    onSendTestAlbumArt: () -> Unit,
    onSendTestWeather: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Test Commands
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Test Commands",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendTestState,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Test State Update")
                }
                
                Button(
                    onClick = onSendTestTimeSync,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Time Sync")
                }
                
                Button(
                    onClick = onSendTestAlbumArt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Album Art Transfer")
                }
                
                Button(
                    onClick = onSendTestWeather,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Weather Data")
                }
            }
            
            if (connectedDevicesCount == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        "Connect a device to enable test commands",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        // Last Command
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Last Command Received",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (lastCommand != null) {
                MinimalGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = formatJson(lastCommand),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = SuccessGreen
                    )
                }
            } else {
                Text(
                    "No commands received yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogsContent(
    debugLogs: List<DebugLogger.DebugLogEntry>,
    autoScroll: Boolean,
    logFilter: BleConstants.DebugLevel,
    onAutoScrollToggle: (Boolean) -> Unit,
    onFilterChange: (BleConstants.DebugLevel) -> Unit,
    onClearLogs: () -> Unit,
    listState: LazyListState
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Controls
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter dropdown
                var expanded by remember { mutableStateOf(false) }
                Box {
                    FilledTonalButton(
                        onClick = { expanded = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${logFilter.name}")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        BleConstants.DebugLevel.values().forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level.name) },
                                onClick = {
                                    onFilterChange(level)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { onAutoScrollToggle(!autoScroll) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (autoScroll) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            if (autoScroll) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (autoScroll) "Stop auto-scroll" else "Start auto-scroll",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onClearLogs
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear logs",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        
        // Log entries
        val filteredLogs = when (logFilter) {
            BleConstants.DebugLevel.VERBOSE -> debugLogs
            BleConstants.DebugLevel.DEBUG -> debugLogs.filter { 
                it.level >= BleConstants.DebugLevel.DEBUG 
            }
            BleConstants.DebugLevel.INFO -> debugLogs.filter { 
                it.level >= BleConstants.DebugLevel.INFO 
            }
            BleConstants.DebugLevel.WARNING -> debugLogs.filter { 
                it.level >= BleConstants.DebugLevel.WARNING 
            }
            BleConstants.DebugLevel.ERROR -> debugLogs.filter { 
                it.level == BleConstants.DebugLevel.ERROR 
            }
        }
        
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredLogs) { log ->
                LogEntry(log)
            }
            
            if (filteredLogs.isEmpty()) {
                item {
                    SurfaceGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Article,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No logs to display",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntry(log: DebugLogger.DebugLogEntry) {
    val backgroundColor = when (log.level) {
        BleConstants.DebugLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        BleConstants.DebugLevel.WARNING -> WarningOrange.copy(alpha = 0.2f)
        BleConstants.DebugLevel.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        BleConstants.DebugLevel.DEBUG -> SuccessGreen.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    
    val textColor = when (log.level) {
        BleConstants.DebugLevel.ERROR -> MaterialTheme.colorScheme.error
        BleConstants.DebugLevel.WARNING -> WarningOrange
        BleConstants.DebugLevel.INFO -> MaterialTheme.colorScheme.primary
        BleConstants.DebugLevel.DEBUG -> SuccessGreen
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    MinimalGlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = backgroundColor
                    ) {
                        Text(
                            text = log.type,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = textColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = log.level.name,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (log.data != null && log.data.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    log.data.forEach { (key, value) ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "$key: $value",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatJson(json: String): String {
    return try {
        val element = Json.parseToJsonElement(json)
        val gson = GsonBuilder().setPrettyPrinting().create()
        gson.toJson(element)
    } catch (e: Exception) {
        json // Return original if parsing fails
    }
}