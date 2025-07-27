package com.paulcity.nocturnecompanion.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.data.Command
import com.paulcity.nocturnecompanion.data.StateUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.coroutineContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced BLE Server Manager with improved support for:
 * - Proper GATT server implementation
 * - Multi-device connection support
 * - Characteristic notifications
 * - Connection quality monitoring
 * - Album art transfer
 * - Debug logging
 */
@Suppress("MissingPermission")
class EnhancedBleServerManager(
    private val context: Context,
    private val onCommandReceived: (Command) -> Unit,
    private val onDeviceConnected: ((BluetoothDevice) -> Unit)? = null
) {
    companion object {
        private const val TAG = "EnhancedBleServer"
        private const val MAX_NOTIFICATION_SIZE = 512 // Conservative MTU assumption
        private const val ALBUM_ART_CHUNK_SIZE = 16384 // 16KB chunks for album art
        private const val MAX_RETRIES = 3
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val gson = Gson()
    
    // Connection management
    private val connectedDevices = ConcurrentHashMap<String, DeviceContext>()
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus
    
    // Album art management
    private val albumArtManager = AlbumArtManager()
    private val mediaStoreAlbumArtManager = MediaStoreAlbumArtManager(context)
    private val albumArtTransferJobs = ConcurrentHashMap<String, Job>()
    
    // Debug logging
    private val debugLogger = DebugLogger()
    private val _debugLogs = MutableSharedFlow<DebugLogger.DebugLogEntry>(replay = 100)
    val debugLogs = _debugLogs.asSharedFlow()
    
    // Track last sent state to avoid duplicates
    private var lastSentStateJson: String = ""
    
    // Device tracking for UI
    private val _connectedDevicesList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val connectedDevicesList: StateFlow<List<DeviceInfo>> = _connectedDevicesList
    
    data class DeviceContext(
        val device: BluetoothDevice,
        val connectionTime: Long = System.currentTimeMillis(),
        var mtu: Int = 23, // Default BLE MTU
        val subscriptions: MutableSet<String> = mutableSetOf(),
        var lastActivity: Long = System.currentTimeMillis()
    )
    
    data class DeviceInfo(
        val address: String,
        val name: String,
        val mtu: Int,
        val connectionDuration: Long,
        val subscriptions: List<String>
    )
    
    data class AlbumArtQuery(
        val type: String = "album_art_query",
        val track_id: String,
        val checksum: String
    )
    
    data class AlbumArtStart(
        val type: String = "album_art_start",
        val track_id: String,
        val checksum: String,
        val size: Int,
        val total_chunks: Int
    )
    
    data class AlbumArtChunk(
        val type: String = "album_art_chunk",
        val checksum: String,
        val chunk_index: Int,
        val data: String // Base64 encoded chunk
    )
    
    data class AlbumArtEnd(
        val type: String = "album_art_end",
        val checksum: String,
        val success: Boolean
    )
    
    private fun hasPermission(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    fun startServer() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            _connectionStatus.value = "Bluetooth Disabled"
            return
        }
        
        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) || 
                !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                Log.e(TAG, "Missing required Bluetooth permissions")
                _connectionStatus.value = "Permission Denied"
                return
            }
        }
        
        setupGattServer()
        startAdvertising()
        
        debugLogger.info("SERVER_STATE", "BLE server started")
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    private fun setupGattServer() {
        val service = BluetoothGattService(
            BleConstants.NOCTURNE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Command receive characteristic (write)
        val commandChar = BluetoothGattCharacteristic(
            BleConstants.COMMAND_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // State transmit characteristic (notify)
        val stateChar = BluetoothGattCharacteristic(
            BleConstants.STATE_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val stateDescriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        stateChar.addDescriptor(stateDescriptor)
        
        // Album art characteristic (notify, larger chunks)
        val albumArtChar = BluetoothGattCharacteristic(
            BleConstants.ALBUM_ART_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val albumArtDescriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        albumArtChar.addDescriptor(albumArtDescriptor)
        
        service.addCharacteristic(commandChar)
        service.addCharacteristic(stateChar)
        service.addCharacteristic(albumArtChar)
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
        
        Log.d(TAG, "GATT server setup complete")
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Log connection status for debugging
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val statusName = when (status) {
                    0x01 -> "GATT_INVALID_HANDLE"
                    0x02 -> "GATT_READ_NOT_PERMITTED" 
                    0x03 -> "GATT_WRITE_NOT_PERMITTED"
                    0x04 -> "GATT_INVALID_PDU"
                    0x05 -> "GATT_INSUFFICIENT_AUTHENTICATION"
                    0x06 -> "GATT_REQUEST_NOT_SUPPORTED"
                    0x07 -> "GATT_INVALID_OFFSET"
                    0x08 -> "GATT_INSUFFICIENT_AUTHORIZATION"
                    0x09 -> "GATT_PREPARE_QUEUE_FULL"
                    0x0a -> "GATT_ATTRIBUTE_NOT_FOUND"
                    0x0b -> "GATT_ATTRIBUTE_NOT_LONG"
                    0x0c -> "GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE"
                    0x0d -> "GATT_INVALID_ATTRIBUTE_LENGTH"
                    0x0e -> "GATT_UNLIKELY_ERROR"
                    0x0f -> "GATT_INSUFFICIENT_ENCRYPTION"
                    0x10 -> "GATT_UNSUPPORTED_GROUP_TYPE"
                    0x11 -> "GATT_INSUFFICIENT_RESOURCES"
                    else -> "UNKNOWN_ERROR_$status"
                }
                
                Log.e(TAG, "Connection state change with error - Status: $status ($statusName), State: $newState")
                debugLogger.error(
                    "CONNECTION_ERROR",
                    "Connection state change failed",
                    mapOf(
                        "device" to device.address,
                        "status_code" to status.toString(),
                        "status_name" to statusName,
                        "new_state" to newState.toString()
                    )
                )
            }
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Device connected: ${device.address}")
                        val deviceContext = DeviceContext(device)
                        connectedDevices[device.address] = deviceContext
                        updateConnectionStatus()
                        updateConnectedDevicesList()
                        
                        // Try to request higher MTU for better performance
                        gattServer?.connect(device, false)
                        
                        debugLogger.info(
                            "CONNECTION",
                            "Device connected",
                            mapOf("address" to device.address, "name" to (device.name ?: "Unknown"))
                        )
                        
                        // Notify callback if provided
                        onDeviceConnected?.invoke(device)
                    } else {
                        Log.e(TAG, "Connection failed for device: ${device.address}")
                    }
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Device disconnected: ${device.address} (status: $status)")
                    
                    // Cancel any ongoing album art transfer
                    albumArtTransferJobs[device.address]?.cancel()
                    albumArtTransferJobs.remove(device.address)
                    
                    connectedDevices.remove(device.address)
                    updateConnectionStatus()
                    updateConnectedDevicesList()
                    
                    debugLogger.info(
                        "CONNECTION",
                        "Device disconnected",
                        mapOf(
                            "address" to device.address,
                            "status" to status.toString()
                        )
                    )
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                    }
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "Write request from ${device.address} to ${characteristic.uuid}, responseNeeded: $responseNeeded, value size: ${value?.size}")
            
            if (characteristic.uuid == BleConstants.COMMAND_RX_CHAR_UUID && value != null) {
                try {
                    val jsonStr = String(value, Charsets.UTF_8).trim()
                    Log.d(TAG, "Received command: $jsonStr")
                    
                    debugLogger.debug(
                        "COMMAND_RECEIVED",
                        "Raw command data",
                        mapOf("data" to jsonStr, "device" to device.address)
                    )
                    
                    // Parse command
                    val command = gson.fromJson(jsonStr, Command::class.java)
                    
                    // Handle special commands
                    when (command.command) {
                        "album_art_needed", "album_art_query" -> {
                            // Extract track_id and checksum from payload
                            val payload = command.payload
                            val trackId = payload?.get("track_id") as? String ?: ""
                            val checksum = payload?.get("checksum") as? String ?: ""
                            
                            debugLogger.info(
                                "ALBUM_ART",
                                "Album art requested",
                                mapOf("track_id" to trackId, "checksum" to checksum, "command" to command.command)
                            )
                            
                            // For album_art_query, the checksum is the artist/album hash
                            // We need to check if we have album art for this hash
                            handleAlbumArtQuery(device, trackId, checksum)
                        }
                        else -> {
                            // Regular command
                            onCommandReceived(command)
                            
                            debugLogger.info(
                                "COMMAND_RECEIVED",
                                "Command processed: ${command.command}",
                                mapOf("device" to device.address)
                            )
                        }
                    }
                    
                    // Success ACK removed - rely on state updates for confirmation
                    // The "received" ACK is sufficient, and state updates provide
                    // the actual confirmation that the command was executed
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                    }
                    
                    // Update last activity
                    connectedDevices[device.address]?.lastActivity = System.currentTimeMillis()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing command", e)
                    
                    debugLogger.error(
                        "ERROR",
                        "Command parse error: ${e.message}",
                        mapOf("device" to device.address)
                    )
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                    }
                }
            }
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "Descriptor write request from ${device.address}")
            
            if (descriptor.uuid == BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                val charUuid = descriptor.characteristic.uuid
                val enableNotifications = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ?: false
                
                if (enableNotifications) {
                    connectedDevices[device.address]?.subscriptions?.add(charUuid.toString())
                    Log.d(TAG, "Notifications enabled for $charUuid")
                    
                    debugLogger.info(
                        "SUBSCRIPTION",
                        "Notifications enabled",
                        mapOf("char" to charUuid.toString(), "device" to device.address)
                    )
                } else {
                    connectedDevices[device.address]?.subscriptions?.remove(charUuid.toString())
                    Log.d(TAG, "Notifications disabled for $charUuid")
                    
                    debugLogger.info(
                        "SUBSCRIPTION",
                        "Notifications disabled",
                        mapOf("char" to charUuid.toString(), "device" to device.address)
                    )
                }
                
                updateConnectedDevicesList()
                
                CoroutineScope(Dispatchers.IO).launch {
                    _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                }
            }
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "MTU changed for ${device.address}: $mtu")
            connectedDevices[device.address]?.mtu = mtu
            updateConnectedDevicesList()
            
            debugLogger.info(
                "MTU_CHANGED",
                "MTU changed",
                mapOf("device" to device.address, "mtu" to mtu)
            )
            
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    private fun startAdvertising() {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            _connectionStatus.value = "Advertising Not Supported"
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // Device name in scan response instead
            .addServiceUuid(ParcelUuid(BleConstants.NOCTURNE_SERVICE_UUID))
            .build()
        
        // Scan response can include additional data like device name
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
            _connectionStatus.value = "Advertising"
            
            debugLogger.info("SERVER_STATE", "Advertising started")
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising start failed: $errorCode")
            _connectionStatus.value = "Advertising Failed: $errorCode"
            
            debugLogger.error(
                "ERROR",
                "Advertising failed",
                mapOf("error_code" to errorCode)
            )
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    fun sendStateUpdate(state: StateUpdate) {
        val stateJson = gson.toJson(state)
        
        // Only send if state actually changed (excluding position for comparison)
        val stateForComparison = state.copy(position_ms = 0)
        val compareJson = gson.toJson(stateForComparison)
        
        if (compareJson != lastSentStateJson) {
            lastSentStateJson = compareJson
            
            debugLogger.verbose(
                "STATE_SENT",
                "Sending state update",
                mapOf("state" to stateJson)
            )
            
            sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, stateJson)
            
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    fun sendStateUpdate(data: Any) {
        val json = gson.toJson(data)
        
        debugLogger.verbose(
            "STATE_SENT",
            "Sending custom state",
            mapOf("data" to json)
        )
        
        sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, json)
        
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    private fun sendNotificationToAll(characteristicUuid: UUID, data: String) {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        
        connectedDevices.values.forEach { context ->
            if (context.subscriptions.contains(characteristicUuid.toString())) {
                sendNotificationToDevice(context.device, characteristicUuid, dataBytes)
            }
        }
    }
    
    private fun sendNotificationToDevice(device: BluetoothDevice, characteristicUuid: UUID, data: ByteArray) {
        val service = gattServer?.getService(BleConstants.NOCTURNE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(characteristicUuid)
        
        if (characteristic != null) {
            // Get the actual MTU for this device, accounting for ATT header
            val deviceContext = connectedDevices[device.address]
            val effectiveMtu = (deviceContext?.mtu ?: 23) - 3 // 3 bytes for ATT header
            
            // Check if data fits within MTU
            if (data.size <= effectiveMtu) {
                characteristic.value = data
                try {
                    val sent = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                    if (!sent) {
                        Log.w(TAG, "Failed to send notification to ${device.address}")
                        debugLogger.error(
                            "NOTIFICATION_FAILED",
                            "Failed to send notification",
                            mapOf(
                                "device" to device.address,
                                "char_uuid" to characteristicUuid.toString(),
                                "data_size" to data.size.toString()
                            )
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException sending notification", e)
                    debugLogger.error(
                        "NOTIFICATION_ERROR",
                        "Security exception during notification",
                        mapOf(
                            "device" to device.address,
                            "error" to e.message.orEmpty()
                        )
                    )
                }
            } else {
                // Data exceeds MTU - implement chunking for large messages
                Log.w(TAG, "Data size ${data.size} exceeds MTU-3 ($effectiveMtu) for device ${device.address}")
                debugLogger.warning(
                    "DATA_TOO_LARGE",
                    "Notification data exceeds MTU, chunking required",
                    mapOf(
                        "device" to device.address,
                        "data_size" to data.size.toString(),
                        "mtu" to deviceContext?.mtu.toString(),
                        "effective_mtu" to effectiveMtu.toString()
                    )
                )
                
                // For state updates, try to reduce size by removing null fields
                if (characteristicUuid == BleConstants.STATE_TX_CHAR_UUID) {
                    try {
                        // Parse the JSON and remove null/empty fields
                        val jsonStr = String(data, Charsets.UTF_8)
                        val json = gson.fromJson(jsonStr, Map::class.java)
                        val compactJson = json.filterValues { it != null && it != "" }
                        val compactData = gson.toJson(compactJson).toByteArray()
                        
                        if (compactData.size <= effectiveMtu) {
                            // Compact version fits, send it
                            characteristic.value = compactData
                            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                            Log.i(TAG, "Sent compacted notification (${compactData.size} bytes, original ${data.size} bytes)")
                            return
                        }
                        
                        // Still too large, truncate metadata fields
                        val mutableJson = compactJson.toMutableMap()
                        val maxFieldLength = 50 // Truncate long strings to 50 chars
                        
                        listOf("artist", "album", "track").forEach { field ->
                            val value = mutableJson[field] as? String
                            if (value != null && value.length > maxFieldLength) {
                                mutableJson[field] = value.substring(0, maxFieldLength) + "..."
                            }
                        }
                        
                        val truncatedJsonData = gson.toJson(mutableJson).toByteArray()
                        if (truncatedJsonData.size <= effectiveMtu) {
                            characteristic.value = truncatedJsonData
                            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                            Log.w(TAG, "Sent truncated metadata notification (${truncatedJsonData.size} bytes)")
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error compacting JSON", e)
                    }
                }
                
                // Last resort: drop the notification to prevent disconnection
                Log.e(TAG, "Unable to send notification - data too large even after compaction")
                debugLogger.error(
                    "NOTIFICATION_DROPPED",
                    "Notification dropped due to size constraints",
                    mapOf(
                        "device" to device.address,
                        "data_size" to data.size.toString(),
                        "char_uuid" to characteristicUuid.toString()
                    )
                )
            }
        }
        
        // Emit debug log asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    fun sendAlbumArtFromMetadata(metadata: android.media.MediaMetadata?, trackId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val cacheKey = "$artist|$album|$title"
            
            // Add detailed logging to help diagnose the issue
            debugLogger.info(
                "ALBUM_ART",
                "Checking album art for track",
                mapOf(
                    "artist" to artist,
                    "album" to album, 
                    "title" to title,
                    "has_metadata" to (metadata != null).toString()
                )
            )

            // Try MediaStore approach which is more reliable
            mediaStoreAlbumArtManager.getAlbumArt(metadata)?.let { (artData, checksum) ->
                debugLogger.info(
                    "ALBUM_ART",
                    "Album art extracted successfully",
                    mapOf(
                        "size" to artData.size.toString(),
                        "checksum" to checksum,
                        "track_id" to trackId,
                        "source" to "MediaStore"
                    )
                )
                sendAlbumArt(artData, checksum, cacheKey)
            } ?: run {
                // Enhanced logging to understand why album art is not available
                val hasArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                val hasArt2 = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null  
                val hasIcon = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null
                
                debugLogger.info(
                    "NOTIFICATION_SENT",
                    "No album art available from MediaStore or MediaMetadata",
                    mapOf(
                        "track_id" to trackId,
                        "has_ALBUM_ART" to hasArt.toString(),
                        "has_ART" to hasArt2.toString(),
                        "has_DISPLAY_ICON" to hasIcon.toString(),
                        "metadata_null" to (metadata == null).toString(),
                        "artist" to artist,
                        "album" to album,
                        "title" to title
                    )
                )
            }
            
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    fun sendAlbumArt(albumArtData: ByteArray, checksum: String, trackId: String) {
        // For now, immediately start the transfer without waiting for a query
        // This simplifies the implementation until nocturned supports the query/response pattern
        
        debugLogger.info(
            "ALBUM_ART",
            "Starting album art transfer immediately",
            mapOf("track_id" to trackId, "checksum" to checksum, "size" to albumArtData.size.toString())
        )
        
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
        
        // Cache the album art data
        albumArtManager.getArtFromCache(trackId) ?: run {
            // Add to cache if not already there
            try {
                val cacheField = AlbumArtManager::class.java.getDeclaredField("albumArtCache")
                cacheField.isAccessible = true
                val cache = cacheField.get(albumArtManager) as android.util.LruCache<String, AlbumArtManager.CachedAlbumArt>
                cache.put(trackId, AlbumArtManager.CachedAlbumArt(albumArtData, checksum))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add to cache", e)
            }
        }
        
        // Start transfer immediately
        startAlbumArtTransfer(trackId, checksum)
    }

    fun startAlbumArtTransfer(trackId: String, checksum: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // This function is called when nocturned confirms it needs the album art
            // Use trackId as the cache key for now - ideally we'd have the full metadata
            val cachedArt = albumArtManager.getArtFromCache(trackId)
            if (cachedArt != null && cachedArt.checksum == checksum) {
                val albumArtData = cachedArt.data
                // Send to all connected devices that have subscribed to album art
                connectedDevices.values.forEach { context ->
                    if (context.subscriptions.contains(BleConstants.ALBUM_ART_TX_CHAR_UUID.toString())) {
                        // Cancel any existing transfer for this specific device
                        albumArtTransferJobs[context.device.address]?.cancel()

                        val job = launch {
                            sendAlbumArtToDevice(context.device, albumArtData, checksum, trackId)
                        }
                        albumArtTransferJobs[context.device.address] = job
                    }
                }
            } else {
                Log.w(TAG, "Album art not found in cache for transfer: $trackId")
                debugLogger.warning(
                    "ALBUM_ART",
                    "Album art not in cache",
                    mapOf("track_id" to trackId, "checksum" to checksum)
                )
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    private suspend fun sendAlbumArtToDevice(
        device: BluetoothDevice,
        albumArtData: ByteArray,
        checksum: String,
        trackId: String
    ) {
        try {
            val deviceContext = connectedDevices[device.address] ?: return
            val effectiveMtu = deviceContext.mtu - 3 // Account for BLE overhead
            
            // Account for JSON overhead when calculating chunk size
            // JSON structure: {"type":"album_art_chunk","checksum":"...","chunk_index":0,"data":"..."}
            // Estimated overhead: ~100 bytes for JSON structure + checksum
            val jsonOverhead = 150 // Conservative estimate for JSON wrapper
            val maxDataSize = effectiveMtu - jsonOverhead
            val chunkSize = minOf(maxDataSize, 400) // Conservative chunk size
            
            // Convert to base64 for transmission
            val base64Data = android.util.Base64.encodeToString(albumArtData, android.util.Base64.NO_WRAP)
            val totalChunks = (base64Data.length + chunkSize - 1) / chunkSize
            
            Log.d(TAG, "Starting album art transfer to ${device.address}: ${albumArtData.size} bytes, $totalChunks chunks, MTU: ${deviceContext.mtu}, chunk size: $chunkSize")
            
            // Send start notification
            val startMsg = AlbumArtStart(
                track_id = trackId,
                checksum = checksum,
                size = albumArtData.size,
                total_chunks = totalChunks
            )
            
            val startData = gson.toJson(startMsg).toByteArray()
            sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, startData)
            
            debugLogger.info(
                "ALBUM_ART",
                "Album art transfer started",
                mapOf(
                    "device" to device.address,
                    "size" to albumArtData.size.toString(),
                    "chunks" to totalChunks.toString()
                )
            )
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
            
            delay(50) // Small delay between start and chunks
            
            // Send chunks
            for (i in 0 until totalChunks) {
                val currentJob = albumArtTransferJobs[device.address]
                if (currentJob == null || !currentJob.isActive) break // Check if coroutine was cancelled
                
                val start = i * chunkSize
                val end = minOf(start + chunkSize, base64Data.length)
                val chunkData = base64Data.substring(start, end)
                
                val chunk = AlbumArtChunk(
                    checksum = checksum,
                    chunk_index = i,
                    data = chunkData
                )
                
                val chunkJson = gson.toJson(chunk).toByteArray()
                
                // Log if chunk is approaching MTU limit
                if (chunkJson.size > effectiveMtu) {
                    Log.e(TAG, "Album art chunk too large! Chunk size: ${chunkJson.size}, MTU limit: $effectiveMtu")
                    debugLogger.error(
                        "CHUNK_TOO_LARGE", 
                        "Album art chunk exceeds MTU",
                        mapOf(
                            "chunk_size" to chunkJson.size.toString(),
                            "mtu_limit" to effectiveMtu.toString(),
                            "chunk_index" to i.toString()
                        )
                    )
                }
                
                sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, chunkJson)
                
                if (i % 10 == 0) {
                    debugLogger.debug(
                        "ALBUM_ART",
                        "Chunk progress",
                        mapOf(
                            "device" to device.address,
                            "progress" to "${i + 1}/$totalChunks"
                        )
                    )
                    _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
                }
                
                delay(20) // Small delay between chunks to avoid overwhelming the connection
            }
            
            // Send end notification
            val endMsg = AlbumArtEnd(checksum = checksum, success = true)
            val endData = gson.toJson(endMsg).toByteArray()
            sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, endData)
            
            Log.d(TAG, "Album art transfer completed to ${device.address}")
            
            debugLogger.info(
                "ALBUM_ART",
                "Album art transfer completed",
                mapOf("device" to device.address, "checksum" to checksum)
            )
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during album art transfer", e)
            
            debugLogger.error(
                "ERROR",
                "Album art transfer failed: ${e.message}",
                mapOf("device" to device.address)
            )
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
            
            // Send failure notification
            try {
                val endMsg = AlbumArtEnd(checksum = checksum, success = false)
                val endData = gson.toJson(endMsg).toByteArray()
                sendNotificationToDevice(device, BleConstants.ALBUM_ART_TX_CHAR_UUID, endData)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to send error notification", e2)
            }
        }
    }
    
    private fun updateConnectionStatus() {
        _connectionStatus.value = when (connectedDevices.size) {
            0 -> if (advertiser != null) "Advertising" else "Disconnected"
            1 -> "Connected to ${connectedDevices.values.first().device.name ?: "Unknown"}"
            else -> "Connected to ${connectedDevices.size} devices"
        }
    }
    
    fun hasConnectedDevices(): Boolean {
        return connectedDevices.isNotEmpty()
    }
    
    private fun updateConnectedDevicesList() {
        val devices = connectedDevices.values.map { context ->
            DeviceInfo(
                address = context.device.address,
                name = context.device.name ?: "Unknown Device",
                mtu = context.mtu,
                connectionDuration = System.currentTimeMillis() - context.connectionTime,
                subscriptions = context.subscriptions.map { uuid ->
                    when (uuid) {
                        BleConstants.STATE_TX_CHAR_UUID.toString() -> "State Updates"
                        BleConstants.ALBUM_ART_TX_CHAR_UUID.toString() -> "Album Art"
                        else -> "Unknown"
                    }
                }
            )
        }
        _connectedDevicesList.value = devices
    }
    
    private fun handleAlbumArtQuery(device: BluetoothDevice, trackId: String, requestedChecksum: String) {
        debugLogger.info(
            "ALBUM_ART_QUERY",
            "Processing album art query",
            mapOf("track_id" to trackId, "checksum" to requestedChecksum)
        )
        
        // Get current album art from media session or media store
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get album art based on current media
                val albumArtResult = mediaStoreAlbumArtManager?.getAlbumArtFromCurrentMedia()
                
                if (albumArtResult != null) {
                    val (artData, checksum) = albumArtResult
                    
                    debugLogger.info(
                        "ALBUM_ART_QUERY",
                        "Found album art",
                        mapOf(
                            "size" to artData.size.toString(),
                            "checksum" to checksum,
                            "requested_checksum" to requestedChecksum
                        )
                    )
                    
                    // Send the album art
                    sendAlbumArt(artData, checksum, trackId)
                } else {
                    // No album art available
                    debugLogger.info(
                        "ALBUM_ART_QUERY",
                        "No album art available",
                        mapOf("track_id" to trackId)
                    )
                    
                    // Send a response indicating no album art
                    val noArtMsg = mapOf(
                        "type" to "album_art_not_available",
                        "track_id" to trackId,
                        "checksum" to requestedChecksum
                    )
                    val msgData = gson.toJson(noArtMsg).toByteArray()
                    sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, msgData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling album art query", e)
                debugLogger.error(
                    "ALBUM_ART_QUERY",
                    "Failed to process query",
                    mapOf("error" to e.message.orEmpty())
                )
            }
            
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    fun stopServer() {
        debugLogger.info("SERVER_STATE", "Stopping BLE server")
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
        
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        connectedDevices.clear()
        albumArtTransferJobs.values.forEach { it.cancel() }
        albumArtTransferJobs.clear()
        _connectionStatus.value = "Disconnected"
        _connectedDevicesList.value = emptyList()
        
        Log.d(TAG, "BLE server stopped")
    }
}