# NocturneCompanion BLE Implementation

## Overview

NocturneCompanion has been converted from Bluetooth Classic SPP (Serial Port Profile) to BLE (Bluetooth Low Energy) with enhanced debugging capabilities. This provides better battery efficiency, improved connection management, and comprehensive debugging tools.

## Key Features

### 1. Enhanced BLE Server
- **Nordic UART Service Compatible**: Uses standard UUIDs for better compatibility
- **Multiple Characteristics**:
  - Command RX (Write): Receive JSON commands
  - State TX (Notify): Send media state updates
  - Debug Log (Notify): Real-time debug log streaming
  - Device Info (Read): Capability negotiation

### 2. Robust Debugging Tools
- **Debug Logger**: Comprehensive logging with levels (VERBOSE, DEBUG, INFO, WARNING, ERROR)
- **Real-time Log Streaming**: Debug logs sent via BLE characteristic
- **Debug UI**: New DebugActivity with tabs for:
  - Connected devices with MTU and subscription info
  - Live debug logs with filtering
  - Command history
  - Current media state

### 3. Protocol Enhancements
- **Command Acknowledgments**: Every command receives an ACK with status
- **Error Reporting**: Structured error messages with codes
- **Capability Negotiation**: Automatic feature discovery
- **Connection Status**: Real-time connection state updates

## Architecture Changes

### Removed Components
- `BluetoothServerManager.kt` - SPP server implementation
- SPP-related code from `NocturneService.kt`

### New Components
- `EnhancedBleServerManager.kt` - Advanced BLE GATT server
- `DebugLogger.kt` - Structured logging system
- `NocturneServiceBLE.kt` - BLE-only service implementation
- `DebugActivity.kt` - Comprehensive debug UI

### Updated Components
- `BleConstants.kt` - Enhanced with debug levels and message types
- `AndroidManifest.xml` - Added new service and activity

## Usage

### Starting the Service

1. **Using Debug UI** (Recommended for testing):
   ```
   - Launch the app
   - Open "Nocturne BLE Debug" from launcher
   - Tap the play button to start the service
   - Monitor all tabs for real-time information
   ```

2. **Programmatically**:
   ```kotlin
   val intent = Intent(context, NocturneServiceBLE::class.java).apply {
       action = NocturneServiceBLE.ACTION_START
   }
   startForegroundService(intent)
   ```

### Testing with Python Client

1. Install dependencies:
   ```bash
   pip install bleak
   ```

2. Run the test client:
   ```bash
   python3 BLE_TEST_CLIENT.py
   ```

3. Use interactive commands:
   - `play` - Start playback
   - `pause` - Pause playback
   - `next` - Next track
   - `prev` - Previous track
   - `seek <ms>` - Seek to position
   - `vol <0-100>` - Set volume
   - `info` - Read device capabilities

## Debug Features

### Log Levels
- **VERBOSE**: Detailed operation logs
- **DEBUG**: Development information
- **INFO**: Important events
- **WARNING**: Potential issues
- **ERROR**: Failures and exceptions

### Log Types
- `CONNECTION`: Device connections
- `COMMAND_RECEIVED`: Incoming commands
- `STATE_UPDATED`: Media state changes
- `MTU_CHANGED`: MTU negotiations
- `NOTIFICATION_SENT`: Outgoing notifications
- `ADVERTISING`: BLE advertising status

### Debug UI Features
1. **Status Bar**: Color-coded connection status
2. **Device Tab**: Connected devices with details
3. **Logs Tab**: Filterable real-time logs
4. **Commands Tab**: Last received command
5. **State Tab**: Current media state

## Protocol Details

### Command Format
```json
{
  "command": "play|pause|next|previous|seek_to|set_volume",
  "value_ms": 30000,      // For seek_to
  "value_percent": 75     // For set_volume
}
```

### State Update Format
```json
{
  "type": "stateUpdate",
  "artist": "Artist Name",
  "album": "Album Name",
  "track": "Track Title",
  "duration_ms": 240000,
  "position_ms": 30000,
  "is_playing": true,
  "volume_percent": 75
}
```

### Acknowledgment Format
```json
{
  "type": "ack",
  "command_id": "cmd_123",
  "status": "success|received|error",
  "message": "Command executed"
}
```

### Error Format
```json
{
  "type": "error",
  "code": "NO_MEDIA_SESSION",
  "message": "No active media session found",
  "timestamp": 1704067200000
}
```

### Capabilities Format
```json
{
  "type": "capabilities",
  "version": "2.0",
  "features": ["media_control", "volume_control", "seek_support", "debug_logging"],
  "mtu": 512,
  "debug_enabled": true
}
```

## Benefits Over SPP

1. **Lower Power Consumption**: BLE is designed for efficiency
2. **Better Connection Management**: Built-in connection parameters
3. **Notification System**: No polling required
4. **MTU Negotiation**: Dynamic packet size optimization
5. **Service Discovery**: Standardized GATT profile
6. **Debug Streaming**: Dedicated characteristic for logs

## Troubleshooting

### Common Issues

1. **Service Not Starting**
   - Check Bluetooth permissions
   - Ensure Bluetooth is enabled
   - Verify notification listener access

2. **No Debug Logs**
   - Ensure device is subscribed to debug characteristic
   - Check log level filter in UI

3. **Commands Not Working**
   - Verify media app is playing
   - Check notification listener permission
   - Review debug logs for errors

### Debug Commands

```bash
# Build and install
./gradlew installDebug

# View Android logs
adb logcat | grep -E "EnhancedBleServer|DebugLogger|NocturneServiceBLE"

# Clear app data
adb shell pm clear com.paulcity.nocturnecompanion
```

## Future Enhancements

1. **Batch Command Support**: Send multiple commands in one write
2. **Compression**: For large state updates
3. **Encryption**: For sensitive data
4. **Multi-Device Support**: Handle multiple simultaneous connections
5. **Custom Characteristics**: For app-specific features