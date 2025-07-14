package com.paulcity.nocturnecompanion.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothManager(
    context: Context,
    private val onDataReceived: (String) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun connectToDevice(deviceAddress: String) {
        CoroutineScope(Dispatchers.IO).launch {
            _connectionStatus.value = "Connecting..."
            val device: BluetoothDevice? = bluetoothAdapter.getRemoteDevice(deviceAddress)
            if (device == null) {
                _connectionStatus.value = "Device not found"
                return@launch
            }

            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
                inputStream = socket?.inputStream
                outputStream = socket?.outputStream
                _connectionStatus.value = "Connected to ${device.name}"
                Log.d("BluetoothManager", "Connection successful")
                listenForData()
            } catch (e: IOException) {
                _connectionStatus.value = "Connection failed"
                Log.e("BluetoothManager", "Connection failed", e)
                disconnect()
            }
        }
    }

    private fun listenForData() {
        val buffer = ByteArray(1024)
        var bytes: Int
        CoroutineScope(Dispatchers.IO).launch {
            while (socket?.isConnected == true) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val receivedData = String(buffer, 0, bytes)
                        onDataReceived(receivedData)
                    }
                } catch (e: IOException) {
                    Log.e("BluetoothManager", "Input stream was disconnected", e)
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                    break
                }
            }
        }
    }

    suspend fun sendData(data: String) {
        if (socket?.isConnected == true) {
            try {
                outputStream?.write(data.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e("BluetoothManager", "Error sending data", e)
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            socket?.close()
            inputStream?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("BluetoothManager", "Error on closing socket", e)
        } finally {
            socket = null
            inputStream = null
            outputStream = null
            _connectionStatus.value = "Disconnected"
        }
    }
}