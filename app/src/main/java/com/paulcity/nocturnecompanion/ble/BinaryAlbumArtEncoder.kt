package com.paulcity.nocturnecompanion.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Encoder for binary album art protocol
 * Handles chunking and encoding of album art data for efficient BLE transfer
 */
class BinaryAlbumArtEncoder {
    companion object {
        private const val TAG = "BinaryAlbumArtEncoder"
    }
    
    private val _encodingStats = MutableStateFlow(EncodingStats())
    val encodingStats: StateFlow<EncodingStats> = _encodingStats
    
    data class EncodingStats(
        val lastImageSize: Int = 0,
        val lastChunkCount: Int = 0,
        val lastEncodingTimeMs: Long = 0,
        val compressionRatio: Float = 0f
    )
    
    /**
     * Encode album art start message in binary format
     */
    fun encodeAlbumArtStart(
        imageData: ByteArray,
        checksum: String,
        trackId: String,
        chunkSize: Int,
        isTest: Boolean = false
    ): ByteArray {
        val totalChunks = (imageData.size + chunkSize - 1) / chunkSize
        
        // Convert hex checksum to bytes
        val checksumBytes = BinaryProtocol.hexToBytes(checksum)
        
        val payload = BinaryProtocol.AlbumArtStartPayload(
            checksum = checksumBytes,
            totalChunks = totalChunks,
            imageSize = imageData.size,
            trackId = trackId
        )
        
        val header = BinaryProtocol.BinaryHeader(
            messageType = if (isTest) BinaryProtocol.MSG_TEST_ALBUM_ART_START else BinaryProtocol.MSG_ALBUM_ART_START
        )
        
        Log.d(TAG, "Encoding ${if (isTest) "test " else ""}album art start - Size: ${imageData.size}, Chunks: $totalChunks, Binary payload: ${payload.toByteArray().size} bytes")
        
        return BinaryProtocol.createBinaryMessage(header, payload.toByteArray())
    }
    
    /**
     * Encode album art chunk in binary format
     */
    fun encodeAlbumArtChunk(
        chunkData: ByteArray,
        chunkIndex: Int,
        isTest: Boolean = false
    ): ByteArray {
        val header = BinaryProtocol.BinaryHeader(
            messageType = if (isTest) BinaryProtocol.MSG_TEST_ALBUM_ART_CHUNK else BinaryProtocol.MSG_ALBUM_ART_CHUNK,
            chunkIndex = chunkIndex.toShort()
        )
        
        // For chunks, the payload is just the raw image data
        return BinaryProtocol.createBinaryMessage(header, chunkData)
    }
    
    /**
     * Encode album art end message in binary format
     */
    fun encodeAlbumArtEnd(
        checksum: String,
        success: Boolean,
        isTest: Boolean = false
    ): ByteArray {
        val checksumBytes = BinaryProtocol.hexToBytes(checksum)
        
        val payload = BinaryProtocol.AlbumArtEndPayload(
            checksum = checksumBytes,
            success = success
        )
        
        val header = BinaryProtocol.BinaryHeader(
            messageType = if (isTest) BinaryProtocol.MSG_TEST_ALBUM_ART_END else BinaryProtocol.MSG_ALBUM_ART_END
        )
        
        return BinaryProtocol.createBinaryMessage(header, payload.toByteArray())
    }
    
    /**
     * Calculate optimal chunk size for binary protocol
     * Binary overhead is much smaller than JSON
     */
    fun calculateOptimalChunkSize(mtu: Int): Int {
        val effectiveMtu = mtu - 3  // BLE overhead
        val binaryOverhead = BinaryProtocol.HEADER_SIZE + 4  // Header + some margin
        return maxOf(50, effectiveMtu - binaryOverhead)
    }
    
    /**
     * Create chunks from image data
     */
    fun createChunks(imageData: ByteArray, chunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < imageData.size) {
            val remainingBytes = imageData.size - offset
            val currentChunkSize = minOf(chunkSize, remainingBytes)
            
            val chunk = imageData.sliceArray(offset until offset + currentChunkSize)
            chunks.add(chunk)
            
            offset += currentChunkSize
        }
        
        Log.d(TAG, "Created ${chunks.size} chunks of max size $chunkSize from ${imageData.size} bytes")
        
        return chunks
    }
    
    /**
     * Encode complete album art transfer to binary messages
     * Returns list of all messages ready to send
     */
    fun encodeAlbumArtTransfer(
        imageData: ByteArray,
        checksum: String,
        trackId: String,
        mtu: Int,
        isTest: Boolean = false
    ): AlbumArtBinaryTransfer {
        val startTime = System.currentTimeMillis()
        
        // Calculate optimal chunk size for binary protocol
        val chunkSize = calculateOptimalChunkSize(mtu)
        
        // Create start message
        val startMessage = encodeAlbumArtStart(imageData, checksum, trackId, chunkSize, isTest)
        
        // Create chunks
        val chunks = createChunks(imageData, chunkSize)
        val chunkMessages = chunks.mapIndexed { index, chunkData ->
            encodeAlbumArtChunk(chunkData, index, isTest)
        }
        
        // Create end message
        val endMessage = encodeAlbumArtEnd(checksum, true, isTest)
        
        // Update stats
        val encodingTime = System.currentTimeMillis() - startTime
        val totalBinarySize = startMessage.size + chunkMessages.sumOf { it.size } + endMessage.size
        val compressionRatio = imageData.size.toFloat() / totalBinarySize
        
        _encodingStats.value = EncodingStats(
            lastImageSize = imageData.size,
            lastChunkCount = chunks.size,
            lastEncodingTimeMs = encodingTime,
            compressionRatio = compressionRatio
        )
        
        Log.d(TAG, "Binary encoding complete - Original: ${imageData.size} bytes, Binary: $totalBinarySize bytes, " +
                "Compression: ${String.format("%.2f", compressionRatio)}x, Time: ${encodingTime}ms")
        
        return AlbumArtBinaryTransfer(
            startMessage = startMessage,
            chunkMessages = chunkMessages,
            endMessage = endMessage,
            totalChunks = chunks.size,
            chunkSize = chunkSize,
            originalSize = imageData.size,
            binarySize = totalBinarySize
        )
    }
    
    data class AlbumArtBinaryTransfer(
        val startMessage: ByteArray,
        val chunkMessages: List<ByteArray>,
        val endMessage: ByteArray,
        val totalChunks: Int,
        val chunkSize: Int,
        val originalSize: Int,
        val binarySize: Int
    ) {
        val compressionRatio: Float
            get() = originalSize.toFloat() / binarySize
            
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as AlbumArtBinaryTransfer

            if (!startMessage.contentEquals(other.startMessage)) return false
            if (chunkMessages.size != other.chunkMessages.size) return false
            if (!endMessage.contentEquals(other.endMessage)) return false
            if (totalChunks != other.totalChunks) return false
            if (chunkSize != other.chunkSize) return false
            if (originalSize != other.originalSize) return false
            if (binarySize != other.binarySize) return false

            return true
        }

        override fun hashCode(): Int {
            var result = startMessage.contentHashCode()
            result = 31 * result + chunkMessages.size
            result = 31 * result + endMessage.contentHashCode()
            result = 31 * result + totalChunks
            result = 31 * result + chunkSize
            result = 31 * result + originalSize
            result = 31 * result + binarySize
            return result
        }
    }
}