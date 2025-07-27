package com.paulcity.nocturnecompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.paulcity.nocturnecompanion.ble.DebugLogger
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.MediaStoreAlbumArtManager
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.services.NocturneService
import com.paulcity.nocturnecompanion.ui.theme.NocturneCompanionTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.*
import android.media.session.MediaSessionManager
import android.media.MediaMetadata

@SuppressLint("MissingPermission")
class DebugActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DebugActivity"
        private const val ACTION_SEND_BLE_STATE = "com.paulcity.nocturnecompanion.SEND_BLE_STATE"
        private const val ACTION_SEND_BLE_TIME_SYNC = "com.paulcity.nocturnecompanion.SEND_BLE_TIME_SYNC"
        private const val EXTRA_STATE_UPDATE = "state_update"
        private const val EXTRA_TIME_SYNC = "time_sync"
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isReceiverRegistered = false
    private val gson = Gson()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var mediaStoreAlbumArtManager: MediaStoreAlbumArtManager
    
    // State variables
    private val serverStatus = mutableStateOf("Disconnected")
    private val isServerRunning = mutableStateOf(false)
    private val connectedDevices = mutableStateListOf<EnhancedBleServerManager.DeviceInfo>()
    private val debugLogs = mutableStateListOf<DebugLogger.DebugLogEntry>()
    private val lastCommand = mutableStateOf<String?>(null)
    private val lastStateUpdate = mutableStateOf<StateUpdate?>(null)
    private val albumArtInfo = mutableStateOf<AlbumArtInfo?>(null)
    
    data class AlbumArtInfo(
        val hasArt: Boolean,
        val checksum: String? = null,
        val size: Int = 0,
        val lastQuery: String? = null,
        val lastTransferTime: Long? = null,
        val bitmap: android.graphics.Bitmap? = null
    )
    
    // UI state
    private val selectedTab = mutableStateOf(0)
    private val autoScrollLogs = mutableStateOf(true)
    private val logFilter = mutableStateOf(BleConstants.DebugLevel.VERBOSE)
    
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
                    when {
                        logEntry.message.contains("album_art_query") -> {
                            // Parse the query to get checksum
                            try {
                                val queryData = JsonParser.parseString(logEntry.data?.get("data") as? String ?: "{}")
                                val checksum = queryData.asJsonObject.get("checksum")?.asString
                                albumArtInfo.value = albumArtInfo.value?.copy(
                                    lastQuery = checksum
                                ) ?: AlbumArtInfo(false, lastQuery = checksum)
                            } catch (e: Exception) {
                                // Ignore parse errors
                            }
                        }
                        logEntry.message.contains("album_art_start") -> {
                            try {
                                val data = JsonParser.parseString(logEntry.data?.get("data") as? String ?: "{}")
                                val size = data.asJsonObject.get("size")?.asInt ?: 0
                                val checksum = data.asJsonObject.get("checksum")?.asString
                                albumArtInfo.value = AlbumArtInfo(
                                    hasArt = true,
                                    checksum = checksum,
                                    size = size,
                                    lastQuery = albumArtInfo.value?.lastQuery,
                                    lastTransferTime = System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                                // Ignore parse errors
                            }
                        }
                        logEntry.message.contains("album_art_end") -> {
                            albumArtInfo.value = albumArtInfo.value?.copy(
                                lastTransferTime = System.currentTimeMillis()
                            )
                        }
                        logEntry.message.contains("No album art available") -> {
                            albumArtInfo.value = AlbumArtInfo(hasArt = false)
                        }
                    }
                    
                    // Keep only last 500 logs
                    while (debugLogs.size > 500) {
                        debugLogs.removeAt(0)
                    }
                }
                NocturneServiceBLE.ACTION_COMMAND_RECEIVED -> {
                    lastCommand.value = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA)
                }
                NocturneServiceBLE.ACTION_STATE_UPDATED -> {
                    val json = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA) ?: return
                    lastStateUpdate.value = gson.fromJson(json, StateUpdate::class.java)
                    
                    // Try to get album art when state updates
                    serviceScope.launch {
                        updateAlbumArtFromMediaSession()
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startNocturneService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        mediaStoreAlbumArtManager = MediaStoreAlbumArtManager(this)
        
        setContent {
            NocturneCompanionTheme {
                DebugScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        requestNotificationListenerAccess()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(NocturneServiceBLE.ACTION_SERVER_STATUS)
                addAction(NocturneServiceBLE.ACTION_CONNECTED_DEVICES)
                addAction(NocturneServiceBLE.ACTION_DEBUG_LOG)
                addAction(NocturneServiceBLE.ACTION_COMMAND_RECEIVED)
                addAction(NocturneServiceBLE.ACTION_STATE_UPDATED)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiver() {
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
            isReceiverRegistered = false
        }
    }

    private fun requestNotificationListenerAccess() {
        if (!isNotificationServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun startNocturneService() {
        val serviceIntent = Intent(this, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopNocturneService() {
        val serviceIntent = Intent(this, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_STOP
        }
        startService(serviceIntent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DebugScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                // Header
                TopAppBar(
                    title = { Text("Nocturne BLE Debug") },
                    actions = {
                        IconButton(
                            onClick = {
                                if (isServerRunning.value) {
                                    stopNocturneService()
                                } else {
                                    requestPermissionsAndStart()
                                }
                            }
                        ) {
                            Icon(
                                if (isServerRunning.value) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = if (isServerRunning.value) "Stop" else "Start"
                            )
                        }
                    }
                )
                
                // Status bar
                StatusBar()
                
                // Tabs
                TabRow(selectedTabIndex = selectedTab.value) {
                    Tab(
                        selected = selectedTab.value == 0,
                        onClick = { selectedTab.value = 0 },
                        text = { Text("Devices") }
                    )
                    Tab(
                        selected = selectedTab.value == 1,
                        onClick = { selectedTab.value = 1 },
                        text = { Text("Logs") }
                    )
                    Tab(
                        selected = selectedTab.value == 2,
                        onClick = { selectedTab.value = 2 },
                        text = { Text("Commands") }
                    )
                    Tab(
                        selected = selectedTab.value == 3,
                        onClick = { selectedTab.value = 3 },
                        text = { Text("State") }
                    )
                    Tab(
                        selected = selectedTab.value == 4,
                        onClick = { selectedTab.value = 4 },
                        text = { Text("Playback") }
                    )
                }
                
                // Content
                when (selectedTab.value) {
                    0 -> DevicesTab()
                    1 -> LogsTab()
                    2 -> CommandsTab()
                    3 -> StateTab()
                    4 -> PlaybackTab()
                }
            }
        }
    }

    @Composable
    fun StatusBar() {
        val statusColor by animateColorAsState(
            targetValue = when {
                connectedDevices.isNotEmpty() -> Color(0xFF4CAF50)
                serverStatus.value == "Advertising" -> Color(0xFF2196F3)
                isServerRunning.value -> Color(0xFFFFC107)
                else -> Color(0xFFFF5252)
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
                Spacer(modifier = Modifier.width(8.dp))
                Badge(
                    modifier = Modifier.background(
                        Color.White.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp)
                    ).padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${connectedDevices.size} connected",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    @Composable
    fun DevicesTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (connectedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No devices connected",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(connectedDevices) { device ->
                    DeviceCard(device = device)
                }
            }
        }
    }

    @Composable
    fun DeviceCard(device: EnhancedBleServerManager.DeviceInfo) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoChip("MTU: ${device.mtu}")
                    InfoChip("${formatDuration(device.connectionDuration)}")
                }
                
                if (device.subscriptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Subscriptions:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
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
    }

    @Composable
    fun InfoChip(text: String) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    @Composable
    fun SubscriptionChip(text: String) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp
            )
        }
    }

    @Composable
    fun LogsTab() {
        val listState = rememberLazyListState()
        
        Column(modifier = Modifier.fillMaxSize()) {
            // Log controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter dropdown
                LogFilterDropdown()
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Auto-scroll toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            autoScrollLogs.value = !autoScrollLogs.value
                        }
                    ) {
                        Checkbox(
                            checked = autoScrollLogs.value,
                            onCheckedChange = { autoScrollLogs.value = it }
                        )
                        Text("Auto-scroll", style = MaterialTheme.typography.labelMedium)
                    }
                    
                    // Clear button
                    TextButton(
                        onClick = { debugLogs.clear() }
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
            
            HorizontalDivider()
            
            // Log list
            val filteredLogs = debugLogs.filter { it.level.ordinal >= logFilter.value.ordinal }
            
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filteredLogs) { log ->
                    LogEntry(log)
                }
            }
            
            // Auto-scroll
            LaunchedEffect(filteredLogs.size) {
                if (autoScrollLogs.value && filteredLogs.isNotEmpty()) {
                    listState.animateScrollToItem(filteredLogs.size - 1)
                }
            }
        }
    }

    @Composable
    fun LogFilterDropdown() {
        var expanded by remember { mutableStateOf(false) }
        
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.width(120.dp)
            ) {
                Text(
                    text = logFilter.value.name,
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                BleConstants.DebugLevel.values().forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.name) },
                        onClick = {
                            logFilter.value = level
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun LogEntry(log: DebugLogger.DebugLogEntry) {
        val backgroundColor = when (log.level) {
            BleConstants.DebugLevel.ERROR -> Color(0xFFFFEBEE)
            BleConstants.DebugLevel.WARNING -> Color(0xFFFFF3E0)
            BleConstants.DebugLevel.INFO -> Color(0xFFE3F2FD)
            BleConstants.DebugLevel.DEBUG -> Color(0xFFF5F5F5)
            BleConstants.DebugLevel.VERBOSE -> Color.Transparent
        }
        
        val textColor = when (log.level) {
            BleConstants.DebugLevel.ERROR -> Color(0xFFB71C1C)
            BleConstants.DebugLevel.WARNING -> Color(0xFFE65100)
            BleConstants.DebugLevel.INFO -> Color(0xFF0D47A1)
            BleConstants.DebugLevel.DEBUG -> Color(0xFF424242)
            BleConstants.DebugLevel.VERBOSE -> Color(0xFF757575)
        }
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = backgroundColor,
            shape = RoundedCornerShape(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "[${log.level.name.first()}] ${log.type}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
                
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    fontFamily = FontFamily.Monospace
                )
                
                log.data?.let { data ->
                    Text(
                        text = gson.toJson(data),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun CommandsTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Last Command",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (lastCommand.value != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = formatJson(lastCommand.value ?: ""),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(
                            text = "No commands received yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun StateTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Current Media State",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    lastStateUpdate.value?.let { state ->
                        StateRow("Artist", state.artist ?: "Unknown")
                        StateRow("Album", state.album ?: "Unknown")
                        StateRow("Track", state.track ?: "Unknown")
                        StateRow("Playing", if (state.is_playing) "Yes" else "No")
                        StateRow("Position", "${state.position_ms / 1000}s / ${state.duration_ms / 1000}s")
                        StateRow("Volume", "${state.volume_percent}%")
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Album Art Section
                        Text(
                            text = "Album Art Status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        albumArtInfo.value?.let { artInfo ->
                            // Display album art image if available
                            artInfo.bitmap?.let { bitmap ->
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Album Art",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .padding(8.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            StateRow("Has Album Art", if (artInfo.hasArt) "Yes" else "No")
                            artInfo.checksum?.let { StateRow("Checksum", it.take(16) + "...") }
                            if (artInfo.size > 0) StateRow("Size", "${artInfo.size / 1024} KB")
                            artInfo.lastQuery?.let { StateRow("Last Query", it.take(16) + "...") }
                            artInfo.lastTransferTime?.let { 
                                val timeAgo = (System.currentTimeMillis() - it) / 1000
                                StateRow("Last Transfer", "${timeAgo}s ago")
                            }
                        } ?: run {
                            Text(
                                text = "No album art data yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } ?: run {
                        Text(
                            text = "No state updates yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun StateRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
    
    @Composable
    fun PlaybackTab() {
        // Track current playback state
        var currentPosition by remember { mutableStateOf(0L) }
        var isPlaying by remember { mutableStateOf(false) }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Playback State Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Playback State Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Play/Pause buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isPlaying = true
                                sendPlaybackStateUpdate(true, currentPosition)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isPlaying
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                        
                        Button(
                            onClick = {
                                isPlaying = false
                                sendPlaybackStateUpdate(false, currentPosition)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = isPlaying
                        ) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Status: ${if (isPlaying) "Playing" else "Paused"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPlaying) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Position/Seek Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Position Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Current Position: ${currentPosition / 1000}s")
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Quick position buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                currentPosition = 0
                                sendPlaybackStateUpdate(isPlaying, 0)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("0s")
                        }
                        
                        Button(
                            onClick = {
                                currentPosition = 30000
                                sendPlaybackStateUpdate(isPlaying, 30000)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("30s")
                        }
                        
                        Button(
                            onClick = {
                                currentPosition = 60000
                                sendPlaybackStateUpdate(isPlaying, 60000)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("1m")
                        }
                        
                        Button(
                            onClick = {
                                currentPosition = 300000
                                sendPlaybackStateUpdate(isPlaying, 300000)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("5m")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Custom position slider
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { currentPosition = it.toLong() },
                        valueRange = 0f..600000f, // 0 to 10 minutes
                        modifier = Modifier.fillMaxWidth(),
                        onValueChangeFinished = {
                            sendPlaybackStateUpdate(isPlaying, currentPosition)
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // System Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "System Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Time Sync Button
                    Button(
                        onClick = { sendTimeSync() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Time Sync")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Send Full State Button (with test data)
                    Button(
                        onClick = { 
                            sendFullStateUpdate(
                                isPlaying = isPlaying,
                                positionMs = currentPosition,
                                durationMs = 300000, // 5 min
                                artist = "Debug Artist",
                                album = "Debug Album",
                                track = "Debug Track",
                                volumePercent = 75
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Full State (Test Data)")
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startNocturneService()
        }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000 / 60) % 60
        val hours = millis / 1000 / 60 / 60
        
        return when {
            hours > 0 -> String.format("%dh %dm", hours, minutes)
            minutes > 0 -> String.format("%dm %ds", minutes, seconds)
            else -> String.format("%ds", seconds)
        }
    }

    private fun formatJson(json: String): String {
        return try {
            val element = JsonParser.parseString(json)
            gson.newBuilder().setPrettyPrinting().create().toJson(element)
        } catch (e: Exception) {
            json
        }
    }
    
    private fun sendPlaybackStateUpdate(isPlaying: Boolean, positionMs: Long) {
        // Get stored values for track info from lastStateUpdate or use defaults
        val lastState = lastStateUpdate.value
        val stateUpdate = StateUpdate(
            artist = lastState?.artist,
            album = lastState?.album,
            track = lastState?.track,
            duration_ms = lastState?.duration_ms ?: 300000, // Default 5 min
            position_ms = positionMs,
            is_playing = isPlaying,
            volume_percent = lastState?.volume_percent ?: 50
        )
        
        // Send the state update through broadcast to service
        val intent = Intent(NocturneService.ACTION_DEBUG_STATE_UPDATE)
        intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(stateUpdate))
        sendBroadcast(intent)
        
        // Update local state
        lastStateUpdate.value = stateUpdate
        
        // Log it
        val logEntry = DebugLogger.DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = BleConstants.DebugLevel.INFO,
            type = "PLAYBACK_UPDATE",
            message = "Sent playback state update",
            data = mapOf(
                "is_playing" to isPlaying.toString(),
                "position_ms" to positionMs.toString()
            )
        )
        debugLogs.add(logEntry)
    }
    
    private fun sendFullStateUpdate(
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        artist: String,
        album: String,
        track: String,
        volumePercent: Int
    ) {
        val stateUpdate = StateUpdate(
            artist = artist,
            album = album,
            track = track,
            duration_ms = durationMs,
            position_ms = positionMs,
            is_playing = isPlaying,
            volume_percent = volumePercent
        )
        
        // Send the state update through broadcast to service
        val intent = Intent(NocturneService.ACTION_DEBUG_STATE_UPDATE)
        intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(stateUpdate))
        sendBroadcast(intent)
        
        // Update local state
        lastStateUpdate.value = stateUpdate
        
        // Log it
        val logEntry = DebugLogger.DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = BleConstants.DebugLevel.INFO,
            type = "FULL_STATE_UPDATE",
            message = "Sent full state update",
            data = mapOf(
                "state" to gson.toJson(stateUpdate)
            )
        )
        debugLogs.add(logEntry)
    }
    
    private fun sendTimeSync() {
        val timeSyncData = mapOf(
            "type" to "time_sync",
            "timestamp" to System.currentTimeMillis()
        )
        
        // Send time sync through broadcast to service
        val intent = Intent(NocturneService.ACTION_DEBUG_TIME_SYNC)
        intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(timeSyncData))
        sendBroadcast(intent)
        
        // Log it
        val logEntry = DebugLogger.DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = BleConstants.DebugLevel.INFO,
            type = "TIME_SYNC",
            message = "Sent time sync",
            data = timeSyncData
        )
        debugLogs.add(logEntry)
    }
    
    private fun sendActualTrackInfo() {
        // Get the last known state from the service
        val currentState = lastStateUpdate.value
        
        if (currentState == null || (currentState.artist == null && currentState.track == null)) {
            // No track info available
            val noTrackData = mapOf(
                "type" to "stateUpdate",
                "error" to "No active media session",
                "timestamp" to System.currentTimeMillis()
            )
            
            val intent = Intent(NocturneService.ACTION_DEBUG_STATE_UPDATE)
            intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(noTrackData))
            sendBroadcast(intent)
            
            debugLogs.add(DebugLogger.DebugLogEntry(
                timestamp = System.currentTimeMillis(),
                level = BleConstants.DebugLevel.WARNING,
                type = "TRACK_INFO",
                message = "No track info available",
                data = noTrackData
            ))
            return
        }
        
        // Send the actual current media state
        val intent = Intent(NocturneService.ACTION_DEBUG_STATE_UPDATE)
        intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(currentState))
        sendBroadcast(intent)
        
        debugLogs.add(DebugLogger.DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = BleConstants.DebugLevel.INFO,
            type = "TRACK_INFO",
            message = "Sent actual track info",
            data = mapOf(
                "artist" to (currentState.artist ?: "Unknown"),
                "track" to (currentState.track ?: "Unknown"),
                "album" to (currentState.album ?: "Unknown"),
                "duration_ms" to currentState.duration_ms,
                "position_ms" to currentState.position_ms,
                "is_playing" to currentState.is_playing
            )
        ))
    }
    
    private fun sendSystemTime() {
        val systemTimeData = mapOf(
            "type" to "system_time",
            "timestamp_ms" to System.currentTimeMillis(),
            "timezone" to TimeZone.getDefault().id,
            "timezone_offset" to TimeZone.getDefault().rawOffset,
            "formatted_time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        )
        
        // Send system time through broadcast to service
        val intent = Intent(NocturneService.ACTION_DEBUG_TIME_SYNC)
        intent.putExtra(NocturneService.EXTRA_JSON_DATA, gson.toJson(systemTimeData))
        sendBroadcast(intent)
        
        // Log it
        debugLogs.add(DebugLogger.DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = BleConstants.DebugLevel.INFO,
            type = "SYSTEM_TIME",
            message = "Sent system time",
            data = systemTimeData
        ))
    }
    
    private suspend fun updateAlbumArtFromMediaSession() {
        try {
            // Use the current state update to get artist/album info
            val state = lastStateUpdate.value ?: return
            
            // Try to get album art using MediaStore
            val albumArtResult = mediaStoreAlbumArtManager.getAlbumArtFromMediaStore(
                artist = state.artist,
                album = state.album,
                title = state.track
            )
            
            if (albumArtResult != null) {
                val (artData, checksum) = albumArtResult
                // Convert WebP data back to bitmap for display
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(artData, 0, artData.size)
                
                albumArtInfo.value = AlbumArtInfo(
                    hasArt = true,
                    checksum = checksum,
                    size = artData.size,
                    bitmap = bitmap,
                    lastTransferTime = albumArtInfo.value?.lastTransferTime
                )
                
                Log.d("DebugActivity", "Album art loaded from MediaStore: ${artData.size} bytes")
            } else {
                // Try MediaSession as fallback
                val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = mediaSessionManager.getActiveSessions(ComponentName(this, com.paulcity.nocturnecompanion.services.NocturneNotificationListener::class.java))
                
                if (controllers.isNotEmpty()) {
                    val metadata = controllers[0].metadata
                    if (metadata != null) {
                        // Try to get album art bitmap
                        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                        
                        if (albumArt != null) {
                            albumArtInfo.value = AlbumArtInfo(
                                hasArt = true,
                                bitmap = albumArt,
                                lastTransferTime = albumArtInfo.value?.lastTransferTime
                            )
                            Log.d("DebugActivity", "Album art loaded from MediaSession")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DebugActivity", "Error getting album art", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}