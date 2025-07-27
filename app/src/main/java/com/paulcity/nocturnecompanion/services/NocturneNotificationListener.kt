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

class NocturneNotificationListener : NotificationListenerService() {

    companion object {
        private val _activeMediaController = MutableStateFlow<MediaController?>(null)
        val activeMediaController = _activeMediaController.asStateFlow()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateActiveMediaSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        updateActiveMediaSession()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateActiveMediaSession()
    }

    private fun updateActiveMediaSession() {
        try {
            Log.d("NotificationListener", "Updating active media session...")
            // 1. Get the MediaSessionManager system service.
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            // 2. Get the ComponentName of this NotificationListenerService.
            val componentName = ComponentName(this, this.javaClass)
            // 3. Call getActiveSessions on the manager, passing the componentName.
            val controllers = manager.getActiveSessions(componentName)

            Log.d("NotificationListener", "Found ${controllers.size} active media sessions")
            
            // Log all sessions for debugging
            for (i in controllers.indices) {
                val controller = controllers[i]
                val state = controller.playbackState
                Log.d("NotificationListener", 
                    "Session $i: ${controller.packageName}, " +
                    "playing: ${state?.state == android.media.session.PlaybackState.STATE_PLAYING}, " +
                    "state: ${state?.state}, " +
                    "lastUpdate: ${state?.lastPositionUpdateTime}"
                )
            }

            // Find the highest-priority controller using multi-tiered prioritization
            val bestController = controllers.sortedWith(
                // Primary Sort: Actively playing sessions come first
                compareByDescending<MediaController> { controller -> 
                    controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING 
                }
                // Secondary Sort: Tie-break with the most recently updated session
                .thenByDescending { controller -> 
                    controller.playbackState?.lastPositionUpdateTime ?: 0L 
                }
            ).firstOrNull()

            // Update the StateFlow intelligently
            val currentController = _activeMediaController.value
            
            if (bestController?.packageName != currentController?.packageName || 
                (bestController != null && currentController == null)) {
                // Session has changed, update the StateFlow
                Log.d("NotificationListener", 
                    "Active session changed from ${currentController?.packageName} to ${bestController?.packageName}")
                _activeMediaController.value = bestController
            } else if (bestController == null && currentController != null) {
                // No active sessions anymore
                Log.w("NotificationListener", "No active media sessions found, clearing current session")
                _activeMediaController.value = null
            } else {
                // The highest-priority session is the one we are already tracking
                Log.d("NotificationListener", 
                    "Keeping existing session: ${currentController?.packageName} " +
                    "(priority unchanged)")
            }
        } catch (e: Exception) {
            Log.e("NotificationListener", "Error updating media session", e)
        }
    }
}