# NocturneCompanion - Reference Guide

## Overview

NocturneCompanion is an Android application that acts as a Bluetooth SPP (Serial Port Profile) server and media control bridge. It receives JSON commands from the nocturned service and controls Android media playback through the MediaSessionManager API.

## Technology Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Target SDK**: 34
- **Minimum SDK**: 26
- **Build System**: Gradle
- **Architecture**: MVVM with StateFlow
- **Concurrency**: Kotlin Coroutines

## Project Structure

```
/NocturneCompanion/
├── app/src/main/java/com/nocturne/companion/
│   ├── ui/
│   │   └── MainActivity.kt              # Main UI with debug interface
│   ├── services/
│   │   ├── NocturneService.kt          # Foreground service coordinator
│   │   ├── BluetoothServerManager.kt   # SPP server management
│   │   └── NocturneNotificationListener.kt # Media session monitoring
│   ├── data/
│   │   └── Models.kt                   # Command and state data models
│   └── AndroidManifest.xml             # Permissions and service declarations
├── app/build.gradle                     # Dependencies and build config
└── gradle/
    └── wrapper/                         # Gradle wrapper files
```

## Build Commands

### Development Build
```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Build and install in one step
./gradlew installDebug
```

### Release Build
```bash
# Build release APK
./gradlew assembleRelease

# Build signed release (requires keystore)
./gradlew assembleRelease -Pandroid.injected.signing.store.file=release.keystore
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests on device/emulator
./gradlew connectedAndroidTest

# Generate test coverage report
./gradlew createDebugCoverageReport
```

### Code Quality
```bash
# Run lint checks
./gradlew lint

# Generate lint report
./gradlew lintDebug

# Check for dependency updates
./gradlew dependencyUpdates
```

## Core Architecture

### NocturneService.kt
Foreground service that orchestrates media control functionality.

**Key Responsibilities**:
- Lifecycle management for Bluetooth server and notification listener
- Media controller observation and command execution
- Real-time state broadcasting to connected Car Thing devices
- Service notification management

**Service Lifecycle**:
```kotlin
class NocturneService : Service() {
    private lateinit var bluetoothManager: BluetoothServerManager
    private lateinit var notificationListener: NocturneNotificationListener
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        initializeBluetoothServer()
        observeMediaControllers()
        return START_STICKY // Restart if killed
    }
}
```

### BluetoothServerManager.kt
Manages SPP Bluetooth server socket and client connections.

**Key Features**:
- SPP server socket creation and management
- Client connection handling with coroutines
- Bidirectional JSON message communication
- Connection state monitoring and error recovery

**SPP Server Implementation**:
```kotlin
class BluetoothServerManager(private val context: Context) {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    
    suspend fun startServer() = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(
            "NocturneCompanionSPP", SPP_UUID
        )
        
        // Accept client connections
        while (isActive) {
            try {
                clientSocket = serverSocket?.accept()
                handleClientConnection(clientSocket)
            } catch (e: IOException) {
                // Handle connection errors
            }
        }
    }
}
```

### NocturneNotificationListener.kt
NotificationListenerService that monitors active media sessions.

**Key Features**:
- MediaSessionManager integration for detecting active media apps
- Real-time media metadata and playback state monitoring
- StateFlow-based state management for reactive updates
- Media controller callback handling

**Media Session Monitoring**:
```kotlin
class NocturneNotificationListener : NotificationListenerService() {
    private val _activeMediaController = MutableStateFlow<MediaController?>(null)
    val activeMediaController: StateFlow<MediaController?> = _activeMediaController
    
    override fun onCreate() {
        super.onCreate()
        observeActiveMediaSessions()
    }
    
    private fun observeActiveMediaSessions() {
        val sessionManager = getSystemService(MediaSessionManager::class.java)
        sessionManager.addOnActiveSessionsChangedListener(
            sessionListener, ComponentName(this, javaClass)
        )
    }
}
```

## Data Models

### Command Model
Represents incoming JSON commands from nocturned service.

```kotlin
data class Command(
    val command: String,                    // "play", "pause", "next", etc.
    @SerializedName("value_ms")
    val valueMs: Long? = null,              // For seek_to command
    @SerializedName("value_percent") 
    val valuePercent: Int? = null           // For set_volume command
)
```

**Supported Commands**:
- `play` - Start/resume media playback
- `pause` - Pause media playback
- `next` - Skip to next track
- `previous` - Skip to previous track
- `seek_to` - Seek to specific position (requires `value_ms`)
- `set_volume` - Set media volume (requires `value_percent`)

### StateUpdate Model
Represents outgoing JSON state updates sent to nocturned service.

```kotlin
data class StateUpdate(
    val type: String = "stateUpdate",       // Always "stateUpdate"
    val artist: String?,                    // Artist name
    val album: String?,                     // Album name
    val track: String?,                     // Track title
    @SerializedName("duration_ms")
    val durationMs: Long,                   // Total track duration
    @SerializedName("position_ms")
    val positionMs: Long,                   // Current playback position
    @SerializedName("is_playing")
    val isPlaying: Boolean,                 // Playback state
    @SerializedName("volume_percent")
    val volumePercent: Int                  // Current volume (0-100)
)
```

## Permission Requirements

### Required Permissions
```xml
<!-- Bluetooth connectivity -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- For Bluetooth device discovery -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- For media session access -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- For foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

### Runtime Permission Handling
```kotlin
// Request Bluetooth permissions
private fun requestBluetoothPermissions() {
    val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    
    ActivityCompat.requestPermissions(this, permissions, BLUETOOTH_PERMISSION_CODE)
}

// Check notification listener permission
private fun isNotificationListenerEnabled(): Boolean {
    val packageName = packageName
    val enabledListeners = Settings.Secure.getString(
        contentResolver, "enabled_notification_listeners"
    )
    return enabledListeners?.contains(packageName) == true
}
```

## Communication Protocol

### JSON Command Processing
Commands received from nocturned service via Bluetooth SPP.

```kotlin
private suspend fun handleIncomingCommand(commandJson: String) {
    try {
        val command = gson.fromJson(commandJson, Command::class.java)
        val mediaController = getCurrentMediaController()
        
        when (command.command) {
            "play" -> mediaController?.transportControls?.play()
            "pause" -> mediaController?.transportControls?.pause()
            "next" -> mediaController?.transportControls?.skipToNext()
            "previous" -> mediaController?.transportControls?.skipToPrevious()
            "seek_to" -> command.valueMs?.let { 
                mediaController?.transportControls?.seekTo(it)
            }
            "set_volume" -> command.valuePercent?.let {
                setSystemVolume(it)
            }
        }
    } catch (e: JsonSyntaxException) {
        Log.e(TAG, "Invalid JSON command: $commandJson", e)
    }
}
```

### State Update Broadcasting
Media state changes broadcast to connected Car Thing devices.

```kotlin
private fun broadcastStateUpdate(mediaController: MediaController?) {
    val metadata = mediaController?.metadata
    val playbackState = mediaController?.playbackState
    
    val stateUpdate = StateUpdate(
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        positionMs = playbackState?.position ?: 0L,
        isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
        volumePercent = getCurrentVolume()
    )
    
    bluetoothManager.sendStateUpdate(stateUpdate)
}
```

## UI Components

### MainActivity.kt
Main activity with Jetpack Compose UI for debugging and monitoring.

**Key Features**:
- Service status display (running/stopped)
- Live connection status to Car Thing devices
- Debug panel showing last received command and sent state
- Paired device list with connection indicators
- Service control buttons (start/stop)

**Compose UI Structure**:
```kotlin
@Composable
fun NocturneCompanionApp() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ServiceStatusCard(serviceState)
        BluetoothConnectionCard(connectionState)
        DebugInfoCard(lastCommand, lastStateUpdate)
        PairedDevicesCard(pairedDevices)
        ServiceControlButtons(onStartService, onStopService)
    }
}
```

### Service Status Indicators
```kotlin
@Composable
fun ServiceStatusIndicator(isRunning: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isRunning) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (isRunning) Color.Green else Color.Red
        )
        Text(
            text = if (isRunning) "Service Running" else "Service Stopped",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
```

## Development Guidelines

### Bluetooth Best Practices
```kotlin
// Always check Bluetooth availability
private fun isBluetoothAvailable(): Boolean {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    return bluetoothAdapter != null && bluetoothAdapter.isEnabled
}

// Handle connection errors gracefully
private suspend fun handleConnectionError(error: Throwable) {
    when (error) {
        is IOException -> {
            Log.w(TAG, "Bluetooth connection lost, attempting reconnect")
            delay(5000) // Wait before retry
            startServer() // Restart server
        }
        is SecurityException -> {
            Log.e(TAG, "Bluetooth permission denied", error)
            // Request permissions again
        }
    }
}
```

### Media Session Integration
```kotlin
// Robust media controller access
private fun getCurrentMediaController(): MediaController? {
    return try {
        val sessionManager = getSystemService(MediaSessionManager::class.java)
        val sessions = sessionManager.getActiveSessions(
            ComponentName(this, NocturneNotificationListener::class.java)
        )
        sessions.firstOrNull { it.isSessionActive() }
    } catch (e: SecurityException) {
        Log.e(TAG, "No notification listener permission", e)
        null
    }
}

// Handle media controller callbacks
private val mediaCallback = object : MediaController.Callback() {
    override fun onMetadataChanged(metadata: MediaMetadata?) {
        broadcastStateUpdate(mediaController)
    }
    
    override fun onPlaybackStateChanged(state: PlaybackState?) {
        broadcastStateUpdate(mediaController)
    }
}
```

### Coroutine Usage
```kotlin
// Structured concurrency for service operations
class NocturneService : Service() {
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob()
    )
    
    override fun onCreate() {
        super.onCreate()
        
        // Launch coroutines for concurrent operations
        serviceScope.launch {
            bluetoothManager.startServer()
        }
        
        serviceScope.launch {
            observeMediaControllers()
        }
    }
    
    override fun onDestroy() {
        serviceScope.cancel() // Clean up all coroutines
        super.onDestroy()
    }
}
```

## Testing and Debugging

### Unit Testing
```kotlin
@Test
fun testCommandParsing() {
    val commandJson = """{"command": "play"}"""
    val command = gson.fromJson(commandJson, Command::class.java)
    
    assertEquals("play", command.command)
    assertNull(command.valueMs)
    assertNull(command.valuePercent)
}

@Test
fun testStateUpdateSerialization() {
    val stateUpdate = StateUpdate(
        artist = "Test Artist",
        track = "Test Track",
        durationMs = 180000,
        positionMs = 45000,
        isPlaying = true,
        volumePercent = 75
    )
    
    val json = gson.toJson(stateUpdate)
    assertTrue(json.contains("\"is_playing\":true"))
    assertTrue(json.contains("\"volume_percent\":75"))
}
```

### Integration Testing
```kotlin
// Test Bluetooth server functionality
@Test
fun testBluetoothServerConnection() = runTest {
    val bluetoothManager = BluetoothServerManager(context)
    
    // Mock Bluetooth adapter
    val mockAdapter = mockk<BluetoothAdapter>()
    every { mockAdapter.isEnabled } returns true
    
    // Test server startup
    bluetoothManager.startServer()
    assertTrue(bluetoothManager.isServerRunning())
}
```

### Debugging Tools
```kotlin
// Enable detailed logging
private const val DEBUG = true

private fun logDebug(message: String) {
    if (DEBUG) {
        Log.d(TAG, message)
    }
}

// Monitor Bluetooth operations
private fun logBluetoothEvent(event: String, details: String) {
    Log.i("BluetoothDebug", "$event: $details")
    
    // Update debug UI
    updateDebugPanel(event, details)
}
```

### ADB Debugging Commands
```bash
# Install and run debug build
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start com.nocturne.companion/.ui.MainActivity

# Monitor application logs
adb logcat | grep NocturneCompanion

# Check service status
adb shell dumpsys activity services com.nocturne.companion

# Monitor Bluetooth operations
adb shell dumpsys bluetooth_manager

# Check notification listener status
adb shell settings get secure enabled_notification_listeners
```

## Common Issues and Solutions

### Issue: Notification Listener Permission Not Granted
**Symptoms**: App cannot access media sessions
**Solution**: 
```kotlin
// Direct user to notification listener settings
private fun openNotificationListenerSettings() {
    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    startActivity(intent)
}
```

### Issue: Bluetooth Connection Fails
**Symptoms**: SPP server cannot accept connections
**Solutions**:
- Verify Bluetooth permissions are granted
- Check if Bluetooth adapter is enabled
- Ensure device is discoverable
- Verify SPP UUID matches nocturned client

### Issue: Media Commands Not Working
**Symptoms**: Commands received but media doesn't respond
**Solutions**:
- Check if media app supports MediaSession
- Verify notification listener permission
- Test with different media apps (Spotify, YouTube Music)
- Monitor MediaController callbacks

### Issue: Service Stops Unexpectedly
**Symptoms**: Foreground service terminates
**Solutions**:
- Implement proper foreground service notification
- Handle uncaught exceptions in coroutines
- Use START_STICKY service restart policy
- Monitor battery optimization settings

### Issue: JSON Parsing Errors
**Symptoms**: Commands fail to parse
**Solutions**:
- Validate JSON format with terminal delimiter (`\n`)
- Handle partial message reception
- Implement robust error handling for malformed JSON
- Log raw received data for debugging

## Performance Optimization

### Memory Management
```kotlin
// Use object pooling for frequent allocations
class MessagePool {
    private val pool = mutableListOf<ByteArray>()
    
    fun acquire(): ByteArray {
        return if (pool.isNotEmpty()) {
            pool.removeAt(pool.lastIndex)
        } else {
            ByteArray(1024)
        }
    }
    
    fun release(buffer: ByteArray) {
        if (pool.size < MAX_POOL_SIZE) {
            pool.add(buffer)
        }
    }
}
```

### Battery Optimization
```kotlin
// Efficient state update frequency
private var lastUpdateTime = 0L
private const val MIN_UPDATE_INTERVAL = 100L // 100ms

private fun shouldSendUpdate(): Boolean {
    val currentTime = System.currentTimeMillis()
    return (currentTime - lastUpdateTime) >= MIN_UPDATE_INTERVAL
}
```

## Deployment Checklist

- [ ] Build signed release APK
- [ ] Test on multiple Android versions (API 26+)
- [ ] Verify all permissions are properly declared
- [ ] Test Bluetooth connectivity with Car Thing
- [ ] Validate media control with various music apps
- [ ] Check notification listener functionality
- [ ] Test service persistence across device reboots
- [ ] Verify foreground service notification
- [ ] Test connection recovery after Bluetooth disruption
- [ ] Validate JSON protocol compatibility with nocturned

---

**Last Updated**: 2025-01-05  
**App Version**: 1.0.0  
**Target SDK**: 34  
**Minimum SDK**: 26  
**Build Tools**: Android Gradle Plugin 8.0+