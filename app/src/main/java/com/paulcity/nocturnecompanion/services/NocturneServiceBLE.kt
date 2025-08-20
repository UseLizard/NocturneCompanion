package com.paulcity.nocturnecompanion.services

import android.annotation.SuppressLint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.bluetooth.BluetoothAdapter
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.ble.protocol.BinaryProtocol
import com.paulcity.nocturnecompanion.ble.protocol.BinaryProtocolV2
import com.paulcity.nocturnecompanion.ble.AlbumArtStatus
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.WeatherBleManager
import com.paulcity.nocturnecompanion.data.Command
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.data.AudioEvent
import com.paulcity.nocturnecompanion.data.AudioEventType
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ble.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.TimeZone
import android.os.Handler
import android.os.Looper
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.security.MessageDigest

class NocturneServiceBLE : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var bleServerManager: EnhancedBleServerManager
    private lateinit var weatherBleManager: WeatherBleManager
    private lateinit var audioManager: AudioManager
    private val gson = Gson()
    
    private val debugMode = true // Enhanced debug mode
    
    // Time sync handler for periodic updates (optional)
    private val timeSyncHandler = Handler(Looper.getMainLooper())
    private val timeSyncRunnable = object : Runnable {
        override fun run() {
            if (bleServerManager.connectedDevicesList.value.isNotEmpty()) {
                Log.d(TAG, "Periodic time sync")
                sendTimeSync()
            }
            // Schedule next sync in 1 hour
            timeSyncHandler.postDelayed(this, 3600000)
        }
    }
    
    // Debounce handler for state updates
    private val stateUpdateHandler = Handler(Looper.getMainLooper())
    private var stateUpdateRunnable: Runnable? = null
    private val STATE_UPDATE_DEBOUNCE_MS = 100L // 100ms debounce
    
    // Polling handler for media state when callbacks might not fire
    private val mediaPollingHandler = Handler(Looper.getMainLooper())
    private var mediaPollingRunnable: Runnable? = null
    private val MEDIA_POLL_INTERVAL_MS = 1000L // Normal poll interval
    private val AGGRESSIVE_POLL_INTERVAL_MS = 500L // Aggressive poll interval (reduced from 250ms to prevent BLE flooding)
    private var isAggressivePolling = false
    private var aggressivePollingEndTime = 0L
    private var pollingRetryCount = 0
    private val MAX_POLLING_RETRIES = 10
    
    private var currentMediaController: MediaController? = null
    private var lastTrackId: String? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isAudioActuallyPlaying = false
    
    // Audio events tracking
    private val audioEvents = mutableListOf<AudioEvent>()
    private val MAX_AUDIO_EVENTS = 500
    
    // Broadcast receiver for settings updates and test commands
    private val settingsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_UPDATE_ALBUM_ART_SETTINGS -> {
                    Log.d(TAG, "Received album art settings update broadcast")
                    if (::bleServerManager.isInitialized) {
                        // Load settings from SharedPreferences
                        val prefs = context.getSharedPreferences("album_art_settings", Context.MODE_PRIVATE)
                        val format = prefs.getString("format", "JPEG") ?: "JPEG"
                        val quality = prefs.getInt("quality", 80)
                        val chunkSize = prefs.getInt("chunk_size", 512)
                        val chunkDelayMs = prefs.getInt("chunk_delay_ms", 20)
                        val useBinaryProtocol = prefs.getBoolean("use_binary_protocol", false)
                        val imageSize = prefs.getInt("image_size", 200)
                        
                        bleServerManager.updateAlbumArtSettings(
                            format = format,
                            quality = quality,
                            chunkSize = chunkSize,
                            chunkDelayMs = chunkDelayMs,
                            useBinaryProtocol = useBinaryProtocol,
                            imageSize = imageSize
                        )
                        Log.d(TAG, "Album art settings updated in BLE server - format: $format, quality: $quality, binary: $useBinaryProtocol")
                    }
                }
                ACTION_TEST_ALBUM_ART -> {
                    Log.d(TAG, "Received test album art command broadcast")
                    if (::bleServerManager.isInitialized) {
                        bleServerManager.sendTestAlbumArtCommand()
                    }
                }
                ACTION_SEND_TIME_SYNC -> {
                    Log.d(TAG, "Received manual time sync request broadcast")
                    sendTimeSync()
                }
                ACTION_ALBUM_ART_AVAILABLE -> {
                    Log.d(TAG, "🎨 ALBUM_ART_AVAILABLE broadcast received!")
                    val artist = intent.getStringExtra("artist") ?: ""
                    val album = intent.getStringExtra("album") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    
                    // Get bitmap from static holder (avoids Intent size limits)
                    val bitmap = com.paulcity.nocturnecompanion.ui.MediaTabBitmapHolder.getBitmap()
                    Log.d(TAG, "🎨 Received album art available broadcast for: $artist - $album - $title, bitmap available: ${bitmap != null}")
                    
                    if (bitmap != null) {
                        // Convert bitmap to byte array
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        val bitmapData = stream.toByteArray()
                        
                        // Trigger album art processing using the bitmap from the Media tab
                        if (::bleServerManager.isInitialized) {
                            Log.d(TAG, "🎨 BLE server initialized and bitmap data available - calling triggerAlbumArtFromBitmap")
                            triggerAlbumArtFromBitmap(artist, album, title, bitmapData)
                        } else {
                            Log.w(TAG, "🎨 Cannot process album art: bleServerManager not initialized")
                        }
                        
                        // Clear bitmap after use
                        com.paulcity.nocturnecompanion.ui.MediaTabBitmapHolder.clearBitmap()
                    } else {
                        Log.w(TAG, "🎨 No bitmap available from MediaTabBitmapHolder")
                    }
                }
            }
        }
    }
    
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "Media metadata changed for ${currentMediaController?.packageName}")
            
            val previousArtist = lastState.artist
            val previousAlbum = lastState.album
            val previousTrack = lastState.track
            
            lastState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            lastState.album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            lastState.track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            lastState.duration_ms = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
            
            // Generate track ID from metadata
            val newTrackId = "${lastState.artist}|${lastState.album}|${lastState.track}"
            
            // Track ID changed - automatically extract and send album art
            if (newTrackId != lastTrackId && metadata != null) {
                lastTrackId = newTrackId
                Log.d(TAG, "Track changed to: $newTrackId")
                
                // Extract album art - note: automatic transmission will also happen via callback
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Debug: Log available metadata keys
                        Log.d(TAG, "DEBUG: Available metadata keys:")
                        metadata.keySet()?.forEach { key ->
                            val value = when (key) {
                                MediaMetadata.METADATA_KEY_ALBUM_ART_URI -> metadata.getString(key)
                                MediaMetadata.METADATA_KEY_ALBUM_ART -> "Bitmap available: ${metadata.getBitmap(key) != null}"
                                MediaMetadata.METADATA_KEY_ART -> "Bitmap available: ${metadata.getBitmap(key) != null}"
                                MediaMetadata.METADATA_KEY_DISPLAY_ICON -> "Bitmap available: ${metadata.getBitmap(key) != null}"
                                else -> metadata.getString(key) ?: "null"
                            }
                            Log.d(TAG, "DEBUG:   $key = $value")
                        }
                        
                        val albumArtResult = bleServerManager.albumArtManager.extractAlbumArt(metadata)
                        if (albumArtResult != null) {
                            Log.d(TAG, "Album art extracted for new track: ${albumArtResult.data.size} bytes, checksum: ${albumArtResult.checksum}")
                            // Note: Transmission is handled automatically by the callback mechanism
                        } else {
                            Log.d(TAG, "No album art available for auto-transmission")
                            
                            // Try to force extraction if we see an ALBUM_ART_URI
                            val albumArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                            if (!albumArtUri.isNullOrEmpty()) {
                                Log.d(TAG, "Found ALBUM_ART_URI: $albumArtUri - attempting manual extraction")
                                // We could potentially load from URI here if needed
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in automatic album art extraction", e)
                    }
                }
                
                // Track metadata change event
                trackAudioEvent(
                    AudioEventType.METADATA_CHANGED,
                    "Track changed: ${lastState.track}",
                    mapOf(
                        "artist" to (lastState.artist ?: "Unknown"),
                        "album" to (lastState.album ?: "Unknown"),
                        "track" to (lastState.track ?: "Unknown"),
                        "duration_ms" to lastState.duration_ms,
                        "previousTrack" to (previousTrack ?: "Unknown")
                    )
                )
                
                // Enable aggressive polling when track changes (user likely to interact)
                enableAggressivePolling(3000) // 3 seconds of aggressive polling (reduced to prevent BLE flooding)
            }
            
            sendStateUpdate()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "onPlaybackStateChanged called for ${currentMediaController?.packageName}")
            Log.d(TAG, "State: ${state?.state}, Position: ${state?.position}, Actions: ${state?.actions}")
            
            val previouslyPlaying = lastState.is_playing
            val newIsPlaying = state?.state == PlaybackState.STATE_PLAYING
            lastState.is_playing = newIsPlaying
            lastState.position_ms = state?.position ?: 0
            
            Log.d(TAG, "State change: was playing=$previouslyPlaying, now playing=$newIsPlaying")
            
            // Track playback state change
            if (previouslyPlaying != lastState.is_playing) {
                trackAudioEvent(
                    AudioEventType.PLAYBACK_STATE_CHANGED,
                    if (lastState.is_playing) "Playback started" else "Playback paused/stopped",
                    mapOf(
                        "state" to (state?.state ?: -1),
                        "position_ms" to lastState.position_ms,
                        "track" to (lastState.track ?: "Unknown")
                    )
                )
            }
            
            sendStateUpdate()
        }
        
        override fun onSessionDestroyed() {
            Log.w(TAG, "MediaController session destroyed for ${currentMediaController?.packageName}")
            // The session was destroyed, controller will be updated via observeMediaControllers
        }
    }

    private var lastState = StateUpdate(
        artist = null, 
        album = null, 
        track = null,
        duration_ms = 0, 
        position_ms = 0, 
        is_playing = false, 
        volume_percent = 0
    )
    
    // Track what was last sent to avoid redundant updates
    private var lastSentState: StateUpdate? = null
    private var lastSentTimestamp = 0L
    private val MIN_UPDATE_INTERVAL_MS = 500L // Don't send updates more frequently than this
    private val POSITION_UPDATE_THRESHOLD_MS = 5000L // Only send position updates if changed by more than 5 seconds
    
    // For binary incremental updates
    private var previousBinaryState: StateUpdate? = null
    private val POSITION_CHANGE_THRESHOLD_MS = 15000L // 15 seconds for significant position change
    private var useBinaryIncrementalUpdates = false // Will be enabled after protocol negotiation

    companion object {
        private const val TAG = "NocturneServiceBLE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "NocturneServiceChannel"
        const val COMMAND_NOTIFICATION_ID = 2
        const val COMMAND_CHANNEL_ID = "NocturneCommandChannel"
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        // Broadcast actions
        const val ACTION_COMMAND_RECEIVED = "com.paulcity.nocturnecompanion.COMMAND_RECEIVED"
        const val ACTION_STATE_UPDATED = "com.paulcity.nocturnecompanion.STATE_UPDATED"
        const val ACTION_SERVER_STATUS = "com.paulcity.nocturnecompanion.SERVER_STATUS"
        const val ACTION_DEBUG_LOG = "com.paulcity.nocturnecompanion.DEBUG_LOG"
        const val ACTION_CONNECTED_DEVICES = "com.paulcity.nocturnecompanion.CONNECTED_DEVICES"
        const val ACTION_NOTIFICATION = "com.paulcity.nocturnecompanion.NOTIFICATION"
        const val ACTION_UPDATE_ALBUM_ART_SETTINGS = "com.paulcity.nocturnecompanion.UPDATE_ALBUM_ART_SETTINGS"
        const val ACTION_TEST_ALBUM_ART = "com.paulcity.nocturnecompanion.TEST_ALBUM_ART_COMMAND"
        const val ACTION_SEND_TIME_SYNC = "com.paulcity.nocturnecompanion.SEND_BLE_TIME_SYNC"
        const val ACTION_AUDIO_EVENT = "com.paulcity.nocturnecompanion.AUDIO_EVENT"
        const val ACTION_ALBUM_ART_AVAILABLE = "com.paulcity.nocturnecompanion.ALBUM_ART_AVAILABLE"
        
        // Extras
        const val EXTRA_JSON_DATA = "json_data"
        const val EXTRA_SERVER_STATUS = "server_status"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_DEBUG_LOG = "debug_log"
        const val EXTRA_CONNECTED_DEVICES = "connected_devices"
        const val EXTRA_NOTIFICATION_MESSAGE = "notification_message"
        const val EXTRA_AUDIO_EVENT = "audio_event"
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() called")
        
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d(TAG, "AudioManager initialized")
            
            createNotificationChannel()
            Log.d(TAG, "Notification channel created")
            
            if (debugMode) {
                createCommandNotificationChannel()
                Log.d(TAG, "Command notification channel created")
            }
            
            startForeground(NOTIFICATION_ID, createNotification("Initializing BLE..."))
            Log.d(TAG, "Started foreground service")
            
            // Start periodic time sync (every hour)
            timeSyncHandler.postDelayed(timeSyncRunnable, 3600000)
            Log.d(TAG, "Scheduled periodic time sync")
            
            // Register broadcast receiver for settings updates and test commands
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_ALBUM_ART_SETTINGS)
                addAction(ACTION_TEST_ALBUM_ART)
                addAction(ACTION_SEND_TIME_SYNC)
                addAction(ACTION_ALBUM_ART_AVAILABLE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(settingsUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(settingsUpdateReceiver, filter)
            }
            Log.d(TAG, "Registered settings update broadcast receiver")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate()", e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")
        
        try {
            when (intent?.action) {
                ACTION_START -> {
                    Log.d(TAG, "Starting BLE service")
                    updateNotification("Starting BLE server...")
                    
                    initializeBleServer()
                    observeMediaControllers()
                    observeServerStatus()
                    observeDeviceConnections()
                    observeDebugLogs()
                    observeConnectedDevices()
                    startPeriodicCleanup()
                    
                    // Setup audio playback monitoring
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setupAudioPlaybackCallback()
                    }
                    
                    // Check notification listener permission
                    checkNotificationListenerPermission()
                    
                    // Don't start polling here - wait for MediaController to be set
                    // startMediaPolling() moved to observeMediaControllers
                    
                    updateNotification("BLE server running")
                }
                ACTION_STOP -> {
                    Log.d(TAG, "Stopping service")
                    stopSelf()
                    return START_NOT_STICKY // Don't restart when stopped intentionally
                }
                "com.paulcity.nocturnecompanion.REQUEST_PHY_UPDATE" -> {
                    val deviceAddress = intent.getStringExtra("device_address")
                    if (deviceAddress != null && ::bleServerManager.isInitialized) {
                        Log.d(TAG, "Requesting PHY update for device: $deviceAddress")
                        bleServerManager.requestPhyUpdateForDevice(deviceAddress)
                    } else {
                        Log.w(TAG, "Cannot request PHY update: device address null or BLE server not initialized")
                    }
                }
                "SEND_WEATHER_UPDATE" -> {
                    val weatherDataJson = intent.getStringExtra("weather_data")
                    val locationName = intent.getStringExtra("location_name")
                    val isCurrentLocation = intent.getBooleanExtra("is_current_location", false)
                    
                    if (weatherDataJson != null && locationName != null) {
                        try {
                            val weatherResponse = gson.fromJson(weatherDataJson, com.paulcity.nocturnecompanion.data.WeatherResponse::class.java)
                            Log.d(TAG, "Received weather update request for: $locationName")
                            sendWeatherUpdate(weatherResponse, locationName, isCurrentLocation)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing weather data", e)
                        }
                    } else {
                        Log.w(TAG, "Missing weather data or location name in SEND_WEATHER_UPDATE intent")
                    }
                }
                "SEND_GRADIENT_COLORS" -> {
                    val gradientColors = intent.getIntegerArrayListExtra("gradient_colors")
                    if (gradientColors != null && ::bleServerManager.isInitialized) {
                        try {
                            Log.d(TAG, "Sending gradient colors via BLE: ${gradientColors.size} colors")
                            sendGradientColors(gradientColors)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending gradient colors", e)
                        }
                    } else {
                        Log.w(TAG, "Cannot send gradient colors: no colors provided or BLE server not initialized")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown action: ${intent?.action}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }

    private fun initializeBleServer() {
        Log.d(TAG, "Initializing BLE server...")
        
        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasAdvertise = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            
            if (!hasConnect || !hasAdvertise) {
                Log.e(TAG, "Missing Bluetooth permissions - Connect: $hasConnect, Advertise: $hasAdvertise")
                updateNotification("Error: Missing Bluetooth permissions")
                stopSelf()
                return
            }
        }

        // Command handler
        val commandHandler: (Command) -> Unit = { command ->
            Log.d(TAG, "=== COMMAND HANDLER INVOKED ===")
            Log.d(TAG, "Command: ${command.command}")
            Log.d(TAG, "Value MS: ${command.value_ms}")
            Log.d(TAG, "Value Percent: ${command.value_percent}")
            
            if (debugMode) {
                showDataReceivedNotification(gson.toJson(command))
            }
            
            // Broadcast command
            val intent = Intent(ACTION_COMMAND_RECEIVED)
            intent.putExtra(EXTRA_JSON_DATA, gson.toJson(command))
            sendBroadcastCompat(intent)
            Log.d(TAG, "Command broadcast sent")
            
            handleCommand(command)
            Log.d(TAG, "Command handling completed")
        }

        // Initialize BLE server
        bleServerManager = EnhancedBleServerManager(
            this, 
            commandHandler,
            onDeviceConnected = { device ->
                Log.d(TAG, "Device connected: ${device.address}")
                
                // Media polling already running, just log
                Log.d(TAG, "Device connected, media polling already active")
            },
            onDeviceReady = { device ->
                Log.d(TAG, "Device ready with all subscriptions: ${device.address}")
                
                // Send initial data now that notifications are enabled
                serviceScope.launch {
                    // First ensure we have optimal connection parameters for high-speed data transfer
                    Log.d(TAG, "Optimizing connection parameters for high-speed data transfer")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            val optimized = bleServerManager.optimizeConnectionForDevice(device.address)
                            if (optimized) {
                                Log.d(TAG, "BLE_LOG: Connection optimized successfully for ${device.address}")
                            } else {
                                Log.w(TAG, "BLE_LOG: Connection optimization failed for ${device.address}")
                            }
                            delay(200) // Allow connection optimization to complete
                        } catch (e: Exception) {
                            Log.w(TAG, "Connection optimization failed, continuing with standard connection: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "Connection optimization not available on this Android version")
                    }
                    
                    // Send time sync first
                    Log.d(TAG, "Sending time sync to ready device")
                    sendTimeSync()
                    
                    // Short delay between messages
                    delay(100)
                    
                    // Then send current media state
                    Log.d(TAG, "Sending initial media state to ready device")
                    sendStateUpdate()
                    
                    // Longer delay before weather to ensure previous messages are processed
                    delay(200)
                    
                    // Send current weather data if available
                    Log.d(TAG, "Sending initial weather data to ready device")
                    sendInitialWeatherData()
                    
                    // Send album art for current track if available
                    delay(200)
                    Log.d(TAG, "Sending album art for current track to ready device")
                    sendCurrentTrackAlbumArt(device.address)
                }
            }
        )
        bleServerManager.startServer()
        
        // Initialize weather manager
        weatherBleManager = WeatherBleManager(bleServerManager)
        Log.d(TAG, "WeatherBleManager initialized")
        
        Log.d(TAG, "BLE server initialized")
    }

    private fun handleCommand(command: Command) {
        Log.d(TAG, "Handling command: ${command.command}")
        
        // First check if we have necessary permissions
        if (!isNotificationServiceEnabled()) {
            Log.e(TAG, "Cannot handle command - NotificationListener not enabled")
            val intent = Intent(ACTION_NOTIFICATION)
            intent.putExtra(EXTRA_NOTIFICATION_MESSAGE, "⚠️ Cannot control media - Notification Access required")
            sendBroadcastCompat(intent)
            return
        }
        
        // Handle commands that don't require a media controller
        when (command.command) {
            "get_capabilities" -> {
                // Handled by binary protocol in EnhancedBleServerManager
                Log.d(TAG, "Capabilities requested - handled by binary protocol")
                return
            }
            "enable_binary_incremental" -> {
                // Enable binary incremental updates
                Log.d(TAG, "Enabling binary incremental updates")
                useBinaryIncrementalUpdates = true
                previousBinaryState = null // Reset to force initial full update
                // Response handled by binary protocol in EnhancedBleServerManager
                return
            }
            "request_state" -> {
                Log.d(TAG, "Received request_state command")
                sendStateUpdate(forceUpdate = true)
                return
            }
            "request_timestamp" -> {
                Log.d(TAG, "Received request_timestamp command")
                sendTimeSync()
                // Position included in binary time sync message
                return
            }
            "request_track_refresh" -> {
                // Force immediate state update
                Log.d(TAG, "Track refresh requested - sending current state")
                Log.d(TAG, "Current track: ${lastState.track ?: "null"}")
                
                // Update the media state to force a refresh
                currentMediaController?.let { controller ->
                    Log.d(TAG, "Updating media state from controller")
                    updateMediaState(controller)
                } ?: run {
                    Log.d(TAG, "No media controller, sending last known state")
                    // No controller, just send the last state via the debounced method
                    sendStateUpdate()
                }
                
                Log.d(TAG, "State update triggered for track refresh")
                
                Log.d(TAG, "Exiting request_track_refresh handler")
                return
            }
            "refresh_weather" -> {
                Log.d(TAG, "Weather refresh requested from nocturned")
                weatherBleManager.handleWeatherRefreshRequest()
                
                // Check if we have cached weather data to send immediately
                try {
                    val sharedPrefs = getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
                    val cachedWeatherJson = sharedPrefs.getString("weather_data", null)
                    val cachedLocationName = sharedPrefs.getString("location_name", null)
                    val cachedIsCurrentLocation = sharedPrefs.getBoolean("is_current_location", false)
                    val cacheTimestamp = sharedPrefs.getLong("cache_timestamp", 0)
                    val isCacheValid = (System.currentTimeMillis() - cacheTimestamp) < (30 * 60 * 1000) // 30 minutes
                    
                    if (cachedWeatherJson != null && cachedLocationName != null && isCacheValid) {
                        Log.d(TAG, "Sending cached weather data in response to refresh_weather command")
                        val weatherResponse = gson.fromJson(cachedWeatherJson, com.paulcity.nocturnecompanion.data.WeatherResponse::class.java)
                        sendWeatherUpdate(weatherResponse, cachedLocationName, cachedIsCurrentLocation)
                    } else {
                        Log.d(TAG, "No valid cached weather data, requesting fresh data from ViewModel")
                        // Only request fresh data if no valid cache exists
                        val intent = Intent("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
                        sendBroadcast(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling weather refresh request", e)
                    // Fallback: request fresh data
                    val intent = Intent("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
                    sendBroadcast(intent)
                }
                return
            }
            "album_art_query" -> {
                // Handle album art request from nocturned
                val payload = command.payload
                val trackId = payload?.get("track_id") as? String ?: ""
                val checksum = payload?.get("checksum") as? String ?: ""
                val hash = payload?.get("hash") as? String ?: ""
                
                Log.d(TAG, "BLE_LOG: Album art query received - track_id: $trackId, checksum: $checksum, hash: $hash")
                Log.d(TAG, "BLE_LOG: Full payload: $payload")
                
                // Get device address from command context (passed via BLE server)
                val deviceAddress = payload?.get("device_address") as? String
                
                if (deviceAddress != null) {
                    Log.d(TAG, "BLE_LOG: Processing album art query for device: $deviceAddress")
                    handleAlbumArtQuery(deviceAddress, hash)
                } else {
                    Log.w(TAG, "BLE_LOG: No device address provided for album art query")
                }
                return
            }
        }
        
        currentMediaController?.let { controller ->
            try {
                when (command.command) {
                    "play" -> {
                        controller.transportControls.play()
                        Log.d(TAG, "Play command executed")
                        // Force state update after command
                        serviceScope.launch {
                            delay(100)
                            updateMediaState(controller)
                        }
                    }
                    "pause" -> {
                        controller.transportControls.pause()
                        Log.d(TAG, "Pause command executed")
                        // Force state update after command
                        serviceScope.launch {
                            delay(100)
                            updateMediaState(controller)
                        }
                    }
                    "next" -> {
                        controller.transportControls.skipToNext()
                        Log.d(TAG, "Next command executed")
                        
                        // The onMetadataChanged callback will handle the track change and state update
                        // We just need to ensure position is updated correctly
                        serviceScope.launch {
                            delay(100) // Small delay to let the transport control take effect
                            
                            // Update position to ensure it's correct after skip
                            controller.playbackState?.let { state ->
                                val newPosition = state.position
                                if (lastState.position_ms != newPosition) {
                                    Log.d(TAG, "Next command - updating position to: ${newPosition}ms")
                                    lastState.position_ms = newPosition
                                    // Only send position update if metadata hasn't changed yet
                                    // If metadata changed, the callback will handle the full update
                                    sendStateUpdate()
                                }
                            }
                        }
                    }
                    "previous" -> {
                        controller.transportControls.skipToPrevious()
                        Log.d(TAG, "Previous command executed")
                        
                        // The onMetadataChanged callback will handle the track change and state update
                        // We just need to ensure position is updated correctly
                        serviceScope.launch {
                            delay(100) // Small delay to let the transport control take effect
                            
                            // Update position to ensure it's correct after skip
                            controller.playbackState?.let { state ->
                                val newPosition = state.position
                                if (lastState.position_ms != newPosition) {
                                    Log.d(TAG, "Previous command - updating position to: ${newPosition}ms")
                                    lastState.position_ms = newPosition
                                    // Only send position update if metadata hasn't changed yet
                                    // If metadata changed, the callback will handle the full update
                                    sendStateUpdate()
                                }
                            }
                        }
                    }
                    "seek_to" -> {
                        command.value_ms?.let { position ->
                            controller.transportControls.seekTo(position)
                            Log.d(TAG, "Seek to $position ms executed")
                        }
                    }
                    "set_volume" -> {
                        command.value_percent?.let { percent ->
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val targetVolume = (maxVolume * percent / 100).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                            
                            lastState.volume_percent = percent
                            // Don't send state update for volume changes - the UI already knows
                            // sendStateUpdate()
                            
                            Log.d(TAG, "Volume set to $percent% (raw: $targetVolume/$maxVolume)")
                        }
                    }
                    "ping" -> {
                        // Speed test ping - immediately send pong response
                        handlePingCommand(command)
                    }
                    "bt_speed_test" -> {
                        // Start speed test sequence
                        handleSpeedTestCommand(command)
                    }
                    "throughput_test" -> {
                        // Handle throughput test data chunk
                        handleThroughputTestCommand(command)
                    }
                    "request_2m_phy" -> {
                        // Handle request to switch to 2M PHY for better throughput
                        handle2MPhyRequest(command)
                    }
                    "test_album_art_request", "test_album_art" -> {
                        // Handle test album art request - send current track's album art
                        Log.d(TAG, "Test album art request received")
                        handleTestAlbumArtRequest(command)
                    }
                    "album_art_query", "album_art_needed" -> {
                        // Album art queries are no longer supported - art is sent automatically when available
                        Log.d(TAG, "Album art query received but queries are no longer supported - art is sent automatically when available")
                    }
                    else -> {
                        Log.w(TAG, "Unknown command: ${command.command}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing command: ${command.command}", e)
            }
        } ?: run {
            Log.w(TAG, "No active media controller to handle command: ${command.command}")
            
            // Try to recover by checking for media sessions again
            serviceScope.launch {
                delay(100) // Small delay
                if (currentMediaController == null) {
                    // Force notification listener to update
                    Log.d(TAG, "Attempting to recover media controller...")
                    val intent = Intent(ACTION_NOTIFICATION)
                    intent.putExtra(EXTRA_NOTIFICATION_MESSAGE, "⚠️ No active media session found")
                    sendBroadcastCompat(intent)
                }
            }
        }
    }

    private fun observeMediaControllers() {
        serviceScope.launch {
            // Check if we have an active controller immediately
            if (NocturneNotificationListener.activeMediaController.value == null) {
                Log.w(TAG, "No active media controller available at startup")
                
                // Check if notification listener is enabled
                if (!isNotificationServiceEnabled()) {
                    Log.e(TAG, "NotificationListener is not enabled!")
                    val intent = Intent(ACTION_NOTIFICATION)
                    intent.putExtra(EXTRA_NOTIFICATION_MESSAGE, "⚠️ Media control requires Notification Access permission")
                    sendBroadcastCompat(intent)
                }
            }
            
            NocturneNotificationListener.activeMediaController.collectLatest { controller ->
                if (controller?.packageName != currentMediaController?.packageName) {
                    Log.i(TAG, "Media controller changed from ${currentMediaController?.packageName} to ${controller?.packageName}")
                    
                    // Unregister old callback
                    currentMediaController?.unregisterCallback(mediaCallback)
                    
                    // Register new callback
                    currentMediaController = controller
                    controller?.registerCallback(mediaCallback)
                    Log.d(TAG, "Registered MediaController callback for ${controller?.packageName}")
                    
                    // Start or restart polling now that we have a controller
                    if (controller != null) {
                        Log.d(TAG, "Starting media polling after controller set")
                        startMediaPolling()
                    }
                    
                    // Update state immediately
                    updateMediaState(controller)
                } else if (controller == null) {
                    Log.w(TAG, "Media controller became null")
                    
                    // Stop polling when controller is null
                    stopMediaPolling()
                    
                    // Clear media state when no controller
                    lastState = StateUpdate(
                        artist = null,
                        album = null,
                        track = null,
                        duration_ms = 0,
                        position_ms = 0,
                        is_playing = false,
                        volume_percent = lastState.volume_percent // Keep volume
                    )
                    sendStateUpdate()
                }
            }
        }
    }

    private fun observeServerStatus() {
        serviceScope.launch {
            bleServerManager.connectionStatus.collect { status ->
                Log.d(TAG, "Server status: $status")
                
                val intent = Intent(ACTION_SERVER_STATUS)
                intent.putExtra(EXTRA_SERVER_STATUS, status)
                intent.putExtra(EXTRA_IS_RUNNING, true)
                sendBroadcastCompat(intent)
                
                updateNotification(status)
            }
        }
    }
    
    private fun observeDeviceConnections() {
        serviceScope.launch {
            bleServerManager.connectedDevicesList.collect { devices ->
                // Device cleanup - no longer needed with simplified album art transmission
            }
        }
    }
    
    private fun startPeriodicCleanup() {
        // Cleanup coroutine no longer needed with simplified album art transmission
    }

    private fun observeDebugLogs() {
        serviceScope.launch {
            bleServerManager.debugLogs.collect { logEntry ->
                if (debugMode) {
                    val intent = Intent(ACTION_DEBUG_LOG)
                    intent.putExtra(EXTRA_DEBUG_LOG, logEntry.toJson())
                    sendBroadcastCompat(intent)
                }
            }
        }
    }

    private fun observeConnectedDevices() {
        serviceScope.launch {
            var previousDeviceCount = 0
            bleServerManager.connectedDevicesList.collect { devices ->
                val intent = Intent(ACTION_CONNECTED_DEVICES)
                intent.putExtra(EXTRA_CONNECTED_DEVICES, gson.toJson(devices))
                sendBroadcastCompat(intent)
                
                // Send state update when a new device connects
                if (devices.size > previousDeviceCount) {
                    Log.d(TAG, "New device connected, sending initial state update")
                    delay(500) // Wait for device to setup notifications
                    sendTimeSync()
                    sendStateUpdate(forceUpdate = true)
                    
                    // Send album art for current track to newly connected devices
                    delay(200)
                    devices.forEach { device ->
                        sendCurrentTrackAlbumArt(device.address)
                    }
                }
                previousDeviceCount = devices.size
            }
        }
    }

    private fun updateMediaState(controller: MediaController?) {
        controller?.let {
            // Update metadata
            it.metadata?.let { metadata ->
                lastState.artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                lastState.album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                lastState.track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                lastState.duration_ms = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                
                // Generate track ID - nocturned will request album art if needed
                val newTrackId = "${lastState.artist}|${lastState.album}|${lastState.track}"
                if (newTrackId != lastTrackId) {
                    lastTrackId = newTrackId
                    Log.d(TAG, "Track changed during controller update to: $newTrackId")
                    // Album art will be sent only when nocturned requests it via album_art_query
                }
            }
            
            // Update playback state
            it.playbackState?.let { state ->
                val mediaControllerPlaying = state.state == PlaybackState.STATE_PLAYING
                lastState.position_ms = state.position
                
                // Use MediaController state as primary source of truth
                // Only override if we have conflicting information
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // If MediaController says playing but audio isn't actually playing,
                    // it might be Spotify Connect or a delay in audio starting
                    if (mediaControllerPlaying && !isAudioActuallyPlaying) {
                        Log.d(TAG, "MediaController says playing but no audio detected - might be Spotify Connect")
                    }
                    // Trust the MediaController state in most cases
                    lastState.is_playing = mediaControllerPlaying
                } else {
                    lastState.is_playing = mediaControllerPlaying
                }
            }
            
            // Update volume
            val previousVolume = lastState.volume_percent
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            lastState.volume_percent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)
            
            // Track volume change
            if (previousVolume != lastState.volume_percent) {
                trackAudioEvent(
                    AudioEventType.VOLUME_CHANGED,
                    "Volume changed to ${lastState.volume_percent}%",
                    mapOf(
                        "previousVolume" to previousVolume,
                        "newVolume" to lastState.volume_percent,
                        "rawVolume" to currentVolume,
                        "maxVolume" to maxVolume
                    )
                )
            }
            
            sendStateUpdate()
        }
    }

    private fun sendStateUpdate(forceUpdate: Boolean = false, fromAudioEvent: Boolean = false) {
        // Cancel any pending state update
        stateUpdateRunnable?.let { 
            stateUpdateHandler.removeCallbacks(it)
        }
        
        // If from audio event, send immediately without debounce
        if (fromAudioEvent) {
            serviceScope.launch {
                performStateUpdate(forceUpdate)
            }
            return
        }
        
        // Schedule new state update with debounce for non-audio-event updates
        stateUpdateRunnable = Runnable {
            serviceScope.launch {
                // Only check shouldSendStateUpdate for non-audio-event updates
                if (!forceUpdate && !shouldSendStateUpdate(forceUpdate)) {
                    Log.d(TAG, "Skipping redundant state update")
                    return@launch
                }
                performStateUpdate(forceUpdate)
            }
        }
        
        // Post with debounce delay
        stateUpdateHandler.postDelayed(stateUpdateRunnable!!, STATE_UPDATE_DEBOUNCE_MS)
    }
    
    private suspend fun performStateUpdate(forceUpdate: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            
            // Check if we should use incremental binary updates
            if (useBinaryIncrementalUpdates && previousBinaryState != null && !forceUpdate) {
                sendIncrementalBinaryUpdates()
            } else {
                // Send full state update via BLE
                bleServerManager.sendStateUpdate(lastState, forceUpdate)
            }
            
            // Broadcast locally
            val intent = Intent(ACTION_STATE_UPDATED)
            intent.putExtra(EXTRA_JSON_DATA, gson.toJson(lastState))
            sendBroadcastCompat(intent)
            
            Log.d(TAG, "State update sent: ${lastState.track} - playing: ${lastState.is_playing}, position: ${lastState.position_ms}ms")
            Log.d(TAG, "Sent to nocturned - Track: ${lastState.track}, Playing: ${lastState.is_playing}, Audio Actually Playing: $isAudioActuallyPlaying")
            Log.d(TAG, "BLE_LOG: state_sent - Time since last: ${now - lastSentTimestamp}ms")
            
            // Update last sent state
            lastSentState = lastState.copy()
            lastSentTimestamp = now
            previousBinaryState = lastState.copy()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send state update", e)
        }
    }
    
    private suspend fun sendIncrementalBinaryUpdates() {
        val previous = previousBinaryState ?: return
        val current = lastState
        
        // Check what changed and send only those updates
        
        // Artist or Album changed - send as combined update
        if (current.artist != previous.artist || current.album != previous.album) {
            Log.d(TAG, "BLE_LOG: Sending combined artist+album update: ${current.artist} / ${current.album}")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_ARTIST_ALBUM,
                Pair(current.artist ?: "", current.album ?: "")
            )
        }
        
        // Track changed
        if (current.track != previous.track) {
            Log.d(TAG, "BLE_LOG: Sending incremental track update: ${current.track}")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_TRACK,
                current.track ?: ""
            )
        }
        
        // Duration changed
        if (current.duration_ms != previous.duration_ms) {
            Log.d(TAG, "BLE_LOG: Sending incremental duration update: ${current.duration_ms}ms")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_DURATION,
                current.duration_ms
            )
        }
        
        // Position changed significantly
        val positionDiff = kotlin.math.abs(current.position_ms - previous.position_ms)
        val positionReset = current.position_ms <= 2000 && previous.position_ms > 2000
        if (positionDiff >= POSITION_CHANGE_THRESHOLD_MS || positionReset) {
            Log.d(TAG, "BLE_LOG: Sending incremental position update: ${current.position_ms}ms (diff: $positionDiff, reset: $positionReset)")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_POSITION,
                current.position_ms
            )
        }
        
        // Play state changed
        if (current.is_playing != previous.is_playing) {
            Log.d(TAG, "BLE_LOG: Sending incremental play state update: ${current.is_playing}")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_PLAY_STATUS,
                current.is_playing
            )
        }
        
        // Volume changed
        if (current.volume_percent != previous.volume_percent) {
            Log.d(TAG, "BLE_LOG: Sending incremental volume update: ${current.volume_percent}%")
            bleServerManager.sendIncrementalUpdate(
                BinaryProtocolV2.MSG_STATE_VOLUME,
                current.volume_percent.toByte()
            )
        }
    }
    
    private fun shouldSendStateUpdate(forceUpdate: Boolean): Boolean {
        // Always send if forced
        if (forceUpdate) return true
        
        // Always send if no previous state was sent
        val previousState = lastSentState ?: return true
        
        val now = System.currentTimeMillis()
        
        // Check if the state is exactly the same as the last sent state
        val isIdenticalState = lastState.track == previousState.track &&
            lastState.artist == previousState.artist &&
            lastState.album == previousState.album &&
            lastState.is_playing == previousState.is_playing &&
            lastState.duration_ms == previousState.duration_ms &&
            lastState.position_ms == previousState.position_ms &&
            lastState.volume_percent == previousState.volume_percent
        
        // If state is identical and was sent less than 1 second ago, skip it
        if (isIdenticalState && (now - lastSentTimestamp < 1000L)) {
            Log.d(TAG, "Skipping duplicate state update (sent ${now - lastSentTimestamp}ms ago)")
            return false
        }
        
        // Check if only position changed
        val onlyPositionChanged = lastState.track == previousState.track &&
            lastState.artist == previousState.artist &&
            lastState.album == previousState.album &&
            lastState.is_playing == previousState.is_playing &&
            kotlin.math.abs(lastState.volume_percent - previousState.volume_percent) <= 5
        
        // If only position changed, apply stricter filtering
        if (onlyPositionChanged) {
            // Check minimum time interval for position-only updates
            if (now - lastSentTimestamp < MIN_UPDATE_INTERVAL_MS) {
                return false
            }
            
            // Only send if position changed significantly
            val positionDiff = kotlin.math.abs(lastState.position_ms - previousState.position_ms)
            if (positionDiff < POSITION_UPDATE_THRESHOLD_MS) {
                return false
            }
        }
        
        // For all other changes (track, play state, volume), check for rapid duplicates
        // Even for meaningful changes, prevent sending the exact same update within 1 second
        if (now - lastSentTimestamp < 1000L) {
            // Check if this is a duplicate of what we just sent
            val isDuplicate = lastState.track == previousState.track &&
                lastState.artist == previousState.artist &&
                lastState.album == previousState.album &&
                lastState.is_playing == previousState.is_playing &&
                kotlin.math.abs(lastState.position_ms - previousState.position_ms) < 1000 && // Allow small position drift
                lastState.volume_percent == previousState.volume_percent
            
            if (isDuplicate) {
                Log.d(TAG, "Skipping rapid duplicate state update (${now - lastSentTimestamp}ms since last)")
                return false
            }
        }
        
        // Send the update
        return true
    }
    
    private fun sendTimeSync() {
        serviceScope.launch {
            try {
                // Create binary time sync message
                val timeSyncPayload = BinaryProtocolV2.createTimeSyncPayload(
                    timestampMs = System.currentTimeMillis(),
                    timezone = TimeZone.getDefault().id
                )
                val timeSyncData = BinaryProtocolV2.createMessage(
                    BinaryProtocolV2.MSG_TIME_SYNC,
                    timeSyncPayload
                )
                
                // Send with HIGH priority
                bleServerManager.sendBinaryMessage(timeSyncData, com.paulcity.nocturnecompanion.ble.MessageQueue.Priority.HIGH)
                
                Log.d(TAG, "Time sync sent (binary): ${System.currentTimeMillis()} - ${TimeZone.getDefault().id}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send time sync", e)
            }
        }
    }
    
    /**
     * Send gradient colors via BLE
     */
    private fun sendGradientColors(colors: List<Int>) {
        serviceScope.launch {
            try {
                // Create binary gradient colors message
                val gradientPayload = BinaryProtocolV2.createGradientColorsPayload(colors)
                val gradientMessage = BinaryProtocolV2.createMessage(
                    BinaryProtocolV2.MSG_GRADIENT_COLORS,
                    gradientPayload
                )
                
                // Send with normal priority
                bleServerManager.sendBinaryMessage(gradientMessage, com.paulcity.nocturnecompanion.ble.MessageQueue.Priority.NORMAL)
                
                Log.d(TAG, "Gradient colors sent via BLE: ${colors.size} colors")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send gradient colors", e)
            }
        }
    }
    
    /**
     * Send weather update via BLE
     * Called by ViewModel when weather data changes
     */
    fun sendWeatherUpdate(weatherResponse: com.paulcity.nocturnecompanion.data.WeatherResponse, locationName: String, isCurrentLocation: Boolean) {
        if (::weatherBleManager.isInitialized) {
            Log.d(TAG, "Sending weather update for location: $locationName")
            
            // Cache weather data for future connections
            try {
                val prefs = getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putString("last_weather_data", gson.toJson(weatherResponse))
                    putString("last_location_name", locationName)
                    putBoolean("last_is_current_location", isCurrentLocation)
                    putLong("cache_timestamp", System.currentTimeMillis())
                    apply()
                }
                Log.d(TAG, "Weather data cached for future connections")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache weather data", e)
            }
            
            weatherBleManager.sendWeatherUpdate(weatherResponse, locationName, isCurrentLocation)
        } else {
            Log.w(TAG, "WeatherBleManager not initialized, cannot send weather update")
        }
    }
    
    /**
     * Send initial weather data when a device connects
     * Try to get current weather data, or request fresh data if none available
     */
    private fun sendInitialWeatherData() {
        Log.d(TAG, "Attempting to send initial weather data")
        
        serviceScope.launch {
            try {
                // First, try to get weather data from SharedPreferences cache
                val prefs = getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
                val cachedWeatherJson = prefs.getString("last_weather_data", null)
                val cachedLocationName = prefs.getString("last_location_name", null)
                val cachedIsCurrentLocation = prefs.getBoolean("last_is_current_location", false)
                val cacheTimestamp = prefs.getLong("cache_timestamp", 0)
                
                // Check if cache is recent (less than 30 minutes old)
                val cacheAge = System.currentTimeMillis() - cacheTimestamp
                val isCacheValid = cacheAge < 30 * 60 * 1000 // 30 minutes
                
                if (cachedWeatherJson != null && cachedLocationName != null && isCacheValid) {
                    Log.d(TAG, "Sending cached weather data to new device")
                    
                    // Parse cached weather data and send it
                    val weatherResponse = gson.fromJson(cachedWeatherJson, com.paulcity.nocturnecompanion.data.WeatherResponse::class.java)
                    sendWeatherUpdate(weatherResponse, cachedLocationName, cachedIsCurrentLocation)
                } else {
                    Log.d(TAG, "No valid cached weather data, requesting refresh from ViewModel")
                    
                    // No valid cache, request fresh data
                    val intent = Intent("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
                    sendBroadcast(intent)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending initial weather data", e)
                
                // Fallback: request fresh data
                val intent = Intent("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
                sendBroadcast(intent)
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupAudioPlaybackCallback() {
        Log.d(TAG, "Setting up AudioPlaybackCallback for system-wide audio monitoring")
        
        audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
            @android.annotation.SuppressLint("NewApi")
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                super.onPlaybackConfigChanged(configs)
                
                // Filter for actively playing audio
                val activeConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // For API 26+, we need to check if audio is actually playing
                    // Note: playerState is available in API 28+, so we'll use a workaround for API 26-27
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // API 28+ has playerState
                        configs.filter { config ->
                            try {
                                // Use reflection for playerState as it might not be available in all API levels
                                val playerStateField = config.javaClass.getDeclaredField("playerState")
                                playerStateField.isAccessible = true
                                val playerState = playerStateField.getInt(config)
                                playerState == 2 // PLAYER_STATE_STARTED = 2
                            } catch (e: Exception) {
                                // Fallback to checking audio attributes
                                config.audioAttributes?.usage == AudioAttributes.USAGE_MEDIA ||
                                config.audioAttributes?.usage == AudioAttributes.USAGE_GAME
                            }
                        }
                    } else {
                        // API 26-27, use audio attributes as fallback
                        configs.filter { config ->
                            config.audioAttributes?.usage == AudioAttributes.USAGE_MEDIA ||
                            config.audioAttributes?.usage == AudioAttributes.USAGE_GAME
                        }
                    }
                } else {
                    emptyList()
                }
                
                val previouslyPlaying = isAudioActuallyPlaying
                isAudioActuallyPlaying = activeConfigs.isNotEmpty()
                
                Log.d(TAG, "Audio playback config changed - Active: ${activeConfigs.size}, Playing: $isAudioActuallyPlaying")
                
                // Track overall audio state change
                if (previouslyPlaying != isAudioActuallyPlaying) {
                    // Update the playing state based on audio actually playing
                    if (!isAudioActuallyPlaying && lastState.is_playing) {
                        Log.d(TAG, "Audio stopped - updating is_playing to false")
                        lastState.is_playing = false
                    } else if (isAudioActuallyPlaying && !lastState.is_playing) {
                        Log.d(TAG, "Audio started - updating is_playing to true")
                        lastState.is_playing = true
                    }
                    
                    trackAudioEvent(
                        if (isAudioActuallyPlaying) AudioEventType.AUDIO_STARTED else AudioEventType.AUDIO_STOPPED,
                        if (isAudioActuallyPlaying) "Audio playback started" else "Audio playback stopped",
                        mapOf(
                            "activeConfigs" to activeConfigs.size,
                            "totalConfigs" to configs.size
                        )
                    )
                    
                    // Enable aggressive polling when audio state changes
                    enableAggressivePolling(2000) // 2 seconds of aggressive polling (reduced to prevent BLE flooding)
                }
                
                // Track playback config change
                trackAudioEvent(
                    AudioEventType.PLAYBACK_CONFIG_CHANGED,
                    "Audio playback configuration changed",
                    mapOf(
                        "activeConfigs" to activeConfigs.size,
                        "totalConfigs" to configs.size,
                        "isPlaying" to isAudioActuallyPlaying
                    )
                )
                
                // Don't enable aggressive polling on config changes - it floods BLE
                // Aggressive polling should only be for user-initiated actions
                
                // Log details about active audio sources
                activeConfigs.forEach { config ->
                    val audioDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        config.audioDeviceInfo?.productName ?: "Unknown device"
                    } else {
                        "Device info not available"
                    }
                    
                    val deviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && config.audioDeviceInfo != null) {
                        when (config.audioDeviceInfo?.type) {
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                            android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                            else -> "Other (${config.audioDeviceInfo?.type})"
                        }
                    } else {
                        "Unknown"
                    }
                    
                    Log.d(TAG, "Active audio: Device=$audioDevice, Type=$deviceType")
                    
                    // Track device connection event
                    trackAudioEvent(
                        AudioEventType.AUDIO_DEVICE_CONNECTED,
                        "Audio device active: $audioDevice",
                        mapOf(
                            "deviceName" to audioDevice,
                            "deviceType" to deviceType,
                            "audioUsage" to (config.audioAttributes?.usage ?: -1)
                        )
                    )
                    
                    // Check if audio is playing on this device (not Spotify Connect)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && config.audioDeviceInfo != null) {
                        val isLocalDevice = config.audioDeviceInfo?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                                          config.audioDeviceInfo?.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                          config.audioDeviceInfo?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        
                        if (!isLocalDevice) {
                            Log.w(TAG, "Audio playing on non-local device: ${config.audioDeviceInfo?.productName}")
                        }
                    }
                }
                
                // Don't call updateMediaState here as it causes conflicting updates
                // The state has already been updated and sent via trackAudioEvent
            }
        }
        
        // Register the callback
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback!!, null)
        Log.d(TAG, "AudioPlaybackCallback registered successfully")
    }
    
    private fun checkNotificationListenerPermission() {
        val isEnabled = isNotificationServiceEnabled()
        
        if (!isEnabled) {
            Log.w(TAG, "NotificationListenerService permission is NOT granted")
            
            // Send notification to user
            val intent = Intent(ACTION_NOTIFICATION)
            intent.putExtra(EXTRA_NOTIFICATION_MESSAGE, "Please enable Notification Access for Nocturne in Settings")
            sendBroadcastCompat(intent)
            
            // Update notification
            updateNotification("⚠️ Notification Access Required")
        } else {
            Log.i(TAG, "NotificationListenerService permission is granted")
        }
    }
    
    private fun isNotificationServiceEnabled(): Boolean {
        val myPackageName = this.packageName
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(myPackageName)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nocturne Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Nocturne service is running"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createCommandNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMMAND_CHANNEL_ID,
                "Nocturne Commands",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows received Bluetooth commands"
                setShowBadge(true)
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nocturne BLE Service")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    
    
    

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showDataReceivedNotification(data: String) {
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setContentTitle("Command Received")
            .setContentText(data)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setStyle(NotificationCompat.BigTextStyle().bigText(data))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(COMMAND_NOTIFICATION_ID, notification)
    }

    private fun sendBroadcastCompat(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startMediaPolling() {
        Log.d(TAG, "Starting media state polling")
        
        // Cancel any existing polling
        mediaPollingRunnable?.let {
            mediaPollingHandler.removeCallbacks(it)
        }
        
        mediaPollingRunnable = object : Runnable {
            override fun run() {
                // Always poll while service is running (not just when connected)
                Log.d(TAG, "Checking media state (aggressive: $isAggressivePolling)")
                
                if (currentMediaController == null) {
                    Log.w(TAG, "Polling skipped - currentMediaController is null (retry count: $pollingRetryCount)")
                    
                    // Retry logic for null controller
                    if (pollingRetryCount < MAX_POLLING_RETRIES) {
                        pollingRetryCount++
                        Log.d(TAG, "Will retry polling in 2 seconds...")
                        
                        // Try to get controller from NotificationListener
                        val latestController = NocturneNotificationListener.activeMediaController.value
                        if (latestController != null) {
                            Log.d(TAG, "Found controller from NotificationListener during retry: ${latestController.packageName}")
                            currentMediaController = latestController
                            latestController.registerCallback(mediaCallback)
                            pollingRetryCount = 0 // Reset retry count
                        }
                    } else {
                        Log.e(TAG, "Max polling retries reached. Stopping polling.")
                        stopMediaPolling()
                        return // Don't schedule next poll
                    }
                } else {
                    // Reset retry count on successful controller access
                    if (pollingRetryCount > 0) {
                        Log.d(TAG, "Controller recovered after $pollingRetryCount retries")
                        pollingRetryCount = 0
                    }
                    val controller = currentMediaController!!
                    
                    // Check for metadata changes first
                    controller.metadata?.let { metadata ->
                        val currentArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        val currentAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        val currentTrack = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val currentDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        
                        // Check if metadata changed
                        if (currentArtist != lastState.artist || 
                            currentAlbum != lastState.album || 
                            currentTrack != lastState.track ||
                            currentDuration != lastState.duration_ms) {
                            
                            Log.d(TAG, "Polling detected metadata change - Track: $currentTrack")
                            
                            // Update metadata
                            lastState.artist = currentArtist
                            lastState.album = currentAlbum
                            lastState.track = currentTrack
                            lastState.duration_ms = currentDuration
                            
                            // Send state update immediately for track change
                            sendStateUpdate(forceUpdate = true)
                        }
                    }
                    
                    // Manually check and update state
                    controller.playbackState?.let { state ->
                        val isPlaying = state.state == PlaybackState.STATE_PLAYING
                        val position = state.position
                        
                        // Check if playback state changed
                        if (lastState.is_playing != isPlaying) {
                            Log.d(TAG, "Polling detected state change - playing: $isPlaying, position: $position")
                            
                            // Update state immediately
                            lastState.is_playing = isPlaying
                            lastState.position_ms = position
                            
                            // Track the playback state change
                            trackAudioEvent(
                                AudioEventType.PLAYBACK_STATE_CHANGED,
                                if (isPlaying) "Playback started (polling)" else "Playback paused (polling)",
                                mapOf(
                                    "state" to state.state,
                                    "position_ms" to position,
                                    "track" to (lastState.track ?: "Unknown"),
                                    "detected_by" to "polling"
                                )
                            )
                            // trackAudioEvent will send the state update immediately
                        } else {
                            // Update position always
                            lastState.position_ms = position
                            
                            if (kotlin.math.abs(lastState.position_ms - position) > 1000) {
                                // Position changed significantly, but don't force update
                                // The smart sendStateUpdate will decide if it's worth sending
                                Log.d(TAG, "Polling detected position change - playing: $isPlaying, position: $position")
                                sendStateUpdate()
                            }
                        }
                    }
                }
                
                // Check if we should end aggressive polling
                if (isAggressivePolling && System.currentTimeMillis() > aggressivePollingEndTime) {
                    Log.d(TAG, "Ending aggressive polling mode")
                    isAggressivePolling = false
                }
                
                // Schedule next poll with appropriate interval
                val interval = if (isAggressivePolling) AGGRESSIVE_POLL_INTERVAL_MS else MEDIA_POLL_INTERVAL_MS
                mediaPollingHandler.postDelayed(this, interval)
            }
        }
        
        // Reset retry count when starting
        pollingRetryCount = 0
        
        // Start polling
        mediaPollingHandler.post(mediaPollingRunnable!!)
    }
    
    private fun stopMediaPolling() {
        Log.d(TAG, "Stopping media state polling")
        mediaPollingRunnable?.let {
            mediaPollingHandler.removeCallbacks(it)
        }
        mediaPollingRunnable = null
    }

    private fun enableAggressivePolling(durationMs: Long = 5000) {
        Log.d(TAG, "Enabling aggressive polling for ${durationMs}ms")
        isAggressivePolling = true
        aggressivePollingEndTime = System.currentTimeMillis() + durationMs
    }
    
    private fun trackAudioEvent(eventType: AudioEventType, message: String, details: Map<String, Any>? = null) {
        val event = AudioEvent(
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            message = message,
            details = details
        )
        
        // Add to list
        audioEvents.add(event)
        
        // Keep only last MAX_AUDIO_EVENTS
        while (audioEvents.size > MAX_AUDIO_EVENTS) {
            audioEvents.removeAt(0)
        }
        
        // Broadcast the event
        val intent = Intent(ACTION_AUDIO_EVENT)
        intent.putExtra(EXTRA_AUDIO_EVENT, gson.toJson(event))
        sendBroadcastCompat(intent)
        
        Log.d(TAG, "Audio event tracked: $eventType - $message")
        
        // Log current track overview for every audio event
        logTrackOverview()
        
        // Send state update immediately for important audio events
        val shouldSendUpdate = when (eventType) {
            AudioEventType.METADATA_CHANGED,
            AudioEventType.PLAYBACK_STATE_CHANGED,
            AudioEventType.AUDIO_STARTED,
            AudioEventType.AUDIO_STOPPED -> true
            AudioEventType.VOLUME_CHANGED -> {
                // Only send update for significant volume changes
                val volumeChange = (details?.get("newVolume") as? Int ?: 0) - 
                                  (details?.get("previousVolume") as? Int ?: 0)
                kotlin.math.abs(volumeChange) > 10
            }
            // Don't send for PLAYBACK_CONFIG_CHANGED or AUDIO_DEVICE_CONNECTED
            // as these are too frequent and don't represent user actions
            else -> false
        }
        
        if (shouldSendUpdate) {
            Log.d(TAG, "Sending state update due to $eventType event")
            sendStateUpdate(forceUpdate = true, fromAudioEvent = true)
        }
    }
    
    private fun logTrackOverview() {
        val overview = buildString {
            append("=== Current Track Overview ===\n")
            append("Track: ${lastState.track ?: "Unknown"}\n")
            append("Artist: ${lastState.artist ?: "Unknown"}\n")
            append("Album: ${lastState.album ?: "Unknown"}\n")
            append("Playing: ${lastState.is_playing}\n")
            append("Position: ${lastState.position_ms / 1000}s / ${lastState.duration_ms / 1000}s\n")
            append("Volume: ${lastState.volume_percent}%\n")
            append("Audio Actually Playing: $isAudioActuallyPlaying\n")
            append("Connected Devices: ${bleServerManager.connectedDevicesList.value.size}\n")
            append("Last Sent State: ${lastSentState?.track ?: "None"} (${if (lastSentState?.is_playing == true) "Playing" else "Paused"})\n")
            append("=============================")
        }
        Log.i(TAG, overview)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        
        // Send broadcast that service is stopping
        val intent = Intent(ACTION_SERVER_STATUS)
        intent.putExtra(EXTRA_SERVER_STATUS, "Stopped")
        intent.putExtra(EXTRA_IS_RUNNING, false)
        sendBroadcastCompat(intent)
        
        // Stop periodic time sync
        timeSyncHandler.removeCallbacks(timeSyncRunnable)
        Log.d(TAG, "Stopped periodic time sync")
        
        // Cancel any pending state updates
        stateUpdateRunnable?.let {
            stateUpdateHandler.removeCallbacks(it)
        }
        
        // Stop media polling
        stopMediaPolling()
        
        // Unregister audio playback callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback!!)
            Log.d(TAG, "AudioPlaybackCallback unregistered")
        }
        
        // Unregister settings update receiver
        try {
            unregisterReceiver(settingsUpdateReceiver)
            Log.d(TAG, "Settings update broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering settings receiver", e)
        }
        
        serviceScope.cancel()
        currentMediaController?.unregisterCallback(mediaCallback)
        
        if (::bleServerManager.isInitialized) {
            bleServerManager.stopServer()
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    // Speed test handler functions
    private fun handlePingCommand(command: Command) {
        // Immediately send pong response with the same timestamp
        val commandId = command.payload?.get("command_id") as? String ?: ""
        val timestamp = command.payload?.get("timestamp") as? Long ?: System.currentTimeMillis()
        
        val pongData = mapOf(
            "type" to "pong",
            "command_id" to commandId,
            "timestamp" to timestamp,
            "received_at" to System.currentTimeMillis()
        )
        
        bleServerManager.sendCustomData(pongData)
        Log.d(TAG, "BLE_LOG: Sent pong response for ping $commandId")
    }
    
    private fun handleSpeedTestCommand(command: Command) {
        Log.d(TAG, "BLE_LOG: Starting BT speed test sequence")
        
        val commandId = command.payload?.get("command_id") as? String ?: ""
        
        // Send acknowledgment that speed test is starting
        val startData = mapOf(
            "type" to "speed_test_started",
            "command_id" to commandId,
            "timestamp" to System.currentTimeMillis()
        )
        
        bleServerManager.sendCustomData(startData)
    }
    
    private fun handleThroughputTestCommand(command: Command) {
        // Extract the chunk info from command payload
        val commandId = command.payload?.get("command_id") as? String ?: ""
        val chunkNum = (command.payload?.get("chunk_num") as? Double)?.toInt() ?: 0
        val total = (command.payload?.get("total") as? Double)?.toInt() ?: 0
        
        // Get the size directly from payload (no actual data transferred for speed test)
        val dataSize = (command.payload?.get("size") as? Double)?.toInt() ?: 0
        
        // Send acknowledgment with chunk info
        val ackData = mapOf(
            "type" to "throughput_chunk_ack",
            "command_id" to commandId,
            "chunk_num" to chunkNum,
            "total_chunks" to total,
            "data_size" to dataSize,
            "timestamp" to System.currentTimeMillis()
        )
        
        bleServerManager.sendCustomData(ackData)
        
        Log.d(TAG, "BLE_LOG: Throughput test chunk $chunkNum/$total received (${dataSize} bytes)")
        
        // If this is the last chunk, send completion
        if (chunkNum == total - 1) {
            serviceScope.launch {
                delay(100)
                val completeData = mapOf(
                    "type" to "throughput_test_complete",
                    "total_chunks" to total,
                    "timestamp" to System.currentTimeMillis()
                )
                bleServerManager.sendCustomData(completeData)
                Log.d(TAG, "BLE_LOG: Throughput test completed")
            }
        }
    }
    
    private fun handleTestAlbumArtRequest(command: Command) {
        Log.d(TAG, "BLE_LOG: Test album art request received")
        
        // Get device address from command payload if available
        val deviceAddress = (command.payload?.get("device_address") as? String) ?: run {
            // If no device address, send to first connected device
            bleServerManager.connectedDevicesList.value.firstOrNull()?.address ?: run {
                Log.w(TAG, "BLE_LOG: No connected devices for test album art")
                return
            }
        }
        
        // Send test album art using test-specific message types
        sendTestAlbumArt(deviceAddress)
    }
    
    private fun sendTestAlbumArt(deviceAddress: String) {
        Log.d(TAG, "BLE_LOG: Sending test album art to $deviceAddress")
        
        // Try to get current album art
        val metadata = currentMediaController?.metadata
        if (metadata == null) {
            Log.w(TAG, "BLE_LOG: No media metadata available for test album art")
            return
        }
        
        val albumArtResult = bleServerManager.mediaStoreAlbumArtManager.getAlbumArt(metadata)
        if (albumArtResult == null) {
            Log.w(TAG, "BLE_LOG: No album art available for test")
            return
        }
        
        val (artData, sha256Checksum) = albumArtResult
        Log.d(TAG, "BLE_LOG: Test album art extracted: ${artData.size} bytes, SHA256: $sha256Checksum")
        
        // Calculate chunk size based on MTU
        val deviceInfo = bleServerManager.connectedDevicesList.value.find { it.address == deviceAddress }
        val mtu = deviceInfo?.mtu ?: 512
        val effectiveMtu = mtu - 3 // BLE overhead
        val jsonOverhead = 174 // Typical JSON overhead for chunks
        val chunkSize = maxOf(50, minOf(400, effectiveMtu - jsonOverhead))
        
        val totalChunks = (artData.size + chunkSize - 1) / chunkSize
        
        // Get the device object
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG, "BLE_LOG: Device not found: $deviceAddress")
            return
        }
        
        // Send test_album_art_start
        val startMessage = mapOf(
            "type" to "test_album_art_start",
            "size" to artData.size,
            "checksum" to sha256Checksum,
            "total_chunks" to totalChunks
        )
        val startJson = gson.toJson(startMessage)
        bleServerManager.sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, startJson.toByteArray())
        
        // Send chunks
        serviceScope.launch(Dispatchers.IO) {
            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, artData.size)
                val chunkData = artData.sliceArray(start until end)
                val encodedChunk = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                
                val chunkMessage = mapOf(
                    "type" to "test_album_art_chunk",
                    "checksum" to sha256Checksum,
                    "chunk_index" to i,
                    "data" to encodedChunk
                )
                
                val chunkJson = gson.toJson(chunkMessage)
                bleServerManager.sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, chunkJson.toByteArray())
                
                // Small delay between chunks to avoid overwhelming
                delay(5)
                
                if (i % 10 == 0) {
                    Log.d(TAG, "BLE_LOG: Test album art progress: ${i + 1}/$totalChunks chunks")
                }
            }
            
            // Send test_album_art_end
            val endMessage = mapOf(
                "type" to "test_album_art_end",
                "checksum" to sha256Checksum,
                "success" to true
            )
            val endJson = gson.toJson(endMessage)
            bleServerManager.sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, endJson.toByteArray())
            
            Log.d(TAG, "BLE_LOG: Test album art transfer complete")
        }
    }
    
    /**
     * Handle album art query from nocturned
     */
    private fun handleAlbumArtQuery(deviceAddress: String, requestedHash: String) {
        Log.d(TAG, "BLE_LOG: Handling album art query for hash: $requestedHash, device: $deviceAddress")
        
        // Add a 250ms delay to allow album art to be fully loaded
        serviceScope.launch {
            delay(50)
            
            // Try to get current album art from the media controller
            val metadata = currentMediaController?.metadata
            if (metadata == null) {
                Log.w(TAG, "BLE_LOG: No media metadata available for album art query")
                return@launch
            }
            
            // First, try to get album art from the media tab bitmap (most reliable source)
            val mediaTabBitmap = com.paulcity.nocturnecompanion.ui.MediaTabBitmapHolder.getBitmap()
            val albumArtResult = if (mediaTabBitmap != null) {
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                
                Log.d(TAG, "BLE_LOG: Using media tab bitmap for album art query")
                
                // Process the media tab bitmap directly
                val processedArt = processMediaTabBitmap(mediaTabBitmap, artist, album)
                if (processedArt != null) {
                    // Convert to expected format (ByteArray, String)
                    Pair(processedArt.data, processedArt.checksum)
                } else {
                    null
                }
            } else {
                // Fallback to MediaStore album art manager
                Log.d(TAG, "BLE_LOG: No media tab bitmap available, using MediaStore fallback")
                bleServerManager.mediaStoreAlbumArtManager.getAlbumArt(metadata)
            }
            
            if (albumArtResult == null) {
                Log.w(TAG, "BLE_LOG: No album art available for query from any source")
                return@launch
            }
            
            val (artData, sha256Checksum) = albumArtResult
            Log.d(TAG, "BLE_LOG: Album art extracted for query: ${artData.size} bytes, SHA256: $sha256Checksum")
            
            // Get the device object
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                Log.e(TAG, "BLE_LOG: Device not found for album art query: $deviceAddress")
                return@launch
            }
            
            // Send album art using binary protocol
            val trackId = "$requestedHash" // Use the requested hash as track ID
            bleServerManager.sendAlbumArt(artData, sha256Checksum, trackId)
            
            Log.d(TAG, "BLE_LOG: Album art query response sent via binary protocol")
        }
    }
    
    private fun handle2MPhyRequest(command: Command) {
        // Request 2M PHY from the BLE server manager
        val commandId = command.payload?.get("command_id") as? String ?: ""
        
        Log.d(TAG, "BLE_LOG: Received request to switch to 2M PHY")
        
        // Request PHY change through the BLE server manager
        val success = bleServerManager.request2MPHY()
        
        // Send response back
        val responseData = mapOf(
            "type" to "2m_phy_response",
            "command_id" to commandId,
            "success" to success,
            "timestamp" to System.currentTimeMillis()
        )
        
        bleServerManager.sendCustomData(responseData)
        
        Log.d(TAG, "BLE_LOG: 2M PHY request ${if (success) "initiated" else "failed"}")
    }
    
    /**
     * Calculate album hash using the same algorithm as nocturned backend
     * This matches the algorithm in BinaryProtocolV2.kt
     */
    private fun calculateAlbumHash(album: String, artist: String): Int {
        val combined = "$album|$artist"
        return combined.hashCode()
    }
    
    /**
     * Trigger album art extraction when we know it's available in the media tab
     */
    private fun triggerAlbumArtFromBitmap(artist: String, album: String, title: String, bitmapData: ByteArray) {
        Log.d(TAG, "🎨🎨🎨 triggerAlbumArtFromBitmap called!")
        Log.d(TAG, "🎨 Processing album art from Media tab bitmap for: $artist - $album - $title, size: ${bitmapData.size} bytes")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Decode the bitmap from the Media tab
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.size)
                if (bitmap != null) {
                    Log.d(TAG, "Successfully decoded bitmap from Media tab: ${bitmap.width}x${bitmap.height}")
                    
                    // Process the bitmap the same way AlbumArtManager would
                    val processedArt = processMediaTabBitmap(bitmap, artist, album)
                    if (processedArt != null) {
                        Log.d(TAG, "Successfully processed Media tab album art: ${processedArt.data.size} bytes, hash: ${processedArt.checksum}")
                        
                        // Send to all connected devices via the callback mechanism
                        bleServerManager.albumArtManager.setOnAlbumArtCachedCallback { cachedArt, artistCallback, albumCallback ->
                            val trackId = "$artistCallback|$albumCallback"
                            bleServerManager.sendAlbumArt(cachedArt.data, cachedArt.checksum, trackId)
                        }
                        
                        // Cache the processed art and trigger the callback
                        bleServerManager.albumArtManager.cacheAlbumArt(artist, album, processedArt.data, processedArt.checksum)
                    } else {
                        Log.e(TAG, "Failed to process Media tab bitmap")
                    }
                } else {
                    Log.e(TAG, "Failed to decode bitmap from Media tab data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing album art from Media tab bitmap", e)
            }
        }
    }
    
    private fun processMediaTabBitmap(bitmap: android.graphics.Bitmap, artist: String, album: String): com.paulcity.nocturnecompanion.ble.AlbumArtManager.CachedAlbumArt? {
        return try {
            // Create square 300x300 bitmap (same as AlbumArtManager)
            val targetSize = 300
            val squareBitmap = createSquareBitmap(bitmap, targetSize)
            
            // Compress using WebP format with low quality for smaller file size (same as MediaStoreAlbumArtManager)
            val outputStream = java.io.ByteArrayOutputStream()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                squareBitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 35, outputStream)
            } else {
                @Suppress("DEPRECATION")
                squareBitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 35, outputStream)
            }
            val imageData = outputStream.toByteArray()
            
            // Calculate SHA-256 checksum from the actual image data
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(imageData)
            val checksum = digest.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "Processed Media tab bitmap: ${imageData.size} bytes (WebP), SHA256: $checksum")
            
            // Clean up
            squareBitmap.recycle()
            
            com.paulcity.nocturnecompanion.ble.AlbumArtManager.CachedAlbumArt(
                imageData, 
                checksum, 
                com.paulcity.nocturnecompanion.ble.AlbumArtStatus.AVAILABLE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Media tab bitmap", e)
            null
        }
    }
    
    private fun createSquareBitmap(bitmap: android.graphics.Bitmap, targetSize: Int): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val minDim = kotlin.math.min(width, height)
        
        // Calculate crop coordinates to center the square
        val x = (width - minDim) / 2
        val y = (height - minDim) / 2
        
        // Create square crop
        val squareBitmap = android.graphics.Bitmap.createBitmap(bitmap, x, y, minDim, minDim)
        
        // Scale to target size
        return android.graphics.Bitmap.createScaledBitmap(squareBitmap, targetSize, targetSize, true)
    }
    
    private fun triggerAlbumArtExtraction(artist: String, album: String, title: String) {
        Log.d(TAG, "DEPRECATED: triggerAlbumArtExtraction called - should use triggerAlbumArtFromBitmap instead")
        Log.d(TAG, "Triggering album art extraction for: $artist - $album - $title")
        
        // Try to get the current media controller and metadata
        val controller = currentMediaController
        if (controller != null) {
            val metadata = controller.metadata
            if (metadata != null) {
                Log.d(TAG, "Found current media metadata, extracting album art")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val albumArtResult = bleServerManager.albumArtManager.extractAlbumArt(metadata)
                        if (albumArtResult != null) {
                            Log.d(TAG, "Successfully extracted album art from metadata: ${albumArtResult.data.size} bytes, checksum: ${albumArtResult.checksum}")
                            // The callback mechanism will automatically send it to all connected devices
                        } else {
                            Log.w(TAG, "Album art extraction from metadata failed even though media tab has it loaded")
                            
                            // If metadata extraction fails, try to create a synthetic metadata object
                            // This is a fallback when the MediaController metadata doesn't have bitmap data
                            // but the media tab successfully loaded it via other means
                            Log.d(TAG, "Attempting fallback album art extraction using media tab success indicator")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during triggered album art extraction", e)
                    }
                }
            } else {
                Log.w(TAG, "No metadata available in current media controller")
            }
        } else {
            Log.w(TAG, "No current media controller available for album art extraction")
        }
    }
    
    /**
     * Send album art for the current track to a newly connected device
     */
    private fun sendCurrentTrackAlbumArt(deviceAddress: String) {
        val metadata = currentMediaController?.metadata
        if (metadata != null) {
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            
            // Check if we have album art cached for current track
            val (status, cachedArt) = bleServerManager.albumArtManager.getArtWithStatus(artist, album)
            
            if (status == AlbumArtStatus.AVAILABLE && cachedArt != null) {
                val trackId = "$artist|$album"
                Log.d(TAG, "Sending cached album art to new device: ${cachedArt.data.size} bytes for $trackId")
                
                // Send album art to the specific device
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        bleServerManager.sendAlbumArtToDevice(deviceAddress, cachedArt.data, cachedArt.checksum, trackId)
                        Log.d(TAG, "Successfully sent current track album art to new device: $deviceAddress")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending current track album art to new device", e)
                    }
                }
            } else {
                Log.d(TAG, "No cached album art available for current track ($artist - $album), status: $status")
                
                // If no album art is cached, try to extract it
                if (status == AlbumArtStatus.NOT_REQUESTED) {
                    Log.d(TAG, "Attempting to extract album art for current track")
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val albumArtResult = bleServerManager.albumArtManager.extractAlbumArt(metadata)
                            if (albumArtResult != null) {
                                Log.d(TAG, "Extracted album art for current track: ${albumArtResult.data.size} bytes")
                                // The callback will automatically send it to all connected devices
                            } else {
                                Log.d(TAG, "No album art available to extract for current track")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error extracting album art for current track", e)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "No current media metadata available for album art")
        }
    }
}