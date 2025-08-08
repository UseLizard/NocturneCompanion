package com.paulcity.nocturnecompanion.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.data.Command
import com.paulcity.nocturnecompanion.data.StateUpdate
import com.paulcity.nocturnecompanion.services.NocturneNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.coroutineContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import kotlinx.coroutines.channels.Channel

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
    private val onDeviceConnected: ((BluetoothDevice) -> Unit)? = null,
    private val onDeviceReady: ((BluetoothDevice) -> Unit)? = null
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
    val albumArtManager = AlbumArtManager()
    private val mediaStoreAlbumArtManager = MediaStoreAlbumArtManager(context)
    private val albumArtTransferJobs = ConcurrentHashMap<String, Job>()
    private val binaryEncoder = BinaryAlbumArtEncoder()
    
    // Debug logging
    private val debugLogger = DebugLogger()
    private val _debugLogs = MutableSharedFlow<DebugLogger.DebugLogEntry>(replay = 100)
    val debugLogs = _debugLogs.asSharedFlow()
    
    // Track last sent state to avoid duplicates
    private var lastSentStateJson: String = ""
    
    // Message queue for congestion control
    private lateinit var messageQueue: MessageQueue
    private val messageQueueScope = CoroutineScope(Dispatchers.IO)
    
    // Device tracking for UI
    private val _connectedDevicesList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val connectedDevicesList: StateFlow<List<DeviceInfo>> = _connectedDevicesList
    
    data class DeviceContext(
        val device: BluetoothDevice,
        val connectionTime: Long = System.currentTimeMillis(),
        var mtu: Int = 23, // Default BLE MTU
        val subscriptions: MutableSet<String> = mutableSetOf(),
        var lastActivity: Long = System.currentTimeMillis(),
        var supportsBinaryProtocol: Boolean = false, // Track binary protocol support
        var requestHighPriority: Boolean = false, // Request high priority connection
        var supports2MPHY: Boolean = false, // Track 2M PHY support
        var currentTxPhy: Int = BluetoothDevice.PHY_LE_1M, // Current TX PHY
        var currentRxPhy: Int = BluetoothDevice.PHY_LE_1M, // Current RX PHY
        var phyUpdateAttempted: Boolean = false
    )
    
    data class DeviceInfo(
        val address: String,
        val name: String,
        val mtu: Int,
        val connectionDuration: Long,
        val subscriptions: List<String>,
        val supportsBinaryProtocol: Boolean = false,
        val supports2MPHY: Boolean = false,
        val currentTxPhy: String = "Unknown",
        val currentRxPhy: String = "Unknown",
        val phyUpdateAttempted: Boolean = false,
        val requestHighPriority: Boolean = false
    )
    
    // Removed - now using binary protocol only
    /*
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
    */
    
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
        
        // Initialize message queue
        messageQueue = MessageQueue(messageQueueScope) { device, charUuid, data ->
            sendNotificationDirectly(device, charUuid, data)
        }
        
        // Load saved album art settings
        val prefs = context.getSharedPreferences("AlbumArtSettings", Context.MODE_PRIVATE)
        val savedFormat = prefs.getString("imageFormat", "JPEG") ?: "JPEG"
        val savedQuality = prefs.getInt("compressionQuality", 85)
        val savedSize = prefs.getInt("imageSize", 300)
        val savedChunkDelay = prefs.getInt("chunkDelayMs", 5)
        val savedUseBinary = prefs.getBoolean("useBinaryProtocol", true)
        
        // Apply saved settings
        albumArtManager.updateSettings(savedFormat, savedQuality, savedSize)
        messageQueue.updateSettings(512, savedChunkDelay, savedUseBinary)
        
        Log.d(TAG, "Loaded saved settings - Format: $savedFormat, Quality: $savedQuality, Size: $savedSize")
        
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
                        
                        // Request high connection priority for faster transfers
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try {
                                // We need to use the BluetoothGatt object from the client side
                                // Since we're the server, we'll track this request for when we get a client connection
                                deviceContext.requestHighPriority = true
                                Log.d(TAG, "Marked device ${device.address} for high priority connection")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to mark for high priority", e)
                            }
                        }
                        
                        // Automatically request 2M PHY for better performance
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000) // Give connection time to stabilize
                                Log.d(TAG, "Auto-requesting 2M PHY for newly connected device ${device.address}")
                                gattServer?.setPreferredPhy(
                                    device,
                                    BluetoothDevice.PHY_LE_2M,  // TX PHY
                                    BluetoothDevice.PHY_LE_2M,  // RX PHY
                                    BluetoothDevice.PHY_OPTION_NO_PREFERRED
                                )
                                
                                // Read PHY after requesting to verify
                                delay(500)
                                gattServer?.readPhy(device)
                            }
                        }
                        
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
                    
                    // Clear message queue for this device
                    if (::messageQueue.isInitialized) {
                        messageQueue.clearDeviceQueue(device.address)
                    }
                    
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
            Log.d(TAG, "=== WRITE REQUEST RECEIVED ===")
            Log.d(TAG, "From: ${device.address}")
            Log.d(TAG, "Characteristic: ${characteristic.uuid}")
            Log.d(TAG, "Response needed: $responseNeeded")
            Log.d(TAG, "Value size: ${value?.size}")
            if (value != null && value.size > 0) {
                Log.d(TAG, "First bytes: ${value.take(10).joinToString { "0x%02X".format(it) }}")
            }
            
            if (characteristic.uuid == BleConstants.COMMAND_RX_CHAR_UUID && value != null) {
                Log.d(TAG, "Processing command on COMMAND_RX characteristic")
                try {
                    // Try to parse as binary protocol first
                    val parsedMessage = BinaryProtocolV2.parseMessage(value)
                    Log.d(TAG, "Binary parse result: ${if (parsedMessage != null) "SUCCESS" else "FAILED"}")
                    if (parsedMessage != null) {
                        val (header, payload, complete) = parsedMessage
                        if (complete) {
                            debugLogger.debug(
                                "BINARY_COMMAND",
                                "Binary: ${BinaryProtocolV2.getMessageTypeString(header.messageType)}",
                                mapOf("type" to "0x${header.messageType.toString(16)}", "size" to payload.size)
                            )
                            handleBinaryCommand(device, header, payload)
                            
                            // CRITICAL: Send GATT response if needed before returning
                            if (responseNeeded) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                            }
                            return
                        }
                    }
                    
                    // Fallback to JSON parsing for backward compatibility
                    val jsonStr = String(value, Charsets.UTF_8).trim()
                    
                    // Check if this looks like JSON (starts with { or [) before parsing
                    if (!jsonStr.startsWith("{") && !jsonStr.startsWith("[")) {
                        // This is raw binary data (e.g., throughput test data)
                        Log.d(TAG, "Received raw binary data: ${value.size} bytes")
                        debugLogger.debug(
                            "BINARY_DATA",
                            "Raw data received (throughput test?)",
                            mapOf("size" to value.size.toString(), "device" to device.address)
                        )
                        
                        // For throughput test, just acknowledge receipt
                        // The data itself is just test data and doesn't need processing
                        CoroutineScope(Dispatchers.IO).launch {
                            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                        }
                        
                        // Send response if needed and return early
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                        return
                    }
                    
                    Log.d(TAG, "Received JSON command (fallback): $jsonStr")
                    
                    debugLogger.debug(
                        "JSON_COMMAND",
                        "JSON fallback",
                        mapOf("data" to jsonStr, "device" to device.address)
                    )
                    
                    // Parse command as JSON
                    val command = gson.fromJson(jsonStr, Command::class.java)
                    
                    // Handle special commands
                    when (command.command) {
                        "request_high_priority_connection" -> {
                            // Request high priority connection for fast transfers
                            val reason = command.payload?.get("reason") as? String ?: "unknown"
                            debugLogger.info(
                                "CONNECTION",
                                "High priority connection requested",
                                mapOf("device" to device.address, "reason" to reason)
                            )
                            
                            // As a GATT server (peripheral), we can request connection parameter updates
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    // Request connection parameter update for low latency
                                    // Note: The central (nocturned) must accept these parameters
                                    val minInterval = 6    // 7.5ms (6 * 1.25ms)
                                    val maxInterval = 12   // 15ms (12 * 1.25ms)
                                    val latency = 0        // No slave latency for fastest response
                                    val timeout = 500      // 5 second supervision timeout (500 * 10ms)
                                    
                                    // BluetoothGattServer doesn't have direct connection parameter update API
                                    // But we can log what we would request
                                    Log.d(TAG, "Optimal connection parameters for ${device.address}:")
                                    Log.d(TAG, "  Min interval: ${minInterval * 1.25}ms")
                                    Log.d(TAG, "  Max interval: ${maxInterval * 1.25}ms")
                                    Log.d(TAG, "  Slave latency: $latency")
                                    Log.d(TAG, "  Supervision timeout: ${timeout * 10}ms")
                                    
                                    // Note: On Android 8.0+, connection parameters are largely controlled
                                    // by the system based on the active connection's requirements
                                    
                                    // Connection parameters logged, no ACK needed with binary protocol
                                    
                                    // Log connection quality
                                    val quality = getConnectionQuality(device.address)
                                    Log.d(TAG, "Current connection quality: $quality")
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to process high priority request", e)
                                }
                            } else {
                                // Pre-Android O
                                Log.d(TAG, "Connection parameter updates not available on API ${Build.VERSION.SDK_INT}")
                            }
                        }
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
                            
                            // Forward to NocturneServiceBLE with device address
                            val modifiedCommand = command.copy(
                                payload = (payload ?: emptyMap()).plus("device_address" to device.address)
                            )
                            onCommandReceived(modifiedCommand)
                        }
                        "test_album_art_request" -> {
                            // Test command that requests current track's album art
                            debugLogger.info(
                                "TEST_ALBUM_ART",
                                "Test album art request received",
                                mapOf("device" to device.address)
                            )
                            
                            // Forward to NocturneServiceBLE with device address
                            val modifiedCommand = command.copy(
                                payload = mapOf("device_address" to device.address)
                            )
                            onCommandReceived(modifiedCommand)
                        }
                        "test_album_art" -> {
                            // Test command for album art transfer with custom settings
                            debugLogger.info(
                                "ALBUM_ART_TEST",
                                "Test album art transfer requested",
                                mapOf("device" to device.address)
                            )
                            
                            // Forward to NocturneServiceBLE with device address
                            val modifiedCommand = command.copy(
                                payload = mapOf("device_address" to device.address)
                            )
                            onCommandReceived(modifiedCommand)
                        }
                        "get_capabilities" -> {
                            // Send capabilities using binary protocol v2
                            val mtu = connectedDevices[device.address]?.mtu ?: 512
                            val capabilitiesPayload = BinaryProtocolV2.createCapabilitiesPayload(
                                version = "2.0.0",
                                features = listOf("album_art", "time_sync", "media_control", "binary_v2", "incremental"),
                                mtu = mtu,
                                debugEnabled = false
                            )
                            
                            val message = BinaryProtocolV2.createMessage(
                                BinaryProtocolV2.MSG_CAPABILITIES,
                                capabilitiesPayload
                            )
                            
                            sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, message)
                            
                            debugLogger.info(
                                "CAPABILITIES",
                                "Sent binary protocol v2 capabilities",
                                mapOf("device" to device.address, "mtu" to mtu)
                            )
                        }
                        "enable_binary_protocol" -> {
                            // Enable binary protocol for this device
                            connectedDevices[device.address]?.supportsBinaryProtocol = true
                            
                            debugLogger.info(
                                "BINARY_PROTOCOL",
                                "Binary protocol enabled",
                                mapOf("device" to device.address)
                            )
                            
                            // Binary protocol is already enabled by default, no ACK needed
                        }
                        "request_time_sync" -> {
                            // Handle explicit time sync request
                            // Send time sync in binary format
                            val timeSyncPayload = BinaryProtocolV2.createTimeSyncPayload(
                                timestampMs = System.currentTimeMillis(),
                                timezone = TimeZone.getDefault().id
                            )
                            val timeSyncData = BinaryProtocolV2.createMessage(
                                BinaryProtocolV2.MSG_TIME_SYNC,
                                timeSyncPayload
                            )
                            
                            // Send with HIGH priority since time sync is important
                            val message = MessageQueue.Message(
                                device = device,
                                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                                data = timeSyncData,
                                priority = MessageQueue.Priority.HIGH
                            )
                            messageQueue.enqueue(message)
                            
                            debugLogger.info(
                                "TIME_SYNC",
                                "Time sync sent in response to request",
                                mapOf(
                                    "device" to device.address,
                                    "timestamp" to System.currentTimeMillis(),
                                    "timezone" to TimeZone.getDefault().id
                                )
                            )
                        }
                        "request_track_refresh" -> {
                            // Handle explicit request to refresh track data
                            // Forward to the service to send current media state
                            onCommandReceived(command)
                            
                            debugLogger.info(
                                "TRACK_REFRESH",
                                "Track refresh requested",
                                mapOf("device" to device.address)
                            )
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
                    
                    // Check if device is now ready (both STATE_TX and ALBUM_ART_TX subscribed)
                    val subscriptions = connectedDevices[device.address]?.subscriptions ?: mutableSetOf()
                    val hasStateTx = subscriptions.contains(BleConstants.STATE_TX_CHAR_UUID.toString())
                    val hasAlbumArtTx = subscriptions.contains(BleConstants.ALBUM_ART_TX_CHAR_UUID.toString())
                    
                    if (hasStateTx && hasAlbumArtTx) {
                        Log.d(TAG, "Device ${device.address} is now ready with all required subscriptions")
                        onDeviceReady?.invoke(device)
                    }
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
            
            // Calculate optimal chunk size for album art based on new MTU
            val effectiveMtu = mtu - 3
            val jsonOverhead = 174 // Typical JSON overhead for album art chunks
            val optimalChunkSize = maxOf(50, effectiveMtu - jsonOverhead)
            
            debugLogger.info(
                "MTU_CHANGED",
                "MTU negotiated - Album art optimization ready",
                mapOf(
                    "device" to device.address, 
                    "mtu" to mtu.toString(),
                    "effective_mtu" to effectiveMtu.toString(),
                    "optimal_chunk_size" to optimalChunkSize.toString(),
                    "improvement" to String.format("%.1fx vs 400-byte chunks", optimalChunkSize / 400.0)
                )
            )
            
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
        
        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "PHY read for ${device.address}: TX=$txPhy, RX=$rxPhy")
                connectedDevices[device.address]?.let {
                    it.currentTxPhy = txPhy
                    it.currentRxPhy = rxPhy
                }
                updateConnectedDevicesList()
            }
        }
        
        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "PHY updated for ${device.address}: TX=$txPhy, RX=$rxPhy")
                connectedDevices[device.address]?.let {
                    it.currentTxPhy = txPhy
                    it.currentRxPhy = rxPhy
                    it.supports2MPHY = (txPhy == BluetoothDevice.PHY_LE_2M || rxPhy == BluetoothDevice.PHY_LE_2M)
                }
                
                debugLogger.info(
                    "PHY_UPDATE",
                    "PHY configuration updated",
                    mapOf(
                        "device" to device.address,
                        "tx_phy" to getPhyName(txPhy),
                        "rx_phy" to getPhyName(rxPhy),
                        "status" to if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failed"
                    )
                )
                
                updateConnectedDevicesList()
            } else {
                Log.e(TAG, "PHY update failed for ${device.address}: status=$status")
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
    
    private fun handleBinaryCommand(device: BluetoothDevice, header: BinaryProtocolV2.MessageHeader, payload: ByteArray) {
        Log.d(TAG, "=== HANDLING BINARY COMMAND ===")
        Log.d(TAG, "Message Type: 0x${header.messageType.toString(16)} (${BinaryProtocolV2.getMessageTypeString(header.messageType)})")
        Log.d(TAG, "Message ID: ${header.messageId}")
        Log.d(TAG, "Payload size: ${payload.size}")
        
        when (header.messageType) {
            BinaryProtocolV2.MSG_CMD_PLAY -> {
                Log.d(TAG, "Binary command: PLAY - forwarding to command handler")
                onCommandReceived(Command("play"))
                Log.d(TAG, "PLAY command forwarded")
            }
            
            BinaryProtocolV2.MSG_CMD_PAUSE -> {
                Log.d(TAG, "Binary command: PAUSE")
                onCommandReceived(Command("pause"))
            }
            
            BinaryProtocolV2.MSG_CMD_NEXT -> {
                Log.d(TAG, "Binary command: NEXT")
                onCommandReceived(Command("next"))
            }
            
            BinaryProtocolV2.MSG_CMD_PREVIOUS -> {
                Log.d(TAG, "Binary command: PREVIOUS")
                onCommandReceived(Command("previous"))
            }
            
            BinaryProtocolV2.MSG_CMD_SEEK_TO -> {
                val (valueMs, _) = BinaryProtocolV2.parseCommandPayload(payload)
                Log.d(TAG, "Binary command: SEEK_TO $valueMs")
                onCommandReceived(Command("seek_to", value_ms = valueMs))
            }
            
            BinaryProtocolV2.MSG_CMD_SET_VOLUME -> {
                val (_, valuePercent) = BinaryProtocolV2.parseCommandPayload(payload)
                Log.d(TAG, "Binary command: SET_VOLUME $valuePercent")
                onCommandReceived(Command("set_volume", value_percent = valuePercent))
            }
            
            BinaryProtocolV2.MSG_CMD_REQUEST_STATE -> {
                Log.d(TAG, "Binary command: REQUEST_STATE")
                onCommandReceived(Command("request_state"))
            }
            
            BinaryProtocolV2.MSG_CMD_REQUEST_TIMESTAMP -> {
                Log.d(TAG, "Binary command: REQUEST_TIMESTAMP")
                onCommandReceived(Command("request_timestamp"))
            }
            
            BinaryProtocolV2.MSG_CMD_ALBUM_ART_QUERY -> {
                val hash = BinaryProtocolV2.parseAlbumArtQueryPayload(payload)
                Log.d(TAG, "Binary command: ALBUM_ART_QUERY hash=$hash, device=${device.address}")
                // Use the hash as a track identifier since we don't have the actual track name here
                onCommandReceived(Command("album_art_query", payload = mapOf(
                    "hash" to hash,
                    "checksum" to hash,  // Use hash as checksum for now
                    "track_id" to hash,  // Use hash as track_id to avoid empty string
                    "device_address" to device.address
                )))
            }
            
            BinaryProtocolV2.MSG_CMD_TEST_ALBUM_ART -> {
                Log.d(TAG, "Binary command: TEST_ALBUM_ART")
                onCommandReceived(Command("test_album_art_request"))
            }
            
            BinaryProtocolV2.MSG_PROTOCOL_ENABLE -> {
                Log.d(TAG, "Binary protocol enable request")
                // Send capabilities in binary format
                sendBinaryCapabilities(device)
            }
            
            BinaryProtocolV2.MSG_GET_CAPABILITIES -> {
                Log.d(TAG, "Binary command: GET_CAPABILITIES")
                // Send capabilities in binary format
                sendBinaryCapabilities(device)
            }
            
            BinaryProtocolV2.MSG_ENABLE_BINARY_INCREMENTAL -> {
                Log.d(TAG, "Binary command: ENABLE_BINARY_INCREMENTAL")
                // Enable binary incremental updates (already using binary protocol v2)
                connectedDevices[device.address]?.supportsBinaryProtocol = true
                Log.d(TAG, "Binary incremental updates enabled for ${device.address}")
                // No acknowledgment needed - nocturned doesn't expect a response
            }
            
            BinaryProtocolV2.MSG_REQUEST_HIGH_PRIORITY_CONNECTION -> {
                val reason = if (payload.isNotEmpty()) String(payload, Charsets.UTF_8) else "unknown"
                Log.d(TAG, "Binary command: REQUEST_HIGH_PRIORITY_CONNECTION reason=$reason")
                
                // Log connection parameters request
                debugLogger.info(
                    "CONNECTION",
                    "High priority connection requested (binary)",
                    mapOf("device" to device.address, "reason" to reason)
                )
                
                // Note: Android system manages BLE connection intervals
                // We acknowledge the request but actual parameters are system-controlled
            }
            
            BinaryProtocolV2.MSG_OPTIMIZE_CONNECTION_PARAMS -> {
                // Parse payload: float32 interval + string reason
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                val intervalMs = buffer.getFloat()
                val reasonLen = buffer.getShort()
                val reasonBytes = ByteArray(reasonLen.toInt())
                buffer.get(reasonBytes)
                val reason = String(reasonBytes, Charsets.UTF_8)
                
                Log.d(TAG, "Binary command: OPTIMIZE_CONNECTION_PARAMS interval=$intervalMs, reason=$reason")
                
                debugLogger.info(
                    "CONNECTION",
                    "Connection optimization notification (binary)",
                    mapOf("device" to device.address, "interval" to intervalMs.toString(), "reason" to reason)
                )
            }
            
            else -> {
                Log.w(TAG, "Unknown binary command type: 0x${header.messageType.toString(16)}")
            }
        }
    }
    
    private fun sendBinaryCapabilities(device: BluetoothDevice) {
        val mtu = connectedDevices[device.address]?.mtu ?: 512
        val capabilities = BinaryProtocolV2.createCapabilitiesPayload(
            version = "2.0.0",
            features = listOf("album_art", "time_sync", "media_control", "binary_v2", "incremental"),
            mtu = mtu,
            debugEnabled = false
        )
        
        val message = BinaryProtocolV2.createMessage(
            BinaryProtocolV2.MSG_CAPABILITIES,
            capabilities
        )
        
        sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, message)
        Log.d(TAG, "Sent binary capabilities to ${device.address}")
    }
    
    fun sendBinaryMessage(message: ByteArray, priority: MessageQueue.Priority = MessageQueue.Priority.NORMAL) {
        debugLogger.verbose(
            "BINARY_MSG_SENT",
            "Sending binary message",
            mapOf("size" to message.size)
        )
        
        // Send binary message with specified priority
        sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, message, priority)
        
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    fun sendStateUpdate(state: StateUpdate, forceUpdate: Boolean = false) {
        Log.d(TAG, "BLE_LOG: sendStateUpdate called - artist: ${state.artist}, track: ${state.track}, playing: ${state.is_playing}, force: $forceUpdate")
        // Create binary state message
        val statePayload = BinaryProtocolV2.createFullStatePayload(
            artist = state.artist ?: "",
            album = state.album ?: "",
            track = state.track ?: "",
            durationMs = state.duration_ms ?: 0L,
            positionMs = state.position_ms ?: 0L,
            isPlaying = state.is_playing ?: false,
            volumePercent = state.volume_percent ?: 50
        )
        
        val binaryMessage = BinaryProtocolV2.createMessage(
            BinaryProtocolV2.MSG_STATE_FULL,
            statePayload
        )
        Log.d(TAG, "BLE_LOG: Created binary state message type: 0x${BinaryProtocolV2.MSG_STATE_FULL.toString(16)}, size: ${binaryMessage.size}")
        
        // Only send if state actually changed (excluding position for comparison) OR if forced
        val stateForComparison = state.copy(position_ms = 0)
        val compareJson = gson.toJson(stateForComparison)
        
        if (forceUpdate || compareJson != lastSentStateJson) {
            lastSentStateJson = compareJson
            
            Log.d(TAG, "BLE_LOG: State changed, sending update")
            debugLogger.verbose(
                "STATE_SENT",
                "Sending binary state update",
                mapOf("size" to binaryMessage.size)
            )
            
            // Send binary state with NORMAL priority
            Log.d(TAG, "BLE_LOG: Sending state update to all devices")
            sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, binaryMessage, MessageQueue.Priority.NORMAL)
            
            CoroutineScope(Dispatchers.IO).launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        } else {
            Log.d(TAG, "BLE_LOG: State unchanged, not sending update")
        }
    }
    
    // Method to send custom JSON data (for speed test)
    fun sendCustomData(data: Map<String, Any>) {
        val json = gson.toJson(data)
        Log.d(TAG, "Sending custom data: $json")
        
        // Determine priority based on data type
        val priority = when {
            data["type"] == "pong" -> MessageQueue.Priority.HIGH
            data["type"]?.toString()?.contains("throughput") == true -> MessageQueue.Priority.NORMAL
            else -> MessageQueue.Priority.NORMAL
        }
        
        sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, json, priority)
    }
    
    // Removed - using binary protocol only
    /*
    fun sendStateUpdate(data: Any) {
        Log.d(TAG, "sendStateUpdate called with data type: ${data::class.simpleName}")
        val json = gson.toJson(data)
        Log.d(TAG, "JSON to send: $json")
        
        debugLogger.verbose(
            "STATE_SENT",
            "Sending custom state",
            mapOf("data" to json)
        )
        
        // Determine priority based on data type
        val priority = when {
            json.contains("\"type\":\"timeSync\"") -> MessageQueue.Priority.HIGH
            json.contains("\"type\":\"album_art_start\"") -> MessageQueue.Priority.NORMAL
            json.contains("\"type\":\"album_art_end\"") -> MessageQueue.Priority.NORMAL
            else -> MessageQueue.Priority.NORMAL
        }
        
        Log.d(TAG, "About to call sendNotificationToAll with priority: $priority")
        sendNotificationToAll(BleConstants.STATE_TX_CHAR_UUID, json, priority)
        Log.d(TAG, "sendNotificationToAll completed")
        
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    */
    
    fun request2MPHY(): Boolean {
        // Request 2M PHY for all connected devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "2M PHY requires Android O (API 26) or higher")
            return false
        }
        
        var anySuccess = false
        connectedDevices.values.forEach { context ->
            try {
                // Request 2M PHY for this device through the GATT server
                // Note: On GATT server side, we can't directly set PHY, but we can
                // configure our preferences for when the client requests it
                Log.d(TAG, "BLE_LOG: Ready to accept 2M PHY from device ${context.device.address}")
                
                // Set flag that we support 2M PHY
                context.supports2MPHY = true
                anySuccess = true
                
                // The actual PHY negotiation happens when the central (nocturned) requests it
                // We just mark that we're ready to support it
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare for 2M PHY on device ${context.device.address}", e)
            }
        }
        
        if (anySuccess) {
            Log.d(TAG, "BLE_LOG: Server ready to accept 2M PHY requests")
        }
        
        return anySuccess
    }
    
    fun requestPhyUpdateForDevice(deviceAddress: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "PHY update requires Android O (API 26) or higher")
            return false
        }
        
        val context = connectedDevices[deviceAddress]
        if (context == null) {
            Log.e(TAG, "Device $deviceAddress not found in connected devices")
            return false
        }
        
        try {
            // Mark that we've attempted PHY update
            context.phyUpdateAttempted = true
            context.supports2MPHY = true
            
            updateConnectedDevicesList()
            
            // Request PHY update through the GATT server
            // The GATT server (Android side) needs to initiate the PHY change, not the client
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Request 2M PHY for both TX and RX
                gattServer?.setPreferredPhy(
                    context.device,
                    BluetoothDevice.PHY_LE_2M,  // TX PHY  
                    BluetoothDevice.PHY_LE_2M,  // RX PHY
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED  // PHY options
                )
                
                Log.d(TAG, "PHY update requested for ${context.device.address} via GATT server")
                Log.d(TAG, "Requesting 2M PHY for both TX and RX")
                
                // Also read current PHY to verify after a short delay
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500) // Give time for PHY to update
                    gattServer?.readPhy(context.device)
                    Log.d(TAG, "Reading PHY status after update request")
                }
                
                return true
            }
            
            Log.w(TAG, "PHY update not supported on this Android version")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request PHY update for $deviceAddress", e)
            return false
        }
    }
    
    private fun sendNotificationToAll(characteristicUuid: UUID, data: String, priority: MessageQueue.Priority = MessageQueue.Priority.NORMAL) {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        sendNotificationToAll(characteristicUuid, dataBytes, priority)
    }
    
    private fun sendNotificationToAll(characteristicUuid: UUID, data: ByteArray, priority: MessageQueue.Priority = MessageQueue.Priority.NORMAL) {
        Log.d(TAG, "sendNotificationToAll: ${connectedDevices.size} connected devices, sending to char: $characteristicUuid")
        
        connectedDevices.values.forEach { context ->
            Log.d(TAG, "Device ${context.device.address} subscriptions: ${context.subscriptions}")
            if (context.subscriptions.contains(characteristicUuid.toString())) {
                // Queue the message instead of sending directly
                val message = MessageQueue.Message(
                    device = context.device,
                    characteristicUuid = characteristicUuid,
                    data = data,
                    priority = priority
                )
                
                if (!messageQueue.enqueue(message)) {
                    Log.w(TAG, "Failed to enqueue message for ${context.device.address} - queue full")
                } else {
                    Log.d(TAG, "Message enqueued for ${context.device.address}")
                }
            } else {
                Log.w(TAG, "Device ${context.device.address} not subscribed to ${characteristicUuid}")
            }
        }
    }
    
    // Renamed to indicate this is now the direct send function
    private suspend fun sendNotificationDirectly(device: BluetoothDevice, characteristicUuid: UUID, data: ByteArray): Boolean {
        return sendNotificationToDeviceInternal(device, characteristicUuid, data)
    }
    
    private fun sendNotificationToDevice(device: BluetoothDevice, characteristicUuid: UUID, data: ByteArray) {
        // Queue the message instead of sending directly
        val priority = when (characteristicUuid) {
            BleConstants.ALBUM_ART_TX_CHAR_UUID -> MessageQueue.Priority.BULK
            else -> MessageQueue.Priority.NORMAL
        }
        
        val message = MessageQueue.Message(
            device = device,
            characteristicUuid = characteristicUuid,
            data = data,
            priority = priority
        )
        
        if (!messageQueue.enqueue(message)) {
            Log.w(TAG, "Failed to enqueue direct message for ${device.address}")
        }
    }
    
    private fun sendNotificationToDeviceInternal(device: BluetoothDevice, characteristicUuid: UUID, data: ByteArray): Boolean {
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
                return true
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
                            return true
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
                            return true
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
                return false
            }
        } else {
            Log.e(TAG, "Characteristic not found: $characteristicUuid")
            return false
        }
        
        // Emit debug log asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    // DEPRECATED: Album art is now only sent when requested via album_art_query
    // This follows the nocturned protocol where it checks its cache first
    /*
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
    */
    
    fun sendAlbumArt(albumArtData: ByteArray, checksum: String, trackId: String) {
        // Called when nocturned has requested album art via album_art_query
        // and we've validated that the MD5 hash matches
        
        debugLogger.info(
            "ALBUM_ART",
            "Sending requested album art",
            mapOf("track_id" to trackId, "checksum" to checksum, "size" to albumArtData.size.toString())
        )
        
        // Debug log emission is handled by the calling function (handleAlbumArtQuery)
        
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
    
    fun sendAlbumArtToDevice(deviceAddress: String, albumArtData: ByteArray, checksum: String, trackId: String) {
        // Send album art to a specific device
        val device = connectedDevices[deviceAddress]?.device
        if (device == null) {
            Log.w(TAG, "Device $deviceAddress not connected, cannot send album art")
            return
        }
        
        debugLogger.info(
            "ALBUM_ART",
            "Sending album art to specific device",
            mapOf(
                "device" to deviceAddress,
                "track_id" to trackId,
                "checksum" to checksum,
                "size" to albumArtData.size.toString()
            )
        )
        
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
        
        // Cancel any existing transfer for this device
        albumArtTransferJobs[deviceAddress]?.cancel()
        
        // Start transfer to specific device
        val job = CoroutineScope(Dispatchers.IO).launch {
            // Call the private suspend function, not the public one (avoid recursion)
            try {
                val deviceContext = connectedDevices[device.address] ?: return@launch
                // Always use binary protocol
                sendAlbumArtBinary(device, albumArtData, checksum, trackId, deviceContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error during album art transfer", e)
                
                debugLogger.error(
                    "ERROR",
                    "Album art transfer failed: ${e.message}",
                    mapOf("device" to device.address)
                )
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                
                // Send failure notification in binary
                try {
                    val endData = binaryEncoder.encodeAlbumArtEnd(checksum, false)
                    sendNotificationToDevice(device, BleConstants.ALBUM_ART_TX_CHAR_UUID, endData)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to send error notification", e2)
                }
            }
        }
        albumArtTransferJobs[deviceAddress] = job
    }
    
    fun sendNoAlbumArtAvailable(deviceAddress: String, trackId: String, checksum: String, reason: String) {
        val device = connectedDevices[deviceAddress]?.device
        if (device == null) {
            Log.w(TAG, "Device $deviceAddress not connected")
            return
        }
        
        debugLogger.info(
            "ALBUM_ART_QUERY",
            "No album art available",
            mapOf("device" to deviceAddress, "track_id" to trackId, "reason" to reason)
        )
        
        val noArtMsg = mapOf(
            "type" to "album_art_not_available",
            "track_id" to trackId,
            "checksum" to checksum,
            "reason" to reason
        )
        val msgData = gson.toJson(noArtMsg).toByteArray()
        sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, msgData)
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
            
            // Always use binary protocol
            sendAlbumArtBinary(device, albumArtData, checksum, trackId, deviceContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error during album art transfer", e)
            
            debugLogger.error(
                "ERROR",
                "Album art transfer failed: ${e.message}",
                mapOf("device" to device.address)
            )
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
            
            // Send failure notification in binary
            try {
                val endData = binaryEncoder.encodeAlbumArtEnd(checksum, false)
                sendNotificationToDevice(device, BleConstants.ALBUM_ART_TX_CHAR_UUID, endData)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to send error notification", e2)
            }
        }
    }
    
    // Removed - now using binary protocol only
    /*
    private suspend fun sendAlbumArtJson(
        device: BluetoothDevice,
        albumArtData: ByteArray,
        checksum: String,
        trackId: String,
        deviceContext: DeviceContext
    ) {
        val effectiveMtu = deviceContext.mtu - 3
            
            // Account for JSON overhead when calculating chunk size
            // JSON structure: {"type":"album_art_chunk","checksum":"...","chunk_index":0,"data":"..."}
            // Base JSON overhead (without data): ~90 bytes + checksum length (64)
            val jsonOverhead = 90 + checksum.length + 20 // ~174 bytes for JSON wrapper
            val maxDataSize = effectiveMtu - jsonOverhead
            val chunkSize = maxOf(50, maxDataSize) // Use full available MTU, minimum 50 bytes
            
            Log.d(TAG, "Album art chunk sizing - MTU: ${deviceContext.mtu}, Effective: $effectiveMtu, JSON overhead: $jsonOverhead, Chunk size: $chunkSize")
            
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
            // Use message queue with NORMAL priority for album art start
            val startMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                data = startData,
                priority = MessageQueue.Priority.NORMAL
            )
            messageQueue.enqueue(startMessage)
            
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
            
            // Removed delay - message queue handles pacing
            
            // Track chunk transmission completion
            val chunksToSend = totalChunks
            val chunksSent = java.util.concurrent.atomic.AtomicInteger(0)
            val sendCompleteLatch = java.util.concurrent.CountDownLatch(totalChunks)
            
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
                
                // Use BULK priority for album art chunks with callback
                val chunkMessage = MessageQueue.Message(
                    device = device,
                    characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
                    data = chunkJson,
                    priority = MessageQueue.Priority.BULK,
                    callback = { success ->
                        if (success) {
                            val sent = chunksSent.incrementAndGet()
                            if (sent % 10 == 0 || sent == chunksToSend) {
                                Log.d(TAG, "Album art chunk sent: $sent/$chunksToSend")
                            }
                        }
                        sendCompleteLatch.countDown()
                    }
                )
                
                if (!messageQueue.enqueue(chunkMessage)) {
                    Log.w(TAG, "Album art chunk queue full, pausing transfer")
                    sendCompleteLatch.countDown() // Count failed enqueues too
                    delay(100) // Back off if queue is full
                }
                
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
                
                // Message queue handles pacing - no manual delay needed
            }
            
            // Wait for all chunks to be sent (with timeout)
            withTimeoutOrNull(30000) { // 30 second timeout
                withContext(Dispatchers.IO) {
                    sendCompleteLatch.await()
                }
            } ?: Log.w(TAG, "Timeout waiting for all chunks to send")
            
            // Send end notification with BULK priority to maintain order
            val endMsg = AlbumArtEnd(checksum = checksum, success = true)
            val endData = gson.toJson(endMsg).toByteArray()
            val endMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                data = endData,
                priority = MessageQueue.Priority.BULK
            )
            messageQueue.enqueue(endMessage)
            
            Log.d(TAG, "Album art transfer completed to ${device.address}")
            
            debugLogger.info(
                "ALBUM_ART",
                "Album art transfer completed",
                mapOf("device" to device.address, "checksum" to checksum)
            )
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
    }
    */
    
    private suspend fun sendAlbumArtBinary(
        device: BluetoothDevice,
        albumArtData: ByteArray,
        checksum: String,
        trackId: String,
        deviceContext: DeviceContext
    ) {
        val binaryTransfer = binaryEncoder.encodeAlbumArtTransfer(
            imageData = albumArtData,
            checksum = checksum,
            trackId = trackId,
            mtu = deviceContext.mtu
        )
        
        Log.d(TAG, "Starting binary album art transfer to ${device.address}: " +
            "${albumArtData.size} bytes -> ${binaryTransfer.binarySize} bytes, " +
            "${binaryTransfer.totalChunks} chunks, compression: ${String.format("%.2f", binaryTransfer.compressionRatio)}x")
        
        // Send start message
        val startMessage = MessageQueue.Message(
            device = device,
            characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
            data = binaryTransfer.startMessage,
            priority = MessageQueue.Priority.NORMAL,
            isBinary = true
        )
        messageQueue.enqueue(startMessage)
        
        debugLogger.info(
            "ALBUM_ART_BINARY",
            "Binary album art transfer started",
            mapOf(
                "device" to device.address,
                "original_size" to albumArtData.size.toString(),
                "binary_size" to binaryTransfer.binarySize.toString(),
                "chunks" to binaryTransfer.totalChunks.toString(),
                "chunk_size" to binaryTransfer.chunkSize.toString()
            )
        )
        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
        
        // Track chunk transmission completion
        val chunksToSend = binaryTransfer.totalChunks
        val chunksSent = java.util.concurrent.atomic.AtomicInteger(0)
        val sendCompleteLatch = java.util.concurrent.CountDownLatch(chunksToSend)
        
        // Send chunks with proper pacing to avoid overflowing BLE buffers
        binaryTransfer.chunkMessages.forEachIndexed { index, chunkData ->
            val currentJob = albumArtTransferJobs[device.address]
            if (currentJob == null || !currentJob.isActive) return@forEachIndexed
            
            // Add delay between chunks to prevent buffer overflow
            // Adjust delay based on chunk size - larger chunks need more time
            /*val delayMs = when {
                binaryTransfer.chunkSize > 400 -> 100L  // Large chunks need more spacing
                binaryTransfer.chunkSize > 200 -> 75L   // Medium chunks
                else -> 50L                             // Small chunks (still need good spacing)
            }
            
            if (index > 0) {
                delay(delayMs)
            }*/
            
            val chunkMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
                data = chunkData,
                priority = MessageQueue.Priority.BULK,
                callback = { success ->
                    if (success) {
                        val sent = chunksSent.incrementAndGet()
                        // Log every chunk for debugging
                        Log.d(TAG, "Binary album art chunk sent: $sent/$chunksToSend (index: $index)")
                    }
                    sendCompleteLatch.countDown()
                },
                isBinary = true
            )
            
            if (!messageQueue.enqueue(chunkMessage)) {
                Log.w(TAG, "Binary album art chunk queue full")
                sendCompleteLatch.countDown()
                delay(50)
            }
            
            if (index % 20 == 0) {
                debugLogger.debug(
                    "ALBUM_ART_BINARY",
                    "Binary chunk progress",
                    mapOf(
                        "device" to device.address,
                        "progress" to "${index + 1}/$chunksToSend"
                    )
                )
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
            }
        }
        
        // Wait for all chunks to be sent (with timeout)
        withTimeoutOrNull(30000) { // 30 second timeout
            withContext(Dispatchers.IO) {
                sendCompleteLatch.await()
            }
        } ?: Log.w(TAG, "Timeout waiting for all binary chunks to send")
        
        // Send end message
        val endMessage = MessageQueue.Message(
            device = device,
            characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
            data = binaryTransfer.endMessage,
            priority = MessageQueue.Priority.BULK,
            isBinary = true
        )
        messageQueue.enqueue(endMessage)
        
        Log.d(TAG, "Binary album art transfer completed to ${device.address}")
        
        debugLogger.info(
            "ALBUM_ART_BINARY",
            "Binary transfer completed",
            mapOf(
                "device" to device.address,
                "time_ms" to (System.currentTimeMillis() - deviceContext.lastActivity).toString(),
                "throughput_kb_s" to String.format("%.1f", 
                    albumArtData.size / 1024.0 / ((System.currentTimeMillis() - deviceContext.lastActivity) / 1000.0))
            )
        )
        _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return)
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
    
    fun getConnectionQuality(deviceAddress: String): String {
        if (!::messageQueue.isInitialized) return "Unknown"
        
        val congestionStates = messageQueue.congestionState.value
        val isCongested = congestionStates[deviceAddress] ?: false
        val queueDepths = messageQueue.getQueueDepths()
        
        return when {
            isCongested -> "Poor - High congestion"
            queueDepths.third > 100 -> "Fair - Bulk transfer active"
            queueDepths.second > 20 -> "Good - Moderate load"
            else -> "Excellent"
        }
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
                },
                supportsBinaryProtocol = context.supportsBinaryProtocol,
                supports2MPHY = context.supports2MPHY,
                currentTxPhy = getPhyName(context.currentTxPhy),
                currentRxPhy = getPhyName(context.currentRxPhy),
                phyUpdateAttempted = context.phyUpdateAttempted,
                requestHighPriority = context.requestHighPriority
            )
        }
        _connectedDevicesList.value = devices
    }
    
    private fun getPhyName(phy: Int): String {
        return when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M PHY"
            BluetoothDevice.PHY_LE_2M -> "2M PHY"
            BluetoothDevice.PHY_LE_CODED -> "Coded PHY"
            else -> "Unknown"
        }
    }
    
    private fun handleAlbumArtQuery(device: BluetoothDevice, trackId: String, requestedChecksum: String) {
        debugLogger.info(
            "ALBUM_ART_QUERY",
            "Processing album art query",
            mapOf("track_id" to trackId, "checksum" to requestedChecksum)
        )
        
        // Parse track_id to extract artist and album (format: "artist - album")
        // Note: Currently not used as we validate by comparing MD5 hashes instead
        
        // Get current album art from media session or media store
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First check if we have the exact album art in cache by MD5 hash
                val cachedArt = albumArtManager.getArtFromCache(requestedChecksum)
                if (cachedArt != null) {
                    debugLogger.info(
                        "ALBUM_ART_QUERY",
                        "Found exact match in cache",
                        mapOf(
                            "size" to cachedArt.data.size.toString(),
                            "checksum" to cachedArt.checksum
                        )
                    )
                    sendAlbumArt(cachedArt.data, cachedArt.checksum, trackId)
                    _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                    return@launch
                }
                
                // Get current media metadata
                val currentMetadata = NocturneNotificationListener.activeMediaController.value?.metadata
                if (currentMetadata != null) {
                    val currentArtist = currentMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val currentAlbum = currentMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                    
                    // Generate MD5 hash for current media
                    val currentHash = albumArtManager.generateMetadataHash(currentArtist, currentAlbum)
                    
                    debugLogger.info(
                        "ALBUM_ART_QUERY",
                        "Comparing metadata hashes",
                        mapOf(
                            "requested" to requestedChecksum,
                            "current" to currentHash,
                            "current_artist" to currentArtist,
                            "current_album" to currentAlbum
                        )
                    )
                    
                    // Only send album art if the MD5 hashes match OR if requesting current track (empty checksum)
                    if (currentHash == requestedChecksum || requestedChecksum.isEmpty() || requestedChecksum == "current") {
                        // Extract and send the album art
                        val albumArtResult = albumArtManager.extractAlbumArt(currentMetadata)
                        if (albumArtResult != null) {
                            val (artData, sha256Checksum) = albumArtResult
                            
                            debugLogger.info(
                                "ALBUM_ART_QUERY",
                                "Hash match - sending album art",
                                mapOf(
                                    "size" to artData.size.toString(),
                                    "sha256" to sha256Checksum,
                                    "md5" to currentHash
                                )
                            )
                            
                            sendAlbumArt(artData, sha256Checksum, trackId)
                        } else {
                            // Hash matches but no art available yet - might still be loading
                            debugLogger.info(
                                "ALBUM_ART_QUERY",
                                "Hash match but no art available yet, scheduling retry",
                                mapOf("track_id" to trackId, "retry_delay" to "100ms")
                            )
                            
                            // Schedule a retry after a short delay
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(100) // Wait for album art to potentially load
                                
                                // Re-check metadata
                                val retryMetadata = NocturneNotificationListener.activeMediaController.value?.metadata
                                if (retryMetadata != null) {
                                    val retryResult = albumArtManager.extractAlbumArt(retryMetadata)
                                    if (retryResult != null) {
                                        val (artData, sha256Checksum) = retryResult
                                        debugLogger.info(
                                            "ALBUM_ART_QUERY",
                                            "Retry successful - album art now available",
                                            mapOf("size" to artData.size.toString(), "sha256" to sha256Checksum)
                                        )
                                        sendAlbumArt(artData, sha256Checksum, trackId)
                                    } else {
                                        // Still no art after retry
                                        sendNoArtAvailable(device, trackId, requestedChecksum, "No artwork in metadata after retry")
                                    }
                                } else {
                                    sendNoArtAvailable(device, trackId, requestedChecksum, "No media controller on retry")
                                }
                                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
                            }
                        }
                    } else {
                        // Hash mismatch - different track
                        sendNoArtAvailable(device, trackId, requestedChecksum, "Track mismatch")
                    }
                } else {
                    // No current media playing
                    sendNoArtAvailable(device, trackId, requestedChecksum, "No media playing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling album art query", e)
                debugLogger.error(
                    "ALBUM_ART_QUERY",
                    "Failed to process query",
                    mapOf("error" to e.message.orEmpty())
                )
                sendNoArtAvailable(device, trackId, requestedChecksum, "Error: ${e.message}")
            }
            
            _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
        }
    }
    
    private fun sendNoArtAvailable(device: BluetoothDevice, trackId: String, checksum: String, reason: String) {
        debugLogger.info(
            "ALBUM_ART_QUERY",
            "No album art available",
            mapOf("track_id" to trackId, "reason" to reason)
        )
        
        val noArtMsg = mapOf(
            "type" to "album_art_not_available",
            "track_id" to trackId,
            "checksum" to checksum,
            "reason" to reason
        )
        val msgData = gson.toJson(noArtMsg).toByteArray()
        sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, msgData)
    }
    
    private fun handleTestAlbumArt(device: BluetoothDevice) {
        messageQueueScope.launch {
            try {
                debugLogger.info(
                    "ALBUM_ART_TEST", 
                    "Creating test album art image",
                    mapOf("device" to device.address)
                )
                
                // Create a test bitmap with gradient and text
                val size = 300 // Default size, will be updated with actual settings
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                
                // Create gradient background
                val gradient = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(0xFF6200EA.toInt(), 0xFF03DAC5.toInt()),
                    null,
                    Shader.TileMode.CLAMP
                )
                val paint = Paint().apply {
                    shader = gradient
                }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
                
                // Add text
                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = size / 8f
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                
                // Draw test info
                canvas.drawText("Album Art Test", size / 2f, size / 3f, textPaint)
                canvas.drawText("Size: ${size}x${size}", size / 2f, size / 2f, textPaint)
                
                // Get current settings from SharedPreferences
                val prefs = context.getSharedPreferences("album_art_settings", Context.MODE_PRIVATE)
                val format = prefs.getString("format", "JPEG") ?: "JPEG"
                val quality = prefs.getInt("quality", 80)
                canvas.drawText("Format: $format", size / 2f, size * 2f / 3f, textPaint)
                canvas.drawText("Quality: $quality", size / 2f, size * 5f / 6f, textPaint)
                
                // Compress the bitmap using ByteArrayOutputStream
                val outputStream = ByteArrayOutputStream()
                val compressFormat = when (format.uppercase()) {
                    "WEBP" -> Bitmap.CompressFormat.WEBP
                    "PNG" -> Bitmap.CompressFormat.PNG
                    else -> Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(compressFormat, quality, outputStream)
                val artData = outputStream.toByteArray()
                bitmap.recycle()
                
                if (artData != null) {
                    // Calculate checksum
                    val sha256Checksum = calculateSha256(artData)
                    val testTrackId = "test|album|art"
                    
                    debugLogger.info(
                        "ALBUM_ART_TEST",
                        "Sending test album art",
                        mapOf(
                            "size_bytes" to artData.size.toString(),
                            "sha256" to sha256Checksum,
                            "format" to format,
                            "quality" to quality.toString()
                        )
                    )
                    
                    // Send the test album art
                    sendAlbumArt(artData, sha256Checksum, testTrackId)
                } else {
                    debugLogger.error(
                        "ALBUM_ART_TEST",
                        "Failed to compress test image",
                        mapOf("device" to device.address)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating test album art", e)
                debugLogger.error(
                    "ALBUM_ART_TEST",
                    "Test failed",
                    mapOf("error" to e.message.orEmpty())
                )
            }
            
            messageQueueScope.launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    private fun handleTestAlbumArtRequest(device: BluetoothDevice) {
        messageQueueScope.launch {
            try {
                debugLogger.info(
                    "TEST_ALBUM_ART_REQUEST", 
                    "Processing test album art request",
                    mapOf("device" to device.address)
                )
                
                // Try to get album art from cache - check all cached entries
                val cacheField = AlbumArtManager::class.java.getDeclaredField("albumArtCache")
                cacheField.isAccessible = true
                val cache = cacheField.get(albumArtManager) as android.util.LruCache<String, AlbumArtManager.CachedAlbumArt>
                
                // Get the most recent album art from cache
                var albumArtData: ByteArray? = null
                var trackId: String? = null
                var checksum: String? = null
                
                // Get a snapshot of the cache
                val snapshot = cache.snapshot()
                if (snapshot.isNotEmpty()) {
                    // Get the first (most recent) entry
                    val entry = snapshot.entries.first()
                    trackId = entry.key
                    val cachedArt = entry.value
                    albumArtData = cachedArt.data
                    checksum = cachedArt.checksum
                    
                    debugLogger.info(
                        "TEST_ALBUM_ART_REQUEST",
                        "Found cached album art",
                        mapOf(
                            "track_id" to trackId,
                            "size" to albumArtData.size.toString(),
                            "checksum" to checksum
                        )
                    )
                } else {
                    debugLogger.info(
                        "TEST_ALBUM_ART_REQUEST",
                        "No album art in cache",
                        mapOf("device" to device.address)
                    )
                    
                    // Send failure message so UI doesn't get stuck
                    val failMsg = mapOf(
                        "type" to "test_album_art_end",
                        "checksum" to "",
                        "success" to false
                    )
                    val failData = gson.toJson(failMsg).toByteArray()
                    sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, failData)
                    
                    return@launch
                }
                
                if (albumArtData == null || albumArtData.isEmpty()) {
                    debugLogger.info(
                        "TEST_ALBUM_ART_REQUEST",
                        "Album art data is empty",
                        mapOf("device" to device.address)
                    )
                    
                    // Send failure message so UI doesn't get stuck
                    val failMsg = mapOf(
                        "type" to "test_album_art_end",
                        "checksum" to "",
                        "success" to false
                    )
                    val failData = gson.toJson(failMsg).toByteArray()
                    sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, failData)
                    
                    return@launch
                }
                
                // If no checksum, calculate it
                if (checksum == null) {
                    checksum = calculateSha256(albumArtData)
                }
                
                debugLogger.info(
                    "TEST_ALBUM_ART_REQUEST",
                    "Sending album art via test flow",
                    mapOf(
                        "track_id" to (trackId ?: "unknown"),
                        "size_bytes" to albumArtData.size.toString(),
                        "sha256" to checksum
                    )
                )
                
                // Send album art using test message flow
                sendTestAlbumArt(device, albumArtData, checksum, trackId ?: "test")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling test album art request", e)
                debugLogger.error(
                    "TEST_ALBUM_ART_REQUEST",
                    "Request failed",
                    mapOf("error" to e.message.orEmpty())
                )
            }
            
            messageQueueScope.launch {
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    private suspend fun sendTestAlbumArt(device: BluetoothDevice, artData: ByteArray, checksum: String, trackId: String) {
        try {
            val deviceContext = connectedDevices[device.address]
            if (deviceContext == null) {
                debugLogger.error(
                    "TEST_ALBUM_ART_SEND",
                    "Device context not found",
                    mapOf("device" to device.address)
                )
                return
            }
            
            // Get chunk settings from SharedPreferences
            val prefs = context.getSharedPreferences("album_art_settings", Context.MODE_PRIVATE)
            val chunkSize = prefs.getInt("chunk_size", 512)
            val chunkDelayMs = prefs.getInt("chunk_delay_ms", 20)
            
            // Check if we should use binary protocol (same as normal album art)
            val useBinary = deviceContext.supportsBinaryProtocol
            
            debugLogger.info(
                "TEST_ALBUM_ART_SEND",
                "Starting test album art transfer",
                mapOf(
                    "size" to artData.size.toString(),
                    "chunk_size" to chunkSize.toString(),
                    "chunk_delay_ms" to chunkDelayMs.toString(),
                    "use_binary" to useBinary.toString(),
                    "device" to device.address
                )
            )
            
            if (useBinary) {
                // Use binary protocol for test transfer
                sendTestAlbumArtBinary(device, artData, checksum, trackId, deviceContext)
            } else {
                // Use JSON protocol for test transfer
                sendTestAlbumArtJson(device, artData, checksum, trackId, chunkSize, chunkDelayMs)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending test album art", e)
            
            // Send failure message
            val failMsg = mapOf(
                "type" to "test_album_art_end",
                "checksum" to checksum,
                "success" to false
            )
            val failData = gson.toJson(failMsg).toByteArray()
            sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, failData)
            
            debugLogger.error(
                "TEST_ALBUM_ART_SEND",
                "Test transfer failed",
                mapOf("error" to e.message.orEmpty())
            )
        }
    }
    
    private suspend fun sendTestAlbumArtJson(device: BluetoothDevice, artData: ByteArray, checksum: String, trackId: String, chunkSize: Int, chunkDelayMs: Int) {
        // Calculate chunks
        val totalChunks = (artData.size + chunkSize - 1) / chunkSize
        
        Log.d(TAG, "TEST: Sending $totalChunks chunks with NO DELAY (was $chunkDelayMs ms)")
        
        // Send test start message
        val startMsg = mapOf(
            "type" to "test_album_art_start",
            "size" to artData.size,
            "checksum" to checksum,
            "total_chunks" to totalChunks
        )
        val startData = gson.toJson(startMsg).toByteArray()
        sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, startData)
            
        delay(50) // Small delay before chunks
        
        // Send chunks
        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * chunkSize
            val end = minOf(start + chunkSize, artData.size)
            val chunkData = artData.copyOfRange(start, end)
            
            // Encode chunk to base64
            val base64Chunk = android.util.Base64.encodeToString(chunkData, android.util.Base64.NO_WRAP)
            
            val chunkMsg = mapOf(
                "type" to "test_album_art_chunk",
                "checksum" to checksum,
                "chunk_index" to chunkIndex,
                "data" to base64Chunk
            )
            
            val chunkMsgData = gson.toJson(chunkMsg).toByteArray()
            sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, chunkMsgData)
            
            // No delay for test transfers - we want maximum speed
            // The BLE connection parameters will handle flow control
        }
        
        // Send test end message
        val endMsg = mapOf(
            "type" to "test_album_art_end",
            "checksum" to checksum,
            "success" to true
        )
        val endData = gson.toJson(endMsg).toByteArray()
        sendNotificationToDevice(device, BleConstants.STATE_TX_CHAR_UUID, endData)
        
        debugLogger.info(
            "TEST_ALBUM_ART_SEND",
            "Test album art transfer complete (JSON)",
            mapOf(
                "checksum" to checksum,
                "total_bytes" to artData.size.toString()
            )
        )
    }
    
    private suspend fun sendTestAlbumArtBinary(device: BluetoothDevice, artData: ByteArray, checksum: String, trackId: String, deviceContext: DeviceContext) {
        // For binary protocol, we don't send JSON start message - the binary protocol handles everything
        
        debugLogger.info(
            "TEST_ALBUM_ART_SEND",
            "Sending test album art using binary protocol",
            mapOf(
                "size" to artData.size.toString(),
                "checksum" to checksum
            )
        )
        
        // Use binary protocol to send the actual data
        val effectiveMtu = deviceContext.mtu - 3
        
        // Encode the complete transfer with test flag
        val binaryTransfer = binaryEncoder.encodeAlbumArtTransfer(
            imageData = artData,
            checksum = checksum,
            trackId = trackId,
            mtu = deviceContext.mtu,
            isTest = true  // Mark as test transfer
        )
        
        debugLogger.info(
            "TEST_ALBUM_ART_SEND",
            "Binary test transfer prepared",
            mapOf(
                "total_chunks" to binaryTransfer.totalChunks.toString(),
                "chunk_size" to binaryTransfer.chunkSize.toString(),
                "compression_ratio" to String.format("%.2f", binaryTransfer.compressionRatio)
            )
        )
        
        // Track transfer start time
        val transferStartTime = System.currentTimeMillis()
        
        // Send binary start using message queue with test flag
        val startMessage = MessageQueue.Message(
            device = device,
            characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
            data = binaryTransfer.startMessage,
            priority = MessageQueue.Priority.NORMAL,
            isBinary = true,
            isTestTransfer = true // Mark as test transfer for fast timing
        )
        messageQueue.enqueue(startMessage)
        
        // Send binary chunks using message queue with test flag
        var chunksSent = 0
        binaryTransfer.chunkMessages.forEachIndexed { index, chunkData ->
            val chunkMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
                data = chunkData,
                priority = MessageQueue.Priority.BULK,
                isBinary = true,
                isTestTransfer = true, // Mark as test transfer for fast timing
                callback = { success ->
                    if (success) {
                        chunksSent++
                        if (chunksSent % 10 == 0 || chunksSent == binaryTransfer.totalChunks) {
                            val elapsed = System.currentTimeMillis() - transferStartTime
                            val throughput = (chunksSent * binaryTransfer.chunkSize) / (elapsed / 1000.0) / 1024.0
                            Log.d(TAG, "Test transfer progress: $chunksSent/${binaryTransfer.totalChunks} chunks, ${String.format("%.1f", throughput)} KB/s")
                        }
                    }
                }
            )
            messageQueue.enqueue(chunkMessage)
        }
        
        // Send binary end using message queue with test flag
        val endMessage = MessageQueue.Message(
            device = device,
            characteristicUuid = BleConstants.ALBUM_ART_TX_CHAR_UUID,
            data = binaryTransfer.endMessage,
            priority = MessageQueue.Priority.BULK,
            isBinary = true,
            isTestTransfer = true // Mark as test transfer for fast timing
        )
        messageQueue.enqueue(endMessage)
        
        // For binary protocol, we don't send JSON end message - the binary protocol handles everything
        
        debugLogger.info(
            "TEST_ALBUM_ART_SEND",
            "Test album art transfer complete (Binary)",
            mapOf(
                "checksum" to checksum,
                "total_bytes" to artData.size.toString(),
                "total_chunks" to binaryTransfer.totalChunks.toString()
            )
        )
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
        
        // Stop message queue
        if (::messageQueue.isInitialized) {
            messageQueue.stop()
        }
        messageQueueScope.cancel()
        
        Log.d(TAG, "BLE server stopped")
    }
    
    fun sendTestAlbumArtCommand() {
        messageQueueScope.launch {
            connectedDevices.values.forEach { deviceContext ->
                // Log connection parameters for debugging
                debugLogger.info(
                    "ALBUM_ART_TEST",
                    "Initiating test album art transfer with connection info",
                    mapOf(
                        "device" to deviceContext.device.address,
                        "mtu" to deviceContext.mtu.toString(),
                        "binary_support" to deviceContext.supportsBinaryProtocol.toString(),
                        "connection_duration_ms" to (System.currentTimeMillis() - deviceContext.connectionTime).toString()
                    )
                )
                
                handleTestAlbumArt(deviceContext.device)
            }
            
            if (connectedDevices.isEmpty()) {
                debugLogger.debug(
                    "ALBUM_ART_TEST",
                    "No connected devices to send test album art",
                    mapOf()
                )
                _debugLogs.emit(debugLogger.getRecentLogs(1).firstOrNull() ?: return@launch)
            }
        }
    }
    
    private fun calculateSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    // Removed - using binary protocol only
    /*
    fun sendResponse(response: String) {
        // Send response to all connected devices via ResponseTx characteristic
        connectedDevices.values.forEach { deviceContext ->
            val message = MessageQueue.Message(
                device = deviceContext.device,
                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                data = response.toByteArray(),
                priority = MessageQueue.Priority.HIGH
            )
            messageQueue.enqueue(message)
        }
    }
    */
    
    fun sendIncrementalUpdate(messageType: Short, value: Any) {
        // Create binary payload based on value type
        val payload = when (value) {
            is String -> BinaryProtocol.createStringPayload(value)
            is Long -> BinaryProtocol.createLongPayload(value)
            is Boolean -> BinaryProtocol.createBooleanPayload(value)
            is Byte -> BinaryProtocol.createBytePayload(value)
            is Pair<*, *> -> {
                // Handle artist+album pair
                val artist = value.first as? String ?: ""
                val album = value.second as? String ?: ""
                BinaryProtocol.createArtistAlbumPayload(artist, album)
            }
            else -> {
                Log.e(TAG, "Unsupported incremental update type: ${value::class.simpleName}")
                return
            }
        }
        
        // Create binary message with header
        val header = BinaryProtocol.BinaryHeader(
            messageType = messageType,
            chunkIndex = 0,
            totalSize = payload.size
        )
        
        val binaryMessage = BinaryProtocol.createBinaryMessage(header, payload)
        
        // Send to all connected devices
        connectedDevices.values.forEach { deviceContext ->
            if (deviceContext.supportsBinaryProtocol) {
                val message = MessageQueue.Message(
                    device = deviceContext.device,
                    characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                    data = binaryMessage,
                    priority = MessageQueue.Priority.HIGH,
                    isBinary = true
                )
                messageQueue.enqueue(message)
                
                debugLogger.verbose(
                    "INCREMENTAL_UPDATE",
                    "Sent binary incremental update",
                    mapOf(
                        "type" to messageType.toString(),
                        "size" to binaryMessage.size.toString()
                    )
                )
            }
        }
    }
    
    fun updateAlbumArtSettings(
        format: String,
        quality: Int,
        chunkSize: Int,
        chunkDelayMs: Int,
        useBinaryProtocol: Boolean,
        imageSize: Int
    ) {
        // Update AlbumArtManager settings
        albumArtManager.updateSettings(
            format = format,
            quality = quality,
            imageSize = imageSize
        )
        
        // Update MessageQueue settings
        if (::messageQueue.isInitialized) {
            messageQueue.updateSettings(
                chunkSize = chunkSize,
                chunkDelayMs = chunkDelayMs,
                useBinaryProtocol = useBinaryProtocol
            )
        }
        
        // Store in preferences for persistence
        val prefs = context.getSharedPreferences("AlbumArtSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("imageFormat", format)
            putInt("compressionQuality", quality)
            putInt("chunkSize", chunkSize)
            putInt("chunkDelayMs", chunkDelayMs)
            putBoolean("useBinaryProtocol", useBinaryProtocol)
            putInt("imageSize", imageSize)
            apply()
        }
        
        Log.d(TAG, "Album art settings updated - Format: $format, Quality: $quality, Size: ${imageSize}x$imageSize, ChunkSize: $chunkSize, Delay: ${chunkDelayMs}ms, Binary: $useBinaryProtocol")
    }
    
    fun testAlbumArtTransfer() {
        // Get the current playing media's album art
        val currentMetadata = NocturneNotificationListener.activeMediaController.value?.metadata
        if (currentMetadata != null) {
            // Force send album art to all connected devices
            connectedDevices.keys.forEach { device ->
                CoroutineScope(Dispatchers.IO).launch {
                    // Extract album art
                    val albumArtResult = albumArtManager.extractAlbumArt(currentMetadata)
                    if (albumArtResult != null) {
                        val (artData, sha256Checksum) = albumArtResult
                        
                        val currentArtist = currentMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                        val currentAlbum = currentMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                        val trackId = "$currentArtist|$currentAlbum"
                        
                        Log.d(TAG, "Test transfer starting - Size: ${artData.size} bytes")
                        
                        // Send using current settings
                        sendAlbumArt(artData, sha256Checksum, trackId)
                    } else {
                        Log.w(TAG, "No album art available for test transfer")
                    }
                }
            }
        } else {
            Log.w(TAG, "No media playing for test transfer")
        }
    }
}