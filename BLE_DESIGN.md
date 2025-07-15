# NocturneCompanion BLE Design

## Overview
Converting NocturneCompanion to use BLE (Bluetooth Low Energy) exclusively with enhanced debugging capabilities.

## BLE Service Structure

### Primary Service: Nocturne Media Control
- **UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service compatible)
- **Type**: Primary Service

### Characteristics

#### 1. Command RX Characteristic (Write)
- **UUID**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- **Properties**: Write, Write Without Response
- **Purpose**: Receive JSON commands from nocturned
- **Max Length**: 512 bytes (after MTU negotiation)

#### 2. State TX Characteristic (Notify)
- **UUID**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- **Properties**: Notify
- **Purpose**: Send media state updates to nocturned
- **Descriptor**: Client Characteristic Configuration (0x2902)

#### 3. Debug Log Characteristic (Notify)
- **UUID**: `6E400004-B5A3-F393-E0A9-E50E24DCCA9E`
- **Properties**: Notify
- **Purpose**: Real-time debug log streaming
- **Descriptor**: Client Characteristic Configuration (0x2902)

#### 4. Device Info Characteristic (Read)
- **UUID**: `6E400005-B5A3-F393-E0A9-E50E24DCCA9E`
- **Properties**: Read
- **Purpose**: Provide device information and capabilities
- **Content**: JSON with version, capabilities, etc.

## Debug Features

### 1. Debug Log Levels
```kotlin
enum class DebugLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARNING,
    ERROR
}
```

### 2. Debug Event Types
- Connection events
- Command received/parsed
- Media controller changes
- State update sent
- Error conditions
- Performance metrics

### 3. Debug Log Format
```json
{
  "timestamp": 1704067200000,
  "level": "DEBUG",
  "type": "COMMAND_RECEIVED",
  "message": "Received play command",
  "data": {
    "command": "play",
    "source": "AA:BB:CC:DD:EE:FF"
  }
}
```

## Protocol Enhancements

### 1. Command Acknowledgment
```json
{
  "type": "ack",
  "command_id": "12345",
  "status": "success",
  "message": "Command executed"
}
```

### 2. Error Reporting
```json
{
  "type": "error",
  "code": "NO_MEDIA_SESSION",
  "message": "No active media session found",
  "timestamp": 1704067200000
}
```

### 3. Capability Negotiation
```json
{
  "type": "capabilities",
  "version": "2.0",
  "features": [
    "media_control",
    "volume_control",
    "seek_support",
    "debug_logging",
    "album_art"
  ],
  "mtu": 512
}
```

## Implementation Plan

### Phase 1: Core BLE Server
1. Remove BluetoothServerManager (SPP)
2. Enhance BleServerManager with new characteristics
3. Implement debug logging infrastructure
4. Add connection state management

### Phase 2: Debug Tools
1. Create DebugLogger class
2. Implement circular buffer for logs
3. Add performance metrics collection
4. Create debug UI components

### Phase 3: Protocol Enhancement
1. Add command acknowledgments
2. Implement error reporting
3. Add capability negotiation
4. Enhance state update format

### Phase 4: Testing & Optimization
1. Create BLE test client
2. Add unit tests for BLE operations
3. Optimize for battery efficiency
4. Stress test with multiple connections

## Benefits Over SPP

1. **Lower Power Consumption**: BLE designed for efficiency
2. **Better Connection Management**: Built-in connection parameters
3. **Notification System**: No polling required
4. **MTU Negotiation**: Dynamic packet size optimization
5. **Service Discovery**: Standardized GATT profile
6. **Debug Streaming**: Dedicated characteristic for logs