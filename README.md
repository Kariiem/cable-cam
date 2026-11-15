# Cable Cam

Use your Android phone's camera as a webcam source in OBS, with no watermark and nothing to install on the PC except OBS.
The phone encodes H.264 and serves it as MPEG-TS over HTTP. OBS's built-in **Media Source** opens the address, and OBS's **Virtual Camera** passes it on to Teams, Zoom, Meet and so on.

## Phone
The screen has a status card with the preview and Start/Stop, a Connect card with the address(es) and copy buttons, and a Camera card (lens, size, frame rate, bitrate, stabilisation, torch). Warnings (VPN active, hot phone, dropped frames, errors) show as coloured banners.

1. Install `app-release.apk` and open Cable Cam. Allow the camera (and notifications).
2. Pick a **Mode**:
   - **Cable (USB):** plug in the cable and turn on **USB tethering** (Settings > Mobile network > Personal hotspot > USB tethering on the HONOR; the app has a button that opens the Mobile network page). Windows 10/11 needs no driver.
   - **Wireless:** same Wi-Fi as the PC, the PC on the phone's hotspot, or Bluetooth tethering (low bitrate, soft picture).
3. Tap **Start**. The app lists the address for each link that exists, e.g. `http://192.168.42.129:8765/cam.ts`.

## OBS
1. Sources > **+** > **Media Source**; uncheck **Local File**.
2. **Input:** the address from the app. **Input Format:** empty.
3. **Network Buffering** 0 MB (OBS's default of 2 MB adds about 2 seconds of delay at 4 Mbit/s), **Reconnect Delay** 1 s, hardware decoding on, "Restart playback when source becomes active" off. **FFmpeg Options** (important for low delay): `probesize=32768 fflags=nobuffer flags=low_delay`. Without `probesize`, FFmpeg reads 5 MB (about 5 seconds of video) before it starts, and that stays as lag: measured here, 6.3 s to the first picture without it and 1.4 s with it.
4. For meetings: **Start Virtual Camera**, then choose "OBS Virtual Camera" in the meeting app.

Open the address without `/cam.ts` in a browser to check that the PC can reach the phone.

### If USB tethering is not available
With adb on the PC and USB debugging on: `adb forward tcp:8765 tcp:8765`, then use `http://127.0.0.1:8765/cam.ts`.

### If OBS or the browser cannot connect
A VPN on the phone (including DNS/ad blockers such as RethinkDNS) blocks connections from the PC. Pause it while streaming: on the HONOR X9c the stream was unreachable from the PC until the VPN was stopped. The app warns when a VPN is active.

### Wi-Fi and tethering
USB tethering can stay on while the phone is on Wi-Fi; the app lists every address it finds. (Not yet checked on this phone: if the USB address does not appear, try turning the phone's Wi-Fi off.)

### If the picture lags
1. In OBS set **Network Buffering** to 0 MB and add `probesize=32768 fflags=nobuffer flags=low_delay` to FFmpeg Options, then switch the source off and on.
2. Use **720p, 30 fps** (the app's recommended setting). 1080p doubles the data and the receiver's work.
3. Check the Wi-Fi band: the app warns when the phone is on **2.4 GHz** (about 72 Mbit/s). Put the phone and the PC on the router's **5 GHz** network. Many ISP routers are 2.4 GHz only (the ZTE H168N is: it has one 2.4 GHz radio, so there is no 5 GHz setting to switch on); then use the **USB cable** mode, or add a dual-band access point to the router.
4. Prefer the **USB cable** mode: Wi-Fi from phone to router to PC crosses the radio twice and is bursty. Measured on a 72 Mbit/s PC Wi-Fi link: frames arrived with a median 26 ms and worst-case 357 ms of jitter after the app was tuned; before, bursts of 0.7-1.7 s were common.
5. The app shows "phone delay" (camera to encoded frame, about 80-100 ms). Anything beyond that is the link or OBS.

## Two formats: the URL decides
The address you type in OBS picks the encoding. Neither is the default and the app does not choose for you: `/cam.ts` is H.264 in MPEG-TS, `/cam.mjpg` is Motion JPEG. The app lists both under each link.

`/cam.mjpg` sends every picture as a standalone JPEG. There are no keyframes, nothing for the receiver to probe, and a slow client is always sent the newest picture, so delay cannot pile up. It uses more bandwidth (about 9 Mbit/s at 720p30 here, against 4 for H.264). In OBS: Media Source, Input `http://<ip>:8765/cam.mjpg`, Network Buffering 0 MB, FFmpeg Options `fflags=nobuffer flags=low_delay` (the `probesize` option is only needed for `/cam.ts`).

Measured with a probe client, 720p30, 20 s each (this phone and PC):

| Link | Format | Result |
| --- | --- | --- |
| Wi-Fi 2.4 GHz | H.264 | arrival jitter p95 345 ms, 12 stalls over 100 ms, largest 1062 ms |
| Wi-Fi 2.4 GHz | MJPEG | 29.4 fps, gaps p95 66 ms, max 103 ms, 1 stall over 100 ms |
| USB tethering | H.264 | arrival jitter p95 9 ms, max 14 ms |
| USB tethering | MJPEG | 30.0 fps, gaps p95 36 ms, max 55 ms, no stall |

Time to the first picture in ffmpeg: H.264 with the old OBS options 6.2 s (2.2 s with `probesize=32768`); MJPEG 0.16 s with just `fflags=nobuffer`.

## Details
- Port 8765, `GET /cam.ts` (video/mp2t), `GET /` (status page). Only private-network and loopback peers are served.
- H.264 High, no B-frames, keyframe every second, MPEG-TS with PCR. Encoding runs only while a client is connected. No audio.
- Automatic bitrate: 4 Mbit/s at 720p30 (8 at 1080p30, +50% at 60 fps), 1.2 Mbit/s over Bluetooth, reduced when the phone gets hot.
- Nothing is dropped for stalls under about a second; the backlog is sent at link speed afterwards. A client more than about a second behind loses frames up to the next keyframe, so latency cannot build up without limit. Keyframes every 2 s (about 100 KB each), requested at most once a second.
- The stream is portrait (720x1280 or 1080x1920), like the phone held upright: each frame is rotated on the GPU before encoding. The app is portrait-locked.
- Every size and frame-rate combination is allowed (720p/1080p, 30/60 fps). Recommended for meetings: **720p, 30 fps, automatic bitrate** (about 4 Mbit/s). 720p60 (about 6 Mbit/s) gives smoother motion; 1080p30 (8) is sharper; 1080p60 (12) is heavy: use the USB cable and a PC that decodes in hardware. If the camera does not offer 60 fps at a size, it runs at the fastest rate it has.
- Keep the screen on while streaming (**Dim screen** saves power). Exempt the app from battery optimisation if MagicOS kills it.

## Build
`gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest` (JDK 21, Android SDK in `local.properties`). `scripts/build-release.sh` runs tests and lint and builds the signed APK into `dist/` (needs a git-ignored `keystore.properties` with storeFile/storePassword/keyAlias/keyPassword).
The test suite includes a check that ffmpeg decodes the muxer's output (skipped when ffmpeg is missing).
