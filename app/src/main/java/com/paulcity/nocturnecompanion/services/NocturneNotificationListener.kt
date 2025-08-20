package com.paulcity.nocturnecompanion.services

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.media.session.PlaybackState
import android.os.Build

class NocturneNotificationListener : NotificationListenerService() {

    companion object {
        private val _activeMediaController = MutableStateFlow<MediaController?>(null)
        val activeMediaController = _activeMediaController.asStateFlow()
        
        private const val TAG = "NocturneNotificationListener"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateActiveMediaSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        updateActiveMediaSession()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected")
        updateActiveMediaSession()
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected")
        _activeMediaController.value = null
    }

    private fun updateActiveMediaSession() {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, this.javaClass)
            val controllers = manager.getActiveSessions(componentName)

            Log.d(TAG, "Found ${controllers.size} active media sessions")

            // Find the highest-priority controller
            val bestController = controllers.sortedWith(
                compareByDescending<MediaController> { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0 }
            ).firstOrNull()

            val currentController = _activeMediaController.value
            val sessionChanged = bestController?.packageName != currentController?.packageName || 
                               (bestController != null && currentController == null) ||
                               (bestController == null && currentController != null)
            
            if (sessionChanged) {
                Log.d(TAG, "Active session changed to: ${bestController?.packageName}")
                _activeMediaController.value = bestController
                
                // Trigger album art refresh when media session changes
                if (bestController != null) {
                    // Send broadcast to trigger album art reload with retry mechanism
                    val intent = android.content.Intent("com.paulcity.nocturnecompanion.MEDIA_SESSION_CHANGED")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    Log.d(TAG, "🎨 Sent MEDIA_SESSION_CHANGED broadcast to trigger album art refresh")
                }
            } else {
                Log.d(TAG, "Keeping existing session: ${currentController?.packageName}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while updating active media session. Check permissions.", e)
            _activeMediaController.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media session", e)
            _activeMediaController.value = null
        }
    }
    
    
}