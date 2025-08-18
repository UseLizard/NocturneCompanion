package com.paulcity.nocturnecompanion.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Complete binary protocol for all BLE communication
 * Replaces JSON entirely with efficient binary format
 */
object BinaryProtocolV2 {
    
    // Protocol version
    const val PROTOCOL_VERSION: Byte = 2
    
    // Header size
    const val HEADER_SIZE = 16
    
    // System messages (0x00xx)
    const val MSG_CAPABILITIES: Short = 0x0001
    const val MSG_TIME_SYNC: Short = 0x0002
    const val MSG_PROTOCOL_ENABLE: Short = 0x0003
    const val MSG_DEVICE_INFO: Short = 0x0004
    const val MSG_CONNECTION_PARAMS: Short = 0x0005
    const val MSG_GET_CAPABILITIES: Short = 0x0006
    const val MSG_ENABLE_BINARY_INCREMENTAL: Short = 0x0007
    const val MSG_REQUEST_HIGH_PRIORITY_CONNECTION: Short = 0x0008
    const val MSG_OPTIMIZE_CONNECTION_PARAMS: Short = 0x0009
    
    // Command messages - must fit in 12 bits (0x000-0xFFF)
    // Using 0x01xx range for commands
    const val MSG_CMD_PLAY: Short = 0x0101
    const val MSG_CMD_PAUSE: Short = 0x0102
    const val MSG_CMD_NEXT: Short = 0x0103
    const val MSG_CMD_PREVIOUS: Short = 0x0104
    const val MSG_CMD_SEEK_TO: Short = 0x0105
    const val MSG_CMD_SET_VOLUME: Short = 0x0106
    const val MSG_CMD_REQUEST_STATE: Short = 0x0107
    const val MSG_CMD_REQUEST_TIMESTAMP: Short = 0x0108
    const val MSG_CMD_ALBUM_ART_QUERY: Short = 0x0109
    const val MSG_CMD_TEST_ALBUM_ART: Short = 0x010A
    
    // State messages - must fit in 12 bits (0x000-0xFFF)
    // Using 0x02xx range for states  
    const val MSG_STATE_FULL: Short = 0x0201
    const val MSG_STATE_ARTIST: Short = 0x0202
    const val MSG_STATE_ALBUM: Short = 0x0203
    const val MSG_STATE_TRACK: Short = 0x0204
    const val MSG_STATE_POSITION: Short = 0x0205
    const val MSG_STATE_DURATION: Short = 0x0206
    const val MSG_STATE_PLAY_STATUS: Short = 0x0207
    const val MSG_STATE_VOLUME: Short = 0x0208
    const val MSG_STATE_ARTIST_ALBUM: Short = 0x0209
    
    // Album art messages - must fit in 12 bits (0x000-0xFFF)
    // Using 0x03xx range for album art
    const val MSG_ALBUM_ART_START: Short = 0x0301
    const val MSG_ALBUM_ART_CHUNK: Short = 0x0302
    const val MSG_ALBUM_ART_END: Short = 0x0303
    const val MSG_ALBUM_ART_NOT_AVAILABLE: Short = 0x0304
    
    // Test album art messages
    const val MSG_TEST_ALBUM_ART_START: Short = 0x0310
    const val MSG_TEST_ALBUM_ART_CHUNK: Short = 0x0311
    const val MSG_TEST_ALBUM_ART_END: Short = 0x0312
    
    // Error messages - must fit in 12 bits (0x000-0xFFF)
    // Using 0x04xx range for errors
    const val MSG_ERROR: Short = 0x0401
    const val MSG_ERROR_COMMAND_FAILED: Short = 0x0402
    const val MSG_ERROR_INVALID_MESSAGE: Short = 0x0403
    
    // Gradient messages - must fit in 12 bits (0x000-0xFFF)
    // Using 0x06xx range for gradients
    const val MSG_GRADIENT_COLORS: Short = 0x0601
    
    /**
     * Enhanced binary message header (16 bytes):
     * [0-1]   Protocol version + Message Type (version in high 4 bits, type in lower 12 bits)
     * [2-3]   Message ID (for request/response correlation)
     * [4-7]   Payload size (uint32)
     * [8-11]  CRC32 (uint32) - of payload only
     * [12-13] Flags (uint16) - for future use
     * [14-15] Reserved (uint16)
     */
    data class MessageHeader(
        val messageType: Short,
        val messageId: Short = 0,
        val payloadSize: Int = 0,
        val crc32: Int = 0,
        val flags: Short = 0,
        val reserved: Short = 0
    ) {
        fun toByteArray(): ByteArray {
            // Combine protocol version with message type
            val versionedType = ((PROTOCOL_VERSION.toInt() shl 12) or (messageType.toInt() and 0x0FFF)).toShort()
            
            return ByteBuffer.allocate(HEADER_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(versionedType)
                putShort(messageId)
                putInt(payloadSize)
                putInt(crc32)
                putShort(flags)
                putShort(reserved)
            }.array()
        }
        
        companion object {
            fun fromByteArray(bytes: ByteArray): MessageHeader {
                require(bytes.size >= HEADER_SIZE) { "Invalid header size" }
                
                val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                
                val versionedType = buffer.getShort()
                val version = (versionedType.toInt() shr 12) and 0x0F
                val messageType = (versionedType.toInt() and 0x0FFF).toShort()
                
                // Verify protocol version
                if (version != PROTOCOL_VERSION.toInt()) {
                    throw IllegalArgumentException("Unsupported protocol version: $version")
                }
                
                return MessageHeader(
                    messageType = messageType,
                    messageId = buffer.getShort(),
                    payloadSize = buffer.getInt(),
                    crc32 = buffer.getInt(),
                    flags = buffer.getShort(),
                    reserved = buffer.getShort()
                )
            }
        }
    }
    
    /**
     * Create a complete binary message
     */
    fun createMessage(messageType: Short, payload: ByteArray = byteArrayOf(), messageId: Short = 0): ByteArray {
        val crc = CRC32()
        crc.update(payload)
        
        val header = MessageHeader(
            messageType = messageType,
            messageId = messageId,
            payloadSize = payload.size,
            crc32 = crc.value.toInt()
        )
        
        return header.toByteArray() + payload
    }
    
    /**
     * Parse a binary message
     */
    fun parseMessage(data: ByteArray): Triple<MessageHeader, ByteArray, Boolean>? {
        if (data.size < HEADER_SIZE) return null
        
        return try {
            val header = MessageHeader.fromByteArray(data)
            
            if (data.size < HEADER_SIZE + header.payloadSize) {
                // Incomplete message
                return Triple(header, byteArrayOf(), false)
            }
            
            val payload = data.sliceArray(HEADER_SIZE until HEADER_SIZE + header.payloadSize)
            
            // Verify CRC
            val crc = CRC32()
            crc.update(payload)
            if (crc.value.toInt() != header.crc32) {
                return null // CRC mismatch
            }
            
            Triple(header, payload, true)
        } catch (e: Exception) {
            null
        }
    }
    
    // Payload creators for different message types
    
    /**
     * Command with optional value (play, pause, seek, volume, etc.)
     */
    fun createCommandPayload(valueMs: Long? = null, valuePercent: Int? = null): ByteArray {
        val buffer = mutableListOf<Byte>()
        
        // Flags byte: bit 0 = has valueMs, bit 1 = has valuePercent
        var flags: Byte = 0
        if (valueMs != null) flags = (flags.toInt() or 0x01).toByte()
        if (valuePercent != null) flags = (flags.toInt() or 0x02).toByte()
        buffer.add(flags)
        
        // Add values if present
        if (valueMs != null) {
            buffer.addAll(ByteBuffer.allocate(8).apply {
                order(ByteOrder.BIG_ENDIAN)
                putLong(valueMs)
            }.array().toList())
        }
        
        if (valuePercent != null) {
            buffer.add(valuePercent.toByte())
        }
        
        return buffer.toByteArray()
    }
    
    fun parseCommandPayload(data: ByteArray): Pair<Long?, Int?> {
        if (data.isEmpty()) return Pair(null, null)
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val flags = buffer.get()
        var valueMs: Long? = null
        var valuePercent: Int? = null
        
        if ((flags.toInt() and 0x01) != 0) {
            valueMs = buffer.getLong()
        }
        
        if ((flags.toInt() and 0x02) != 0) {
            valuePercent = buffer.get().toInt() and 0xFF
        }
        
        return Pair(valueMs, valuePercent)
    }
    
    /**
     * Full state update payload
     */
    fun createFullStatePayload(
        artist: String,
        album: String,
        track: String,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean,
        volumePercent: Int
    ): ByteArray {
        val artistBytes = artist.toByteArray(Charsets.UTF_8)
        val albumBytes = album.toByteArray(Charsets.UTF_8)
        val trackBytes = track.toByteArray(Charsets.UTF_8)
        
        val totalSize = 1 + 8 + 8 + 1 + 2 + artistBytes.size + 2 + albumBytes.size + 2 + trackBytes.size
        
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            
            // State flags (1 byte)
            val flags = if (isPlaying) 0x01 else 0x00
            put(flags.toByte())
            
            // Timing (16 bytes)
            putLong(durationMs)
            putLong(positionMs)
            
            // Volume (1 byte)
            put(volumePercent.toByte())
            
            // Strings with length prefixes
            putShort(artistBytes.size.toShort())
            put(artistBytes)
            putShort(albumBytes.size.toShort())
            put(albumBytes)
            putShort(trackBytes.size.toShort())
            put(trackBytes)
        }.array()
    }
    
    fun parseFullStatePayload(data: ByteArray): StateData? {
        if (data.size < 20) return null // Minimum size check
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val flags = buffer.get()
        val isPlaying = (flags.toInt() and 0x01) != 0
        
        val durationMs = buffer.getLong()
        val positionMs = buffer.getLong()
        val volumePercent = buffer.get().toInt() and 0xFF
        
        // Read strings
        val artistLen = buffer.getShort().toInt()
        val artistBytes = ByteArray(artistLen)
        buffer.get(artistBytes)
        val artist = String(artistBytes, Charsets.UTF_8)
        
        val albumLen = buffer.getShort().toInt()
        val albumBytes = ByteArray(albumLen)
        buffer.get(albumBytes)
        val album = String(albumBytes, Charsets.UTF_8)
        
        val trackLen = buffer.getShort().toInt()
        val trackBytes = ByteArray(trackLen)
        buffer.get(trackBytes)
        val track = String(trackBytes, Charsets.UTF_8)
        
        return StateData(artist, album, track, durationMs, positionMs, isPlaying, volumePercent)
    }
    
    /**
     * Time sync payload
     */
    fun createTimeSyncPayload(timestampMs: Long, timezone: String): ByteArray {
        val tzBytes = timezone.toByteArray(Charsets.UTF_8)
        
        return ByteBuffer.allocate(8 + 2 + tzBytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(timestampMs)
            putShort(tzBytes.size.toShort())
            put(tzBytes)
        }.array()
    }
    
    fun parseTimeSyncPayload(data: ByteArray): Pair<Long, String>? {
        if (data.size < 10) return null
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val timestampMs = buffer.getLong()
        val tzLen = buffer.getShort().toInt()
        val tzBytes = ByteArray(tzLen)
        buffer.get(tzBytes)
        val timezone = String(tzBytes, Charsets.UTF_8)
        
        return Pair(timestampMs, timezone)
    }
    
    /**
     * Capabilities payload
     */
    fun createCapabilitiesPayload(
        version: String,
        features: List<String>,
        mtu: Int,
        debugEnabled: Boolean
    ): ByteArray {
        val versionBytes = version.toByteArray(Charsets.UTF_8)
        val featuresStr = features.joinToString(",")
        val featuresBytes = featuresStr.toByteArray(Charsets.UTF_8)
        
        return ByteBuffer.allocate(1 + 2 + 1 + versionBytes.size + 2 + featuresBytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            
            // Flags
            val flags = if (debugEnabled) 0x01 else 0x00
            put(flags.toByte())
            
            // MTU
            putShort(mtu.toShort())
            
            // Version string
            put(versionBytes.size.toByte())
            put(versionBytes)
            
            // Features string
            putShort(featuresBytes.size.toShort())
            put(featuresBytes)
        }.array()
    }
    
    fun parseCapabilitiesPayload(data: ByteArray): CapabilitiesData? {
        if (data.size < 4) return null
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val flags = buffer.get()
        val debugEnabled = (flags.toInt() and 0x01) != 0
        
        val mtu = buffer.getShort().toInt()
        
        val versionLen = buffer.get().toInt() and 0xFF
        val versionBytes = ByteArray(versionLen)
        buffer.get(versionBytes)
        val version = String(versionBytes, Charsets.UTF_8)
        
        val featuresLen = buffer.getShort().toInt()
        val featuresBytes = ByteArray(featuresLen)
        buffer.get(featuresBytes)
        val features = String(featuresBytes, Charsets.UTF_8).split(",").filter { it.isNotEmpty() }
        
        return CapabilitiesData(version, features, mtu, debugEnabled)
    }
    
    /**
     * Album art query payload (MD5 hash)
     */
    fun createAlbumArtQueryPayload(hash: String): ByteArray {
        return hash.toByteArray(Charsets.UTF_8)
    }
    
    fun parseAlbumArtQueryPayload(data: ByteArray): String {
        return String(data, Charsets.UTF_8)
    }
    
    /**
     * Error payload
     */
    fun createErrorPayload(code: String, message: String): ByteArray {
        val codeBytes = code.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        
        return ByteBuffer.allocate(1 + codeBytes.size + 2 + msgBytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(codeBytes.size.toByte())
            put(codeBytes)
            putShort(msgBytes.size.toShort())
            put(msgBytes)
        }.array()
    }
    
    fun parseErrorPayload(data: ByteArray): Pair<String, String>? {
        if (data.size < 3) return null
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val codeLen = buffer.get().toInt() and 0xFF
        val codeBytes = ByteArray(codeLen)
        buffer.get(codeBytes)
        val code = String(codeBytes, Charsets.UTF_8)
        
        val msgLen = buffer.getShort().toInt()
        val msgBytes = ByteArray(msgLen)
        buffer.get(msgBytes)
        val message = String(msgBytes, Charsets.UTF_8)
        
        return Pair(code, message)
    }
    
    // Incremental state updates (single field)
    fun createStringPayload(value: String): ByteArray {
        return value.toByteArray(Charsets.UTF_8)
    }
    
    fun createLongPayload(value: Long): ByteArray {
        return ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(value)
        }.array()
    }
    
    fun createBooleanPayload(value: Boolean): ByteArray {
        return byteArrayOf(if (value) 1 else 0)
    }
    
    fun createBytePayload(value: Byte): ByteArray {
        return byteArrayOf(value)
    }
    
    /**
     * Combined artist+album payload
     * Format: [artist_length:2][artist:N][album_length:2][album:M]
     */
    fun createArtistAlbumPayload(artist: String, album: String): ByteArray {
        val artistBytes = artist.toByteArray(Charsets.UTF_8)
        val albumBytes = album.toByteArray(Charsets.UTF_8)
        
        return ByteBuffer.allocate(4 + artistBytes.size + albumBytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(artistBytes.size.toShort())
            put(artistBytes)
            putShort(albumBytes.size.toShort())
            put(albumBytes)
        }.array()
    }
    
    fun parseArtistAlbumPayload(data: ByteArray): Pair<String, String>? {
        if (data.size < 4) return null
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        // Read artist
        val artistLength = buffer.getShort().toInt()
        val artistBytes = ByteArray(artistLength)
        buffer.get(artistBytes)
        val artist = String(artistBytes, Charsets.UTF_8)
        
        // Read album
        val albumLength = buffer.getShort().toInt()
        val albumBytes = ByteArray(albumLength)
        buffer.get(albumBytes)
        val album = String(albumBytes, Charsets.UTF_8)
        
        return Pair(artist, album)
    }
    
    // Album art payload structures (using existing BinaryProtocol)
    
    /**
     * Album art start message payload structure:
     * [0-31]  SHA256 checksum (32 bytes)
     * [32-35] Total chunks (uint32)
     * [36-39] Image size (uint32)
     * [40+]   Track ID (UTF-8 string)
     */
    data class AlbumArtStartPayload(
        val checksum: ByteArray,  // SHA-256 = 32 bytes
        val totalChunks: Int,
        val imageSize: Int,
        val trackId: String
    ) {
        init {
            require(checksum.size == 32) { "Checksum must be 32 bytes (SHA-256)" }
        }
        
        fun toByteArray(): ByteArray {
            val trackIdBytes = trackId.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(40 + trackIdBytes.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(checksum)
                putInt(totalChunks)
                putInt(imageSize)
                put(trackIdBytes)
            }
            return buffer.array()
        }
        
        companion object {
            fun fromByteArray(bytes: ByteArray): AlbumArtStartPayload {
                require(bytes.size >= 40) { "Invalid payload size" }
                
                val buffer = ByteBuffer.wrap(bytes).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                
                val checksum = ByteArray(32)
                buffer.get(checksum)
                
                val totalChunks = buffer.getInt()
                val imageSize = buffer.getInt()
                
                val trackIdBytes = ByteArray(bytes.size - 40)
                buffer.get(trackIdBytes)
                val trackId = String(trackIdBytes, Charsets.UTF_8)
                
                return AlbumArtStartPayload(checksum, totalChunks, imageSize, trackId)
            }
        }
    }
    
    /**
     * Album art chunk payload - raw image data with chunk index in header
     */
    data class AlbumArtChunkPayload(
        val chunkIndex: Int,
        val data: ByteArray
    )
    
    /**
     * Album art end message payload structure:
     * [0-31]  SHA256 checksum (32 bytes)
     * [32]    Success flag (1 byte)
     */
    data class AlbumArtEndPayload(
        val checksum: ByteArray,  // SHA-256 = 32 bytes
        val success: Boolean
    ) {
        init {
            require(checksum.size == 32) { "Checksum must be 32 bytes (SHA-256)" }
        }
        
        fun toByteArray(): ByteArray {
            return ByteBuffer.allocate(33).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(checksum)
                put(if (success) 1.toByte() else 0.toByte())
            }.array()
        }
        
        companion object {
            fun fromByteArray(bytes: ByteArray): AlbumArtEndPayload {
                require(bytes.size >= 33) { "Invalid payload size" }
                
                val buffer = ByteBuffer.wrap(bytes).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                
                val checksum = ByteArray(32)
                buffer.get(checksum)
                val success = buffer.get() != 0.toByte()
                
                return AlbumArtEndPayload(checksum, success)
            }
        }
    }
    
    // Data classes for structured data
    data class StateData(
        val artist: String,
        val album: String,
        val track: String,
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
        val volumePercent: Int
    )
    
    data class CapabilitiesData(
        val version: String,
        val features: List<String>,
        val mtu: Int,
        val debugEnabled: Boolean
    )
    
    /**
     * Gradient colors payload
     * Format: [count:1][r1:1][g1:1][b1:1][r2:1][g2:1][b2:1]...
     */
    fun createGradientColorsPayload(colors: List<Int>): ByteArray {
        val colorCount = colors.size.coerceAtMost(255) // Limit to 255 colors
        
        return ByteBuffer.allocate(1 + colorCount * 3).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(colorCount.toByte())
            
            colors.take(colorCount).forEach { color ->
                put(((color shr 16) and 0xFF).toByte()) // Red
                put(((color shr 8) and 0xFF).toByte())  // Green
                put((color and 0xFF).toByte())          // Blue
            }
        }.array()
    }
    
    fun parseGradientColorsPayload(data: ByteArray): List<Int>? {
        if (data.isEmpty()) return null
        
        val buffer = ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val colorCount = buffer.get().toInt() and 0xFF
        if (data.size < 1 + colorCount * 3) return null
        
        val colors = mutableListOf<Int>()
        repeat(colorCount) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            colors.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        
        return colors
    }
    
    /**
     * Get human-readable string for message type
     */
    fun getMessageTypeString(msgType: Short): String {
        return when (msgType) {
            // System messages
            MSG_CAPABILITIES -> "Capabilities"
            MSG_TIME_SYNC -> "TimeSync"
            MSG_PROTOCOL_ENABLE -> "ProtocolEnable"
            MSG_GET_CAPABILITIES -> "GetCapabilities"
            MSG_ENABLE_BINARY_INCREMENTAL -> "EnableBinaryIncremental"
            MSG_REQUEST_HIGH_PRIORITY_CONNECTION -> "RequestHighPriorityConnection"
            MSG_OPTIMIZE_CONNECTION_PARAMS -> "OptimizeConnectionParams"
            
            // Command messages
            MSG_CMD_PLAY -> "Play"
            MSG_CMD_PAUSE -> "Pause"
            MSG_CMD_NEXT -> "Next"
            MSG_CMD_PREVIOUS -> "Previous"
            MSG_CMD_SEEK_TO -> "SeekTo"
            MSG_CMD_SET_VOLUME -> "SetVolume"
            MSG_CMD_REQUEST_STATE -> "RequestState"
            MSG_CMD_ALBUM_ART_QUERY -> "AlbumArtQuery"
            
            // State messages
            MSG_STATE_FULL -> "FullState"
            MSG_STATE_ARTIST -> "Artist"
            MSG_STATE_ALBUM -> "Album"
            MSG_STATE_TRACK -> "Track"
            MSG_STATE_POSITION -> "Position"
            MSG_STATE_DURATION -> "Duration"
            MSG_STATE_PLAY_STATUS -> "PlayStatus"
            MSG_STATE_VOLUME -> "Volume"
            
            // Album art messages
            MSG_ALBUM_ART_START -> "AlbumArtStart"
            MSG_ALBUM_ART_CHUNK -> "AlbumArtChunk"
            MSG_ALBUM_ART_END -> "AlbumArtEnd"
            
            // Error messages
            MSG_ERROR -> "Error"
            
            // Gradient messages
            MSG_GRADIENT_COLORS -> "GradientColors"
            
            else -> "Unknown(0x${msgType.toString(16)})"
        }
    }
    
    /**
     * Convert SHA-256 hex string to byte array
     */
    fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    /**
     * Convert byte array to hex string
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}