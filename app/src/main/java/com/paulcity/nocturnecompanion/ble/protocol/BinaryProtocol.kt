package com.paulcity.nocturnecompanion.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Binary protocol for efficient BLE album art transfer
 * Replaces JSON/Base64 encoding with compact binary format
 */
object BinaryProtocol {
    
    // Protocol version for compatibility
    const val PROTOCOL_VERSION: Byte = 1
    
    // Message types (2 bytes)
    const val MSG_ALBUM_ART_START: Short = 0x0100
    const val MSG_ALBUM_ART_CHUNK: Short = 0x0101
    const val MSG_ALBUM_ART_END: Short = 0x0102
    const val MSG_PROTOCOL_INFO: Short = 0x0001
    
    // Test message types
    const val MSG_TEST_ALBUM_ART_START: Short = 0x0200
    const val MSG_TEST_ALBUM_ART_CHUNK: Short = 0x0201
    const val MSG_TEST_ALBUM_ART_END: Short = 0x0202
    
    // Incremental state update message types
    const val MSG_STATE_ARTIST: Short = 0x0300  // Deprecated - use MSG_STATE_ARTIST_ALBUM
    const val MSG_STATE_ALBUM: Short = 0x0301   // Deprecated - use MSG_STATE_ARTIST_ALBUM
    const val MSG_STATE_TRACK: Short = 0x0302
    const val MSG_STATE_POSITION: Short = 0x0303
    const val MSG_STATE_DURATION: Short = 0x0304
    const val MSG_STATE_PLAY_STATE: Short = 0x0305
    const val MSG_STATE_VOLUME: Short = 0x0306
    const val MSG_STATE_FULL: Short = 0x0307
    const val MSG_STATE_ARTIST_ALBUM: Short = 0x0308  // Combined artist+album update
    
    // Binary header size
    const val HEADER_SIZE = 16  // bytes
    
    /**
     * Binary message header structure (16 bytes):
     * [0-1]   Message Type (uint16)
     * [2-3]   Chunk Index (uint16) - 0 for non-chunk messages
     * [4-7]   Total Size (uint32) - total payload size for start, chunk size for chunks
     * [8-11]  CRC32 (uint32) - CRC of the data portion only
     * [12-15] Reserved (uint32) - for future use
     */
    data class BinaryHeader(
        val messageType: Short,
        val chunkIndex: Short = 0,
        val totalSize: Int = 0,
        val crc32: Int = 0,
        val reserved: Int = 0
    ) {
        fun toByteArray(): ByteArray {
            return ByteBuffer.allocate(HEADER_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(messageType)
                putShort(chunkIndex)
                putInt(totalSize)
                putInt(crc32)
                putInt(reserved)
            }.array()
        }
        
        companion object {
            fun fromByteArray(bytes: ByteArray): BinaryHeader {
                require(bytes.size >= HEADER_SIZE) { "Invalid header size" }
                
                val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                
                return BinaryHeader(
                    messageType = buffer.getShort(),
                    chunkIndex = buffer.getShort(),
                    totalSize = buffer.getInt(),
                    crc32 = buffer.getInt(),
                    reserved = buffer.getInt()
                )
            }
        }
    }
    
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
    
    /**
     * Create a complete binary message with header and payload
     */
    fun createBinaryMessage(header: BinaryHeader, payload: ByteArray): ByteArray {
        // Calculate CRC32 of payload
        val crc = CRC32()
        crc.update(payload)
        
        // Update header with CRC
        val finalHeader = header.copy(
            totalSize = payload.size,
            crc32 = crc.value.toInt()
        )
        
        // Combine header and payload
        return finalHeader.toByteArray() + payload
    }
    
    /**
     * Parse a binary message into header and payload
     */
    fun parseBinaryMessage(data: ByteArray): Pair<BinaryHeader, ByteArray>? {
        if (data.size < HEADER_SIZE) return null
        
        val header = BinaryHeader.fromByteArray(data)
        val payload = data.sliceArray(HEADER_SIZE until data.size)
        
        // Verify CRC
        val crc = CRC32()
        crc.update(payload)
        if (crc.value.toInt() != header.crc32) {
            return null // CRC mismatch
        }
        
        return Pair(header, payload)
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
    
    /**
     * Incremental state update payloads
     */
    
    // String payload for artist/album/track updates
    fun createStringPayload(value: String): ByteArray {
        return value.toByteArray(Charsets.UTF_8)
    }
    
    // Long payload for position/duration updates (8 bytes)
    fun createLongPayload(value: Long): ByteArray {
        return ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(value)
        }.array()
    }
    
    // Boolean payload for play state (1 byte)
    fun createBooleanPayload(value: Boolean): ByteArray {
        return byteArrayOf(if (value) 1 else 0)
    }
    
    // Byte payload for volume percentage (1 byte)
    fun createBytePayload(value: Byte): ByteArray {
        return byteArrayOf(value)
    }
    
    // Parse payloads
    fun parseStringPayload(data: ByteArray): String {
        return String(data, Charsets.UTF_8)
    }
    
    fun parseLongPayload(data: ByteArray): Long {
        require(data.size >= 8) { "Invalid long payload size" }
        return ByteBuffer.wrap(data).apply {
            order(ByteOrder.BIG_ENDIAN)
        }.getLong()
    }
    
    fun parseBooleanPayload(data: ByteArray): Boolean {
        require(data.isNotEmpty()) { "Invalid boolean payload size" }
        return data[0] != 0.toByte()
    }
    
    fun parseBytePayload(data: ByteArray): Byte {
        require(data.isNotEmpty()) { "Invalid byte payload size" }
        return data[0]
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
    
    fun parseArtistAlbumPayload(data: ByteArray): Pair<String, String> {
        require(data.size >= 4) { "Invalid artist+album payload size" }
        
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
}