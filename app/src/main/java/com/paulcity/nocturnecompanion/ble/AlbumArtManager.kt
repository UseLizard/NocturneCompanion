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
    
    // Callback for when new album art is cached
    private var onAlbumArtCachedCallback: ((CachedAlbumArt, String, String) -> Unit)? = null

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

    fun getArtWithStatus(artist: String?, album: String?): Pair<AlbumArtStatus, CachedAlbumArt?> {
        val cacheKey = generateMetadataHash(artist, album)
        val cachedArt = albumArtCache.get(cacheKey)
        return Pair(cachedArt?.status ?: AlbumArtStatus.NOT_REQUESTED, cachedArt)
    }

    fun updateArtStatus(artist: String?, album: String?, status: AlbumArtStatus) {
        val cacheKey = generateMetadataHash(artist, album)
        albumArtCache.get(cacheKey)?.let {
            albumArtCache.put(cacheKey, it.copy(status = status))
        }
    }

    fun cacheAlbumArt(artist: String?, album: String?, data: ByteArray, checksum: String) {
        val cacheKey = generateMetadataHash(artist, album)
        val cachedArt = CachedAlbumArt(data, checksum, AlbumArtStatus.AVAILABLE)
        albumArtCache.put(cacheKey, cachedArt)
        
        // Trigger callback for automatic transmission
        onAlbumArtCachedCallback?.invoke(cachedArt, artist ?: "", album ?: "")
    }
    
    /**
     * Set callback to be invoked when new album art is cached
     */
    fun setOnAlbumArtCachedCallback(callback: (CachedAlbumArt, String, String) -> Unit) {
        onAlbumArtCachedCallback = callback
    }
    
    data class CachedAlbumArt(
        val data: ByteArray,
        val checksum: String,
        val status: AlbumArtStatus = AlbumArtStatus.NOT_REQUESTED
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
     * Extract and process album art from MediaMetadata for currently playing track only
     * @return Compressed WebP byte array and checksum, or null if no art available
     */
    fun extractAlbumArt(metadata: MediaMetadata?): CachedAlbumArt? {
        if (metadata == null) {
            Log.d(TAG, "No metadata provided")
            return null
        }
        
        // Generate cache key from track info using MD5 hash (matching nocturned)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        
        // Use MD5 hash of artist-album as cache key
        val cacheKey = generateMetadataHash(artist, album)
        
        // Check if this is for currently playing track by verifying with MediaTabBitmapHolder
        if (!com.paulcity.nocturnecompanion.ui.MediaTabBitmapHolder.isCurrentTrack(artist, track)) {
            Log.d(TAG, "Skipping album art extraction for non-current track: $artist - $track")
            return null
        }
        
        // Check cache first, but clear old entries to ensure only current track art
        albumArtCache.get(cacheKey)?.let { cached ->
            Log.d(TAG, "Album art found in cache for current track: $cacheKey")
            return cached
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
            
            // Calculate album hash using the same algorithm as nocturned
            val checksum = calculateAlbumHash(album, artist)
            
            Log.d(TAG, "Compressed album art size: ${imageData.size} bytes ($imageFormat), Album Hash: $checksum")
            
            // Cache the result
            val cachedArt = CachedAlbumArt(imageData, checksum, AlbumArtStatus.AVAILABLE)
            albumArtCache.put(cacheKey, cachedArt)
            
            // Trigger callback for automatic transmission
            onAlbumArtCachedCallback?.invoke(cachedArt, artist, album)
            
            // Clean up
            squareBitmap.recycle()
            
            return cachedArt
            
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
     * Calculate integer hash matching nocturned's album hash algorithm
     * This uses the same algorithm as BinaryProtocolV2.calculateAlbumHash()
     */
    private fun calculateChecksum(data: ByteArray): String {
        // For album art, we don't use the data checksum but the metadata hash
        // This will be replaced by the actual album hash when extractAlbumArt is called
        return "placeholder"
    }
    
    /**
     * Calculate album hash using the same algorithm as nocturned backend
     * This matches the algorithm in BinaryProtocolV2.kt and NocturneServiceBLE.kt
     */
    private fun calculateAlbumHash(album: String, artist: String): String {
        val combined = "$album|$artist"
        return combined.hashCode().toString()
    }
    
    /**
     * Clear the cache
     */
    fun clearCache() {
        albumArtCache.evictAll()
        Log.d(TAG, "Album art cache cleared")
    }
    
    /**
     * Clear cache for previous track when new track starts to ensure only current track art is cached
     */
    fun clearPreviousTrackCache() {
        albumArtCache.evictAll()
        Log.d(TAG, "Previous track album art cache cleared to ensure only current track art")
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