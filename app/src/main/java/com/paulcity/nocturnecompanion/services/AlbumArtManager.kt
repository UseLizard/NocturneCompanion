package com.paulcity.nocturnecompanion.services

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AlbumArtManager(private val context: Context) {
    
    private val cacheDir = File(context.cacheDir, "album_art")
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    companion object {
        private const val TAG = "AlbumArtManager"
        private const val MAX_IMAGE_SIZE = 512 // Max width/height in pixels
        private const val JPEG_QUALITY = 85
        private const val CHUNK_SIZE = 8192 // 8KB chunks for Bluetooth transmission
        private const val CHUNK_DELAY_MS = 100L // Delay between chunks to prevent buffer overflow
    }
    
    /**
     * Extract album art from MediaMetadata and prepare for Bluetooth transmission
     */
    suspend fun processAlbumArt(metadata: MediaMetadata?, bluetoothManager: BluetoothServerManager? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                
                if (artwork == null) {
                    Log.d(TAG, "No artwork found in metadata")
                    return@withContext null
                }
                
                Log.d(TAG, "Found artwork: ${artwork.width}x${artwork.height}")
                
                // Resize and compress the image
                val processedBitmap = resizeAndCompress(artwork)
                val imageBytes = bitmapToJpegBytes(processedBitmap)
                
                // Calculate hash for deduplication
                val hash = calculateHash(imageBytes)
                Log.d(TAG, "Image hash: $hash, size: ${imageBytes.size} bytes")
                
                // Save to local cache
                val cacheFile = File(cacheDir, "$hash.jpg")
                if (!cacheFile.exists()) {
                    saveBitmapToFile(processedBitmap, cacheFile)
                }
                
                // Send via Bluetooth if manager is provided
                bluetoothManager?.let { btManager ->
                    sendAlbumArtViaBluetooth(btManager, hash, imageBytes)
                }
                
                return@withContext hash
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing album art", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Manually upload album art with a button press
     */
    suspend fun uploadCurrentAlbumArt(metadata: MediaMetadata?, bluetoothManager: BluetoothServerManager?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Manual upload requested")
                
                if (metadata == null) {
                    Log.w(TAG, "No metadata available for manual upload")
                    return@withContext false
                }
                
                if (bluetoothManager == null) {
                    Log.w(TAG, "No Bluetooth manager available")
                    return@withContext false
                }
                
                val result = processAlbumArt(metadata, bluetoothManager)
                val success = result != null
                Log.d(TAG, "Manual upload result: $success")
                return@withContext success
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in manual upload: ${e.javaClass.simpleName}: ${e.message}", e)
                false
            }
        }
    }
    
    private fun resizeAndCompress(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        
        // Calculate new dimensions maintaining aspect ratio
        val maxDimension = MAX_IMAGE_SIZE.toFloat()
        val scale = if (width > height) {
            maxDimension / width
        } else {
            maxDimension / height
        }
        
        return if (scale < 1.0f) {
            val newWidth = (width * scale).toInt()
            val newHeight = (height * scale).toInt()
            Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
        } else {
            original
        }
    }
    
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        return outputStream.toByteArray()
    }
    
    private fun calculateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }
    
    private suspend fun sendAlbumArtViaBluetooth(bluetoothManager: BluetoothServerManager, hash: String, imageBytes: ByteArray) {
        try {
            Log.d(TAG, "Sending album art via Bluetooth: hash=$hash, size=${imageBytes.size} bytes")
            
            // Convert to base64 once
            val base64Data = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)
            val totalSize = base64Data.length
            val totalChunks = (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE // Ceiling division
            
            Log.d(TAG, "Chunking album art: ${totalChunks} chunks of max ${CHUNK_SIZE} bytes each")
            
            // Send chunks sequentially
            for (chunkIndex in 0 until totalChunks) {
                val startIndex = chunkIndex * CHUNK_SIZE
                val endIndex = minOf(startIndex + CHUNK_SIZE, totalSize)
                val chunkData = base64Data.substring(startIndex, endIndex)
                
                val albumArtCommand = mapOf(
                    "command" to "album_art",
                    "hash" to hash,
                    "size" to imageBytes.size,
                    "chunk_index" to chunkIndex,
                    "total_chunks" to totalChunks,
                    "chunk_size" to chunkData.length,
                    "data" to chunkData
                )
                
                val gson = com.google.gson.Gson()
                val jsonCommand = gson.toJson(albumArtCommand)
                
                Log.d(TAG, "Sending chunk ${chunkIndex + 1}/${totalChunks} (${chunkData.length} bytes)")
                
                try {
                    bluetoothManager.sendData(jsonCommand)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send chunk ${chunkIndex + 1}/${totalChunks}: ${e.message}")
                    throw Exception("Failed at chunk ${chunkIndex + 1}/${totalChunks}: ${e.message}")
                }
                
                // Add delay between chunks to prevent buffer overflow
                if (chunkIndex < totalChunks - 1) {
                    kotlinx.coroutines.delay(CHUNK_DELAY_MS)
                }
            }
            
            Log.d(TAG, "All ${totalChunks} chunks sent successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send album art via Bluetooth", e)
            throw e
        }
    }
}