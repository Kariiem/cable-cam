# Cable Cam

Use your Android phone's camera as a webcam source in OBS, with no watermark and nothing to install on the PC except OBS.
The phone encodes H.264 and serves it as MPEG-TS over HTTP. OBS's built-in **Media Source** opens the address, and OBS's **Virtual Camera** passes it on to Teams, Zoom, Meet and so on.

## Phone
1. Install `app-release.apk` and open Cable Cam. Allow the camera (and notifications).
2. Pick a **Mode**:
   - **Cable (USB):** plug in the cable and turn on **USB tethering** (the app has a shortcut button). Windows 10/11 needs no driver.
   - **Wireless:** same Wi-Fi as the PC, the PC on the phone's hotspot, or Bluetooth tethering (low bitrate, soft picture).
3. Tap **Start**. The app lists the address for each link that exists, e.g. `http://192.168.42.129:8765/cam.ts`.

## OBS
1. Sources > **+** > **Media Source**; uncheck **Local File**.
2. **Input:** the address from the app. **Input Format:** empty.
3. **Network Buffering** 0 MB, **Reconnect Delay** 1 s, hardware decoding on, "Restart playback when source becomes active" off. If your OBS has an FFmpeg Options field: `fflags=nobuffer flags=low_delay`.
4. For meetings: **Start Virtual Camera**, then choose "OBS Virtual Camera" in the meeting app.

Open the address without `/cam.ts` in a browser to check that the PC can reach the phone.

### If USB tethering is not available
With adb on the PC and USB debugging on: `adb forward tcp:8765 tcp:8765`, then use `http://127.0.0.1:8765/cam.ts`.

## Details
- Port 8765, `GET /cam.ts` (video/mp2t), `GET /` (status page). Only private-network and loopback peers are served.
- H.264 High, no B-frames, keyframe every second, MPEG-TS with PCR. Encoding runs only while a client is connected. No audio.
- Automatic bitrate: 6 Mbit/s at 720p30 (10 at 1080p30, +50% at 60 fps), 1.2 Mbit/s over Bluetooth, reduced when the phone gets hot.
- A slow client loses frames up to the next keyframe, so latency does not build up.
- Hold the phone in landscape (the app is locked to it); the stream is upright that way.
- Keep the screen on while streaming (**Dim screen** saves power). Exempt the app from battery optimisation if MagicOS kills it.

## Build
`gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest` (JDK 21, Android SDK in `local.properties`). `scripts/build-release.sh` runs tests and lint and builds the signed APK into `dist/` (needs a git-ignored `keystore.properties` with storeFile/storePassword/keyAlias/keyPassword).
The test suite includes a check that ffmpeg decodes the muxer's output (skipped when ffmpeg is missing).
