package com.paulcity.nocturnecompanion.data

// Nocturne -> Android
data class Command(
    val command: String,
    val value_ms: Long? = null,
    val value_percent: Int? = null,
    val payload: Map<String, Any>? = null
)

// Android -> Nocturne
data class StateUpdate(
    val type: String = "stateUpdate",
    var artist: String?,
    var album: String?,
    var track: String?,
    var duration_ms: Long,
    var position_ms: Long,
    var is_playing: Boolean,
    var volume_percent: Int
)

// Audio event tracking
data class AudioEvent(
    val timestamp: Long,
    val eventType: AudioEventType,
    val message: String,
    val details: Map<String, Any>? = null
)

enum class AudioEventType {
    PLAYBACK_CONFIG_CHANGED,
    AUDIO_DEVICE_CONNECTED,
    AUDIO_DEVICE_DISCONNECTED,
    MEDIA_SESSION_CREATED,
    MEDIA_SESSION_DESTROYED,
    METADATA_CHANGED,
    PLAYBACK_STATE_CHANGED,
    AUDIO_STARTED,
    AUDIO_STOPPED,
    VOLUME_CHANGED,
    AUDIO_FOCUS_CHANGED
}

