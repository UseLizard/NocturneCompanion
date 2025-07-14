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
            
            for (i in controllers.indices) {
                val controller = controllers[i]
                Log.d("NotificationListener", "Session $i: ${controller.packageName}, playing: ${controller.playbackState?.state}")
            }

            if (controllers.isNotEmpty()) {
                val newController = controllers[0]
                if (newController.packageName != _activeMediaController.value?.packageName) {
                    Log.d("NotificationListener", "Active session changed to: ${newController.packageName}")
                    _activeMediaController.value = newController
                } else {
                    Log.d("NotificationListener", "Keeping existing session: ${newController.packageName}")
                }
            } else {
                Log.w("NotificationListener", "No active media sessions found")
                _activeMediaController.value = null
            }
        } catch (e: Exception) {
            Log.e("NotificationListener", "Error updating media session", e)
        }
    }
}