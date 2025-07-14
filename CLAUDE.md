# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NocturneCompanion is an Android app that acts as a media control bridge between Android devices and external Bluetooth clients (specifically designed for Car Thing/Nocturne devices). It receives JSON commands via Bluetooth SPP (Serial Port Profile) and controls Android media playback accordingly.

## Build and Development Commands

### Building the Project
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests on connected device/emulator
./gradlew connectedAndroidTest
```

### Development
```bash
# Install debug APK to connected device
./gradlew installDebug

# Lint the code
./gradlew lint

# Check dependencies for updates
./gradlew dependencyUpdates
```

## Architecture Overview

### Core Components

1. **MainActivity** (`ui/MainActivity.kt`): Main UI that shows paired Bluetooth devices, service status, and live debugging info for received commands and sent state updates.

2. **NocturneService** (`services/NocturneService.kt`): Foreground service that orchestrates media control. Manages Bluetooth server, observes media controllers, and handles command execution.

3. **BluetoothServerManager** (`services/BluetoothServerManager.kt`): Manages SPP Bluetooth server socket, handles client connections, and provides bidirectional communication via coroutines.

4. **NocturneNotificationListener** (`services/NocturneNotificationListener.kt`): NotificationListenerService that monitors active media sessions and exposes the current media controller via StateFlow.

5. **Data Models** (`data/Models.kt`): 
   - `Command`: Incoming JSON commands from Bluetooth client
   - `StateUpdate`: Outgoing JSON state updates to Bluetooth client

### Communication Flow

1. **Inbound**: Bluetooth client → BluetoothServerManager → NocturneService → MediaController transport controls
2. **Outbound**: MediaController callbacks → NocturneService → BluetoothServerManager → Bluetooth client

### Key Features

- **Bluetooth SPP Server**: Listens on UUID `00001101-0000-1000-8000-00805F9B34FB`
- **Media Session Integration**: Uses Android's MediaSessionManager to control active media apps
- **Real-time State Updates**: Sends JSON state updates when media metadata/playback state changes
- **Permission Management**: Handles Bluetooth, location, and notification listener permissions
- **Debug UI**: Live display of received commands and sent state updates

### Command Protocol

**Incoming Commands** (JSON):
```json
{"command": "play"}
{"command": "pause"} 
{"command": "next"}
{"command": "previous"}
{"command": "seek_to", "value_ms": 30000}
{"command": "set_volume", "value_percent": 75}
```

**Outgoing State Updates** (JSON):
```json
{
  "type": "stateUpdate",
  "artist": "Artist Name",
  "album": "Album Name", 
  "track": "Track Title",
  "duration_ms": 240000,
  "position_ms": 45000,
  "is_playing": true,
  "volume_percent": 75
}
```

## Development Notes

- Target SDK: 34, Min SDK: 26
- Uses Jetpack Compose for UI
- Kotlin coroutines for async operations
- Gson for JSON serialization
- Testing mode enabled by default (`testingMode = true` in NocturneService)
- Requires notification listener permission for media session access
- Service runs in foreground with persistent notification