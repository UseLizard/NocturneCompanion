package com.paulcity.nocturnecompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.DebugLogger
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ble.MediaStoreAlbumArtManager
import com.paulcity.nocturnecompanion.data.AudioEvent
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.tabs.*
import com.paulcity.nocturnecompanion.ui.theme.NocturneCompanionTheme
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class UnifiedMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "UnifiedMainActivity"
        private const val ACTION_SEND_BLE_STATE = "com.paulcity.nocturnecompanion.SEND_BLE_STATE"
        private const val ACTION_SEND_BLE_TIME_SYNC = "com.paulcity.nocturnecompanion.SEND_BLE_TIME_SYNC"
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isReceiverRegistered = false
    private val gson = Gson()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var mediaStoreAlbumArtManager: MediaStoreAlbumArtManager
    
    // State variables - Combined from both activities
    private val serverStatus = mutableStateOf("Disconnected")
    private val isServerRunning = mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val connectedDevices = mutableStateListOf<EnhancedBleServerManager.DeviceInfo>()
    private val debugLogs = mutableStateListOf<DebugLogger.DebugLogEntry>()
    private val lastCommand = mutableStateOf<String?>(null)
    private val lastStateUpdate = mutableStateOf<StateUpdate?>(null)
    private val albumArtInfo = mutableStateOf<AlbumArtInfo?>(null)
    private val audioEvents = mutableStateListOf<AudioEvent>()
    private val notifications = mutableStateOf(listOf<String>())
    
    // UI state
    private val selectedTab = mutableStateOf(0)
    private val autoScrollLogs = mutableStateOf(true)
    private val logFilter = mutableStateOf(BleConstants.DebugLevel.VERBOSE)
    private val isBluetoothEnabled = mutableStateOf(false)

    private val scanPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                scanForDevices()
            }
        }

    private val startServicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val hasConnect = permissions.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false)
            val hasAdvertise = permissions.getOrDefault(Manifest.permission.BLUETOOTH_ADVERTISE, false)
            
            if (hasConnect && hasAdvertise) {
                startNocturneService()
            } else {
                Log.w(TAG, "Required Bluetooth permissions were denied. Connect: $hasConnect, Advertise: $hasAdvertise")
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NocturneServiceBLE.ACTION_SERVER_STATUS -> {
                    serverStatus.value = intent.getStringExtra(NocturneServiceBLE.EXTRA_SERVER_STATUS) ?: "Unknown"
                    isServerRunning.value = intent.getBooleanExtra(NocturneServiceBLE.EXTRA_IS_RUNNING, false)
                }
                NocturneServiceBLE.ACTION_CONNECTED_DEVICES -> {
                    val json = intent.getStringExtra(NocturneServiceBLE.EXTRA_CONNECTED_DEVICES) ?: return
                    val devices = gson.fromJson<List<EnhancedBleServerManager.DeviceInfo>>(
                        json, 
                        object : TypeToken<List<EnhancedBleServerManager.DeviceInfo>>() {}.type
                    )
                    connectedDevices.clear()
                    connectedDevices.addAll(devices)
                }
                NocturneServiceBLE.ACTION_DEBUG_LOG -> {
                    val json = intent.getStringExtra(NocturneServiceBLE.EXTRA_DEBUG_LOG) ?: return
                    val logEntry = gson.fromJson(json, DebugLogger.DebugLogEntry::class.java)
                    debugLogs.add(logEntry)
                    
                    // Track album art events
                    if (logEntry.type in listOf("ALBUM_ART", "ALBUM_ART_QUERY", "ALBUM_ART_TEST", "TEST_ALBUM_ART")) {
                        updateAlbumArtInfo(logEntry)
                    }
                    
                    // Keep only last 500 logs
                    while (debugLogs.size > 500) {
                        debugLogs.removeAt(0)
                    }
                }
                NocturneServiceBLE.ACTION_STATE_UPDATED -> {
                    val json = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA) ?: return
                    lastStateUpdate.value = gson.fromJson(json, StateUpdate::class.java)
                    
                    // Try to get album art when state updates
                    serviceScope.launch {
                        tryGetAlbumArt()
                    }
                }
                NocturneServiceBLE.ACTION_COMMAND_RECEIVED -> {
                    val commandData = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA) ?: "Error reading command"
                    lastCommand.value = commandData
                }
                NocturneServiceBLE.ACTION_AUDIO_EVENT -> {
                    val json = intent.getStringExtra(NocturneServiceBLE.EXTRA_AUDIO_EVENT) ?: return
                    val audioEvent = gson.fromJson(json, AudioEvent::class.java)
                    audioEvents.add(audioEvent)
                    
                    // Keep only last 500 audio events
                    while (audioEvents.size > 500) {
                        audioEvents.removeAt(0)
                    }
                }
                NocturneServiceBLE.ACTION_NOTIFICATION -> {
                    val message = intent.getStringExtra(NocturneServiceBLE.EXTRA_NOTIFICATION_MESSAGE) ?: return
                    notifications.value = notifications.value + message
                    
                    // Keep only last 10 notifications
                    if (notifications.value.size > 10) {
                        notifications.value = notifications.value.takeLast(10)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                    
                    // Stop server if Bluetooth is turned off
                    if (state == BluetoothAdapter.STATE_OFF && isServerRunning.value) {
                        stopNocturneService()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        isBluetoothEnabled.value = bluetoothAdapter.isEnabled
        
        mediaStoreAlbumArtManager = MediaStoreAlbumArtManager(this)
        
        registerBroadcastReceivers()
        
        setContent {
            NocturneCompanionTheme {
                UnifiedMainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        unregisterBroadcastReceivers()
    }

    private fun registerBroadcastReceivers() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(NocturneServiceBLE.ACTION_SERVER_STATUS)
                addAction(NocturneServiceBLE.ACTION_CONNECTED_DEVICES)
                addAction(NocturneServiceBLE.ACTION_DEBUG_LOG)
                addAction(NocturneServiceBLE.ACTION_STATE_UPDATED)
                addAction(NocturneServiceBLE.ACTION_COMMAND_RECEIVED)
                addAction(NocturneServiceBLE.ACTION_AUDIO_EVENT)
                addAction(NocturneServiceBLE.ACTION_NOTIFICATION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            
            if (Build.VERSION.SDK_INT >= 34) {
                LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
            } else {
                registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterBroadcastReceivers() {
        if (isReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
                } else {
                    unregisterReceiver(receiver)
                }
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
            }
        }
    }

    private fun updateAlbumArtInfo(logEntry: DebugLogger.DebugLogEntry) {
        val data = logEntry.data
        albumArtInfo.value = AlbumArtInfo(
            hasArt = data?.get("size")?.toString()?.toIntOrNull() != null,
            checksum = data?.get("checksum")?.toString() ?: data?.get("sha256")?.toString(),
            size = data?.get("size")?.toString()?.toIntOrNull() ?: 0,
            lastQuery = data?.get("track_id")?.toString() ?: data?.get("hash")?.toString(),
            lastTransferTime = if (logEntry.message.contains("complete", ignoreCase = true)) {
                System.currentTimeMillis()
            } else {
                albumArtInfo.value?.lastTransferTime
            }
        )
    }

    private suspend fun tryGetAlbumArt() {
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, com.paulcity.nocturnecompanion.services.NocturneNotificationListener::class.java)
            val sessions = mediaSessionManager.getActiveSessions(component)
            
            for (controller in sessions) {
                val metadata = controller.metadata
                if (metadata != null) {
                    // First try to get bitmap from metadata
                    var art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    
                    // If no bitmap in metadata, try MediaStore
                    if (art == null) {
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                        
                        // Use MediaStoreAlbumArtManager to get the album art
                        art = mediaStoreAlbumArtManager.getAlbumArtBitmap(artist, album, title)
                    }
                    
                    if (art != null) {
                        albumArtInfo.value = albumArtInfo.value?.copy(bitmap = art) ?: AlbumArtInfo(
                            hasArt = true,
                            bitmap = art
                        )
                        Log.d(TAG, "Album art loaded successfully")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting album art", e)
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        scanPermissionsLauncher.launch(requiredPermissions)
    }

    private fun scanForDevices() {
        try {
            discoveredDevices.clear()
            if (::bluetoothAdapter.isInitialized) {
                discoveredDevices.addAll(bluetoothAdapter.bondedDevices ?: emptySet())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for Bluetooth access", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for devices", e)
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        startServicePermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startNocturneService() {
        Log.d(TAG, "Starting NocturneServiceBLE")
        val intent = Intent(this, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_START
        }
        startService(intent)
    }

    private fun stopNocturneService() {
        Log.d(TAG, "Stopping NocturneServiceBLE")
        val intent = Intent(this, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_STOP
        }
        startService(intent)
    }

    private fun sendTestState() {
        val intent = Intent(ACTION_SEND_BLE_STATE)
        if (Build.VERSION.SDK_INT >= 34) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else {
            sendBroadcast(intent)
        }
    }

    private fun sendTestTimeSync() {
        val intent = Intent(ACTION_SEND_BLE_TIME_SYNC)
        if (Build.VERSION.SDK_INT >= 34) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else {
            sendBroadcast(intent)
        }
    }

    private fun sendTestAlbumArt() {
        val intent = Intent(this, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_TEST_ALBUM_ART
        }
        startService(intent)
    }

    private fun requestPhyUpdate(deviceAddress: String) {
        // PHY update not yet implemented in service
        Log.d(TAG, "PHY update requested for device: $deviceAddress")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun UnifiedMainScreen() {
        val tabTitles = listOf("Status", "Devices", "Connection", "Transfer", "Media", "Commands", "Logs", "Audio", "Podcasts", "Settings")
        
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Nocturne Companion") }
            )
            
            StatusBar()
            
            // Tabs
            ScrollableTabRow(selectedTabIndex = selectedTab.value) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab.value == index,
                        onClick = { selectedTab.value = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Content
            when (selectedTab.value) {
                0 -> StatusTab(
                    serverStatus = serverStatus.value,
                    isServerRunning = isServerRunning.value,
                    notifications = notifications.value,
                    onStartServer = { 
                        isBluetoothEnabled.value = bluetoothAdapter.isEnabled
                        if (bluetoothAdapter.isEnabled) {
                            requestPermissionsAndStart()
                        }
                    },
                    onStopServer = { stopNocturneService() },
                    onClearNotifications = { notifications.value = emptyList() },
                    isBluetoothEnabled = bluetoothAdapter.isEnabled
                )
                1 -> DevicesTab(
                    connectedDevices = connectedDevices,
                    discoveredDevices = discoveredDevices,
                    onScanClick = { checkPermissionsAndScan() },
                    onRequestPhyUpdate = { requestPhyUpdate(it) }
                )
                2 -> ConnectionTab(
                    connectedDevices = connectedDevices
                )
                3 -> ConnectionSettingsTab()
                4 -> MediaTab(
                    lastStateUpdate = lastStateUpdate.value,
                    albumArtInfo = albumArtInfo.value
                )
                5 -> CommandsTab(
                    lastCommand = lastCommand.value,
                    connectedDevicesCount = connectedDevices.size,
                    onSendTestState = { sendTestState() },
                    onSendTestTimeSync = { sendTestTimeSync() },
                    onSendTestAlbumArt = { sendTestAlbumArt() }
                )
                6 -> LogsTab(
                    debugLogs = debugLogs,
                    autoScroll = autoScrollLogs.value,
                    logFilter = logFilter.value,
                    onAutoScrollToggle = { autoScrollLogs.value = it },
                    onFilterChange = { logFilter.value = it },
                    onClearLogs = { debugLogs.clear() }
                )
                7 -> AudioTab(
                    audioEvents = audioEvents,
                    onClearEvents = { audioEvents.clear() }
                )
                8 -> PodcastTab()
                9 -> SettingsTab()
            }
        }
    }

    @Composable
    fun StatusBar() {
        val statusColor by animateColorAsState(
            targetValue = when {
                connectedDevices.isNotEmpty() -> Color(0xFF66BB6A)  // Softer green
                serverStatus.value == "Advertising" -> Color(0xFF42A5F5)  // Softer blue
                isServerRunning.value -> Color(0xFFFFCA28)  // Softer yellow
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
                text = serverStatus.value,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            if (connectedDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "${connectedDevices.size} device(s)",
                    color = Color.White
                )
            }
        }
    }
}