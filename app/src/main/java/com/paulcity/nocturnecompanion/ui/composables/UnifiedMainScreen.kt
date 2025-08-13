package com.paulcity.nocturnecompanion.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paulcity.nocturnecompanion.ui.UnifiedMainViewModel
import com.paulcity.nocturnecompanion.ui.tabs.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedMainScreen(
    viewModel: UnifiedMainViewModel,
    onScanClick: () -> Unit,
    onStartServer: () -> Unit
) {
    val tabTitles = listOf("Status", "Devices", "Connection", "Transfer", "Media", "Commands", "Logs", "Audio", "Podcasts", "Settings", "Weather")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Nocturne Companion") }
        )

        StatusBar(
            serverStatus = viewModel.serverStatus.value,
            isServerRunning = viewModel.isServerRunning.value,
            connectedDevicesCount = viewModel.connectedDevices.size
        )

        ScrollableTabRow(selectedTabIndex = viewModel.selectedTab.value) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = viewModel.selectedTab.value == index,
                    onClick = { viewModel.selectedTab.value = index },
                    text = { Text(title) }
                )
            }
        }

        when (viewModel.selectedTab.value) {
            0 -> StatusTab(
                serverStatus = viewModel.serverStatus.value,
                isServerRunning = viewModel.isServerRunning.value,
                notifications = viewModel.notifications.value,
                onStartServer = {
                    if (viewModel.isBluetoothEnabled.value) {
                        onStartServer()
                    }
                },
                onStopServer = { viewModel.stopNocturneService() },
                onClearNotifications = { viewModel.clearNotifications() },
                isBluetoothEnabled = viewModel.isBluetoothEnabled.value
            )
            1 -> DevicesTab(
                connectedDevices = viewModel.connectedDevices,
                onRequestPhyUpdate = { }
            )
            2 -> ConnectionTab(
                connectedDevices = viewModel.connectedDevices
            )
            3 -> ConnectionSettingsTab()
            4 -> MediaTab(
                lastStateUpdate = viewModel.lastStateUpdate.value,
                albumArtInfo = viewModel.albumArtInfo.value
            )
            5 -> CommandsTab(
                lastCommand = viewModel.lastCommand.value,
                connectedDevicesCount = viewModel.connectedDevices.size,
                onSendTestState = { viewModel.sendTestState() },
                onSendTestTimeSync = { viewModel.sendTestTimeSync() },
                onSendTestAlbumArt = { viewModel.sendTestAlbumArt() }
            )
            6 -> LogsTab(
                debugLogs = viewModel.debugLogs,
                autoScroll = viewModel.autoScrollLogs.value,
                logFilter = viewModel.logFilter.value,
                onAutoScrollToggle = { viewModel.autoScrollLogs.value = it },
                onFilterChange = { viewModel.logFilter.value = it },
                onClearLogs = { viewModel.clearLogs() }
            )
            7 -> AudioTab(
                audioEvents = viewModel.audioEvents,
                onClearEvents = { viewModel.clearAudioEvents() }
            )
            8 -> PodcastTab()
            9 -> SettingsTab()
            10 -> WeatherTab(viewModel = viewModel)
        }
    }
}

@Composable
fun StatusBar(
    serverStatus: String,
    isServerRunning: Boolean,
    connectedDevicesCount: Int
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            connectedDevicesCount > 0 -> Color(0xFF66BB6A)  // Softer green
            serverStatus == "Advertising" -> Color(0xFF42A5F5)  // Softer blue
            isServerRunning -> Color(0xFFFFCA28)  // Softer yellow
            else -> Color(0xFFEF5350)  // Softer red
        },
        label = "status_color"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "Bluetooth",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = serverStatus,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        if (connectedDevicesCount > 0) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "$connectedDevicesCount device(s)",
                color = Color.White
            )
        }
    }
}
