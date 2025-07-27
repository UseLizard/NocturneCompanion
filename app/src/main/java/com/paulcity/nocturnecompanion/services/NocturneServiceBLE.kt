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
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ble.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.TimeZone
import android.os.Handler
import android.os.Looper

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
    
    private var currentMediaController: MediaController? = null
    private var lastTrackId: String? = null
    
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "Media metadata changed for ${currentMediaController?.packageName}")
            
            lastState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            lastState.album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            lastState.track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            lastState.duration_ms = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
            
            // Generate track ID from metadata
            val newTrackId = "${lastState.artist}|${lastState.album}|${lastState.track}"
            
            // Send album art if track changed
            if (newTrackId != lastTrackId && metadata != null) {
                lastTrackId = newTrackId
                Log.d(TAG, "Track changed, sending album art for: $newTrackId")
                bleServerManager.sendAlbumArtFromMetadata(metadata, newTrackId)
            }
            
            sendStateUpdate()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Playback state changed for ${currentMediaController?.packageName}")
            
            lastState.is_playing = state?.state == PlaybackState.STATE_PLAYING
            lastState.position_ms = state?.position ?: 0
            
            sendStateUpdate()
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
        
        // Extras
        const val EXTRA_JSON_DATA = "json_data"
        const val EXTRA_SERVER_STATUS = "server_status"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_DEBUG_LOG = "debug_log"
        const val EXTRA_CONNECTED_DEVICES = "connected_devices"
        const val EXTRA_NOTIFICATION_MESSAGE = "notification_message"
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
                // Send time sync when device connects
                Log.d(TAG, "Device connected, sending time sync")
                sendTimeSync()
                
                // Send current media state after a short delay
                serviceScope.launch {
                    delay(500) // Give time for notifications to be enabled
                    Log.d(TAG, "Sending initial media state to connected device")
                    sendStateUpdate()
                }
            }
        )
        bleServerManager.startServer()
        
        Log.d(TAG, "BLE server initialized")
    }

    private fun handleCommand(command: Command) {
        Log.d(TAG, "Handling command: ${command.command}")
        
        currentMediaController?.let { controller ->
            try {
                when (command.command) {
                    "play" -> {
                        controller.transportControls.play()
                        Log.d(TAG, "Play command executed")
                    }
                    "pause" -> {
                        controller.transportControls.pause()
                        Log.d(TAG, "Pause command executed")
                    }
                    "next" -> {
                        controller.transportControls.skipToNext()
                        Log.d(TAG, "Next command executed")
                    }
                    "previous" -> {
                        controller.transportControls.skipToPrevious()
                        Log.d(TAG, "Previous command executed")
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
                            sendStateUpdate()
                            
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
        }
    }

    private fun observeMediaControllers() {
        serviceScope.launch {
            // Check if we have an active controller immediately
            if (NocturneNotificationListener.activeMediaController.value == null) {
                Log.w(TAG, "No active media controller available at startup")
            }
            
            NocturneNotificationListener.activeMediaController.collectLatest { controller ->
                if (controller?.packageName != currentMediaController?.packageName) {
                    Log.i(TAG, "Media controller changed from ${currentMediaController?.packageName} to ${controller?.packageName}")
                    
                    // Unregister old callback
                    currentMediaController?.unregisterCallback(mediaCallback)
                    
                    // Register new callback
                    currentMediaController = controller
                    controller?.registerCallback(mediaCallback)
                    
                    // Update state immediately
                    updateMediaState(controller)
                } else if (controller == null) {
                    Log.w(TAG, "Media controller became null")
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
            bleServerManager.connectedDevicesList.collect { devices ->
                val intent = Intent(ACTION_CONNECTED_DEVICES)
                intent.putExtra(EXTRA_CONNECTED_DEVICES, gson.toJson(devices))
                sendBroadcastCompat(intent)
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
                
                // Generate track ID and send album art if needed
                val newTrackId = "${lastState.artist}|${lastState.album}|${lastState.track}"
                if (newTrackId != lastTrackId) {
                    lastTrackId = newTrackId
                    Log.d(TAG, "Track changed during controller update, sending album art for: $newTrackId")
                    bleServerManager.sendAlbumArtFromMetadata(metadata, newTrackId)
                }
            }
            
            // Update playback state
            it.playbackState?.let { state ->
                lastState.is_playing = state.state == PlaybackState.STATE_PLAYING
                lastState.position_ms = state.position
            }
            
            // Update volume
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            lastState.volume_percent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)
            
            sendStateUpdate()
        }
    }

    private fun sendStateUpdate() {
        // Cancel any pending state update
        stateUpdateRunnable?.let { 
            stateUpdateHandler.removeCallbacks(it)
        }
        
        // Schedule new state update with debounce
        stateUpdateRunnable = Runnable {
            serviceScope.launch {
                try {
                    // Send via BLE
                    bleServerManager.sendStateUpdate(lastState)
                    
                    // Broadcast locally
                    val intent = Intent(ACTION_STATE_UPDATED)
                    intent.putExtra(EXTRA_JSON_DATA, gson.toJson(lastState))
                    sendBroadcastCompat(intent)
                    
                    Log.d(TAG, "State update sent: ${lastState.track} - playing: ${lastState.is_playing}")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send state update", e)
                }
            }
        }
        
        // Post with debounce delay
        stateUpdateHandler.postDelayed(stateUpdateRunnable!!, STATE_UPDATE_DEBOUNCE_MS)
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
        
        serviceScope.cancel()
        currentMediaController?.unregisterCallback(mediaCallback)
        
        if (::bleServerManager.isInitialized) {
            bleServerManager.stopServer()
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}