# Nocturne Companion

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

**Nocturne Companion** is an Android application that acts as a Bluetooth SPP (Serial Port Profile) bridge between external Bluetooth devices and Android's media subsystem. It enables custom hardware devices to remotely control media playback on Android phones through simple JSON commands while receiving real-time media state updates including track artwork.

## 🎯 Purpose

This app was designed to transform resource-constrained Bluetooth devices (like repurposed Spotify Car Things) into powerful media control hubs. Instead of implementing complex media control logic on the remote device, it can send simple JSON commands to the Nocturne Companion app, which handles all Android media session interactions.

## ✨ Key Features

- **🔗 Bluetooth SPP Server**: Accepts connections from external Bluetooth clients
- **🎵 Universal Media Control**: Works with any Android app using MediaSession API (Spotify, YouTube Music, Podcasts, etc.)
- **📡 Real-time State Updates**: Pushes current media state (track, artist, album, duration, position, volume) to connected clients
- **🖼️ Track Artwork Transmission**: Extracts, compresses, and transmits album artwork as Base64-encoded JPEG
- **🔍 Advanced Debug Interface**: Live monitoring of JSON communication with visual artwork preview
- **🔄 Background Operation**: Foreground service ensures continuous operation
- **📱 Modern UI**: Jetpack Compose interface with tabbed debugging views

## 🏗️ Architecture

The application follows modern Android architecture principles:

- **Language**: Kotlin with Coroutines for async operations
- **UI Framework**: Jetpack Compose for reactive UI
- **Architecture Pattern**: Service-oriented with MVVM influences
- **State Management**: StateFlow for reactive state propagation

### Core Components

```
┌─────────────────┐    ┌──────────────────────┐    ┌─────────────────────┐
│   MainActivity  │◄──►│   NocturneService    │◄──►│ BluetoothServer     │
│                 │    │                      │    │ Manager             │
│ • Debug UI      │    │ • Command Handling   │    │                     │
│ • Service       │    │ • State Broadcasting │    │ • SPP Server        │
│   Control       │    │ • Media Integration  │    │ • JSON Protocol     │
└─────────────────┘    └──────────────────────┘    └─────────────────────┘
                                  │
                                  ▼
                       ┌──────────────────────┐
                       │ NotificationListener │
                       │                      │
                       │ • Media Session      │
                       │   Monitoring         │
                       │ • Controller Access  │
                       └──────────────────────┘
```

## 📋 Prerequisites

- **Android Device**: API level 26+ (Android 8.0)
- **Bluetooth**: Device with Bluetooth SPP capability
- **Permissions**: Notification listener access (manual setup required)

## 🚀 Installation & Setup

### 1. Install the Application

```bash
# Clone the repository
git clone https://github.com/yourusername/nocturne-companion.git
cd nocturne-companion

# Build and install debug APK
./gradlew assembleDebug
./gradlew installDebug
```

### 2. Configure Permissions

1. **Launch the app** - The main screen will prompt for required permissions
2. **Enable Notification Access**:
   - Tap "Open Settings" when prompted
   - Find "Nocturne Companion" in the notification access list
   - Toggle the permission ON
   - This is **critical** for media session access

### 3. Pair Your Bluetooth Device

1. Go to Android Settings → Bluetooth
2. Pair your external Bluetooth device (e.g., Car Thing, custom hardware)
3. Note the device name for connection

### 4. Start the Service

1. Open Nocturne Companion app
2. Navigate to **Main** tab
3. Tap **"Start SPP Server"**
4. Service status should change to "Listening" or "Connected"

## 📡 Communication Protocol

The app uses a JSON-based protocol over Bluetooth SPP (UUID: `00001101-0000-1000-8000-00805F9B34FB`).

### Incoming Commands (Client → App)

```json
// Basic playback controls
{"command": "play"}
{"command": "pause"}
{"command": "next"}
{"command": "previous"}

// Advanced controls
{"command": "seek_to", "value_ms": 30000}
{"command": "set_volume", "value_percent": 75}
```

### Outgoing State Updates (App → Client)

```json
{
  "type": "stateUpdate",
  "artist": "Artist Name",
  "album": "Album Name",
  "track": "Track Title",
  "duration_ms": 240000,
  "position_ms": 45000,
  "is_playing": true,
  "volume_percent": 75,
  "artwork_base64": "/9j/4AAQSkZJRgABAQEAYABgAAD..."
}
```

## 🖼️ Track Artwork Feature

The recent artwork implementation provides high-quality album art transmission:

### Technical Details
- **Source**: Extracts from `MediaMetadata.METADATA_KEY_ART` or `METADATA_KEY_ALBUM_ART`
- **Optimization**: Automatically scales images to 300×300px maximum
- **Compression**: JPEG format at 80% quality for efficient transmission
- **Encoding**: Base64 string in JSON payload
- **Size**: Typically 15-50KB per image

### Debug Visualization
The **Debug Data** tab provides real-time artwork preview:
- Decodes and displays transmitted images
- Shows image size and encoding details
- Validates artwork transmission pipeline

## 🛠️ Development

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Code linting
./gradlew lint

# Clean build
./gradlew clean
```

### Project Structure

```
app/src/main/java/com/paulcity/nocturnecompanion/
├── data/
│   └── Models.kt              # Command & StateUpdate data classes
├── services/
│   ├── BluetoothServerManager.kt    # SPP server implementation
│   ├── NocturneNotificationListener.kt # Media session monitoring
│   └── NocturneService.kt           # Core orchestration service
└── ui/
    ├── MainActivity.kt              # Compose UI with debug interface
    └── theme/                       # App theming
```

## 🐛 Debug Interface

The app includes a comprehensive debug interface with three tabs:

### Main Tab
- **Server Status**: Connection state and control buttons
- **Service Notifications**: Real-time service event log
- **Permission Status**: Notification listener setup guidance

### Debug Data Tab
- **Command History**: Last received JSON command
- **State Updates**: Complete JSON state data
- **Artwork Preview**: Visual display of transmitted album art
- **Transmission Stats**: Image size and encoding information

### Devices Tab
- **Paired Devices**: List of available Bluetooth devices
- **Connection Management**: Device discovery and pairing tools

## 🔧 Configuration

### Environment Variables
- `testingMode = true`: Enables enhanced debug notifications and logging

### Bluetooth Configuration
- **UUID**: `00001101-0000-1000-8000-00805F9B34FB` (standard SPP UUID)
- **Connection**: Single client, auto-reconnect on disconnect
- **Data Format**: UTF-8 JSON strings terminated with `\n`

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines
- Follow Kotlin coding conventions
- Use Compose for new UI components
- Add appropriate logging for debugging
- Test Bluetooth functionality on real hardware
- Update documentation for protocol changes

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built for the [Nocturne ecosystem](https://github.com/nocturne-project) media control platform
- Designed to work with repurposed Spotify Car Thing devices
- Inspired by the need for custom media control solutions

## 📞 Support

- 🐛 **Issues**: [GitHub Issues](https://github.com/yourusername/nocturne-companion/issues)
- 💬 **Discussions**: [GitHub Discussions](https://github.com/yourusername/nocturne-companion/discussions)
- 📧 **Contact**: [your.email@example.com](mailto:your.email@example.com)

---

**Made with ❤️ for the open source community**