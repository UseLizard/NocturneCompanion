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
            
            // Notify connection callback after capabilities
            delay(100)
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
                        else -> uuid
                    }
                },
                connectionDuration = System.currentTimeMillis() - context.connectionTime
            )
        }
        _connectedDevicesList.value = devices
    }
    
    fun stopServer() {
        debugLogger.info(DebugLogger.LogType.INITIALIZATION, "Stopping BLE server")
        
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