package com.paulcity.nocturnecompanion.ble

import java.util.UUID

object BleConstants {
    // Nordic UART Service compatible UUIDs for better compatibility
    val NOCTURNE_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    
    // Characteristics
    val COMMAND_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  // Write
    val STATE_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // Notify
    val DEBUG_LOG_CHAR_UUID: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")  // Notify
    val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("6E400005-B5A3-F393-E0A9-E50E24DCCA9E") // Read
    val ALBUM_ART_TX_CHAR_UUID: UUID = UUID.fromString("6E400006-B5A3-F393-E0A9-E50E24DCCA9E") // Notify
    
    // Standard BLE UUIDs
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val GENERIC_ACCESS_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val DEVICE_NAME_CHAR_UUID: UUID = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
    
    // BLE Configuration
    const val TARGET_MTU = 512
    const val DEFAULT_MTU = 23
    const val MTU_HEADER_SIZE = 3
    const val MAX_CHARACTERISTIC_LENGTH = 512
    const val CONNECTION_TIMEOUT_MS = 10000L
    const val ADVERTISING_TIMEOUT_MS = 0L // Advertise indefinitely
    
    // Album Art Transfer Configuration
    const val ALBUM_ART_CHUNK_SIZE = TARGET_MTU - MTU_HEADER_SIZE // 509 bytes per chunk
    const val ALBUM_ART_TRANSFER_TIMEOUT_MS = 30000L // 30 seconds timeout
    
    // Device identification
    const val DEVICE_NAME = "NocturneCompanion"
    const val MANUFACTURER_ID = 0xFFFF // Test manufacturer ID
    
    // Debug levels
    enum class DebugLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }
    
    // Protocol types
    object MessageType {
        const val COMMAND = "command"
        const val STATE_UPDATE = "stateUpdate"
        const val ACK = "ack"
        const val ERROR = "error"
        const val CAPABILITIES = "capabilities"
        const val DEBUG_LOG = "debugLog"
        const val ALBUM_ART_START = "album_art_start"
        const val ALBUM_ART_END = "album_art_end"
    }
}