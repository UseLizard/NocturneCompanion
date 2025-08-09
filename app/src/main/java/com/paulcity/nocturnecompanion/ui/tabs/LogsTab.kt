package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.DebugLogger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsTab(
    debugLogs: List<DebugLogger.DebugLogEntry>,
    autoScroll: Boolean,
    logFilter: BleConstants.DebugLevel,
    onAutoScrollToggle: (Boolean) -> Unit,
    onFilterChange: (BleConstants.DebugLevel) -> Unit,
    onClearLogs: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-scroll effect
    LaunchedEffect(debugLogs.size, autoScroll) {
        if (autoScroll && debugLogs.isNotEmpty()) {
            listState.animateScrollToItem(debugLogs.size - 1)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter dropdown
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { expanded = true }
                    ) {
                        Text("Filter: ${logFilter.name}")
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onAutoScrollToggle(!autoScroll) }
                    ) {
                        Icon(
                            if (autoScroll) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (autoScroll) "Stop auto-scroll" else "Start auto-scroll",
                            tint = if (autoScroll) Color.Green else Color.Gray
                        )
                    }
                    IconButton(
                        onClick = onClearLogs
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear logs"
                        )
                    }
                }
            }
        }
        
        // Log entries
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
            
            items(filteredLogs) { log ->
                LogEntry(log)
            }
            
            if (filteredLogs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No logs to display",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntry(log: DebugLogger.DebugLogEntry) {
    val backgroundColor = when (log.level) {
        BleConstants.DebugLevel.ERROR -> errorColor().copy(alpha = 0.2f)
        BleConstants.DebugLevel.WARNING -> warningColor().copy(alpha = 0.2f)
        BleConstants.DebugLevel.INFO -> infoColor().copy(alpha = 0.2f)
        BleConstants.DebugLevel.DEBUG -> successColor().copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    
    val textColor = when (log.level) {
        BleConstants.DebugLevel.ERROR -> errorColor()
        BleConstants.DebugLevel.WARNING -> warningColor()
        BleConstants.DebugLevel.INFO -> infoColor()
        BleConstants.DebugLevel.DEBUG -> successColor()
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = textColor.copy(alpha = 0.2f)
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
                Text(
                    text = log.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
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
                            color = MaterialTheme.colorScheme.surfaceVariant
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