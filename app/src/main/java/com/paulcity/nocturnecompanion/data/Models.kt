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

