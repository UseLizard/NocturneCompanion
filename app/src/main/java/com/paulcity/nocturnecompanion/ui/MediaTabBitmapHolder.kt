package com.paulcity.nocturnecompanion.ui

import android.graphics.Bitmap

/**
 * Static holder for current playing track's album art bitmap data to avoid Intent size limits
 * This holds only the currently playing track's album art to ensure UI consistency
 */
object MediaTabBitmapHolder {
    private var storedBitmap: Bitmap? = null
    private var trackInfo: String? = null // Store track info to verify current track
    
    fun storeBitmap(bitmap: Bitmap, artist: String = "", track: String = "") {
        // Clear previous bitmap and store new one with track info
        clearBitmap()
        storedBitmap = bitmap
        trackInfo = "$artist - $track"
    }
    
    fun getBitmap(): Bitmap? {
        return storedBitmap
    }
    
    fun getCurrentTrackInfo(): String? {
        return trackInfo
    }
    
    fun clearBitmap() {
        storedBitmap?.recycle()
        storedBitmap = null
        trackInfo = null
    }
    
    fun isCurrentTrack(artist: String, track: String): Boolean {
        return trackInfo == "$artist - $track"
    }
}