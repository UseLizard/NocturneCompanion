package com.paulcity.nocturnecompanion.ble

import android.util.Log
import com.google.gson.Gson
import com.paulcity.nocturnecompanion.data.WeatherResponse
import com.paulcity.nocturnecompanion.utils.getConditionFromCode
import com.paulcity.nocturnecompanion.ble.protocol.WeatherBinaryEncoder
import com.paulcity.nocturnecompanion.ble.MessageQueue
import com.paulcity.nocturnecompanion.ble.BleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Manager class for handling weather data transmission over BLE
 */
class WeatherBleManager(
    private val bleServerManager: EnhancedBleServerManager
) {
    companion object {
        private const val TAG = "WeatherBleManager"
        private const val MAX_MTU_SAFE_SIZE = 400 // Conservative size for weather data
    }

    private val gson = Gson()
    private val binaryEncoder = WeatherBinaryEncoder()
    private var lastSentWeatherHash: String? = null
    
    /**
     * Send weather data to connected BLE devices
     */
    fun sendWeatherUpdate(weatherResponse: WeatherResponse, locationName: String, isCurrentLocation: Boolean) {
        Log.d(TAG, "BLE_LOG: Starting sequential weather update transmission for $locationName")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "BLE_LOG: Sending hourly weather data and waiting for completion...")
                // Send hourly data first and wait for completion
                sendHourlyWeatherData(weatherResponse, locationName)
                
                // Add delay to ensure hourly transmission completes before starting weekly
                kotlinx.coroutines.delay(2000) // 2 second delay to prevent race condition and corruption
                
                Log.d(TAG, "BLE_LOG: Sending weekly weather data and waiting for completion...")
                // Send weekly data after hourly completes
                sendWeeklyWeatherData(weatherResponse, locationName)
                
                Log.d(TAG, "BLE_LOG: Sequential weather data transmission completed for $locationName - " +
                        "hourly hours: ${weatherResponse.hourly.time.size}, daily days: ${weatherResponse.daily.time.size}")
            } catch (e: Exception) {
                Log.e(TAG, "BLE_LOG: Failed to send weather update for $locationName", e)
            }
        }
    }
    
    /**
     * Send hourly weather data (today's 24 hours)
     */
    private suspend fun sendHourlyWeatherData(weatherResponse: WeatherResponse, locationName: String) {
        val hourlyData = mutableListOf<Map<String, Any>>()
        
        // Get today's date for filtering
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        for (i in weatherResponse.hourly.time.indices) {
            val time = weatherResponse.hourly.time[i]
            
            // Only include today's hours (next 24 hours)
            if (time.startsWith(today) || hourlyData.size < 24) {
                val tempF = weatherResponse.hourly.temperature_2m[i] // API now returns Fahrenheit directly
                val tempC = (tempF - 32) * 5/9 // Convert to Celsius for storage
                val weatherCode = weatherResponse.hourly.weathercode[i]
                
                val hourData = mapOf(
                    "time" to time,
                    "temp_f" to tempF.toInt(),
                    "temp_c" to tempC.toInt(),
                    "condition" to getConditionFromCode(weatherCode),
                    "weather_code" to weatherCode,
                    "precipitation" to (weatherResponse.hourly.precipitation_probability.getOrNull(i) ?: 0),
                    "humidity" to (weatherResponse.hourly.relativehumidity_2m.getOrNull(i) ?: 0),
                    "wind_speed" to (weatherResponse.hourly.windspeed_10m.getOrNull(i) ?: 0.0).toInt()
                )
                hourlyData.add(hourData)
                
                if (hourlyData.size >= 24) break // Limit to 24 hours
            }
        }
        
        val weatherUpdate = mapOf(
            "type" to "weatherUpdate",
            "mode" to "hourly",
            "location" to mapOf(
                "name" to locationName,
                "latitude" to weatherResponse.latitude,
                "longitude" to weatherResponse.longitude
            ),
            "timestamp_ms" to System.currentTimeMillis(),
            "hours" to hourlyData
        )
        
        sendWeatherMessage(weatherResponse, locationName, "hourly")
    }
    
    /**
     * Send weekly weather data (7 days)
     */
    private suspend fun sendWeeklyWeatherData(weatherResponse: WeatherResponse, locationName: String) {
        val weeklyData = mutableListOf<Map<String, Any>>()
        
        for (i in weatherResponse.daily.time.indices.take(7)) {
            val date = weatherResponse.daily.time[i]
            val maxTempF = weatherResponse.daily.temperature_2m_max[i] // API now returns Fahrenheit directly
            val minTempF = weatherResponse.daily.temperature_2m_min[i] // API now returns Fahrenheit directly
            val maxTempC = (maxTempF - 32) * 5/9 // Convert to Celsius for storage
            val minTempC = (minTempF - 32) * 5/9 // Convert to Celsius for storage
            val weatherCode = weatherResponse.daily.weathercode[i]
            
            // Format day name
            val dayName = try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val outputFormat = SimpleDateFormat("EEEE", Locale.getDefault())
                val dateObj = inputFormat.parse(date)
                outputFormat.format(dateObj ?: Date())
            } catch (e: Exception) {
                "Unknown"
            }
            
            val dayData = mapOf(
                "date" to date,
                "day_name" to dayName,
                "high_f" to maxTempF.toInt(),
                "low_f" to minTempF.toInt(),
                "high_c" to maxTempC.toInt(),
                "low_c" to minTempC.toInt(),
                "condition" to getConditionFromCode(weatherCode),
                "weather_code" to weatherCode,
                "precipitation" to (weatherResponse.daily.precipitation_probability_max.getOrNull(i) ?: 0),
                "humidity" to 65 // Average humidity estimate
            )
            weeklyData.add(dayData)
        }
        
        val weatherUpdate = mapOf(
            "type" to "weatherUpdate",
            "mode" to "weekly",
            "location" to mapOf(
                "name" to locationName,
                "latitude" to weatherResponse.latitude,
                "longitude" to weatherResponse.longitude
            ),
            "timestamp_ms" to System.currentTimeMillis(),
            "days" to weeklyData
        )
        
        sendWeatherMessage(weatherResponse, locationName, "weekly")
    }
    
    /**
     * Send weather message using binary protocol with compression and chunking
     */
    private suspend fun sendWeatherMessage(weatherResponse: WeatherResponse, locationName: String, mode: String) {
        try {
            Log.d(TAG, "BLE_LOG: Starting binary weather transfer for $mode mode")
            
            // Get connected devices using reflection to access private field
            val connectedDevices = try {
                val field = bleServerManager.javaClass.getDeclaredField("connectedDevices")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                field.get(bleServerManager) as java.util.concurrent.ConcurrentHashMap<String, Any>
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access connected devices: ${e.message}")
                return
            }
            
            if (connectedDevices.isEmpty()) {
                Log.w(TAG, "BLE_LOG: No connected devices, skipping weather binary transfer")
                return
            }
            
            // Send to all connected devices
            for ((deviceAddress, deviceContext) in connectedDevices) {
                sendWeatherToDevice(deviceAddress, deviceContext, weatherResponse, locationName, mode)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "BLE_LOG: Failed to send binary weather message for mode: $mode", e)
        }
    }
    
    /**
     * Send weather data to a specific device using binary protocol
     */
    private suspend fun sendWeatherToDevice(
        deviceAddress: String,
        deviceContext: Any, // EnhancedBleServerManager.DeviceContext
        weatherResponse: WeatherResponse,
        locationName: String,
        mode: String
    ) {
        try {
            // Get MTU from device context using reflection
            val mtu = try {
                val mtuField = deviceContext.javaClass.getDeclaredField("mtu")
                mtuField.isAccessible = true
                mtuField.getInt(deviceContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get MTU from device context, using default: ${e.message}")
                517 // Default BLE MTU
            }
            
            Log.d(TAG, "BLE_LOG: Encoding weather binary transfer - Device: $deviceAddress, MTU: $mtu, Mode: $mode")
            
            // Encode weather data for binary transfer
            val binaryTransfer = binaryEncoder.encodeWeatherTransfer(
                weatherResponse = weatherResponse,
                locationName = locationName,
                mode = mode,
                mtu = mtu
            )
            
            Log.d(TAG, "BLE_LOG: Weather binary transfer encoded - " +
                    "Original: ${binaryTransfer.originalSize} bytes, " +
                    "Compressed: ${binaryTransfer.compressedSize} bytes " +
                    "(${String.format("%.1f", (1.0 - binaryTransfer.compressionRatio) * 100)}% reduction), " +
                    "${binaryTransfer.totalChunks} chunks")
            
            // Get device from context using reflection
            val device = try {
                val deviceField = deviceContext.javaClass.getDeclaredField("device")
                deviceField.isAccessible = true
                deviceField.get(deviceContext) as android.bluetooth.BluetoothDevice
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get BluetoothDevice from context: ${e.message}")
                return
            }
            
            // Send start message
            val startMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID, // Use STATE_TX for weather
                data = binaryTransfer.startMessage,
                priority = MessageQueue.Priority.NORMAL,
                isBinary = true
            )
            getMessageQueue().enqueue(startMessage)
            
            // Send chunk messages
            binaryTransfer.chunkMessages.forEachIndexed { index, chunkData ->
                Log.d(TAG, "BLE_LOG: Queueing weather chunk ${index + 1}/${binaryTransfer.totalChunks} - Total message: ${chunkData.size} bytes (payload: ${chunkData.size - 16} bytes)")
                val chunkMessage = MessageQueue.Message(
                    device = device,
                    characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                    data = chunkData,
                    priority = MessageQueue.Priority.NORMAL,
                    isBinary = true,
                    callback = { success ->
                        Log.d(TAG, "BLE_LOG: Weather chunk ${index + 1}/${binaryTransfer.totalChunks} transmission: ${if (success) "SUCCESS" else "FAILED"}")
                        if (success) {
                            if ((index + 1) % 10 == 0 || index == binaryTransfer.totalChunks - 1) {
                                Log.d(TAG, "BLE_LOG: Weather chunk progress: ${index + 1}/${binaryTransfer.totalChunks}")
                            }
                        }
                    }
                )
                val queued = getMessageQueue().enqueue(chunkMessage)
                Log.d(TAG, "BLE_LOG: Weather chunk ${index + 1}/${binaryTransfer.totalChunks} queued: $queued")
            }
            
            // Send end message
            val endMessage = MessageQueue.Message(
                device = device,
                characteristicUuid = BleConstants.STATE_TX_CHAR_UUID,
                data = binaryTransfer.endMessage,
                priority = MessageQueue.Priority.NORMAL,
                isBinary = true,
                callback = { success ->
                    Log.d(TAG, "BLE_LOG: Weather binary transfer $mode complete - Success: $success")
                }
            )
            getMessageQueue().enqueue(endMessage)
            
            Log.d(TAG, "BLE_LOG: Weather binary messages queued for device $deviceAddress")
            
        } catch (e: Exception) {
            Log.e(TAG, "BLE_LOG: Failed to send weather binary to device $deviceAddress", e)
        }
    }
    
    /**
     * Check if weather data has changed to avoid duplicate sends
     */
    fun shouldSendWeatherUpdate(weatherResponse: WeatherResponse, locationName: String): Boolean {
        val weatherHash = generateWeatherHash(weatherResponse, locationName)
        return weatherHash != lastSentWeatherHash
    }
    
    /**
     * Mark weather as sent to track duplicates
     */
    fun markWeatherAsSent(weatherResponse: WeatherResponse, locationName: String) {
        lastSentWeatherHash = generateWeatherHash(weatherResponse, locationName)
    }
    
    /**
     * Generate a hash for weather data to detect changes
     */
    private fun generateWeatherHash(weatherResponse: WeatherResponse, locationName: String): String {
        val hashData = StringBuilder()
        hashData.append(locationName)
        hashData.append(weatherResponse.latitude)
        hashData.append(weatherResponse.longitude)
        
        // Include first few hourly temperatures for change detection
        weatherResponse.hourly.temperature_2m.take(5).forEach {
            hashData.append(it.toInt())
        }
        
        // Include daily high/low for change detection
        weatherResponse.daily.temperature_2m_max.take(3).forEach {
            hashData.append(it.toInt())
        }
        weatherResponse.daily.temperature_2m_min.take(3).forEach {
            hashData.append(it.toInt())
        }
        
        return hashData.toString().hashCode().toString()
    }
    
    /**
     * Send weather refresh acknowledgment when requested by Car Thing
     */
    fun handleWeatherRefreshRequest() {
        Log.d(TAG, "Weather refresh requested by Car Thing")
        // The actual refresh will be triggered by the service when it detects this request
    }
    
    /**
     * Get message queue using reflection
     */
    private fun getMessageQueue(): MessageQueue {
        return try {
            val field = bleServerManager.javaClass.getDeclaredField("messageQueue")
            field.isAccessible = true
            field.get(bleServerManager) as MessageQueue
        } catch (e: Exception) {
            throw RuntimeException("Failed to access message queue: ${e.message}", e)
        }
    }
}