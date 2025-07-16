### 1. **Critical Issue: Data Truncation due to MTU Size**

*   **The Problem:** The `sendNotification` function in `EnhancedBleServerManager.kt` is a potential source of silent data corruption. It sends JSON payloads (for media state, debug logs, etc.) without respecting the negotiated Maximum Transmission Unit (MTU) size for each connected device. A BLE packet's data payload cannot exceed `MTU - 3` bytes (the 3 bytes are reserved for the ATT protocol header).
*   **How it Fails:**
    1.  The `nocturned` device connects and negotiates an MTU (e.g., 512 bytes). This value is correctly stored in the `DeviceContext` for that device.
    2.  The media player on the phone updates its state. The track title is very long, causing the serialized `StateUpdate` JSON string to be, for example, 600 bytes.
    3.  The `sendStateUpdate` function calls `sendNotification` with this 600-byte payload.
    4.  The `sendNotification` function sets this 600-byte array as the characteristic's value.
    5.  The Android Bluetooth stack attempts to send the notification. Since the data (600 bytes) is larger than the allowed payload size (`512 - 3 = 509` bytes), **the stack silently truncates the data**.
    6.  The `nocturned` device receives only the first 509 bytes, which is an incomplete and therefore invalid JSON string.
    7.  The JSON parsing on the `nocturned` side fails. The media state update is completely lost, and there is no error message on the Android app to indicate what went wrong.
*   **Detailed Recommendation:**
    *   **Implement Data Chunking:** The `sendNotification` function must be modified to split large payloads into smaller chunks that respect the MTU.
    *   **Protocol:** A simple chunking protocol needs to be established. For example, each chunk could be prefixed with a header: `[message_id, chunk_index, total_chunks, ...data]`. This allows the `nocturned` client to reassemble the full message correctly, even if chunks arrive out of order.
    *   **Implementation in `EnhancedBleServerManager.kt`:**
        *   Before sending, get the target device's MTU from its `DeviceContext`.
        *   Calculate `maxChunkSize = mtu - 3 - chunk_header_size`.
        *   If the payload is larger than `maxChunkSize`, enter a loop that creates and sends each chunk.
        *   It is crucial to add a small delay (e.g., `delay(20)`) between sending each chunk to prevent overwhelming the receiver's buffer, a common cause of packet loss in BLE.
    *   **Required `nocturned` Changes:** The `nocturned` service's `handleNotificationData` function must be updated to understand this new chunking protocol. It will need to buffer incoming chunks for each `message_id` and reassemble them once all `total_chunks` have been received.

### 2. **Debugging Blind Spot: Unhandled Connection Error Statuses**

*   **The Problem:** The `onConnectionStateChange` callback provides two crucial parameters: `newState` (e.g., `STATE_CONNECTED`) and `status`. The code currently ignores the `status` parameter, which indicates *why* a state change occurred. A non-zero status is an error.
*   **How it Fails:**
    1.  A user reports that the app frequently disconnects.
    2.  A developer examines the `nocturned` and Android logs. The logs only show "Device Disconnected."
    3.  The actual reason for the disconnection is unknown. Was it a timeout because the user walked out of range (`status` code 8)? Did the `nocturned` device terminate the connection (`status` code 19)? Was there a low-level L2CAP error (`status` code 256)? Or was it a generic GATT error (`status` code 133)?
    4.  Without the `status` code, debugging is reduced to guesswork, significantly increasing the time required to identify and fix connection stability problems.
*   **Detailed Recommendation:**
    *   **Log the Status Code:** Modify `onConnectionStateChange` to always check the `status` parameter.
    *   If `status != BluetoothGatt.GATT_SUCCESS`, log it as a warning or error using the `debugLogger`.
    *   **Create a Helper Function:** To make logs useful, create a helper function that maps the integer `status` codes to their human-readable names (e.g., `8` -> `"GATT_CONN_TIMEOUT"`). The Android `BluetoothGatt` documentation provides a list of these status codes.
    *   **Example Log:** The new log entry should be highly informative:
        ```
        // Example of an improved log message
        debugLogger.warning(
            DebugLogger.LogType.CONNECTION,
            "Connection state change failed",
            mapOf(
                "device" to device.address,
                "status_code" to status,
                "status_name" to getGattStatusString(status), // From new helper
                "new_state" to getNewStateString(newState)
            )
        )
        ```
    *   **Benefit:** This provides immediate, actionable insight into the root cause of connection failures, which can then be streamed to `nocturned` via the debug characteristic for live debugging.