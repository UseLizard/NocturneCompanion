package com.paulcity.nocturnecompanion.ble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Manages album art extraction, compression, and caching for BLE transfer
 */
class AlbumArtManager {
    companion object {
        private const val TAG = "AlbumArtManager"
        private const val CACHE_SIZE = 10 * 1024 * 1024 // 10MB cache
        private const val WEBP_QUALITY = 80 // WebP quality (0-100)
        private const val TARGET_SIZE = 300 // Target size for square album art
    }
    
    // LRU cache for compressed album art
    private val albumArtCache = object : LruCache<String, CachedAlbumArt>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedAlbumArt): Int {
            return value.data.size
        }
    }
    
    data class CachedAlbumArt(
        val data: ByteArray,
        val checksum: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CachedAlbumArt
            return data.contentEquals(other.data) && checksum == other.checksum
        }
        
        override fun hashCode(): Int {
            return 31 * data.contentHashCode() + checksum.hashCode()
        }
    }
    
    /**
     * Extract and process album art from MediaMetadata
     * @return Compressed WebP byte array and checksum, or null if no art available
     */
    fun extractAlbumArt(metadata: MediaMetadata?): Pair<ByteArray, String>? {
        if (metadata == null) {
            Log.d(TAG, "No metadata provided")
            return null
        }
        
        // Generate cache key from track info
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val cacheKey = "$artist|$album|$title"
        
        // Check cache first
        albumArtCache.get(cacheKey)?.let { cached ->
            Log.d(TAG, "Album art found in cache for: $cacheKey")
            return Pair(cached.data, cached.checksum)
        }
        
        // Extract bitmap from metadata
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        
        if (bitmap == null) {
            Log.d(TAG, "No album art found in metadata")
            return null
        }
        
        Log.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}")
        
        try {
            // Create square 300x300 bitmap
            val squareBitmap = createSquareBitmap(bitmap, TARGET_SIZE)
            Log.d(TAG, "Square bitmap size: ${squareBitmap.width}x${squareBitmap.height}")
            
            // Compress to WebP
            val outputStream = ByteArrayOutputStream()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Use lossy WebP for better compression on Android 11+
                squareBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, outputStream)
            } else {
                // Fallback to standard WebP on older versions
                @Suppress("DEPRECATION")
                squareBitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, outputStream)
            }
            val webpData = outputStream.toByteArray()
            
            // Calculate checksum
            val checksum = calculateChecksum(webpData)
            
            Log.d(TAG, "Compressed album art size: ${webpData.size} bytes (WebP), checksum: $checksum")
            
            // Cache the result
            albumArtCache.put(cacheKey, CachedAlbumArt(webpData, checksum))
            
            // Clean up
            squareBitmap.recycle()
            
            return Pair(webpData, checksum)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing album art", e)
            return null
        }
    }
    
    /**
     * Create a square bitmap with center crop and scale to target size
     */
    private fun createSquareBitmap(source: Bitmap, targetSize: Int): Bitmap {
        // First, create a square crop from the center
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        
        val squareCrop = Bitmap.createBitmap(source, x, y, size, size)
        
        // If already target size, return the crop
        if (size == targetSize) {
            return squareCrop
        }
        
        // Scale to target size
        val scaledBitmap = Bitmap.createScaledBitmap(squareCrop, targetSize, targetSize, true)
        
        // Clean up intermediate bitmap if we created one
        if (squareCrop !== scaledBitmap) {
            squareCrop.recycle()
        }
        
        return scaledBitmap
    }
    
    /**
     * Calculate MD5 checksum for data integrity verification
     */
    private fun calculateChecksum(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clear the cache
     */
    fun clearCache() {
        albumArtCache.evictAll()
        Log.d(TAG, "Album art cache cleared")
    }
    
    /**
     * Get current cache size
     */
    fun getCacheSize(): Int {
        return albumArtCache.size()
    }
}