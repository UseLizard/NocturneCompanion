# Test Album Art Flow - Implementation Guide

## Overview
The test album art flow has been implemented to allow performance testing of album art transfers without interfering with normal media playback. This separate flow uses dedicated test messages and endpoints.

## Implementation Details

### 1. Companion App (NocturneCompanion)
The Android app now handles the `test_album_art_request` command:

- **Command Handler**: `handleTestAlbumArtRequest()` in `EnhancedBleServerManager.kt`
- **Behavior**: Retrieves the most recent album art from the cache and sends it via test messages
- **Test Messages**:
  - `test_album_art_start`: Initiates transfer with metadata
  - `test_album_art_chunk`: Sends individual chunks
  - `test_album_art_end`: Completes transfer

### 2. Nocturned Service
The service has been updated with:

- **Test Endpoints**:
  - `POST /api/test/album-art/request`: Triggers test album art request
  - `GET /api/test/album-art/status`: Returns current test transfer status
  - `GET /api/test/album-art/image`: Serves test album art (from `/tmp/test_album_art.jpg`)

- **BLE Handler**: Processes test album art messages separately from production flow
- **Storage**: Test album art saved to `/tmp/test_album_art.jpg` (separate from production)

### 3. Web UI (AlbumArtTest Component)
Updated to use the new test endpoints and show:

- Transfer progress bar
- Chunk-by-chunk progress updates
- Detailed transfer metrics
- BLE transfer time vs total time

## Testing Steps

### Prerequisites
1. Ensure NocturneCompanion app is running with media playing
2. Nocturned service is running and connected via BLE
3. Web UI is accessible

### Test Procedure

1. **Navigate to Test View**:
   - Open the Nocturne UI
   - Go to the Album Art Test page

2. **Request Test Album Art**:
   - Click "Request Test Album Art" button
   - The button will show current status:
     - Yellow background: Requesting
     - Green background: Transferring (with progress bar)
     - Blue background: Complete
     - Red background: Failed

3. **Monitor Transfer**:
   - Progress bar shows chunk transfer progress
   - Transfer metrics displayed in real-time
   - Album art appears when complete

4. **Verify Separation**:
   - Check that test transfers don't affect normal playback
   - Production album art remains at `/tmp/album_art.jpg`
   - Test album art is at `/tmp/test_album_art.jpg`

### Debug Commands

```bash
# Check test endpoints
curl -X POST http://localhost:5000/api/test/album-art/request
curl http://localhost:5000/api/test/album-art/status
curl http://localhost:5000/api/test/album-art/image

# Monitor logs
journalctl -u nocturned -f | grep -E "test_album_art|TEST_ALBUM"

# Check file locations
ls -la /tmp/album_art.jpg      # Production
ls -la /tmp/test_album_art.jpg # Test
```

### Expected Behavior

1. **No Current Media**: If no media is playing, the test request will fail gracefully
2. **Cache Hit**: The most recent album art from cache is sent
3. **Performance**: Test transfers use the same chunk settings as production
4. **Isolation**: Test and production flows are completely separate

## Troubleshooting

### No Album Art Sent
- Check if media is playing and has album art
- Verify BLE connection is established
- Check companion app logs for cache status

### Transfer Fails
- Verify chunk size settings are reasonable
- Check BLE MTU negotiation
- Monitor for WebSocket disconnections

### Wrong Image
- Test always sends the most recent cached album art
- Clear cache if needed through companion app settings

## Performance Testing

Use this test flow to:
1. Measure transfer times with different chunk sizes
2. Test reliability with various delay settings
3. Validate checksum verification
4. Stress test with repeated requests
5. Compare binary vs JSON protocol performance

The separation ensures production stability while enabling comprehensive testing.