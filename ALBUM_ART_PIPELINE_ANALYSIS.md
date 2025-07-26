### **1. Critical: Incorrect Checksum Algorithm**

*   **The Issue:** The `AlbumArtManager.kt` uses an **MD5** checksum, while the `nocturned` service (in `main.go`) calculates and expects a **SHA-256** checksum.
*   **The Impact:** This is a fatal flaw. When `nocturned` receives the album art data, it will calculate a SHA-256 hash. This hash will **never** match the MD5 hash sent by the Android app. As a result, `nocturned` will treat every album art transfer as corrupt and will likely discard the image, even if the data was transferred perfectly.
*   **The Fix:** The checksum algorithm on the Android side **must** be changed to SHA-256 to match the server's expectation.

### **2. Potential Race Condition: Transfer Cancellation**

*   **The Issue:** In `EnhancedBleServerManager.kt`, the `sendAlbumArt` function cancels any existing transfer by calling `albumArtTransferJobs.values.forEach { it.cancel() }`. However, this cancels jobs for *all* connected devices, not just the one for which a new track has started.
*   **The Impact:** If two `nocturned` devices were connected simultaneously, a track change on one could inadvertently cancel the album art transfer for the other. While unlikely, this is not robust.
*   **The Fix:** The cancellation logic should be more granular, canceling the job only for the specific device context that is starting a new transfer.

### **3. Inefficiency: Redundant Data Transfer**

*   **The Issue:** The `nocturned` service is designed to be intelligent. It uses the checksum to check if it already has a copy of the album art, preventing re-downloading the same image. However, the Android app currently has no mechanism to know if `nocturned` already has the image. It sends the full image data every single time a track changes.
*   **The Impact:** This wastes significant bandwidth and time, especially for frequently played tracks.
*   **The Fix:** A simple protocol enhancement is needed:
    1.  After a track change, the Android app should first send a "query" message containing just the `track_id` and `checksum`.
    2.  `nocturned` receives this query. If it already has the image with that checksum, it responds with an "ack_exists" message.
    3.  If the Android app receives "ack_exists", it skips the data transfer entirely. If it receives no response or a "nack_needed" response, it proceeds with the full data transfer as it does now.

### **4. Minor: Hardcoded Image Size and Quality**

*   **The Issue:** The `AlbumArtManager.kt` hardcodes the target image size to 300x300 and the WebP quality to 80.
*   **The Impact:** While these are reasonable defaults, they are not configurable. The `nocturned` device has a high-resolution screen, and a higher-quality image might be desirable.
*   **The Fix:** These values should be moved to `BleConstants.kt` to be easily configurable. The `nocturned` device could even send its desired image size and quality in its `capabilities` message, allowing the Android app to dynamically adjust the compression.