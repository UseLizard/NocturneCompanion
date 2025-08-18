package com.paulcity.nocturnecompanion.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paulcity.nocturnecompanion.ui.UnifiedMainViewModel
import com.paulcity.nocturnecompanion.ui.components.AnimatedFlowingBackground
import com.paulcity.nocturnecompanion.ui.components.ModernSidebarNavigation
import com.paulcity.nocturnecompanion.ui.components.FullScreenPlayer
import com.paulcity.nocturnecompanion.ui.components.MiniPlayer
import com.paulcity.nocturnecompanion.ui.tabs.*
import com.paulcity.nocturnecompanion.ui.theme.BackgroundTheme

private val DarkColors = listOf(
    Color(0xFF6366F1).copy(alpha = 0.12f),
    Color(0xFF10B981).copy(alpha = 0.08f),
    Color(0xFFF59E0B).copy(alpha = 0.06f),
    Color(0xFF8B5FBF).copy(alpha = 0.10f)
)

private val LightColors = listOf(
    Color(0xFF4338CA).copy(alpha = 0.08f),
    Color(0xFF059669).copy(alpha = 0.06f),
    Color(0xFFD97706).copy(alpha = 0.05f),
    Color(0xFF6366F1).copy(alpha = 0.07f)
)

@Composable
fun UnifiedMainScreen(
    viewModel: UnifiedMainViewModel,
    onStartServer: () -> Unit
) {
    val backgroundTheme = viewModel.backgroundTheme.value
    val isDarkTheme = isSystemInDarkTheme()
    
    when (backgroundTheme) {
        BackgroundTheme.GRADIENT -> {
            val colors = if (isDarkTheme) DarkColors else LightColors
            AnimatedFlowingBackground(colors = colors) {
                MainScreenContent(viewModel, onStartServer)
            }
        }
        BackgroundTheme.SOLID_LIGHT -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFCFCFD))
            ) {
                MainScreenContent(viewModel, onStartServer)
            }
        }
        BackgroundTheme.SOLID_DARK -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
            ) {
                MainScreenContent(viewModel, onStartServer)
            }
        }
        BackgroundTheme.MINIMAL_LIGHT -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF8FAFC))
            ) {
                MainScreenContent(viewModel, onStartServer)
            }
        }
        BackgroundTheme.MINIMAL_DARK -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E293B))
            ) {
                MainScreenContent(viewModel, onStartServer)
            }
        }
    }
}

@Composable
private fun MainScreenContent(
    viewModel: UnifiedMainViewModel,
    onStartServer: () -> Unit
) {
    val selectedTab = viewModel.tabItems.first { it.id == viewModel.selectedTab.value }

    Box(modifier = Modifier.fillMaxSize()) {
        // Show full-screen player if expanded, otherwise show main content
        if (viewModel.isPlayerExpanded.value) {
            FullScreenPlayer(
                currentTrack = viewModel.currentPlayingTrack.value,
                albumArtUrl = viewModel.currentAlbumArt.value,
                onPlayPause = { viewModel.togglePlayPause() },
                onPrevious = { viewModel.playPrevious() },
                onNext = { viewModel.playNext() },
                onSeek = { viewModel.seekTo(it) },
                onMinimize = { viewModel.minimizePlayer() }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ModernSidebarNavigation(
                    selectedTabId = viewModel.selectedTab.value,
                    onTabSelected = { tabId -> viewModel.selectedTab.value = tabId },
                    tabItems = viewModel.tabItems,
                    isHorizontal = true
                )

                TitleBar(title = selectedTab.title)

                // Main content area with mini player
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main tab content
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                bottom = if (viewModel.currentPlayingTrack.value != null) 90.dp else 16.dp
                            )
                    ) {
                        when (viewModel.selectedTab.value) {
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
                                albumArtInfo = viewModel.albumArtInfo.value,
                                gradientInfo = viewModel.gradientInfo.value,
                                isGenerating = viewModel.isGeneratingGradient.value,
                                onGenerateGradient = { viewModel.generateGradientFromAlbumArt() },
                                onSendGradient = { viewModel.sendGradientColors() }
                            )
                            5 -> CommandsTab(
                                lastCommand = viewModel.lastCommand.value,
                                connectedDevicesCount = viewModel.connectedDevices.size,
                                onSendTestState = { viewModel.sendTestState() },
                                onSendTestTimeSync = { viewModel.sendTestTimeSync() },
                                onSendTestAlbumArt = { viewModel.sendTestAlbumArt() },
                                onSendTestWeather = { viewModel.sendTestWeather() },
                                // Logs functionality
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
                            9 -> SettingsTab(
                                currentBackgroundTheme = viewModel.backgroundTheme.value,
                                onBackgroundThemeChanged = { theme -> 
                                    viewModel.updateBackgroundTheme(theme) 
                                },
                                // Status functionality
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
                            10 -> WeatherTab(viewModel = viewModel)
                            else -> SettingsTab(
                                currentBackgroundTheme = viewModel.backgroundTheme.value,
                                onBackgroundThemeChanged = { theme -> 
                                    viewModel.updateBackgroundTheme(theme) 
                                },
                                // Status functionality
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
                        }
                    }
                    
                    // Show mini player at bottom if there's a current track and player is not expanded
                    if (viewModel.currentPlayingTrack.value != null && !viewModel.isPlayerExpanded.value) {
                        MiniPlayer(
                            currentTrack = viewModel.currentPlayingTrack.value,
                            albumArtUrl = viewModel.currentAlbumArt.value,
                            onPlayPause = { viewModel.togglePlayPause() },
                            onPrevious = { viewModel.playPrevious() },
                            onNext = { viewModel.playNext() },
                            onExpand = { viewModel.expandPlayer() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(title: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}