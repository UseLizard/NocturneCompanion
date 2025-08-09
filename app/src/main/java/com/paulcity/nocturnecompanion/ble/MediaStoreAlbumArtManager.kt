package com.paulcity.nocturnecompanion.ble

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Album art manager that uses MediaStore to fetch album art
 * This is more reliable than relying on MediaMetadata bitmaps
 */
class MediaStoreAlbumArtManager(private val context: Context) {
    companion object {
        private const val TAG = "MediaStoreAlbumArt"
        private val ALBUM_ART_URI = Uri.parse("content://media/external/audio/albumart")
    }
    
    private val contentResolver: ContentResolver = context.contentResolver
    private val albumArtManager = AlbumArtManager() // Reuse existing manager for caching/compression
    
    /**
     * Extract album art using MediaStore based on artist/album info
     */
    fun getAlbumArtFromMediaStore(
        artist: String?,
        album: String?,
        title: String? = null // Not used for caching, kept for API compatibility
    ): Pair<ByteArray, String>? {
        if (album.isNullOrEmpty() && artist.isNullOrEmpty()) {
            Log.d(TAG, "No artist or album info to query MediaStore")
            return null
        }
        
        // First check cache using MD5 hash (matching nocturned)
        val cacheKey = albumArtManager.generateMetadataHash(artist, album)
        albumArtManager.getArtFromCache(cacheKey)?.let { cached ->
            Log.d(TAG, "Album art found in cache for MD5: $cacheKey")
            return Pair(cached.data, cached.checksum)
        }
        
        try {
            // Query MediaStore for album ID
            val albumId = getAlbumId(artist, album)
            if (albumId != null) {
                Log.d(TAG, "Found album ID: $albumId for album: $album by artist: $artist")
                
                // Get album art bitmap
                val bitmap = getAlbumArtBitmap(albumId)
                if (bitmap != null) {
                    Log.d(TAG, "Successfully retrieved album art from MediaStore")
                    
                    // Process and cache the bitmap
                    return processAndCacheBitmap(bitmap, cacheKey)
                } else {
                    Log.d(TAG, "No album art found for album ID: $albumId")
                }
            } else {
                Log.d(TAG, "Could not find album ID for: $album by $artist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving album art from MediaStore", e)
        }
        
        return null
    }
    
    /**
     * Get album art from MediaMetadata, falling back to MediaStore
     */
    fun getAlbumArt(metadata: MediaMetadata?): Pair<ByteArray, String>? {
        if (metadata == null) {
            Log.d(TAG, "No metadata provided")
            return null
        }
        
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        
        Log.d(TAG, "Attempting to get album art for: $title by $artist from $album")
        
        // First try the original method (from MediaMetadata bitmap)
        albumArtManager.extractAlbumArt(metadata)?.let { result ->
            Log.d(TAG, "Got album art from MediaMetadata bitmap")
            return result
        }
        
        // Fall back to MediaStore
        Log.d(TAG, "MediaMetadata bitmap not available, trying MediaStore")
        return getAlbumArtFromMediaStore(artist, album, title)
    }
    
    /**
     * Get album art from the currently playing media
     * This uses the NocturneNotificationListener to get current metadata
     */
    fun getAlbumArtFromCurrentMedia(): Pair<ByteArray, String>? {
        try {
            // Get current media controller from the notification listener
            val mediaController = com.paulcity.nocturnecompanion.services.NocturneNotificationListener.activeMediaController.value
            if (mediaController == null) {
                Log.d(TAG, "No active media controller")
                return null
            }
            
            val metadata = mediaController.metadata
            if (metadata == null) {
                Log.d(TAG, "No metadata from current media controller")
                return null
            }
            
            return getAlbumArt(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting album art from current media", e)
            return null
        }
    }
    
    /**
     * Query MediaStore for album ID
     */
    private fun getAlbumId(artist: String?, album: String?): Long? {
        val projection = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST
        )
        
        // Build query based on available info
        val selection = when {
            !album.isNullOrEmpty() && !artist.isNullOrEmpty() -> {
                "${MediaStore.Audio.Albums.ALBUM} = ? AND ${MediaStore.Audio.Albums.ARTIST} = ?"
            }
            !album.isNullOrEmpty() -> {
                "${MediaStore.Audio.Albums.ALBUM} = ?"
            }
            !artist.isNullOrEmpty() -> {
                "${MediaStore.Audio.Albums.ARTIST} = ?"
            }
            else -> return null
        }
        
        val selectionArgs = when {
            !album.isNullOrEmpty() && !artist.isNullOrEmpty() -> arrayOf(album, artist)
            !album.isNullOrEmpty() -> arrayOf(album)
            !artist.isNullOrEmpty() -> arrayOf(artist)
            else -> return null
        }
        
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndex(MediaStore.Audio.Albums._ID)
                if (idColumn >= 0) {
                    return cursor.getLong(idColumn)
                }
            }
        }
        
        // If exact match fails, try a more lenient search
        if (!album.isNullOrEmpty()) {
            val lenientSelection = "${MediaStore.Audio.Albums.ALBUM} LIKE ?"
            val lenientArgs = arrayOf("%$album%")
            
            contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                projection,
                lenientSelection,
                lenientArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndex(MediaStore.Audio.Albums._ID)
                    if (idColumn >= 0) {
                        val foundId = cursor.getLong(idColumn)
                        Log.d(TAG, "Found album with lenient search: $foundId")
                        return foundId
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Get album art bitmap from album ID
     */
    private fun getAlbumArtBitmap(albumId: Long): Bitmap? {
        val albumArtUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
        
        return try {
            contentResolver.openInputStream(albumArtUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "Album art file not found for ID: $albumId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading album art for ID: $albumId", e)
            null
        }
    }
    
    /**
     * Process bitmap and add to cache
     */
    private fun processAndCacheBitmap(bitmap: Bitmap, cacheKey: String): Pair<ByteArray, String>? {
        try {
            // Create square 300x300 bitmap
            val squareBitmap = createSquareBitmap(bitmap, BleConstants.ALBUM_ART_TARGET_SIZE)
            Log.d(TAG, "Square bitmap size: ${squareBitmap.width}x${squareBitmap.height}")
            
            // Compress to WebP
            val outputStream = ByteArrayOutputStream()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                squareBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, BleConstants.ALBUM_ART_WEBP_QUALITY, outputStream)
            } else {
                @Suppress("DEPRECATION")
                squareBitmap.compress(Bitmap.CompressFormat.WEBP, BleConstants.ALBUM_ART_WEBP_QUALITY, outputStream)
            }
            val webpData = outputStream.toByteArray()
            
            // Calculate checksum
            val checksum = calculateChecksum(webpData)
            
            Log.d(TAG, "Compressed album art size: ${webpData.size} bytes (WebP), checksum: $checksum")
            
            // Add to cache
            val cachedArt = AlbumArtManager.CachedAlbumArt(webpData, checksum)
            albumArtManager.getArtFromCache(cacheKey) ?: run {
                // Only add if not already in cache
                try {
                    val cacheField = AlbumArtManager::class.java.getDeclaredField("albumArtCache")
                    cacheField.isAccessible = true
                    val cache = cacheField.get(albumArtManager) as android.util.LruCache<String, AlbumArtManager.CachedAlbumArt>
                    cache.put(cacheKey, cachedArt)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add to cache", e)
                }
            }
            
            // Clean up
            squareBitmap.recycle()
            if (bitmap !== squareBitmap) {
                bitmap.recycle()
            }
            
            return Pair(webpData, checksum)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing album art bitmap", e)
            return null
        }
    }
    
    /**
     * Create a square bitmap with center crop and scale to target size
     */
    private fun createSquareBitmap(source: Bitmap, targetSize: Int): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        
        val squareCrop = Bitmap.createBitmap(source, x, y, size, size)
        
        if (size == targetSize) {
            return squareCrop
        }
        
        val scaledBitmap = Bitmap.createScaledBitmap(squareCrop, targetSize, targetSize, true)
        
        if (squareCrop !== scaledBitmap) {
            squareCrop.recycle()
        }
        
        return scaledBitmap
    }
    
    /**
     * Calculate SHA-256 checksum
     */
    private fun calculateChecksum(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clear the cache
     */
    fun clearCache() {
        albumArtManager.clearCache()
    }
    
    /**
     * Get album art as a Bitmap for display in the UI
     */
    fun getAlbumArtBitmap(artist: String?, album: String?, title: String?): Bitmap? {
        // Try to get album art from MediaStore
        val albumId = getAlbumId(artist, album)
        if (albumId != null) {
            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
            
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(contentResolver, albumArtUri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, albumArtUri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load album art bitmap for albumId: $albumId", e)
                null
            }
        }
        
        return null
    }
}