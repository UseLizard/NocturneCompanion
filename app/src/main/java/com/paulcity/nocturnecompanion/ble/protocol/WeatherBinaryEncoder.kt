package com.paulcity.nocturnecompanion.ble.protocol

import android.util.Log
import com.paulcity.nocturnecompanion.data.WeatherResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * Encodes weather data for binary transfer over BLE
 * Similar to BinaryAlbumArtEncoder but specialized for weather data
 */
class WeatherBinaryEncoder {
    companion object {
        private const val TAG = "WeatherBinaryEncoder"
        
        // Binary protocol message types (matching nocturned)
        private const val MSG_WEATHER_START: UShort = 0x0501u
        private const val MSG_WEATHER_CHUNK: UShort = 0x0502u  
        private const val MSG_WEATHER_END: UShort = 0x0503u
        
        // Header sizes
        private const val BINARY_HEADER_SIZE = 16
        private const val MTU_HEADER_OVERHEAD = 3 // GATT overhead
    }
    
    /**
     * Result of weather binary encoding
     */
    data class WeatherBinaryTransfer(
        val startMessage: ByteArray,
        val chunkMessages: List<ByteArray>,
        val endMessage: ByteArray,
        val totalChunks: Int,
        val chunkSize: Int,
        val originalSize: Int,
        val compressedSize: Int,
        val compressionRatio: Double,
        val checksum: String
    )
    
    /**
     * Encode weather data for binary transfer
     */
    fun encodeWeatherTransfer(
        weatherResponse: WeatherResponse,
        locationName: String,
        mode: String, // "hourly" or "weekly"
        mtu: Int
    ): WeatherBinaryTransfer {
        val startTime = System.currentTimeMillis()
        
        // Convert weather response to JSON
        val gson = com.google.gson.Gson()
        val weatherJson = when (mode) {
            "hourly" -> createHourlyWeatherJson(weatherResponse, locationName)
            "weekly" -> createWeeklyWeatherJson(weatherResponse, locationName)
            else -> throw IllegalArgumentException("Invalid mode: $mode")
        }
        
        val jsonData = gson.toJson(weatherJson).toByteArray(Charsets.UTF_8)
        val originalSize = jsonData.size
        
        // Compress with gzip
        val compressedData = compressData(jsonData)
        val compressedSize = compressedData.size
        val compressionRatio = compressedSize.toDouble() / originalSize.toDouble()
        
        // Generate SHA-256 checksum
        val checksum = generateChecksum(compressedData)
        val checksumBytes = hexToBytes(checksum)
        
        // Calculate chunk size based on MTU with safety margin
        // Total message = GATT overhead + Binary header + Payload + safety margin
        // BLE stacks often need extra overhead beyond reported values
        val safetyMargin = 20 // Extra bytes for BLE stack overhead
        val maxPayloadSize = mtu - MTU_HEADER_OVERHEAD - BINARY_HEADER_SIZE - safetyMargin
        val chunkSize = maxOf(50, maxPayloadSize) // Minimum 50 bytes per chunk
        
        Log.d(TAG, "MTU calculation: MTU=$mtu, Overhead=$MTU_HEADER_OVERHEAD, Header=$BINARY_HEADER_SIZE, Safety=$safetyMargin, MaxPayload=$maxPayloadSize")
        
        // Split into chunks
        val totalChunks = (compressedData.size + chunkSize - 1) / chunkSize
        val chunks = mutableListOf<ByteArray>()
        
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, compressedData.size)
            chunks.add(compressedData.sliceArray(start until end))
        }
        
        Log.d(TAG, "Weather binary encoding: $originalSize bytes -> $compressedSize bytes " +
                "(${String.format("%.1f", (1.0 - compressionRatio) * 100)}% compression), " +
                "$totalChunks chunks of ~$chunkSize bytes")
        
        // Create messages
        val startMessage = createStartMessage(
            originalSize,
            compressedSize, 
            totalChunks,
            checksumBytes,
            mode,
            locationName,
            System.currentTimeMillis()
        )
        
        val chunkMessages = chunks.mapIndexed { index, chunk ->
            createChunkMessage(index, chunk)
        }
        
        val endMessage = createEndMessage(checksumBytes, success = true)
        
        return WeatherBinaryTransfer(
            startMessage = startMessage,
            chunkMessages = chunkMessages,
            endMessage = endMessage,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            originalSize = originalSize,
            compressedSize = compressedSize,
            compressionRatio = compressionRatio,
            checksum = checksum
        )
    }
    
    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(data)
        }
        return output.toByteArray()
    }
    
    /**
     * Generate SHA-256 checksum
     */
    private fun generateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Convert hex string to bytes
     */
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    /**
     * Create binary start message
     */
    private fun createStartMessage(
        originalSize: Int,
        compressedSize: Int,
        totalChunks: Int,
        checksumBytes: ByteArray,
        mode: String,
        location: String,
        timestampMs: Long
    ): ByteArray {
        val modeBytes = mode.toByteArray(Charsets.UTF_8)
        val locationBytes = location.toByteArray(Charsets.UTF_8)
        
        // Calculate payload size: fixed fields + checksum + strings + null terminators
        val payloadSize = 24 + 32 + modeBytes.size + 1 + locationBytes.size + 1
        
        Log.d(TAG, "Creating weather start message - Mode: '${mode}' (${modeBytes.size} bytes), " +
                "Location: '${location}' (${locationBytes.size} bytes), " +
                "Payload size: $payloadSize, Total: ${BINARY_HEADER_SIZE + payloadSize}")
        
        val buffer = ByteBuffer.allocate(BINARY_HEADER_SIZE + payloadSize)
            .order(ByteOrder.BIG_ENDIAN)
        
        // Create payload first
        val payloadBuffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.BIG_ENDIAN)
        
        payloadBuffer.putInt(originalSize)                    // Original size (4 bytes)
        payloadBuffer.putInt(compressedSize)                  // Compressed size (4 bytes)
        payloadBuffer.putInt(totalChunks)                     // Total chunks (4 bytes)  
        payloadBuffer.putInt(0)                               // Reserved (4 bytes)
        payloadBuffer.putLong(timestampMs)                    // Timestamp (8 bytes)
        payloadBuffer.put(checksumBytes)                      // SHA-256 checksum (32 bytes)
        payloadBuffer.put(modeBytes)                          // Mode string
        payloadBuffer.put(0)                                  // Null terminator
        payloadBuffer.put(locationBytes)                      // Location string  
        payloadBuffer.put(0)                                  // Null terminator
        
        val payload = payloadBuffer.array()
        
        // Calculate CRC32 of payload
        val crc = CRC32()
        crc.update(payload)
        
        // Header (16 bytes) - Use BinaryProtocolV2 format with version
        val versionedType = ((BinaryProtocolV2.PROTOCOL_VERSION.toInt() shl 12) or (MSG_WEATHER_START.toInt() and 0x0FFF)).toShort()
        buffer.putShort(versionedType)                 // MessageType with version
        buffer.putShort(0)                             // MessageID  
        buffer.putInt(payloadSize)                     // PayloadSize
        buffer.putInt(crc.value.toInt())               // CRC32 (calculated)
        buffer.putShort(0)                             // Flags
        buffer.putShort(0)                             // Reserved
        
        // Add the payload
        buffer.put(payload)
        
        Log.d(TAG, "Weather start message created successfully, final size: ${buffer.position()}")
        
        return buffer.array()
    }
    
    /**
     * Create binary chunk message
     */
    private fun createChunkMessage(chunkIndex: Int, chunkData: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_HEADER_SIZE + chunkData.size)
            .order(ByteOrder.BIG_ENDIAN)
        
        // Calculate CRC32 of payload
        val crc = CRC32()
        crc.update(chunkData)
        
        // Header (16 bytes) - Use BinaryProtocolV2 format with version
        val versionedType = ((BinaryProtocolV2.PROTOCOL_VERSION.toInt() shl 12) or (MSG_WEATHER_CHUNK.toInt() and 0x0FFF)).toShort()
        buffer.putShort(versionedType)                 // MessageType with version
        buffer.putShort(chunkIndex.toShort())          // MessageID (chunk index)
        buffer.putInt(chunkData.size)                  // PayloadSize
        buffer.putInt(crc.value.toInt())               // CRC32 (calculated)
        buffer.putShort(0)                             // Flags
        buffer.putShort(0)                             // Reserved
        
        // Payload (raw chunk data)
        buffer.put(chunkData)
        
        return buffer.array()
    }
    
    /**
     * Create binary end message  
     */
    private fun createEndMessage(checksumBytes: ByteArray, success: Boolean): ByteArray {
        val payloadSize = 32 + 1 // checksum + success flag
        
        val buffer = ByteBuffer.allocate(BINARY_HEADER_SIZE + payloadSize)
            .order(ByteOrder.BIG_ENDIAN)
        
        // Create payload first
        val payload = ByteArray(payloadSize)
        val payloadBuffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        payloadBuffer.put(checksumBytes)                      // SHA-256 checksum (32 bytes)
        payloadBuffer.put(if (success) 1 else 0)              // Success flag
        
        // Calculate CRC32 of payload
        val crc = CRC32()
        crc.update(payload)
        
        // Header (16 bytes) - Use BinaryProtocolV2 format with version
        val versionedType = ((BinaryProtocolV2.PROTOCOL_VERSION.toInt() shl 12) or (MSG_WEATHER_END.toInt() and 0x0FFF)).toShort()
        buffer.putShort(versionedType)                 // MessageType with version
        buffer.putShort(0)                             // MessageID
        buffer.putInt(payloadSize)                     // PayloadSize
        buffer.putInt(crc.value.toInt())               // CRC32 (calculated)
        buffer.putShort(0)                             // Flags
        buffer.putShort(0)                             // Reserved
        
        // Add the payload
        buffer.put(payload)
        
        return buffer.array()
    }
    
    /**
     * Create hourly weather JSON structure
     */
    private fun createHourlyWeatherJson(
        weatherResponse: WeatherResponse,
        locationName: String
    ): Map<String, Any> {
        // Extract 24 hours of data
        val hours = mutableListOf<Map<String, Any>>()
        
        for (i in 0 until minOf(24, weatherResponse.hourly.time.size)) {
            val hour = mapOf(
                "time" to weatherResponse.hourly.time[i],
                "temp_f" to weatherResponse.hourly.temperature_2m[i].toInt(),
                "temp_c" to ((weatherResponse.hourly.temperature_2m[i] - 32) * 5 / 9).toInt(),
                "condition" to getWeatherCondition(weatherResponse.hourly.weathercode[i]),
                "weather_code" to weatherResponse.hourly.weathercode[i],
                "precipitation" to weatherResponse.hourly.precipitation_probability[i],
                "humidity" to weatherResponse.hourly.relativehumidity_2m[i],
                "wind_speed" to weatherResponse.hourly.windspeed_10m[i].toInt()
            )
            hours.add(hour)
        }
        
        return mapOf(
            "type" to "weatherUpdate",
            "mode" to "hourly",
            "location" to mapOf(
                "name" to locationName,
                "latitude" to weatherResponse.latitude,
                "longitude" to weatherResponse.longitude
            ),
            "timestamp_ms" to System.currentTimeMillis(),
            "hours" to hours
        )
    }
    
    /**
     * Create weekly weather JSON structure
     */
    private fun createWeeklyWeatherJson(
        weatherResponse: WeatherResponse, 
        locationName: String
    ): Map<String, Any> {
        val days = mutableListOf<Map<String, Any>>()
        
        for (i in 0 until minOf(7, weatherResponse.daily.time.size)) {
            val day = mapOf(
                "date" to weatherResponse.daily.time[i],
                "day_name" to getDayName(weatherResponse.daily.time[i]),
                "high_f" to weatherResponse.daily.temperature_2m_max[i].toInt(),
                "low_f" to weatherResponse.daily.temperature_2m_min[i].toInt(), 
                "high_c" to ((weatherResponse.daily.temperature_2m_max[i] - 32) * 5 / 9).toInt(),
                "low_c" to ((weatherResponse.daily.temperature_2m_min[i] - 32) * 5 / 9).toInt(),
                "condition" to getWeatherCondition(weatherResponse.daily.weathercode[i]),
                "weather_code" to weatherResponse.daily.weathercode[i],
                "precipitation" to weatherResponse.daily.precipitation_sum[i].toInt(),
                "humidity" to 65 // Default value as not provided in daily data
            )
            days.add(day)
        }
        
        return mapOf(
            "type" to "weatherUpdate",
            "mode" to "weekly", 
            "location" to mapOf(
                "name" to locationName,
                "latitude" to weatherResponse.latitude,
                "longitude" to weatherResponse.longitude
            ),
            "timestamp_ms" to System.currentTimeMillis(),
            "days" to days
        )
    }
    
    /**
     * Get weather condition string from code
     */
    private fun getWeatherCondition(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2, 3 -> when (code) {
                1 -> "Partly Cloudy" 
                2 -> "Cloudy"
                3 -> "Overcast"
                else -> "Cloudy"
            }
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing Drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing Rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow Grains"
            80, 81, 82 -> "Rain Showers"
            85, 86 -> "Snow Showers" 
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with Hail"
            else -> "Unknown"
        }
    }
    
    /**
     * Get day name from date string
     */
    private fun getDayName(dateString: String): String {
        return try {
            val date = java.time.LocalDate.parse(dateString)
            date.dayOfWeek.name.lowercase().capitalize()
        } catch (e: Exception) {
            "Unknown"
        }
    }
}