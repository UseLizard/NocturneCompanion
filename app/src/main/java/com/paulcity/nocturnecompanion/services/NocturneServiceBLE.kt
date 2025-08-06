package com.paulcity.nocturnecompanion.services

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
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

class NocturneServiceBLE : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var bleServerManager: EnhancedBleServerManager
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
            
            // Track ID changed - nocturned will request album art if needed
            if (newTrackId != lastTrackId && metadata != null) {
                lastTrackId = newTrackId
                Log.d(TAG, "Track changed to: $newTrackId")
                // Album art will be sent only when nocturned requests it via album_art_query
                
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
        const val ACTION_AUDIO_EVENT = "com.paulcity.nocturnecompanion.AUDIO_EVENT"
        
        // Extras
        const val EXTRA_JSON_DATA = "json_data"
        const val EXTRA_SERVER_STATUS = "server_status"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_DEBUG_LOG = "debug_log"
        const val EXTRA_CONNECTED_DEVICES = "connected_devices"
        const val EXTRA_NOTIFICATION_MESSAGE = "notification_message"
        const val EXTRA_AUDIO_EVENT = "audio_event"
    }

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
                    observeDebugLogs()
                    observeConnectedDevices()
                    
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
        val commandHandler = { command: Command ->
            if (debugMode) {
                showDataReceivedNotification(gson.toJson(command))
            }
            
            // Broadcast command
            val intent = Intent(ACTION_COMMAND_RECEIVED)
            intent.putExtra(EXTRA_JSON_DATA, gson.toJson(command))
            sendBroadcastCompat(intent)
            
            handleCommand(command)
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
                    // Send time sync first
                    Log.d(TAG, "Sending time sync to ready device")
                    sendTimeSync()
                    
                    // Small delay between messages
                    delay(50)
                    
                    // Then send current media state
                    Log.d(TAG, "Sending initial media state to ready device")
                    sendStateUpdate()
                }
            }
        )
        bleServerManager.startServer()
        
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
                performStateUpdate()
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
                performStateUpdate()
            }
        }
        
        // Post with debounce delay
        stateUpdateHandler.postDelayed(stateUpdateRunnable!!, STATE_UPDATE_DEBOUNCE_MS)
    }
    
    private suspend fun performStateUpdate() {
        try {
            val now = System.currentTimeMillis()
            
            // Send via BLE
            bleServerManager.sendStateUpdate(lastState)
            
            // Broadcast locally
            val intent = Intent(ACTION_STATE_UPDATED)
            intent.putExtra(EXTRA_JSON_DATA, gson.toJson(lastState))
            sendBroadcastCompat(intent)
            
            Log.d(TAG, "State update sent: ${lastState.track} - playing: ${lastState.is_playing}, position: ${lastState.position_ms}ms")
            Log.d(TAG, "Sent to nocturned - Track: ${lastState.track}, Playing: ${lastState.is_playing}, Audio Actually Playing: $isAudioActuallyPlaying")
            
            // Update last sent state
            lastSentState = lastState.copy()
            lastSentTimestamp = now
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send state update", e)
        }
    }
    
    private fun shouldSendStateUpdate(forceUpdate: Boolean): Boolean {
        // Always send if forced
        if (forceUpdate) return true
        
        // Always send if no previous state was sent
        val previousState = lastSentState ?: return true
        
        // Only apply time filtering for position-only updates
        val now = System.currentTimeMillis()
        
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
        
        // For all other changes (track, play state, volume), send immediately
        return true
    }
    
    private fun sendTimeSync() {
        serviceScope.launch {
            try {
                val timeSync = mapOf(
                    "type" to "timeSync",
                    "timestamp_ms" to System.currentTimeMillis(),
                    "timezone" to TimeZone.getDefault().id
                )
                
                bleServerManager.sendStateUpdate(timeSync)
                
                Log.d(TAG, "Time sync sent: ${System.currentTimeMillis()} - ${TimeZone.getDefault().id}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send time sync", e)
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
}