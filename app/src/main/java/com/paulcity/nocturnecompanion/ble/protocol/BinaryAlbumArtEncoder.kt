package com.paulcity.nocturnecompanion.ble.protocol

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

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
        isTest: Boolean = false,
        compressedSize: Int? = null
    ): ByteArray {
        // Convert hex checksum to bytes (SHA-256 should be 32 bytes)
        val checksumBytes = BinaryProtocolV2.hexToBytes(checksum)
        
        // Use compressed size if provided, otherwise original size
        val actualImageSize = compressedSize ?: imageData.size
        val actualTotalChunks = (actualImageSize + chunkSize - 1) / chunkSize
        
        // Create payload using BinaryProtocolV2 with compression info
        val payload = BinaryProtocolV2.AlbumArtStartPayload(
            checksum = checksumBytes,
            totalChunks = actualTotalChunks,
            imageSize = imageData.size,  // Original size
            trackId = trackId,
            compressedSize = compressedSize ?: 0,
            isGzipCompressed = compressedSize != null
        )
        
        // Use BinaryProtocolV2 message types (0x03xx range)
        val messageType = if (isTest) BinaryProtocolV2.MSG_TEST_ALBUM_ART_START else BinaryProtocolV2.MSG_ALBUM_ART_START
        
        Log.d(TAG, "Encoding ${if (isTest) "test " else ""}album art start - Type: 0x${messageType.toString(16)}, Size: ${imageData.size}, Chunks: $actualTotalChunks")
        
        // Create message using BinaryProtocolV2
        return BinaryProtocolV2.createMessage(messageType, payload.toByteArray())
    }
    
    /**
     * Encode album art chunk in binary format
     */
    fun encodeAlbumArtChunk(
        chunkData: ByteArray,
        chunkIndex: Int,
        isTest: Boolean = false
    ): ByteArray {
        // Use BinaryProtocolV2 message types (0x03xx range)
        val messageType = if (isTest) BinaryProtocolV2.MSG_TEST_ALBUM_ART_CHUNK else BinaryProtocolV2.MSG_ALBUM_ART_CHUNK
        
        // Like the legacy protocol, put chunk index in header, not payload
        // The payload is just the raw chunk data
        // Pass chunkIndex as the MessageID field in the header
        return BinaryProtocolV2.createMessage(messageType, chunkData, chunkIndex.toShort())
    }
    
    /**
     * Encode album art end message in binary format
     */
    fun encodeAlbumArtEnd(
        checksum: String,
        success: Boolean,
        isTest: Boolean = false
    ): ByteArray {
        // Convert hex checksum to bytes (SHA-256 should be 32 bytes)
        val checksumBytes = BinaryProtocolV2.hexToBytes(checksum)
        
        // Create payload using BinaryProtocolV2
        val payload = BinaryProtocolV2.AlbumArtEndPayload(
            checksum = checksumBytes,
            success = success
        )
        
        // Use BinaryProtocolV2 message types (0x03xx range)
        val messageType = if (isTest) BinaryProtocolV2.MSG_TEST_ALBUM_ART_END else BinaryProtocolV2.MSG_ALBUM_ART_END
        
        Log.d(TAG, "Encoding ${if (isTest) "test " else ""}album art end - Type: 0x${messageType.toString(16)}, Success: $success")
        
        // Create message using BinaryProtocolV2
        return BinaryProtocolV2.createMessage(messageType, payload.toByteArray())
    }
    
    /**
     * Calculate optimal chunk size for binary protocol
     * Binary overhead is much smaller than JSON
     */
    fun calculateOptimalChunkSize(mtu: Int): Int {
        val effectiveMtu = mtu - 3  // BLE overhead
        val binaryOverhead = BinaryProtocolV2.HEADER_SIZE + 4  // Header + some margin
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
     * Compress image data using GZIP
     */
    private fun compressImageData(imageData: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()
        
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzipStream ->
            gzipStream.write(imageData)
        }
        
        val compressedData = outputStream.toByteArray()
        val compressionTime = System.currentTimeMillis() - startTime
        val compressionRatio = imageData.size.toFloat() / compressedData.size
        
        Log.d(TAG, "GZIP compression - Original: ${imageData.size} bytes -> Compressed: ${compressedData.size} bytes, " +
                "Ratio: ${String.format("%.2f", compressionRatio)}x, Time: ${compressionTime}ms")
        
        return compressedData
    }

    /**
     * Encode complete album art transfer to binary messages with GZIP compression
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
        
        // Compress the image data first
        val compressedData = compressImageData(imageData)
        
        // Calculate optimal chunk size for binary protocol
        val chunkSize = calculateOptimalChunkSize(mtu)
        
        // Create start message with original size but compressed data info
        val startMessage = encodeAlbumArtStart(imageData, checksum, trackId, chunkSize, isTest, compressedData.size)
        
        // Create chunks from compressed data
        val chunks = createChunks(compressedData, chunkSize)
        val chunkMessages = chunks.mapIndexed { index, chunkData ->
            encodeAlbumArtChunk(chunkData, index, isTest)
        }
        
        // Create end message
        val endMessage = encodeAlbumArtEnd(checksum, true, isTest)
        
        // Update stats
        val encodingTime = System.currentTimeMillis() - startTime
        val totalBinarySize = startMessage.size + chunkMessages.sumOf { it.size } + endMessage.size
        val compressionRatio = imageData.size.toFloat() / compressedData.size
        
        _encodingStats.value = EncodingStats(
            lastImageSize = imageData.size,
            lastChunkCount = chunks.size,
            lastEncodingTimeMs = encodingTime,
            compressionRatio = compressionRatio
        )
        
        Log.d(TAG, "Binary encoding complete - Original: ${imageData.size} bytes, Compressed: ${compressedData.size} bytes, " +
                "Binary: $totalBinarySize bytes, Compression: ${String.format("%.2f", compressionRatio)}x, Time: ${encodingTime}ms")
        
        return AlbumArtBinaryTransfer(
            startMessage = startMessage,
            chunkMessages = chunkMessages,
            endMessage = endMessage,
            totalChunks = chunks.size,
            chunkSize = chunkSize,
            originalSize = imageData.size,
            binarySize = totalBinarySize,
            compressedSize = compressedData.size
        )
    }
    
    data class AlbumArtBinaryTransfer(
        val startMessage: ByteArray,
        val chunkMessages: List<ByteArray>,
        val endMessage: ByteArray,
        val totalChunks: Int,
        val chunkSize: Int,
        val originalSize: Int,
        val binarySize: Int,
        val compressedSize: Int = originalSize
    ) {
        val compressionRatio: Float
            get() = originalSize.toFloat() / compressedSize
            
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
            if (compressedSize != other.compressedSize) return false

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
            result = 31 * result + compressedSize
            return result
        }
    }
}