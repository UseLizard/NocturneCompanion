package com.paulcity.nocturnecompanion.utils

import android.content.Context
import android.util.Log
import com.paulcity.nocturnecompanion.data.PodcastCollection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PodcastStorageManager(private val context: Context) {
    companion object {
        private const val TAG = "PodcastStorageManager"
        private const val PODCAST_DIR = "podcasts"
        private const val PODCAST_FILE = "podcast_collection.json"
    }
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private fun getPodcastFile(): File {
        val dir = File(context.filesDir, PODCAST_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, PODCAST_FILE)
    }
    
    fun savePodcastCollection(collection: PodcastCollection): Boolean {
        return try {
            val file = getPodcastFile()
            val jsonString = json.encodeToString(collection)
            file.writeText(jsonString)
            Log.d(TAG, "Saved podcast collection with ${collection.podcasts.size} podcasts")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving podcast collection", e)
            false
        }
    }
    
    fun loadPodcastCollection(): PodcastCollection? {
        return try {
            val file = getPodcastFile()
            if (!file.exists()) {
                Log.d(TAG, "No saved podcast collection found")
                return null
            }
            
            val jsonString = file.readText()
            val collection = json.decodeFromString<PodcastCollection>(jsonString)
            Log.d(TAG, "Loaded podcast collection with ${collection.podcasts.size} podcasts")
            collection
        } catch (e: Exception) {
            Log.e(TAG, "Error loading podcast collection", e)
            null
        }
    }
    
    fun clearPodcasts(): Boolean {
        return try {
            val file = getPodcastFile()
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleared podcast collection")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing podcasts", e)
            false
        }
    }
}