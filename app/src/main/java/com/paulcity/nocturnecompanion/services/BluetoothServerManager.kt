package com.paulcity.nocturnecompanion.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
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
class BluetoothServerManager(
    context: Context,
    private val onDataReceived: (String) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter

    init {
        Log.d("BluetoothServerManager", "Initializing BluetoothServerManager")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        
        if (bluetoothManager == null) {
            Log.e("BluetoothServerManager", "BluetoothManager is null - Bluetooth service unavailable")
            throw IllegalStateException("Bluetooth service unavailable")
        }
        
        bluetoothAdapter = bluetoothManager.adapter ?: run {
            Log.e("BluetoothServerManager", "BluetoothAdapter is null - Bluetooth not supported")
            throw IllegalStateException("Bluetooth not supported on this device")
        }
        
        Log.d("BluetoothServerManager", "BluetoothAdapter obtained successfully")
        Log.d("BluetoothServerManager", "Bluetooth enabled: ${bluetoothAdapter.isEnabled}")
        Log.d("BluetoothServerManager", "Bluetooth address: ${bluetoothAdapter.address}")
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isServerRunning = false

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serviceName = "NocturneCompanionSPP"

    fun startServer() {
        Log.d("BluetoothServerManager", "startServer() called")
        if (isServerRunning) {
            Log.d("BluetoothServerManager", "Server already running")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e("BluetoothServerManager", "Bluetooth is not enabled")
            _connectionStatus.value = "Bluetooth not enabled"
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                _connectionStatus.value = "Starting server..."
                Log.d("BluetoothServerManager", "Starting SPP server with UUID: $sppUuid")
                
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName, sppUuid)
                isServerRunning = true
                _connectionStatus.value = "Listening for connections..."
                
                Log.d("BluetoothServerManager", "SPP server started, waiting for connections...")
                
                // Accept incoming connections in a loop
                while (isServerRunning) {
                    try {
                        Log.d("BluetoothServerManager", "Waiting for client connection...")
                        clientSocket = serverSocket?.accept() // This blocks until a connection comes in
                        
                        if (clientSocket != null) {
                            Log.d("BluetoothServerManager", "Client connected: ${clientSocket?.remoteDevice?.name}")
                            _connectionStatus.value = "Connected to ${clientSocket?.remoteDevice?.name}"
                            
                            inputStream = clientSocket?.inputStream
                            outputStream = clientSocket?.outputStream
                            
                            // Send immediate connection acknowledgment for discovery
                            sendConnectionAck()
                            
                            // Handle this connection
                            handleConnection()
                        }
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            Log.e("BluetoothServerManager", "Error accepting connection", e)
                            _connectionStatus.value = "Error: ${e.message}"
                        }
                        // Clean up and continue listening
                        cleanupClientConnection()
                    }
                }
            } catch (e: IOException) {
                Log.e("BluetoothServerManager", "Failed to start server", e)
                _connectionStatus.value = "Failed to start server"
                stopServer()
            }
        }
    }

    private suspend fun handleConnection() {
        val buffer = ByteArray(1024)
        
        try {
            while (clientSocket?.isConnected == true && isServerRunning) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val receivedData = String(buffer, 0, bytes).trim()
                        Log.d("BluetoothServerManager", "Received: $receivedData")
                        
                        withContext(Dispatchers.Main) {
                            onDataReceived(receivedData)
                        }
                    } else if (bytes == -1) {
                        Log.d("BluetoothServerManager", "Client disconnected (EOF)")
                        break
                    }
                } catch (e: IOException) {
                    Log.e("BluetoothServerManager", "Error reading data", e)
                    break
                }
            }
        } finally {
            Log.d("BluetoothServerManager", "Connection handling ended")
            cleanupClientConnection()
            _connectionStatus.value = "Listening for connections..."
        }
    }

    suspend fun sendData(data: String) {
        if (clientSocket?.isConnected == true) {
            try {
                val dataWithNewline = data + "\n"  // Add newline for nocturned parser
                outputStream?.write(dataWithNewline.toByteArray())
                outputStream?.flush()
                Log.d("BluetoothServerManager", "Sent: $data")
            } catch (e: IOException) {
                Log.e("BluetoothServerManager", "Error sending data", e)
                cleanupClientConnection()
            }
        } else {
            Log.w("BluetoothServerManager", "No client connected, cannot send data")
        }
    }

    private fun sendConnectionAck() {
        if (clientSocket?.isConnected == true) {
            try {
                // Send immediate state update for discovery purposes
                val ackData = """{
                    "type": "stateUpdate",
                    "artist": "NocturneCompanion",
                    "album": "Connected",
                    "track": "Discovery Response",
                    "duration_ms": 0,
                    "position_ms": 0,
                    "is_playing": false,
                    "volume_percent": 50
                }""".trimIndent()
                
                val dataWithNewline = ackData + "\n"
                outputStream?.write(dataWithNewline.toByteArray())
                outputStream?.flush()
                Log.d("BluetoothServerManager", "Sent connection acknowledgment for discovery")
            } catch (e: IOException) {
                Log.e("BluetoothServerManager", "Error sending connection ack", e)
                // Don't cleanup connection here, let the main handler deal with it
            }
        }
    }

    private fun cleanupClientConnection() {
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
        } catch (e: IOException) {
            Log.e("BluetoothServerManager", "Error cleaning up client connection", e)
        } finally {
            inputStream = null
            outputStream = null
            clientSocket = null
        }
    }

    fun stopServer() {
        Log.d("BluetoothServerManager", "Stopping server...")
        isServerRunning = false
        
        cleanupClientConnection()
        
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("BluetoothServerManager", "Error closing server socket", e)
        } finally {
            serverSocket = null
            _connectionStatus.value = "Disconnected"
        }
        
        Log.d("BluetoothServerManager", "Server stopped")
    }

    fun isConnected(): Boolean {
        return clientSocket?.isConnected == true
    }
}