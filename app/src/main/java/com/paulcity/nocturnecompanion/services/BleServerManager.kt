package com.paulcity.nocturnecompanion.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.paulcity.nocturnecompanion.ble.BleConstants
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleServerManager(
    private val context: Context,
    private val onDataReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "BleServerManager"
    }

    private val bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter

    init {
        Log.d(TAG, "Initializing BleServerManager")
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        Log.d(TAG, "BluetoothAdapter obtained successfully")
        Log.d(TAG, "Bluetooth enabled: ${bluetoothAdapter.isEnabled}")
    }
    
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    
    // Connected devices and their characteristics
    private val connectedDevices = ConcurrentHashMap<BluetoothDevice, DeviceContext>()
    private var negotiatedMtu = BleConstants.DEFAULT_MTU
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()
    
    private data class DeviceContext(
        val device: BluetoothDevice,
        val mtu: Int = BleConstants.DEFAULT_MTU,
        var subscriptions: MutableSet<String> = mutableSetOf()
    )

    fun startServer() {
        Log.d(TAG, "Starting BLE GATT Server")
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            _connectionStatus.value = "Bluetooth not enabled"
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                setupGattServer()
                startAdvertising()
                _connectionStatus.value = "Advertising..."
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE server", e)
                _connectionStatus.value = "Failed to start server: ${e.message}"
            }
        }
    }

    private fun setupGattServer() {
        val gattServerCallback = object : BluetoothGattServerCallback() {
            
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Device connected: ${device.address}")
                        connectedDevices[device] = DeviceContext(device)
                        _connectionStatus.value = "Connected to ${device.address}"
                        
                        // Request MTU increase immediately after connection
                        requestMtuIncrease(device)
                        
                        // Stop advertising when connected
                        stopAdvertising()
                    }
                    
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Device disconnected: ${device.address}")
                        connectedDevices.remove(device)
                        _connectionStatus.value = if (connectedDevices.isEmpty()) {
                            "Disconnected"
                        } else {
                            "Connected to ${connectedDevices.size} devices"
                        }
                        
                        // Resume advertising if no devices connected
                        if (connectedDevices.isEmpty()) {
                            startAdvertising()
                        }
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                super.onMtuChanged(device, mtu)
                Log.d(TAG, "MTU changed for ${device.address}: $mtu")
                
                connectedDevices[device]?.let { context ->
                    connectedDevices[device] = context.copy(mtu = mtu)
                }
                negotiatedMtu = mtu
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
                
                Log.d(TAG, "Write request from ${device.address} to ${characteristic.uuid}")
                
                when (characteristic.uuid) {
                    BleConstants.COMMAND_RX_CHAR_UUID -> {
                        handleCommandReceived(device, value)
                    }
                    
                    // Album art handling removed in BLE-only version
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
                
                Log.d(TAG, "Descriptor write request from ${device.address}")
                
                if (descriptor.uuid == BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    val characteristic = descriptor.characteristic
                    val isNotificationEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ?: false
                    
                    connectedDevices[device]?.let { context ->
                        if (isNotificationEnabled) {
                            context.subscriptions.add(characteristic.uuid.toString())
                            Log.d(TAG, "Device ${device.address} subscribed to ${characteristic.uuid}")
                        } else {
                            context.subscriptions.remove(characteristic.uuid.toString())
                            Log.d(TAG, "Device ${device.address} unsubscribed from ${characteristic.uuid}")
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        // Create GATT Service
        val service = BluetoothGattService(BleConstants.NOCTURNE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Command RX Characteristic (nocturned writes commands here)
        val commandChar = BluetoothGattCharacteristic(
            BleConstants.COMMAND_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(commandChar)
        
        // State TX Characteristic (NocturneCompanion sends notifications here)
        val responseChar = BluetoothGattCharacteristic(
            BleConstants.STATE_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Add descriptor for notifications
        val configDescriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        responseChar.addDescriptor(configDescriptor)
        service.addCharacteristic(responseChar)

        // Open GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service) ?: throw Exception("Failed to add GATT service")
        
        Log.d(TAG, "GATT Server setup complete")
    }

    private fun requestMtuIncrease(device: BluetoothDevice) {
        // Note: MTU requests are typically initiated by the client (central)
        // We'll store this for when the client requests MTU change
        Log.d(TAG, "Waiting for MTU request from ${device.address}")
    }

    private fun startAdvertising() {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "Device does not support BLE advertising")
            _connectionStatus.value = "BLE advertising not supported"
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.NOCTURNE_SERVICE_UUID))
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                Log.i(TAG, "BLE advertising started successfully")
                isAdvertising = true
                _connectionStatus.value = "Advertising (discoverable)"
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.e(TAG, "BLE advertising failed with error: $errorCode")
                isAdvertising = false
                _connectionStatus.value = "Advertising failed (error: $errorCode)"
            }
        }

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (isAdvertising && advertiser != null) {
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
            isAdvertising = false
            Log.d(TAG, "BLE advertising stopped")
        }
    }

    private fun handleCommandReceived(device: BluetoothDevice, data: ByteArray?) {
        if (data == null) return
        
        try {
            val jsonCommand = String(data, Charsets.UTF_8)
            Log.d(TAG, "Received command from ${device.address}: $jsonCommand")
            
            CoroutineScope(Dispatchers.Main).launch {
                onDataReceived(jsonCommand)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command", e)
        }
    }

    private fun handleAlbumArtChunk(device: BluetoothDevice, data: ByteArray?) {
        if (data == null) return
        
        try {
            // Handle album art with priority (skip main thread for performance)
            CoroutineScope(Dispatchers.IO).launch {
                val jsonChunk = String(data, Charsets.UTF_8)
                Log.d(TAG, "Received album art chunk from ${device.address}: ${data.size} bytes")
                
                withContext(Dispatchers.Main) {
                    onDataReceived(jsonChunk)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing album art chunk", e)
        }
    }

    suspend fun sendResponse(response: String) {
        withContext(Dispatchers.IO) {
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            
            connectedDevices.forEach { (device, context) ->
                if (context.subscriptions.contains(BleConstants.STATE_TX_CHAR_UUID.toString())) {
                    sendNotification(device, BleConstants.STATE_TX_CHAR_UUID, responseBytes)
                }
            }
        }
    }

    private fun sendNotification(device: BluetoothDevice, characteristicUuid: java.util.UUID, data: ByteArray) {
        val service = gattServer?.getService(BleConstants.NOCTURNE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(characteristicUuid)
        
        if (characteristic != null) {
            characteristic.value = data
            val success = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
            
            if (success) {
                Log.d(TAG, "Notification sent to ${device.address}: ${data.size} bytes")
            } else {
                Log.w(TAG, "Failed to send notification to ${device.address}")
            }
        }
    }

    fun getNegotiatedMtu(): Int = negotiatedMtu

    fun isConnected(): Boolean = connectedDevices.isNotEmpty()

    fun stopServer() {
        Log.d(TAG, "Stopping BLE server")
        
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        
        _connectionStatus.value = "Disconnected"
    }
}