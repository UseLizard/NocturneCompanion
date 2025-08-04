# NocturneCompanion - Android BLE Server

## Architecture
Android service providing BLE GATT server for media control. Uses NotificationListenerService for media access and AudioPlaybackCallback for robust audio detection.

## Key Files
- `services/NocturneServiceBLE.kt`: Main BLE service with AudioPlaybackCallback
- `services/NocturneNotificationListener.kt`: Media session access with prioritization
- `ble/EnhancedBleServerManager.kt`: BLE GATT server with message queue
- `ble/MessageQueue.kt`: Priority-based message queue system
- `data/Models.kt`: Data classes
- `ui/MainActivity.kt`: UI with permission checker

## BLE Implementation
### Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
### Characteristics:
- CommandRx (6e400002): Receive commands, send ACKs
- StateTx (6e400003): Send state updates/notifications
- AlbumArtTx (6e400006): Send album art chunks

## Protocol
### Incoming Command
```kotlin
data class Command(
    val command: String,
    val value_ms: Long?,
    val value_percent: Int?
)
```

### Message Priority System
```kotlin
enum class Priority {
    HIGH,    // Time sync, critical updates
    NORMAL,  // Regular state updates  
    BULK     // Album art chunks
}
```

### State Updates
- Debounced 100ms to prevent flooding
- Deduplicated (ignores position changes)
- Sent on: connect, metadata change, playback change
- Prioritized over album art transfers

### Album Art Protocol
- Sent only when requested via "album_art_query"
- Compressed to 300x300 WebP at 80% quality
- Chunked with base64 encoding
- SHA-256 checksum for integrity
- Uses BULK priority to avoid blocking

## Key Issues Fixed
1. Unreliable audio detection with Spotify Connect
2. BLE message jamming during album art transfers
3. Time sync race condition on connection
4. No user feedback for missing permissions
5. State update flooding without deduplication
6. Fixed delays causing poor responsiveness

## Major Improvements
1. **AudioPlaybackCallback** (API 26+) for system-wide audio detection
2. **MessageQueue** with 3-tier priority system
3. **Connection quality monitoring** with adaptive backoff
4. **NotificationListener prioritization** for multiple sessions
5. **Permission checker UI** with real-time updates
6. **Remote playback detection** for Spotify Connect

## Permissions Required
- BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
- BIND_NOTIFICATION_LISTENER_SERVICE  
- FOREGROUND_SERVICE
- READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE

## Audio Detection Strategy
1. **AudioPlaybackCallback** monitors system-wide audio
2. **NotificationListener** provides metadata and controls
3. **Remote playback filtering** prevents Spotify Connect issues
4. **Polling fallback** for state changes callbacks miss

## Message Queue Implementation
- **Coroutine-based** with separate queues per priority
- **MTU-aware** chunking for album art
- **Congestion control** with exponential backoff
- **Per-device tracking** of connection quality
- **Automatic flow control** based on failures

## Connection Initialization
1. Device connects via BLE
2. Wait 500ms for notification subscriptions
3. Send time sync with timestamp and timezone
4. Send initial media state
5. Start state polling for reliability