package com.paulcity.nocturnecompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.data.Command
import com.paulcity.nocturnecompanion.data.StateUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class EnhancedBleServerManager(
    private val context: Context,
    private val onCommandReceived: (Command) -> Unit,
    private val onDeviceConnected: ((BluetoothDevice) -> Unit)? = null
) {
    companion object {
        private const val TAG = "EnhancedBleServer"
        private val gson = Gson()
    }
    
    private val debugLogger = DebugLogger()
    private val bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    
    // Connection management
    private val connectedDevices = ConcurrentHashMap<String, DeviceContext>()
    private val commandIdCounter = AtomicInteger(0)
    
    // Album art transfer management
    private val albumArtTransferJobs = ConcurrentHashMap<String, Job>()
    private val albumArtManager = AlbumArtManager()
    
    // State flows
    private val _connectionStatus = MutableStateFlow("Not Initialized")
    val connectionStatus = _connectionStatus.asStateFlow()
    
    private val _connectedDevicesList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val connectedDevicesList = _connectedDevicesList.asStateFlow()
    
    // Debug access
    val debugLogs = debugLogger.logFlow
    
    data class DeviceContext(
        val device: BluetoothDevice,
        val connectionTime: Long = System.currentTimeMillis(),
        var mtu: Int = BleConstants.DEFAULT_MTU,
        var subscriptions: MutableSet<String> = mutableSetOf(),
        var lastActivity: Long = System.currentTimeMillis()
    )
    
    data class DeviceInfo(
        val address: String,
        val name: String,
        val mtu: Int,
        val subscriptions: List<String>,
        val connectionDuration: Long
    )
    
    data class CommandAck(
        val type: String = BleConstants.MessageType.ACK,
        val command_id: String,
        val status: String,
        val message: String
    )
    
    data class ErrorMessage(
        val type: String = BleConstants.MessageType.ERROR,
        val code: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class Capabilities(
        val type: String = BleConstants.MessageType.CAPABILITIES,
        val version: String = "2.0",
        val features: List<String> = listOf(
            "media_control",
            "volume_control", 
            "seek_support",
            "debug_logging",
            "album_art",
            "command_ack",
            "error_reporting"
        ),
        val mtu: Int = BleConstants.TARGET_MTU,
        val debug_enabled: Boolean = true
    )
    
    data class AlbumArtStart(
        val type: String = BleConstants.MessageType.ALBUM_ART_START,
        val size: Int,
        val track_id: String,
        val checksum: String
    )
    
    data class AlbumArtEnd(
        val type: String = BleConstants.MessageType.ALBUM_ART_END,
        val track_id: String,
        val checksum: String,
        val total_chunks: Int
    )
    
    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        debugLogger.info(
            DebugLogger.LogType.INITIALIZATION,
            "BLE Server Manager initialized",
            mapOf(
                "bluetooth_enabled" to (bluetoothAdapter?.isEnabled ?: false),
                "le_supported" to (bluetoothAdapter?.isMultipleAdvertisementSupported ?: false)
            )
        )
    }
    
    fun startServer() {
        if (bluetoothAdapter?.isEnabled != true) {
            val message = "Bluetooth is not enabled"
            debugLogger.error(DebugLogger.LogType.ERROR, message)
            _connectionStatus.value = message
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                setupGattServer()
                startAdvertising()
                _connectionStatus.value = "Advertising"
                debugLogger.info(DebugLogger.LogType.INITIALIZATION, "BLE server started successfully")
            } catch (e: Exception) {
                val message = "Failed to start server: ${e.message}"
                debugLogger.error(DebugLogger.LogType.ERROR, message, mapOf("exception" to e.toString()))
                _connectionStatus.value = message
            }
        }
    }
    
    private fun setupGattServer() {
        val gattServerCallback = object : BluetoothGattServerCallback() {
            
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> handleDeviceConnected(device)
                    BluetoothProfile.STATE_DISCONNECTED -> handleDeviceDisconnected(device)
                }
            }
            
            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                super.onMtuChanged(device, mtu)
                
                connectedDevices[device.address]?.let { context ->
                    context.mtu = mtu
                    debugLogger.info(
                        DebugLogger.LogType.MTU_CHANGED,
                        "MTU changed for device",
                        mapOf("device" to device.address, "mtu" to mtu)
                    )
                }
            }
            
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                
                when (characteristic.uuid) {
                    BleConstants.DEVICE_INFO_CHAR_UUID -> {
                        val capabilities = gson.toJson(Capabilities())
                        val response = capabilities.toByteArray(StandardCharsets.UTF_8)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, 
                            response.sliceArray(offset until minOf(offset + BleConstants.MAX_CHARACTERISTIC_LENGTH, response.size)))
                        
                        debugLogger.debug(
                            DebugLogger.LogType.CHARACTERISTIC_WRITE,
                            "Device info read",
                            mapOf("device" to device.address, "offset" to offset)
                        )
                    }
                    else -> {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
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
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                
                when (characteristic.uuid) {
                    BleConstants.COMMAND_RX_CHAR_UUID -> {
                        value?.let { handleCommandReceived(device, it) }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
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
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
                
                if (descriptor.uuid == BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    handleSubscriptionChange(device, descriptor.characteristic, value)
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }
        
        // Create GATT Service
        val service = BluetoothGattService(BleConstants.NOCTURNE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Command RX Characteristic
        val commandChar = BluetoothGattCharacteristic(
            BleConstants.COMMAND_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(commandChar)
        
        // State TX Characteristic
        val stateChar = createNotifyCharacteristic(BleConstants.STATE_TX_CHAR_UUID)
        service.addCharacteristic(stateChar)
        
        // Debug Log Characteristic
        val debugChar = createNotifyCharacteristic(BleConstants.DEBUG_LOG_CHAR_UUID)
        service.addCharacteristic(debugChar)
        
        // Device Info Characteristic
        val infoChar = BluetoothGattCharacteristic(
            BleConstants.DEVICE_INFO_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(infoChar)
        
        // Album Art TX Characteristic
        val albumArtChar = createNotifyCharacteristic(BleConstants.ALBUM_ART_TX_CHAR_UUID)
        service.addCharacteristic(albumArtChar)
        
        // Open GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service) ?: throw Exception("Failed to add GATT service")
        
        // Start debug log streaming
        startDebugLogStreaming()
    }
    
    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val char = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        val descriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        char.addDescriptor(descriptor)
        
        return char
    }
    
    private fun handleDeviceConnected(device: BluetoothDevice) {
        val context = DeviceContext(device)
        connectedDevices[device.address] = context
        
        debugLogger.info(
            DebugLogger.LogType.CONNECTION,
            "Device connected",
            mapOf(
                "address" to device.address,
                "name" to (device.name ?: "Unknown")
            )
        )
        
        updateConnectionStatus()
        updateConnectedDevicesList()
        
        // Send initial capabilities
        CoroutineScope(Dispatchers.IO).launch {
            delay(100) // Small delay to ensure connection is stable
            sendCapabilities(device)
            
            // Wait longer to ensure client has time to subscribe to notifications
            delay(3000) // 3 second delay to allow subscription
            onDeviceConnected?.invoke(device)
        }
    }
    
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val context = connectedDevices.remove(device.address)
        
        debugLogger.info(
            DebugLogger.LogType.DISCONNECTION,
            "Device disconnected",
            mapOf(
                "address" to device.address,
                "connection_duration" to (context?.let { System.currentTimeMillis() - it.connectionTime } ?: 0)
            )
        )
        
        updateConnectionStatus()
        updateConnectedDevicesList()
        
        // Resume advertising if no devices connected
        if (connectedDevices.isEmpty() && !isAdvertising) {
            startAdvertising()
        }
    }
    
    private fun handleSubscriptionChange(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        val isEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ?: false
        val charUuid = characteristic.uuid.toString()
        
        connectedDevices[device.address]?.let { context ->
            if (isEnabled) {
                context.subscriptions.add(charUuid)
                debugLogger.debug(
                    "SUBSCRIPTION",
                    "Notifications enabled",
                    mapOf("device" to device.address, "characteristic" to charUuid)
                )
            } else {
                context.subscriptions.remove(charUuid)
                debugLogger.debug(
                    "SUBSCRIPTION",
                    "Notifications disabled",
                    mapOf("device" to device.address, "characteristic" to charUuid)
                )
            }
        }
        
        updateConnectedDevicesList()
    }
    
    private fun handleCommandReceived(device: BluetoothDevice, data: ByteArray) {
        try {
            val jsonStr = String(data, StandardCharsets.UTF_8)
            val commandId = "cmd_${commandIdCounter.incrementAndGet()}"
            
            debugLogger.info(
                DebugLogger.LogType.COMMAND_RECEIVED,
                "Command received",
                mapOf(
                    "device" to device.address,
                    "command_id" to commandId,
                    "data" to jsonStr,
                    "size" to data.size
                )
            )
            
            val command = gson.fromJson(jsonStr, Command::class.java)
            
            // Send acknowledgment
            sendAck(device, commandId, "received", "Command received and processing")
            
            // Execute command
            onCommandReceived(command)
            
            // Send success acknowledgment
            sendAck(device, commandId, "success", "Command executed successfully")
            
            debugLogger.info(
                DebugLogger.LogType.COMMAND_EXECUTED,
                "Command executed",
                mapOf(
                    "command_id" to commandId,
                    "command" to command.command
                )
            )
            
        } catch (e: Exception) {
            debugLogger.error(
                DebugLogger.LogType.ERROR,
                "Failed to process command",
                mapOf(
                    "device" to device.address,
                    "error" to (e.message ?: "Unknown error"),
                    "data" to String(data, StandardCharsets.UTF_8)
                )
            )
            
            sendError(device, "COMMAND_PARSE_ERROR", e.message ?: "Unknown error")
        }
    }
    
    private fun sendAck(device: BluetoothDevice, commandId: String, status: String, message: String) {
        val ack = CommandAck(command_id = commandId, status = status, message = message)
        sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, gson.toJson(ack))
    }
    
    private fun sendError(device: BluetoothDevice, code: String, message: String) {
        val error = ErrorMessage(code = code, message = message)
        sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, gson.toJson(error))
    }
    
    private fun sendCapabilities(device: BluetoothDevice) {
        val capabilities = Capabilities(mtu = connectedDevices[device.address]?.mtu ?: BleConstants.DEFAULT_MTU)
        sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, gson.toJson(capabilities))
    }
    
    fun sendStateUpdate(stateUpdate: StateUpdate) {
        val json = gson.toJson(stateUpdate)
        
        debugLogger.debug(
            DebugLogger.LogType.STATE_UPDATED,
            "Sending state update",
            mapOf(
                "is_playing" to stateUpdate.is_playing,
                "track" to (stateUpdate.track ?: "Unknown")
            )
        )
        
        connectedDevices.values.forEach { context ->
            if (context.subscriptions.contains(BleConstants.STATE_TX_CHAR_UUID.toString())) {
                sendNotification(context.device, BleConstants.STATE_TX_CHAR_UUID, json)
            }
        }
    }
    
    fun sendStateUpdate(data: Map<String, Any>) {
        val json = gson.toJson(data)
        
        debugLogger.debug(
            DebugLogger.LogType.STATE_UPDATED,
            "Sending custom state update",
            mapOf(
                "type" to (data["type"] ?: "unknown"),
                "size" to json.length
            )
        )
        
        connectedDevices.values.forEach { context ->
            if (context.subscriptions.contains(BleConstants.STATE_TX_CHAR_UUID.toString())) {
                sendNotification(context.device, BleConstants.STATE_TX_CHAR_UUID, json)
            }
        }
    }
    
    private fun sendNotification(device: BluetoothDevice, characteristicUuid: UUID, data: String) {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        val service = gattServer?.getService(BleConstants.NOCTURNE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(characteristicUuid)
        
        characteristic?.let { char ->
            char.value = bytes
            val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            
            if (success) {
                debugLogger.verbose(
                    DebugLogger.LogType.NOTIFICATION_SENT,
                    "Notification sent",
                    mapOf(
                        "device" to device.address,
                        "characteristic" to characteristicUuid.toString(),
                        "size" to bytes.size
                    )
                )
            } else {
                debugLogger.warning(
                    DebugLogger.LogType.ERROR,
                    "Failed to send notification",
                    mapOf(
                        "device" to device.address,
                        "characteristic" to characteristicUuid.toString()
                    )
                )
            }
        }
    }
    
    private fun startDebugLogStreaming() {
        CoroutineScope(Dispatchers.IO).launch {
            debugLogger.logFlow.collect { logEntry ->
                val json = logEntry.toJson()
                
                connectedDevices.values.forEach { context ->
                    if (context.subscriptions.contains(BleConstants.DEBUG_LOG_CHAR_UUID.toString())) {
                        sendNotification(context.device, BleConstants.DEBUG_LOG_CHAR_UUID, json)
                    }
                }
            }
        }
    }
    
    private fun startAdvertising() {
        if (isAdvertising) return
        
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            debugLogger.error(DebugLogger.LogType.ERROR, "BLE advertising not supported")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(BleConstants.ADVERTISING_TIMEOUT_MS.toInt())
            .build()
        
        // Primary advertising data - keep minimal to avoid exceeding 31 byte limit
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // Don't include name in primary packet
            .setIncludeTxPowerLevel(false) // Don't include TX power
            .addServiceUuid(ParcelUuid(BleConstants.NOCTURNE_SERVICE_UUID))
            .build()
        
        // Scan response can include additional data
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)  // Include name in scan response
            .build()
        
        advertiser?.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                isAdvertising = true
                debugLogger.info(
                    DebugLogger.LogType.ADVERTISING,
                    "Advertising started",
                    mapOf(
                        "mode" to settingsInEffect.mode,
                        "tx_power" to settingsInEffect.txPowerLevel
                    )
                )
                _connectionStatus.value = "Advertising"
            }
            
            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                debugLogger.error(
                    DebugLogger.LogType.ERROR,
                    "Advertising failed",
                    mapOf("error_code" to errorCode)
                )
                _connectionStatus.value = "Advertising failed ($errorCode)"
            }
        })
    }
    
    private fun stopAdvertising() {
        if (!isAdvertising) return
        
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        isAdvertising = false
        debugLogger.info(DebugLogger.LogType.ADVERTISING, "Advertising stopped")
    }
    
    private fun updateConnectionStatus() {
        _connectionStatus.value = when (connectedDevices.size) {
            0 -> if (isAdvertising) "Advertising" else "Disconnected"
            1 -> "Connected to ${connectedDevices.values.first().device.name ?: connectedDevices.values.first().device.address}"
            else -> "Connected to ${connectedDevices.size} devices"
        }
    }
    
    private fun updateConnectedDevicesList() {
        val devices = connectedDevices.values.map { context ->
            DeviceInfo(
                address = context.device.address,
                name = context.device.name ?: "Unknown Device",
                mtu = context.mtu,
                subscriptions = context.subscriptions.map { uuid ->
                    when (uuid) {
                        BleConstants.STATE_TX_CHAR_UUID.toString() -> "State Updates"
                        BleConstants.DEBUG_LOG_CHAR_UUID.toString() -> "Debug Logs"
                        BleConstants.ALBUM_ART_TX_CHAR_UUID.toString() -> "Album Art"
                        else -> uuid
                    }
                },
                connectionDuration = System.currentTimeMillis() - context.connectionTime
            )
        }
        _connectedDevicesList.value = devices
    }
    
    fun sendAlbumArtFromMetadata(metadata: android.media.MediaMetadata?, trackId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            albumArtManager.extractAlbumArt(metadata)?.let { (artData, checksum) ->
                sendAlbumArt(artData, checksum, trackId)
            } ?: run {
                debugLogger.info(
                    DebugLogger.LogType.NOTIFICATION_SENT,
                    "No album art available for track",
                    mapOf("track_id" to trackId)
                )
            }
        }
    }
    
    fun sendAlbumArt(albumArtData: ByteArray, checksum: String, trackId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // Cancel any existing transfer for this device
            albumArtTransferJobs.values.forEach { it.cancel() }
            albumArtTransferJobs.clear()
            
            // Send to all connected devices that have subscribed to album art
            connectedDevices.values.forEach { context ->
                if (context.subscriptions.contains(BleConstants.ALBUM_ART_TX_CHAR_UUID.toString())) {
                    val job = launch {
                        sendAlbumArtToDevice(context.device, albumArtData, checksum, trackId)
                    }
                    albumArtTransferJobs[context.device.address] = job
                }
            }
        }
    }
    
    private suspend fun sendAlbumArtToDevice(device: BluetoothDevice, data: ByteArray, checksum: String, trackId: String) {
        try {
            val mtu = connectedDevices[device.address]?.mtu ?: BleConstants.DEFAULT_MTU
            val chunkSize = minOf(BleConstants.ALBUM_ART_CHUNK_SIZE, mtu - BleConstants.MTU_HEADER_SIZE)
            val totalChunks = (data.size + chunkSize - 1) / chunkSize
            
            debugLogger.info(
                DebugLogger.LogType.NOTIFICATION_SENT,
                "Starting album art transfer",
                mapOf(
                    "device" to device.address,
                    "size" to data.size,
                    "chunks" to totalChunks,
                    "chunk_size" to chunkSize,
                    "track_id" to trackId
                )
            )
            
            // Send start message
            val startMsg = AlbumArtStart(
                size = data.size,
                track_id = trackId,
                checksum = checksum
            )
            sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, gson.toJson(startMsg))
            
            // Small delay to ensure start message is processed
            delay(50)
            
            // Send chunks
            var offset = 0
            var chunkIndex = 0
            
            while (offset < data.size && coroutineContext.isActive) {
                val remainingBytes = data.size - offset
                val currentChunkSize = minOf(chunkSize, remainingBytes)
                val chunk = data.sliceArray(offset until offset + currentChunkSize)
                
                // Send raw bytes on album art characteristic
                sendRawNotification(device, BleConstants.ALBUM_ART_TX_CHAR_UUID, chunk)
                
                debugLogger.verbose(
                    DebugLogger.LogType.NOTIFICATION_SENT,
                    "Sent album art chunk",
                    mapOf(
                        "chunk" to chunkIndex,
                        "size" to currentChunkSize,
                        "offset" to offset
                    )
                )
                
                offset += currentChunkSize
                chunkIndex++
                
                // Small delay between chunks to avoid overwhelming the receiver
                delay(20)
            }
            
            // Send end message
            val endMsg = AlbumArtEnd(
                track_id = trackId,
                checksum = checksum,
                total_chunks = chunkIndex
            )
            sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, gson.toJson(endMsg))
            
            debugLogger.info(
                DebugLogger.LogType.NOTIFICATION_SENT,
                "Album art transfer completed",
                mapOf(
                    "device" to device.address,
                    "total_chunks" to chunkIndex,
                    "track_id" to trackId
                )
            )
            
        } catch (e: Exception) {
            debugLogger.error(
                DebugLogger.LogType.ERROR,
                "Album art transfer failed",
                mapOf(
                    "device" to device.address,
                    "error" to e.message,
                    "track_id" to trackId
                )
            )
        }
    }
    
    private fun sendRawNotification(device: BluetoothDevice, characteristicUuid: UUID, data: ByteArray) {
        val service = gattServer?.getService(BleConstants.NOCTURNE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(characteristicUuid)
        
        characteristic?.let { char ->
            char.value = data
            val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            
            if (!success) {
                debugLogger.warning(
                    DebugLogger.LogType.ERROR,
                    "Failed to send raw notification",
                    mapOf(
                        "device" to device.address,
                        "characteristic" to characteristicUuid.toString(),
                        "size" to data.size
                    )
                )
            }
        }
    }
    
    fun stopServer() {
        debugLogger.info(DebugLogger.LogType.INITIALIZATION, "Stopping BLE server")
        
        // Cancel all album art transfers
        albumArtTransferJobs.values.forEach { it.cancel() }
        albumArtTransferJobs.clear()
        
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        
        _connectionStatus.value = "Stopped"
        _connectedDevicesList.value = emptyList()
    }
    
    fun getDebugLogs(count: Int = 100): List<DebugLogger.DebugLogEntry> {
        return debugLogger.getRecentLogs(count)
    }
    
    fun clearDebugLogs() {
        debugLogger.clearLogs()
    }
}