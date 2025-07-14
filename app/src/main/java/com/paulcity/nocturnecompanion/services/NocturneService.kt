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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class NocturneService : Service() {

   private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
   private lateinit var bluetoothServerManager: BluetoothServerManager
   private lateinit var audioManager: AudioManager
   private lateinit var albumArtManager: AlbumArtManager
   private val gson = Gson()
   
   private val testingMode = true 

   private var currentMediaController: MediaController? = null
   private val mediaCallback = object : MediaController.Callback() {
       override fun onMetadataChanged(metadata: MediaMetadata?) {
           Log.d("NocturneService", "Media metadata changed for ${currentMediaController?.packageName}")
           lastState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
           lastState.album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
           lastState.track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
           lastState.duration_ms = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
           
           // Process album art in background
           serviceScope.launch {
               try {
                   albumArtManager.processAlbumArt(metadata, bluetoothServerManager)
               } catch (e: Exception) {
                   Log.w("NocturneService", "Failed to process album art", e)
               }
           }
           
           sendStateUpdate()
       }

       override fun onPlaybackStateChanged(state: PlaybackState?) {
           Log.d("NocturneService", "Playback state changed for ${currentMediaController?.packageName}")
           lastState.is_playing = state?.state == PlaybackState.STATE_PLAYING
           lastState.position_ms = state?.position ?: 0
           sendStateUpdate()
       }
   }

   private var lastState = StateUpdate(
       artist = null, album = null, track = null,
       duration_ms = 0, position_ms = 0, is_playing = false, volume_percent = 0
   )

   companion object {
       const val NOTIFICATION_ID = 1
       const val CHANNEL_ID = "NocturneServiceChannel"
       const val COMMAND_NOTIFICATION_ID = 2
       const val COMMAND_CHANNEL_ID = "NocturneCommandChannel"
       const val ACTION_START = "ACTION_START"
       const val ACTION_STOP = "ACTION_STOP"
       
       // --- NEW ---
       const val ACTION_COMMAND_RECEIVED = "com.paulcity.nocturnecompanion.COMMAND_RECEIVED"
       const val ACTION_STATE_UPDATED = "com.paulcity.nocturnecompanion.STATE_UPDATED"
       const val ACTION_SERVER_STATUS = "com.paulcity.nocturnecompanion.SERVER_STATUS"
       const val ACTION_NOTIFICATION = "com.paulcity.nocturnecompanion.NOTIFICATION"
       const val ACTION_UPLOAD_ALBUM_ART = "com.paulcity.nocturnecompanion.UPLOAD_ALBUM_ART"
       const val EXTRA_JSON_DATA = "json_data"
       const val EXTRA_SERVER_STATUS = "server_status"
       const val EXTRA_IS_RUNNING = "is_running"
       const val EXTRA_NOTIFICATION_MESSAGE = "notification_message"
       // --- END NEW ---
   }

   override fun onCreate() {
       super.onCreate()
       Log.d("NocturneService", "onCreate() called")
       try {
           audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
           Log.d("NocturneService", "AudioManager initialized")
           
           albumArtManager = AlbumArtManager(this)
           Log.d("NocturneService", "AlbumArtManager initialized")
           
           createNotificationChannel()
           Log.d("NocturneService", "Notification channel created")
           
           if (testingMode) {
               createCommandNotificationChannel()
               Log.d("NocturneService", "Command notification channel created")
           }
           
           startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
           Log.d("NocturneService", "Started foreground service")
           broadcastNotification("Service created and foreground notification started")
       } catch (e: Exception) {
           Log.e("NocturneService", "Error in onCreate()", e)
           throw e
       }
   }

   override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
       Log.d("NocturneService", "onStartCommand called with action: ${intent?.action}")
       try {
           when (intent?.action) {
               ACTION_START -> {
                   Log.d("NocturneService", "Starting service and SPP server")
                   broadcastNotification("Starting SPP server and media monitoring...")
                   
                   initBluetooth()
                   Log.d("NocturneService", "Bluetooth initialized")
                   
                   observeMediaControllers()
                   Log.d("NocturneService", "Media controllers observer started")
                   
                   observeBluetoothStatus()
                   Log.d("NocturneService", "Bluetooth status observer started")
                   
                   broadcastNotification("SPP server startup complete")
               }
               ACTION_STOP -> {
                   Log.d("NocturneService", "Stopping service.")
                   broadcastNotification("Stopping SPP server...")
                   stopSelf()
               }
               ACTION_UPLOAD_ALBUM_ART -> {
                   Log.d("NocturneService", "Manual album art upload requested")
                   serviceScope.launch {
                       try {
                           if (currentMediaController == null) {
                               broadcastNotification("No media controller - start playing music first")
                               return@launch
                           }
                           
                           val metadata = currentMediaController?.metadata
                           if (metadata == null) {
                               broadcastNotification("No metadata available - track may not have album art")
                               return@launch
                           }
                           
                           Log.d("NocturneService", "Attempting upload for: ${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                           broadcastNotification("Uploading album art...")
                           
                           val success = albumArtManager.uploadCurrentAlbumArt(metadata, bluetoothServerManager)
                           val message = if (success) {
                               "Album art uploaded successfully"
                           } else {
                               "Failed to upload album art - check logs for details"
                           }
                           broadcastNotification(message)
                           
                       } catch (e: Exception) {
                           Log.e("NocturneService", "Error uploading album art: ${e.javaClass.simpleName}: ${e.message}", e)
                           broadcastNotification("Upload error: ${e.message}")
                       }
                   }
               }
               else -> {
                   Log.w("NocturneService", "Unknown action: ${intent?.action}")
               }
           }
       } catch (e: Exception) {
           Log.e("NocturneService", "Error in onStartCommand", e)
           return START_NOT_STICKY
       }
       return START_STICKY
   }

   private fun initBluetooth() {
       Log.d("NocturneService", "Initializing Bluetooth...")
       try {
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
               broadcastNotification("Error: Bluetooth Connect permission not granted.")
               Log.e("NocturneService", "BLUETOOTH_CONNECT permission not granted.")
               stopSelf()
               return
           }

           bluetoothServerManager = BluetoothServerManager(this) { receivedJson ->
               if (testingMode) showDataReceivedNotification(receivedJson)
               try {
                   // --- MODIFIED ---
                   // Broadcast the raw command JSON
                   val commandIntent = Intent(ACTION_COMMAND_RECEIVED)
                   commandIntent.putExtra(EXTRA_JSON_DATA, receivedJson)
                   sendBroadcastCompat(commandIntent)
                   Log.d("NocturneService", "Command broadcast sent: $receivedJson")
                   // --- END MODIFIED ---
                   
                   val command = gson.fromJson(receivedJson, Command::class.java)
                   handleCommand(command)
               } catch (e: Exception) {
                   Log.e("NocturneService", "Failed to parse command: $receivedJson", e)
                   if (testingMode) showParseErrorNotification(receivedJson, e.message ?: "Unknown error")
               }
           }
           Log.d("NocturneService", "BluetoothServerManager created")
           
           bluetoothServerManager.startServer()
           Log.d("NocturneService", "Bluetooth server start requested")
       } catch (e: Exception) {
           Log.e("NocturneService", "Failed to initialize Bluetooth", e)
           throw e
       }
   }

   private fun observeMediaControllers() {
       serviceScope.launch {
           NocturneNotificationListener.activeMediaController.collectLatest { controller ->
               if (controller?.packageName != currentMediaController?.packageName) {
                   Log.i("NocturneService", "Media controller changed. Old: ${currentMediaController?.packageName}, New: ${controller?.packageName}")
                   
                   val oldApp = currentMediaController?.packageName ?: "None"
                   val newApp = controller?.packageName ?: "None"
                   broadcastNotification("Media controller changed: $oldApp → $newApp")
                   
                   currentMediaController?.unregisterCallback(mediaCallback)
                   currentMediaController = controller
                   controller?.registerCallback(mediaCallback)
                   
                   mediaCallback.onPlaybackStateChanged(controller?.playbackState)
                   mediaCallback.onMetadataChanged(controller?.metadata)
               }
           }
       }
   }

   private fun observeBluetoothStatus() {
       serviceScope.launch {
           bluetoothServerManager.connectionStatus.collect { status ->
               updateNotification(status)
               
               // Broadcast server status to MainActivity
               val statusIntent = Intent(ACTION_SERVER_STATUS)
               statusIntent.putExtra(EXTRA_SERVER_STATUS, status)
               statusIntent.putExtra(EXTRA_IS_RUNNING, status != "Disconnected")
               sendBroadcastCompat(statusIntent)
               Log.d("NocturneService", "Server status broadcast sent: $status")
               
               // Broadcast notification for status changes
               broadcastNotification("SPP Server: $status")
           }
       }
   }

   private fun handleCommand(command: Command) {
       serviceScope.launch { 
           if (testingMode) showCommandNotification(command)

           if (currentMediaController == null) {
               Log.w("NocturneService", "No media controller available. Requesting listener update.")
               delay(250)
           }

           val transportControls = currentMediaController?.transportControls
           if (transportControls == null) {
               Log.e("NocturneService", "Execution failed: No transport controls found for command: ${command.command}")
               return@launch
           }
           
           Log.i("NocturneService", "Executing command '${command.command}' on ${currentMediaController?.packageName}")
           
           val commandText = when (command.command) {
               "play" -> "Play"
               "pause" -> "Pause" 
               "next" -> "Next track"
               "previous" -> "Previous track"
               "seek_to" -> "Seek to ${command.value_ms}ms"
               "set_volume" -> "Set volume to ${command.value_percent}%"
               else -> "Unknown: ${command.command}"
           }
           broadcastNotification("Executing: $commandText on ${currentMediaController?.packageName}")
           
           when (command.command) {
               "play" -> transportControls.play()
               "pause" -> transportControls.pause()
               "next" -> transportControls.skipToNext()
               "previous" -> transportControls.skipToPrevious()
               "seek_to" -> command.value_ms?.let { transportControls.seekTo(it) }
               "set_volume" -> command.value_percent?.let { setVolume(it) }
               else -> {
                   Log.w("NocturneService", "Unknown command: ${command.command}")
                   broadcastNotification("Error: Unknown command '${command.command}'")
               }
           }
           delay(100)
           sendStateUpdate()
       }
   }
   
   private fun setVolume(percent: Int) {
       val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
       val targetVolume = (maxVolume * (percent / 100.0)).toInt()
       audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
   }

   private fun broadcastNotification(message: String) {
       val notificationIntent = Intent(ACTION_NOTIFICATION)
       notificationIntent.putExtra(EXTRA_NOTIFICATION_MESSAGE, message)
       sendBroadcastCompat(notificationIntent)
       Log.d("NocturneService", "Notification broadcast sent: $message")
   }

   private fun sendStateUpdate() {
       val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
       val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
       lastState.volume_percent = ((currentVolume.toFloat() / maxVolume) * 100).toInt()
       
       currentMediaController?.playbackState?.also {
           lastState.is_playing = it.state == PlaybackState.STATE_PLAYING
           lastState.position_ms = it.position
       }
       currentMediaController?.metadata?.also {
           lastState.artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)
           lastState.album = it.getString(MediaMetadata.METADATA_KEY_ALBUM)
           lastState.track = it.getString(MediaMetadata.METADATA_KEY_TITLE)
           lastState.duration_ms = it.getLong(MediaMetadata.METADATA_KEY_DURATION)
       }

       serviceScope.launch(Dispatchers.IO) {
           val jsonState = gson.toJson(lastState)
           
           // --- MODIFIED ---
           // Broadcast the state update JSON
           val stateIntent = Intent(ACTION_STATE_UPDATED)
           stateIntent.putExtra(EXTRA_JSON_DATA, jsonState)
           sendBroadcastCompat(stateIntent)
           Log.d("NocturneService", "State update broadcast sent: $jsonState")
           // --- END MODIFIED ---
           
           val dataWithNewline = jsonState + "\n"
           bluetoothServerManager.sendData(dataWithNewline)
           Log.d("NocturneService", "Sent update: $jsonState")
       }
   }

   // --- Unchanged methods from here down ---
   private fun createNotification(text: String): Notification {
       return NotificationCompat.Builder(this, CHANNEL_ID)
           .setContentTitle("Nocturne Companion")
           .setContentText(text)
           .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
           .build()
   }

   private fun updateNotification(text: String) {
       val notification = createNotification(text)
       val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
       nm.notify(NOTIFICATION_ID, notification)
   }

   private fun createNotificationChannel() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
           try {
               val serviceChannel = NotificationChannel(
                   CHANNEL_ID, "Nocturne Service Channel", NotificationManager.IMPORTANCE_LOW
               )
               val notificationManager = getSystemService(NotificationManager::class.java)
               notificationManager?.createNotificationChannel(serviceChannel)
               Log.d("NocturneService", "Main notification channel created")
           } catch (e: Exception) {
               Log.e("NocturneService", "Failed to create notification channel", e)
               throw e
           }
       }
   }
   
   private fun createCommandNotificationChannel() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
           try {
               val commandChannel = NotificationChannel(
                   COMMAND_CHANNEL_ID, "Nocturne Commands", NotificationManager.IMPORTANCE_HIGH
               )
               commandChannel.description = "Shows received commands from Car Thing"
               val notificationManager = getSystemService(NotificationManager::class.java)
               notificationManager?.createNotificationChannel(commandChannel)
               Log.d("NocturneService", "Command notification channel created")
           } catch (e: Exception) {
               Log.e("NocturneService", "Failed to create command notification channel", e)
               throw e
           }
       }
   }

   private fun showCommandNotification(command: Command) {
       val commandText = when (command.command) {
           "play" -> "▶️ Play"
           "pause" -> "⏸️ Pause"
           "next" -> "⏭️ Next"
           "previous" -> "⏮️ Previous"
           "set_volume" -> "🔊 Volume: ${command.value_percent ?: "?"}"
           "seek_to" -> "⏩ Seek: ${command.value_ms ?: "?"}"
           else -> "🎛️ ${command.command}"
       }
       
       val statusText = if (currentMediaController != null) {
           "📱 Executing on ${currentMediaController?.packageName}"
       } else {
           "❌ No media controller found!"
       }
       
       val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
           .setContentTitle("Command: $commandText")
           .setContentText(statusText)
           .setSmallIcon(android.R.drawable.ic_media_play)
           .setTimeoutAfter(3000)
           .build()
           
       val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
       nm.notify(COMMAND_NOTIFICATION_ID, notification)
   }

   private fun showDataReceivedNotification(data: String) {
       val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
           .setContentTitle("📡 Data Received")
           .setContentText("Raw: ${data.take(50)}${if(data.length > 50) "..." else ""}")
           .setSmallIcon(android.R.drawable.stat_notify_sync)
           .setTimeoutAfter(2000)
           .build()
           
       val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
       nm.notify(COMMAND_NOTIFICATION_ID + 1, notification)
   }

   private fun showParseErrorNotification(data: String, error: String) {
       val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
           .setContentTitle("❌ Parse Error")
           .setContentText("Data: ${data.take(30)} | Error: ${error.take(20)}")
           .setSmallIcon(android.R.drawable.stat_notify_error)
           .setTimeoutAfter(5000)
           .build()
           
       val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
       nm.notify(COMMAND_NOTIFICATION_ID + 2, notification)
   }

   private fun sendBroadcastCompat(intent: Intent) {
       try {
           if (Build.VERSION.SDK_INT >= 34) {
               LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
               Log.d("NocturneService", "Broadcast sent via LocalBroadcastManager: ${intent.action}")
           } else {
               sendBroadcast(intent)
               Log.d("NocturneService", "Broadcast sent via system: ${intent.action}")
           }
           
           // Also try system broadcast as fallback for Android 14+
           if (Build.VERSION.SDK_INT >= 34) {
               try {
                   sendBroadcast(intent)
                   Log.d("NocturneService", "Fallback system broadcast also sent: ${intent.action}")
               } catch (e: Exception) {
                   Log.w("NocturneService", "Fallback system broadcast failed", e)
               }
           }
       } catch (e: Exception) {
           Log.e("NocturneService", "Failed to send broadcast: ${intent.action}", e)
       }
   }

   override fun onDestroy() {
       super.onDestroy()
       
       // Broadcast that server is stopping
       broadcastNotification("Service shutting down - SPP server stopped")
       val statusIntent = Intent(ACTION_SERVER_STATUS)
       statusIntent.putExtra(EXTRA_SERVER_STATUS, "Disconnected")
       statusIntent.putExtra(EXTRA_IS_RUNNING, false)
       sendBroadcastCompat(statusIntent)
       
       serviceScope.cancel()
       if (::bluetoothServerManager.isInitialized) {
           bluetoothServerManager.stopServer()
       }
       currentMediaController?.unregisterCallback(mediaCallback)
       Log.i("NocturneService", "Service destroyed.")
   }

   override fun onBind(intent: Intent?): IBinder? = null
}