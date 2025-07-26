package com.paulcity.nocturnecompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.gson.JsonParser
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.theme.NocturneCompanionTheme

sealed class ArtworkResult {
    data class Success(val bitmap: Bitmap, val sizeBytes: Int) : ArtworkResult()
    data class Error(val message: String) : ArtworkResult()
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

   private lateinit var bluetoothAdapter: BluetoothAdapter
   private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
   private var isReceiverRegistered = false

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
               Log.w("MainActivity", "Required Bluetooth permissions were denied. Connect: $hasConnect, Advertise: $hasAdvertise")
           }
       }
   
   // --- NEW ---
   private val lastCommand = mutableStateOf("No commands received yet.")
   private val lastStateUpdate = mutableStateOf("No state updates yet.")
   private val serverStatus = mutableStateOf("Disconnected")
   private val isServerRunning = mutableStateOf(false)
   private val notifications = mutableStateOf(listOf<String>())

   private val receiver = object : BroadcastReceiver() {
       override fun onReceive(context: Context?, intent: Intent?) {
           Log.d("MainActivity", "BroadcastReceiver.onReceive called with action: ${intent?.action}")
           when (intent?.action) {
               NocturneServiceBLE.ACTION_COMMAND_RECEIVED -> {
                   val commandData = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA) ?: "Error reading command"
                   Log.d("MainActivity", "Command received: $commandData")
                   lastCommand.value = commandData
               }
               NocturneServiceBLE.ACTION_STATE_UPDATED -> {
                   val stateData = intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA) ?: "Error reading state"
                   Log.d("MainActivity", "State update received: $stateData")
                   lastStateUpdate.value = stateData
               }
               NocturneServiceBLE.ACTION_SERVER_STATUS -> {
                   val status = intent.getStringExtra(NocturneServiceBLE.EXTRA_SERVER_STATUS) ?: "Unknown"
                   val running = intent.getBooleanExtra(NocturneServiceBLE.EXTRA_IS_RUNNING, false)
                   Log.d("MainActivity", "Server status broadcast received: $status, Running: $running")
                   serverStatus.value = status
                   isServerRunning.value = running
               }
               NocturneServiceBLE.ACTION_NOTIFICATION -> {
                   val message = intent.getStringExtra(NocturneServiceBLE.EXTRA_NOTIFICATION_MESSAGE) ?: "Unknown notification"
                   val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                   val formattedMessage = "[$timestamp] $message"
                   Log.d("MainActivity", "Notification broadcast received: $message")
                   
                   val currentList = notifications.value.toMutableList()
                   currentList.add(0, formattedMessage) // Add to beginning of list
                   
                   // Keep only last 50 notifications to avoid memory issues
                   if (currentList.size > 50) {
                       notifications.value = currentList.subList(0, 50)
                   } else {
                       notifications.value = currentList
                   }
               }
               else -> {
                   Log.w("MainActivity", "Unknown broadcast action received: ${intent?.action}")
               }
           }
       }
   }
   // --- END NEW ---

   override fun onCreate(savedInstanceState: Bundle?) {
       super.onCreate(savedInstanceState)
       
       try {
           val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
           if (bluetoothManager?.adapter == null) {
               Log.e("MainActivity", "Bluetooth not supported on this device")
               // You could show an error dialog here
               finish()
               return
           }
           bluetoothAdapter = bluetoothManager.adapter

           // --- NEW ---
           val filter = IntentFilter().apply {
               addAction(NocturneServiceBLE.ACTION_COMMAND_RECEIVED)
               addAction(NocturneServiceBLE.ACTION_STATE_UPDATED)
               addAction(NocturneServiceBLE.ACTION_SERVER_STATUS)
               addAction(NocturneServiceBLE.ACTION_NOTIFICATION)
           }
           
           // Use LocalBroadcastManager for more reliable delivery on Android 14+
           if (Build.VERSION.SDK_INT >= 34) {
               LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
               Log.d("MainActivity", "Registered LocalBroadcastManager receiver for Android 14+")
           } else if (Build.VERSION.SDK_INT >= 33) {
               registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
               Log.d("MainActivity", "Registered system receiver with RECEIVER_NOT_EXPORTED for Android 13+")
           } else {
               registerReceiver(receiver, filter)
               Log.d("MainActivity", "Registered system receiver for Android 12-")
           }
           isReceiverRegistered = true
           // --- END NEW ---
       } catch (e: Exception) {
           Log.e("MainActivity", "Error initializing Bluetooth", e)
           finish()
           return
       }

       setContent {
           NocturneCompanionTheme {
               MainScreen(
                   devices = discoveredDevices,
                   onStartScan = { checkPermissionsAndScan() },
                   onStartServer = { requestBluetoothConnectPermission() },
                   onStopServer = { stopNocturneService() },
                   serverStatus = serverStatus.value,
                   isServerRunning = isServerRunning.value,
                   notifications = notifications.value,
                   onClearNotifications = { notifications.value = listOf() },
                   onUploadAlbumArt = { uploadAlbumArt() },
                   // --- NEW ---
                   lastCommandJson = lastCommand.value,
                   lastStateUpdateJson = lastStateUpdate.value
                   // --- END NEW ---
               )
           }
       }
   }

   // --- NEW ---
   private fun testBroadcastSystem() {
       Log.d("MainActivity", "Testing broadcast system...")
       val testIntent = Intent("TEST_BROADCAST_ACTION")
       testIntent.putExtra("test_data", "Hello from MainActivity")
       
       if (Build.VERSION.SDK_INT >= 34) {
           LocalBroadcastManager.getInstance(this).sendBroadcast(testIntent)
           Log.d("MainActivity", "Test broadcast sent via LocalBroadcastManager")
       } else {
           sendBroadcast(testIntent)
           Log.d("MainActivity", "Test broadcast sent via system")
       }
   }

   override fun onDestroy() {
       super.onDestroy()
       if (isReceiverRegistered) {
           try {
               if (Build.VERSION.SDK_INT >= 34) {
                   LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
                   Log.d("MainActivity", "Unregistered LocalBroadcastManager receiver")
               } else {
                   unregisterReceiver(receiver)
                   Log.d("MainActivity", "Unregistered system receiver")
               }
               isReceiverRegistered = false
           } catch (e: IllegalArgumentException) {
               Log.w("MainActivity", "Receiver was not registered", e)
           }
       }
   }
   // --- END NEW ---

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

   private fun requestBluetoothConnectPermission() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d("MainActivity", "Requesting Bluetooth permissions for API ${Build.VERSION.SDK_INT}")
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        // Add media permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (permissions.isNotEmpty()) {
            startServicePermissionLauncher.launch(permissions.toTypedArray())
        } else {
            Log.d("MainActivity", "No special permissions needed for API ${Build.VERSION.SDK_INT}")
            startNocturneService()
        }
    }

   private fun scanForDevices() {
       try {
           discoveredDevices.clear()
           if (::bluetoothAdapter.isInitialized) {
               discoveredDevices.addAll(bluetoothAdapter.bondedDevices ?: emptySet())
           }
       } catch (e: SecurityException) {
           Log.e("MainActivity", "Permission denied for Bluetooth access", e)
       } catch (e: Exception) {
           Log.e("MainActivity", "Error scanning for devices", e)
       }
   }

   private fun startNocturneService() {
       Log.d("MainActivity", "Starting NocturneServiceBLE on Android API ${Build.VERSION.SDK_INT}...")
       try {
           val intent = Intent(this, NocturneServiceBLE::class.java).apply {
               action = NocturneServiceBLE.ACTION_START
           }
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
               startForegroundService(intent)
               Log.d("MainActivity", "Foreground service start requested for API ${Build.VERSION.SDK_INT}")
           } else {
               startService(intent)
               Log.d("MainActivity", "Service start requested for API ${Build.VERSION.SDK_INT}")
           }
           
           // Give some time for service to start, then check broadcast system
           android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
               testBroadcastSystem()
           }, 2000)
       } catch (e: Exception) {
           Log.e("MainActivity", "Failed to start NocturneServiceBLE", e)
       }
   }

   private fun stopNocturneService() {
       Log.d("MainActivity", "Stopping NocturneServiceBLE...")
       try {
           val intent = Intent(this, NocturneServiceBLE::class.java).apply {
               action = NocturneServiceBLE.ACTION_STOP
           }
           startService(intent)
           Log.d("MainActivity", "Service stop requested")
       } catch (e: Exception) {
           Log.e("MainActivity", "Failed to stop NocturneServiceBLE", e)
       }
   }
   
   private fun uploadAlbumArt() {
       Log.d("MainActivity", "Album art upload not supported in BLE version")
       // Album art upload removed in BLE-only implementation
   }
}

@Composable
fun MainScreen(
   devices: List<BluetoothDevice>,
   onStartScan: () -> Unit,
   onStartServer: () -> Unit,
   onStopServer: () -> Unit,
   serverStatus: String,
   isServerRunning: Boolean,
   notifications: List<String>,
   onClearNotifications: () -> Unit,
   onUploadAlbumArt: () -> Unit,
   lastCommandJson: String,
   lastStateUpdateJson: String
) {
   var selectedTabIndex by remember { mutableStateOf(0) }
   val tabTitles = listOf("Main", "Debug Data", "Devices")

   Column(
       modifier = Modifier.fillMaxSize()
   ) {
       Text(
           "Nocturne Companion",
           style = MaterialTheme.typography.headlineMedium,
           modifier = Modifier.padding(16.dp)
       )
       
       TabRow(selectedTabIndex = selectedTabIndex) {
           tabTitles.forEachIndexed { index, title ->
               Tab(
                   selected = selectedTabIndex == index,
                   onClick = { selectedTabIndex = index },
                   text = { Text(title) }
               )
           }
       }
       
       when (selectedTabIndex) {
           0 -> MainTab(
               onStartServer = onStartServer,
               onStopServer = onStopServer,
               serverStatus = serverStatus,
               isServerRunning = isServerRunning,
               notifications = notifications,
               onClearNotifications = onClearNotifications,
               onUploadAlbumArt = onUploadAlbumArt
           )
           1 -> DebugTab(lastCommandJson, lastStateUpdateJson)
           2 -> DevicesTab(devices, onStartScan)
       }
   }
}

@Composable
fun MainTab(
   onStartServer: () -> Unit,
   onStopServer: () -> Unit,
   serverStatus: String,
   isServerRunning: Boolean,
   notifications: List<String>,
   onClearNotifications: () -> Unit,
   onUploadAlbumArt: () -> Unit
) {
   Column(
       modifier = Modifier
           .fillMaxSize()
           .padding(16.dp),
       horizontalAlignment = Alignment.CenterHorizontally
   ) {
       NotificationListenerPermissionChecker()
       Spacer(modifier = Modifier.height(16.dp))
       
       ServerStatusPanel(
           serverStatus = serverStatus,
           isServerRunning = isServerRunning,
           onStartServer = onStartServer,
           onStopServer = onStopServer
       )

       Spacer(modifier = Modifier.height(16.dp))
       
       // Album Art Upload Button
       Button(
           onClick = onUploadAlbumArt,
           modifier = Modifier.fillMaxWidth(),
           colors = ButtonDefaults.buttonColors(
               containerColor = MaterialTheme.colorScheme.secondary
           )
       ) {
           Text("Upload Current Album Art")
       }
       
       Spacer(modifier = Modifier.height(16.dp))
       
       NotificationsPanel(
           notifications = notifications,
           onClearNotifications = onClearNotifications
       )
   }
}

@Composable
fun DevicesTab(devices: List<BluetoothDevice>, onStartScan: () -> Unit) {
   Column(
       modifier = Modifier
           .fillMaxSize()
           .padding(16.dp),
       horizontalAlignment = Alignment.CenterHorizontally
   ) {
       Button(onClick = onStartScan) {
           Text("Show Paired Devices")
       }
       Spacer(modifier = Modifier.height(16.dp))

       LazyColumn(modifier = Modifier.fillMaxWidth()) {
           items(devices) { device ->
               DeviceItem(device = device)
           }
       }
   }
}

// --- NEW ---
@Composable
fun ServerStatusPanel(
   serverStatus: String,
   isServerRunning: Boolean,
   onStartServer: () -> Unit,
   onStopServer: () -> Unit
) {
   Card(
       modifier = Modifier.fillMaxWidth(),
       colors = CardDefaults.cardColors(
           containerColor = if (isServerRunning) 
               MaterialTheme.colorScheme.primaryContainer 
           else 
               MaterialTheme.colorScheme.surfaceVariant
       )
   ) {
       Column(
           modifier = Modifier.padding(16.dp),
           horizontalAlignment = Alignment.CenterHorizontally
       ) {
           Text(
               "BLE Server Status",
               style = MaterialTheme.typography.titleMedium
           )
           
           Text(
               serverStatus,
               style = MaterialTheme.typography.bodyLarge,
               fontFamily = FontFamily.Monospace,
               modifier = Modifier.padding(vertical = 8.dp)
           )
           
           if (isServerRunning) {
               Button(
                   onClick = onStopServer,
                   modifier = Modifier.fillMaxWidth(),
                   colors = ButtonDefaults.buttonColors(
                       containerColor = MaterialTheme.colorScheme.error
                   )
               ) {
                   Text("Stop BLE Server")
               }
           } else {
               Button(
                   onClick = onStartServer,
                   modifier = Modifier.fillMaxWidth()
               ) {
                   Text("Start BLE Server")
               }
           }
       }
   }
}

@Composable
fun NotificationsPanel(
   notifications: List<String>,
   onClearNotifications: () -> Unit
) {
   Card(
       modifier = Modifier.fillMaxWidth()
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
                   "Service Notifications",
                   style = MaterialTheme.typography.titleMedium
               )
               
               if (notifications.isNotEmpty()) {
                   Button(
                       onClick = onClearNotifications,
                       modifier = Modifier.padding(start = 8.dp),
                       colors = ButtonDefaults.buttonColors(
                           containerColor = MaterialTheme.colorScheme.secondary
                       )
                   ) {
                       Text("Clear", style = MaterialTheme.typography.bodySmall)
                   }
               }
           }
           
           Spacer(modifier = Modifier.height(8.dp))
           
           if (notifications.isEmpty()) {
               Text(
                   "No notifications yet...",
                   style = MaterialTheme.typography.bodyMedium,
                   color = MaterialTheme.colorScheme.onSurfaceVariant,
                   modifier = Modifier
                       .fillMaxWidth()
                       .padding(8.dp)
               )
           } else {
               LazyColumn(
                   modifier = Modifier
                       .fillMaxWidth()
                       .height(120.dp)
                       .background(
                           MaterialTheme.colorScheme.surfaceVariant,
                           RoundedCornerShape(8.dp)
                       )
                       .padding(8.dp),
                   verticalArrangement = Arrangement.spacedBy(2.dp)
               ) {
                   items(notifications) { notification ->
                       Text(
                           text = notification,
                           style = MaterialTheme.typography.bodySmall,
                           fontFamily = FontFamily.Monospace,
                           fontSize = 11.sp,
                           lineHeight = 14.sp
                       )
                   }
               }
           }
       }
   }
}

@Composable
fun DebugTab(command: String, state: String) {
   Column(
       modifier = Modifier
           .fillMaxSize()
           .padding(16.dp)
           .verticalScroll(rememberScrollState())
   ) {
       Text("Live Service Data", style = MaterialTheme.typography.titleLarge)
       
       Spacer(modifier = Modifier.height(16.dp))
       
       // Last command received from Nocturne
       Card(modifier = Modifier.fillMaxWidth()) {
           Column(modifier = Modifier.padding(16.dp)) {
               Text("Last Command Received:", style = MaterialTheme.typography.titleMedium)
               Spacer(modifier = Modifier.height(8.dp))
               Text(
                   text = command,
                   fontFamily = FontFamily.Monospace,
                   fontSize = 12.sp,
                   modifier = Modifier
                       .fillMaxWidth()
                       .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                       .padding(8.dp)
               )
           }
       }
       
       Spacer(modifier = Modifier.height(16.dp))
       
       // Last state update sent to Nocturne with track art
       Card(modifier = Modifier.fillMaxWidth()) {
           Column(modifier = Modifier.padding(16.dp)) {
               Text("Last State Update Sent:", style = MaterialTheme.typography.titleMedium)
               Spacer(modifier = Modifier.height(8.dp))
               
               // Parse JSON to extract artwork if present
               val artwork = try {
                   val stateJson = JsonParser.parseString(state).asJsonObject
                   stateJson.get("artwork_base64")?.asString
               } catch (e: Exception) {
                   null
               }
               
               // Show artwork if available
               artwork?.let { base64String ->
                   if (base64String.isNotBlank()) {
                       Text("Track Artwork:", style = MaterialTheme.typography.bodyMedium)
                       Spacer(modifier = Modifier.height(8.dp))
                       
                       // Decode artwork outside of Composable scope
                       val artworkResult = remember(base64String) {
                           try {
                               val imageBytes = Base64.decode(base64String, Base64.DEFAULT)
                               val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                               ArtworkResult.Success(bitmap, imageBytes.size)
                           } catch (e: Exception) {
                               ArtworkResult.Error(e.message ?: "Unknown error")
                           }
                       }
                       
                       when (artworkResult) {
                           is ArtworkResult.Success -> {
                               Image(
                                   bitmap = artworkResult.bitmap.asImageBitmap(),
                                   contentDescription = "Track Artwork",
                                   modifier = Modifier
                                       .size(200.dp)
                                       .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                               )
                               
                               Spacer(modifier = Modifier.height(8.dp))
                               Text(
                                   "Image size: ${artworkResult.sizeBytes} bytes",
                                   style = MaterialTheme.typography.bodySmall,
                                   color = MaterialTheme.colorScheme.onSurfaceVariant
                               )
                               Spacer(modifier = Modifier.height(16.dp))
                           }
                           is ArtworkResult.Error -> {
                               Text(
                                   "Failed to decode artwork: ${artworkResult.message}",
                                   style = MaterialTheme.typography.bodySmall,
                                   color = MaterialTheme.colorScheme.error
                               )
                               Spacer(modifier = Modifier.height(8.dp))
                           }
                       }
                   }
               }
               
               Text("Full JSON Data:", style = MaterialTheme.typography.bodyMedium)
               Spacer(modifier = Modifier.height(4.dp))
               Text(
                   text = state,
                   fontFamily = FontFamily.Monospace,
                   fontSize = 11.sp,
                   modifier = Modifier
                       .fillMaxWidth()
                       .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                       .padding(8.dp)
               )
           }
       }
   }
}
// --- END NEW ---

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(device: BluetoothDevice) {
   Card(
       modifier = Modifier
           .fillMaxWidth()
           .padding(vertical = 4.dp)
   ) {
       Column(modifier = Modifier.padding(16.dp)) {
           Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
           Text(text = device.address, style = MaterialTheme.typography.bodySmall)
       }
   }
}

@Composable
fun NotificationListenerPermissionChecker() {
   val context = LocalContext.current
   var isEnabled by remember {
       mutableStateOf(
           Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
               ?.contains(context.packageName) == true
       )
   }

   if (!isEnabled) {
       Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
           Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
               Text("Permission Required", style = MaterialTheme.typography.titleMedium)
               Text(
                   "This app requires Notification Access to control media. Please enable it in settings.",
                   style = MaterialTheme.typography.bodyMedium
               )
               Spacer(Modifier.height(8.dp))
               Button(onClick = {
                   context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
               }) {
                   Text("Open Settings")
               }
           }
       }
   }
}