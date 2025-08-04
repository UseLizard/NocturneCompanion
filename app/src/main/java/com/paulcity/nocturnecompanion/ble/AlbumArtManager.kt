package com.paulcity.nocturnecompanion.ble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Manages album art extraction, compression, and caching for BLE transfer
 */
class AlbumArtManager {
    companion object {
        private const val TAG = "AlbumArtManager"
        private const val CACHE_SIZE = 10 * 1024 * 1024 // 10MB cache
    }
    
    // Configurable settings
    private var imageFormat = "JPEG"
    private var compressionQuality = 85
    private var imageSize = 300
    
    // LRU cache for compressed album art
    private val albumArtCache = object : LruCache<String, CachedAlbumArt>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedAlbumArt): Int {
            return value.data.size
        }
    }

    fun getArtFromCache(cacheKey: String): CachedAlbumArt? {
        return albumArtCache.get(cacheKey)
    }
    
    /**
     * Get album art by artist and album, using MD5 hash as cache key
     */
    fun getArtByMetadata(artist: String?, album: String?): CachedAlbumArt? {
        val cacheKey = generateMetadataHash(artist, album)
        return albumArtCache.get(cacheKey)
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
     * Generate MD5 hash from artist and album names (matching nocturned's algorithm)
     * This creates a unique identifier for caching based on metadata
     */
    fun generateMetadataHash(artist: String?, album: String?): String {
        // Normalize strings: lowercase, trim spaces (matching nocturned)
        val normalizedArtist = (artist ?: "").trim().lowercase(Locale.ROOT)
        val normalizedAlbum = (album ?: "").trim().lowercase(Locale.ROOT)
        
        // Combine artist and album with hyphen (matching nocturned)
        val combined = "$normalizedArtist-$normalizedAlbum"
        
        // Generate MD5 hash
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(combined.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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
        
        // Generate cache key from track info using MD5 hash (matching nocturned)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        
        // Use MD5 hash of artist-album as cache key
        val cacheKey = generateMetadataHash(artist, album)
        
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
            val squareBitmap = createSquareBitmap(bitmap, imageSize)
            Log.d(TAG, "Square bitmap size: ${squareBitmap.width}x${squareBitmap.height}")
            
            // Compress using configured format and quality
            val outputStream = ByteArrayOutputStream()
            val compressFormat = when(imageFormat) {
                "WEBP" -> Bitmap.CompressFormat.WEBP
                "PNG" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            squareBitmap.compress(compressFormat, compressionQuality, outputStream)
            val imageData = outputStream.toByteArray()
            
            // Calculate checksum
            val checksum = calculateChecksum(imageData)
            
            Log.d(TAG, "Compressed album art size: ${imageData.size} bytes ($imageFormat), SHA-256: $checksum")
            Log.d(TAG, "Caching with MD5 hash: $cacheKey for $artist - $album")
            
            // Cache the result
            albumArtCache.put(cacheKey, CachedAlbumArt(imageData, checksum))
            
            // Clean up
            squareBitmap.recycle()
            
            return Pair(imageData, checksum)
            
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
     * Calculate SHA-256 checksum for data integrity verification
     */
    private fun calculateChecksum(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
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
    
    /**
     * Update album art compression settings
     */
    fun updateSettings(format: String, quality: Int, imageSize: Int) {
        this.imageFormat = format
        this.compressionQuality = quality
        this.imageSize = imageSize
        
        // Clear cache when settings change to ensure new format is used
        clearCache()
        
        Log.d(TAG, "Settings updated - Format: $format, Quality: $quality, Size: ${imageSize}x$imageSize")
    }
}