package com.paulcity.nocturnecompanion.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.ble.BleConstants
import com.paulcity.nocturnecompanion.ble.DebugLogger
import com.paulcity.nocturnecompanion.ble.EnhancedBleServerManager
import com.paulcity.nocturnecompanion.ble.MediaStoreAlbumArtManager
import com.paulcity.nocturnecompanion.data.*
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.components.ModernTabItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.paulcity.nocturnecompanion.ui.theme.BackgroundTheme
import com.paulcity.nocturnecompanion.utils.GradientInfo
import com.paulcity.nocturnecompanion.utils.GradientUtils
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

@SuppressLint("MissingPermission")
class UnifiedMainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "UnifiedMainViewModel"
    }

    private val gson = Gson()
    private val mediaStoreAlbumArtManager: MediaStoreAlbumArtManager = MediaStoreAlbumArtManager(application)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    // State variables
    val serverStatus = mutableStateOf("Disconnected")
    val isServerRunning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    val connectedDevices = mutableStateListOf<EnhancedBleServerManager.DeviceInfo>()
    val debugLogs = mutableStateListOf<DebugLogger.DebugLogEntry>()
    val lastCommand = mutableStateOf<String?>(null)
    val lastStateUpdate = mutableStateOf<StateUpdate?>(null)
    val albumArtInfo = mutableStateOf<AlbumArtInfo?>(null)
    val audioEvents = mutableStateListOf<AudioEvent>()
    val notifications = mutableStateOf(listOf<String>())

    // UI state
    val selectedTab = mutableStateOf(9)
    val autoScrollLogs = mutableStateOf(true)
    val logFilter = mutableStateOf(BleConstants.DebugLevel.VERBOSE)
    val isBluetoothEnabled = mutableStateOf(false)
    val backgroundTheme = mutableStateOf(BackgroundTheme.GRADIENT)

    // Weather state
    val weatherResponse = mutableStateOf<WeatherResponse?>(null)
    val cities = listOf("New York", "London", "Tokyo", "Sydney")
    val selectedCity = mutableStateOf(cities[0])
    val currentLocation = mutableStateOf<Location?>(null)
    val currentLocationName = mutableStateOf<String?>(null)
    val isUsingCurrentLocation = mutableStateOf(false)

    // Gradient state
    val gradientInfo = mutableStateOf<GradientInfo?>(null)
    val isGeneratingGradient = mutableStateOf(false)
    
    // Player state
    val isPlayerExpanded = mutableStateOf(false)
    val currentPlayingTrack = mutableStateOf<StateUpdate?>(null)
    val currentAlbumArt = mutableStateOf<String?>(null)

    val tabItems = listOf(
        ModernTabItem(9, "Home", Icons.Outlined.Home, Icons.Filled.Home),
        ModernTabItem(1, "Devices", Icons.Outlined.DevicesOther, Icons.Filled.DevicesOther),
        ModernTabItem(2, "Connection", Icons.Outlined.Link, Icons.Filled.Link),
        ModernTabItem(3, "Transfer", Icons.Outlined.CloudSync, Icons.Filled.CloudSync),
        ModernTabItem(4, "Media", Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
        ModernTabItem(7, "Audio", Icons.Outlined.VolumeUp, Icons.Filled.VolumeUp),
        ModernTabItem(8, "Podcasts", Icons.Outlined.Podcasts, Icons.Filled.Podcasts),
        ModernTabItem(5, "Commands", Icons.Outlined.Terminal, Icons.Filled.Terminal),
        ModernTabItem(10, "Weather", Icons.Outlined.Cloud, Icons.Filled.Cloud)
    )

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val ktorClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    init {
        isBluetoothEnabled.value = bluetoothAdapter.isEnabled
        getCurrentLocation()
    }

    fun onBluetoothStateChanged(enabled: Boolean) {
        isBluetoothEnabled.value = enabled
        if (!enabled && isServerRunning.value) {
            stopNocturneService()
        }
    }

    fun onServerStatusUpdate(status: String, isRunning: Boolean) {
        serverStatus.value = status
        isServerRunning.value = isRunning
    }

    fun updateBackgroundTheme(theme: BackgroundTheme) {
        backgroundTheme.value = theme
    }

    fun onConnectedDevicesUpdate(devicesJson: String?) {
        devicesJson?.let {
            val devices = gson.fromJson<List<EnhancedBleServerManager.DeviceInfo>>(
                it,
                object : com.google.gson.reflect.TypeToken<List<EnhancedBleServerManager.DeviceInfo>>() {}.type
            )
            connectedDevices.clear()
            connectedDevices.addAll(devices)
        }
    }

    fun onDebugLogReceived(logJson: String?) {
        logJson?.let {
            val logEntry = gson.fromJson(it, DebugLogger.DebugLogEntry::class.java)
            debugLogs.add(logEntry)

            if (logEntry.type in listOf("ALBUM_ART", "ALBUM_ART_QUERY", "ALBUM_ART_TEST", "TEST_ALBUM_ART")) {
                updateAlbumArtInfo(logEntry)
            }

            while (debugLogs.size > 500) {
                debugLogs.removeAt(0)
            }
        }
    }

    fun onStateUpdated(stateJson: String?) {
        stateJson?.let {
            val newStateUpdate = gson.fromJson(it, StateUpdate::class.java)
            val previousState = lastStateUpdate.value
            lastStateUpdate.value = newStateUpdate
            
            // Check if track changed or if we don't have album art for current track
            val trackChanged = previousState?.track != newStateUpdate.track || 
                             previousState?.artist != newStateUpdate.artist
            val noCurrentAlbumArt = albumArtInfo.value?.bitmap == null
            
            if (trackChanged || noCurrentAlbumArt) {
                Log.d(TAG, "State updated - trackChanged: $trackChanged, noCurrentAlbumArt: $noCurrentAlbumArt")
                tryGetAlbumArt()
            }
        }
    }

    fun onCommandReceived(commandData: String?) {
        lastCommand.value = commandData ?: "Error reading command"
    }

    fun onAudioEvent(eventJson: String?) {
        eventJson?.let {
            val audioEvent = gson.fromJson(it, AudioEvent::class.java)
            audioEvents.add(audioEvent)

            while (audioEvents.size > 500) {
                audioEvents.removeAt(0)
            }
        }
    }

    fun onNotificationReceived(message: String?) {
        message?.let {
            notifications.value = notifications.value + it
            if (notifications.value.size > 10) {
                notifications.value = notifications.value.takeLast(10)
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

    // Exponential backoff retry state
    private var albumArtRetryJob: Job? = null
    private var currentRetryAttempt = 0
    private val maxRetryAttempts = 8 // Will retry up to ~1.2 seconds total
    private val initialRetryDelayMs = 5L
    
    fun tryGetAlbumArt(isRetry: Boolean = false) {
        // Cancel any existing retry job
        if (!isRetry) {
            albumArtRetryJob?.cancel()
            currentRetryAttempt = 0
        }
        
        viewModelScope.launch {
            try {
                // Clear previous album art to ensure only current track art is shown (only on first attempt)
                if (!isRetry) {
                    albumArtInfo.value = albumArtInfo.value?.copy(bitmap = null)
                    MediaTabBitmapHolder.clearBitmap()
                }
                
                val mediaSessionManager = getApplication<Application>().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(getApplication(), com.paulcity.nocturnecompanion.services.NocturneNotificationListener::class.java)
                val sessions = mediaSessionManager.getActiveSessions(component)

                // Get the currently playing session only
                val activeController = sessions.find { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                    ?: sessions.firstOrNull()

                activeController?.let { controller ->
                    val metadata = controller.metadata
                    if (metadata != null) {
                        val currentTrack = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                        val currentArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                        val currentAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                        
                        // Only proceed if this matches our current state update (current playing track)
                        val stateUpdate = lastStateUpdate.value
                        if (stateUpdate != null && 
                            (currentTrack != stateUpdate.track || currentArtist != stateUpdate.artist)) {
                            Log.d(TAG, "Skipping album art for non-current track: $currentArtist - $currentTrack")
                            return@launch
                        }

                        var art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                        if (art == null) {
                            art = mediaStoreAlbumArtManager.getAlbumArtBitmap(currentArtist, currentAlbum, currentTrack)
                        }

                        if (art != null) {
                            albumArtInfo.value = AlbumArtInfo(
                                hasArt = true,
                                bitmap = art
                            )
                            Log.d(TAG, "Current playing track album art loaded: $currentArtist - $currentTrack (attempt ${currentRetryAttempt + 1})")
                            
                            // Store only current track album art with track info
                            MediaTabBitmapHolder.storeBitmap(art, currentArtist, currentTrack)
                            
                            val intent = Intent("com.paulcity.nocturnecompanion.ALBUM_ART_AVAILABLE")
                            intent.putExtra("artist", currentArtist)
                            intent.putExtra("album", currentAlbum)
                            intent.putExtra("title", currentTrack)
                            getApplication<Application>().sendBroadcast(intent)
                            Log.d(TAG, "🎨 Sent ALBUM_ART_AVAILABLE broadcast for current track: $currentArtist - $currentAlbum")
                            
                            // Success - cancel any pending retries
                            albumArtRetryJob?.cancel()
                            currentRetryAttempt = 0
                        } else {
                            Log.d(TAG, "No album art available for current track: $currentArtist - $currentTrack (attempt ${currentRetryAttempt + 1})")
                            
                            // Retry with exponential backoff if we haven't exceeded max attempts
                            if (currentRetryAttempt < maxRetryAttempts) {
                                val delayMs = initialRetryDelayMs * (1L shl currentRetryAttempt) // 2^attempt * 5ms
                                currentRetryAttempt++
                                
                                Log.d(TAG, "Retrying album art load in ${delayMs}ms (attempt $currentRetryAttempt/$maxRetryAttempts)")
                                
                                albumArtRetryJob = viewModelScope.launch {
                                    delay(delayMs)
                                    tryGetAlbumArt(isRetry = true)
                                }
                            } else {
                                Log.w(TAG, "Max retry attempts reached for album art load: $currentArtist - $currentTrack")
                                currentRetryAttempt = 0
                            }
                        }
                    } else {
                        Log.d(TAG, "No metadata available (attempt ${currentRetryAttempt + 1})")
                        
                        // Retry with exponential backoff for missing metadata too
                        if (currentRetryAttempt < maxRetryAttempts) {
                            val delayMs = initialRetryDelayMs * (1L shl currentRetryAttempt)
                            currentRetryAttempt++
                            
                            Log.d(TAG, "Retrying metadata fetch in ${delayMs}ms (attempt $currentRetryAttempt/$maxRetryAttempts)")
                            
                            albumArtRetryJob = viewModelScope.launch {
                                delay(delayMs)
                                tryGetAlbumArt(isRetry = true)
                            }
                        } else {
                            Log.w(TAG, "Max retry attempts reached for metadata fetch")
                            currentRetryAttempt = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting album art (attempt ${currentRetryAttempt + 1})", e)
                
                // Retry on exceptions too
                if (currentRetryAttempt < maxRetryAttempts) {
                    val delayMs = initialRetryDelayMs * (1L shl currentRetryAttempt)
                    currentRetryAttempt++
                    
                    Log.d(TAG, "Retrying after exception in ${delayMs}ms (attempt $currentRetryAttempt/$maxRetryAttempts)")
                    
                    albumArtRetryJob = viewModelScope.launch {
                        delay(delayMs)
                        tryGetAlbumArt(isRetry = true)
                    }
                } else {
                    Log.e(TAG, "Max retry attempts reached after exception")
                    currentRetryAttempt = 0
                }
            }
        }
    }

    fun scanForDevices() {
        try {
            discoveredDevices.clear()
            if (bluetoothAdapter.isEnabled) {
                discoveredDevices.addAll(bluetoothAdapter.bondedDevices ?: emptySet())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for Bluetooth access", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for devices", e)
        }
    }

    fun startNocturneService() {
        Log.d(TAG, "Starting NocturneServiceBLE")
        val intent = Intent(getApplication(), NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_START
        }
        getApplication<Application>().startService(intent)
    }

    fun stopNocturneService() {
        Log.d(TAG, "Stopping NocturneServiceBLE")
        val intent = Intent(getApplication(), NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun sendTestState() {
        val intent = Intent("com.paulcity.nocturnecompanion.SEND_BLE_STATE")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }

    fun sendTestTimeSync() {
        val intent = Intent("com.paulcity.nocturnecompanion.SEND_BLE_TIME_SYNC")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }

    fun sendTestAlbumArt() {
        val intent = Intent(getApplication(), NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_TEST_ALBUM_ART
        }
        getApplication<Application>().startService(intent)
    }
    
    fun sendTestWeather() {
        Log.d(TAG, "sendTestWeather() called - sending REQUEST_WEATHER_REFRESH broadcast")
        val intent = Intent("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
        intent.setPackage(getApplication<Application>().packageName)
        
        // Use LocalBroadcastManager for Android 34+ to match receiver registration
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getApplication())
                .sendBroadcast(intent)
            Log.d(TAG, "REQUEST_WEATHER_REFRESH broadcast sent via LocalBroadcastManager")
        } else {
            getApplication<Application>().sendBroadcast(intent)
            Log.d(TAG, "REQUEST_WEATHER_REFRESH broadcast sent via regular broadcast")
        }
    }

    fun clearNotifications() {
        notifications.value = emptyList()
    }

    fun clearLogs() {
        debugLogs.clear()
    }

    fun clearAudioEvents() {
        audioEvents.clear()
    }

    fun fetchWeather(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                val response: WeatherResponse = ktorClient.get("https://api.open-meteo.com/v1/forecast") {
                    url {
                        parameters.append("latitude", latitude.toString())
                        parameters.append("longitude", longitude.toString())
                        parameters.append("hourly", "temperature_2m,relativehumidity_2m,apparent_temperature,precipitation_probability,weathercode,windspeed_10m")
                        parameters.append("daily", "weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max")
                        parameters.append("temperature_unit", "fahrenheit")
                        parameters.append("windspeed_unit", "mph")
                        parameters.append("precipitation_unit", "inch")
                    }
                }.body()
                weatherResponse.value = response
                
                // Send weather update via BLE
                sendWeatherUpdateToBle(response)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather", e)
            }
        }
    }

    fun getCurrentLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    currentLocation.value = it
                    isUsingCurrentLocation.value = true
                    fetchWeather(it.latitude, it.longitude)
                    resolveLocationName(it.latitude, it.longitude)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for location access", e)
        }
    }
    
    private fun resolveLocationName(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                val geocoder = android.location.Geocoder(getApplication(), java.util.Locale.getDefault())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        addresses.firstOrNull()?.let { address ->
                            val locationName = when {
                                !address.locality.isNullOrEmpty() -> address.locality
                                !address.adminArea.isNullOrEmpty() -> address.adminArea
                                !address.countryName.isNullOrEmpty() -> address.countryName
                                else -> "Unknown Location"
                            }
                            currentLocationName.value = locationName
                        } ?: run {
                            currentLocationName.value = "Unknown Location"
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    val address = addresses?.firstOrNull()
                    val locationName = address?.let {
                        when {
                            !it.locality.isNullOrEmpty() -> it.locality
                            !it.adminArea.isNullOrEmpty() -> it.adminArea
                            !it.countryName.isNullOrEmpty() -> it.countryName
                            else -> "Unknown Location"
                        }
                    } ?: "Unknown Location"
                    currentLocationName.value = locationName
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving location name", e)
                currentLocationName.value = "Unknown Location"
            }
        }
    }

    fun onCitySelected(city: String) {
        selectedCity.value = city
        isUsingCurrentLocation.value = false
        currentLocationName.value = null
        when (city) {
            "New York" -> fetchWeather(40.71, -74.01)
            "London" -> fetchWeather(51.51, -0.13)
            "Tokyo" -> fetchWeather(35.69, 139.69)
            "Sydney" -> fetchWeather(-33.87, 151.21)
        }
    }
    
    /**
     * Send weather update to nocturned service via BLE
     */
    private fun sendWeatherUpdateToBle(weatherResponse: WeatherResponse) {
        try {
            val locationName = if (isUsingCurrentLocation.value) {
                currentLocationName.value ?: "Current Location"
            } else {
                selectedCity.value
            }
            
            Log.d(TAG, "Sending weather update to BLE service for location: $locationName")
            
            // Send weather data to the BLE service immediately
            val intent = Intent(getApplication(), com.paulcity.nocturnecompanion.services.NocturneServiceBLE::class.java)
            intent.action = "SEND_WEATHER_UPDATE"
            intent.putExtra("weather_data", com.google.gson.Gson().toJson(weatherResponse))
            intent.putExtra("location_name", locationName)
            intent.putExtra("is_current_location", isUsingCurrentLocation.value)
            
            getApplication<Application>().startService(intent)
            
            Log.d(TAG, "Weather update sent to BLE service for immediate transmission to connected devices")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending weather update to BLE", e)
        }
    }
    
    /**
     * Refresh weather data for BLE when requested by the service
     */
    fun refreshWeatherForBle() {
        Log.d(TAG, "Refreshing weather data for BLE - isUsingCurrentLocation: ${isUsingCurrentLocation.value}, selectedCity: ${selectedCity.value}")
        
        // Send current weather data if available
        weatherResponse.value?.let { weather ->
            Log.d(TAG, "Sending existing weather data to BLE")
            sendWeatherUpdateToBle(weather)
        } ?: run {
            Log.d(TAG, "No existing weather data, fetching fresh data")
            // If no current weather data, fetch fresh data
            if (isUsingCurrentLocation.value) {
                currentLocation.value?.let {
                    Log.d(TAG, "Fetching weather for current location: ${it.latitude}, ${it.longitude}")
                    fetchWeather(it.latitude, it.longitude)
                } ?: run {
                    Log.d(TAG, "No current location available, attempting to get location")
                    getCurrentLocation()
                }
            } else {
                Log.d(TAG, "Fetching weather for selected city: ${selectedCity.value}")
                onCitySelected(selectedCity.value)
            }
        }
    }

    /**
     * Generate gradient from current album art
     */
    fun generateGradientFromAlbumArt() {
        val bitmap = albumArtInfo.value?.bitmap
        if (bitmap == null) {
            Log.w(TAG, "Cannot generate gradient: no album art bitmap available")
            return
        }

        viewModelScope.launch {
            try {
                isGeneratingGradient.value = true
                Log.d(TAG, "Starting gradient generation from album art")
                
                // Extract colors and generate gradient info
                val generatedGradientInfo = GradientUtils.generateGradientFromBitmap(bitmap, numColors = 6)
                
                gradientInfo.value = generatedGradientInfo
                
                Log.d(TAG, "Gradient generation completed. Found ${generatedGradientInfo.colors.size} colors")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error generating gradient from album art", e)
            } finally {
                isGeneratingGradient.value = false
            }
        }
    }

    /**
     * Send gradient colors to Car Thing via BLE
     */
    fun sendGradientColors() {
        val currentGradientInfo = gradientInfo.value
        if (currentGradientInfo == null) {
            Log.w(TAG, "Cannot send gradient colors: no gradient info available")
            return
        }

        Log.d(TAG, "Sending gradient colors to Car Thing via BLE")
        
        try {
            val intent = Intent(getApplication(), NocturneServiceBLE::class.java).apply {
                action = "SEND_GRADIENT_COLORS"
                // Convert ExtractedColor list to Int list for transmission
                val colorInts = currentGradientInfo.colors.map { it.color }
                putIntegerArrayListExtra("gradient_colors", ArrayList(colorInts))
            }
            getApplication<Application>().startService(intent)
            
            Log.d(TAG, "Gradient colors sent: ${currentGradientInfo.colors.size} colors")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending gradient colors to BLE", e)
        }
    }
    
    // Player control functions
    fun expandPlayer() {
        isPlayerExpanded.value = true
    }
    
    fun minimizePlayer() {
        isPlayerExpanded.value = false
    }
    
    fun togglePlayPause() {
        val track = currentPlayingTrack.value ?: return
        val command = if (track.is_playing) "pause" else "play"
        sendMediaCommand(command)
    }
    
    fun playPrevious() {
        sendMediaCommand("previous")
    }
    
    fun playNext() {
        sendMediaCommand("next")
    }
    
    fun seekTo(position: Float) {
        val track = currentPlayingTrack.value ?: return
        val seekPositionMs = (position * track.duration_ms).toLong()
        sendMediaCommand("seek", seekPositionMs)
    }
    
    private fun sendMediaCommand(command: String, valueMs: Long? = null) {
        try {
            val intent = Intent(getApplication(), NocturneServiceBLE::class.java).apply {
                action = "SEND_MEDIA_COMMAND"
                putExtra("command", command)
                valueMs?.let { putExtra("value_ms", it) }
            }
            getApplication<Application>().startService(intent)
            Log.d(TAG, "Sent media command: $command")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media command: $command", e)
        }
    }
    
    fun updateCurrentTrack(stateUpdate: StateUpdate) {
        currentPlayingTrack.value = stateUpdate
        
        // Clear album art if track changed to ensure only current track art is shown
        if (lastStateUpdate.value?.track != stateUpdate.track || 
            lastStateUpdate.value?.artist != stateUpdate.artist) {
            
            Log.d(TAG, "Track changed, clearing previous album art for: ${stateUpdate.artist} - ${stateUpdate.track}")
            
            // Clear previous album art immediately
            albumArtInfo.value = albumArtInfo.value?.copy(bitmap = null)
            MediaTabBitmapHolder.clearBitmap()
            
            // Trigger album art update for new track with retry mechanism
            tryGetAlbumArt()
        }
        
        lastStateUpdate.value = stateUpdate
    }
    
    /**
     * Manual refresh function for album art that can be called from UI
     */
    fun refreshAlbumArt() {
        Log.d(TAG, "Manual album art refresh requested")
        albumArtRetryJob?.cancel()
        currentRetryAttempt = 0
        tryGetAlbumArt()
    }
    
    /**
     * Force album art reload (cancels retries and starts fresh)
     */
    fun forceAlbumArtReload() {
        Log.d(TAG, "Force album art reload requested")
        albumArtRetryJob?.cancel()
        currentRetryAttempt = 0
        albumArtInfo.value = albumArtInfo.value?.copy(bitmap = null)
        MediaTabBitmapHolder.clearBitmap()
        tryGetAlbumArt()
    }
}

// Data class for AlbumArtInfo, assuming it's defined somewhere accessible
// If not, you should define it. For example:
data class AlbumArtInfo(
    val hasArt: Boolean,
    val checksum: String? = null,
    val size: Int = 0,
    val lastQuery: String? = null,
    val lastTransferTime: Long? = null,
    val bitmap: android.graphics.Bitmap? = null
)