# Plan: Cable Cam: phone camera as an OBS source, over USB or Wi-Fi/Bluetooth, no watermark

## Context
The user used DroidCam + OBS Virtual Camera to turn the phone into a webcam. DroidCam's OBS integration adds a watermark. Cable Cam is a replacement: an Android app that streams the phone's camera to OBS, and OBS's own **Virtual Camera** then feeds it to Teams, Zoom, Google Meet and other apps.

Requirements:
- Two modes: **Cable (USB)**, for PCs with no Wi-Fi/Bluetooth, and **Wireless (Wi-Fi / Bluetooth)**.
- The OBS integration should be as simple as possible: **no PC software besides OBS**, no plugin, no driver, no admin rights. OBS uses its built-in **Media Source** pointed at a URL the app shows.
- No watermark (it's our own app).

Target phone: HONOR X9c, MagicOS 9 (Android 15, API 35), arm64, MediaTek Dimensity 7025-Ultra, front + back cameras (Camera2 hardware level "full"), no root.

## Key design decisions
- **The phone is the server, OBS is the client.** OBS only makes outbound connections, so Windows shows no firewall prompt (allowing an inbound listener needs admin on a work PC).
  - Rejected: OBS listening (`tcp://0.0.0.0:port?listen=1` or `srt://...?mode=listener`): firewall/admin on the PC, and the phone would need to discover the PC's IP. SRT on the phone would also need native libsrt.
- **Every link is an IP link.** One server bound to `0.0.0.0:8765`. The three links only differ in how the IP path exists:
  - **Cable:** USB tethering (RNDIS/NCM). Windows 10/11 has the driver built in, nothing to install. Fallback: `adb forward tcp:8765 tcp:8765`, then OBS uses `http://127.0.0.1:8765/cam.ts` (needs adb on the PC, but gives a stable URL).
  - **Wi-Fi:** phone and PC on the same LAN, or the PC on the phone's hotspot.
  - **Bluetooth:** Bluetooth tethering (PAN); on Windows, "Join a Personal Area Network". Bandwidth is low (about 1-2 Mbit/s in practice), so it gets a low-bitrate preset.
- **Video: H.264 (MediaCodec hardware encoder) in MPEG-TS over plain HTTP:** `http://<phone-ip>:8765/cam.ts`, `Content-Type: video/mp2t`.
  - OBS's Media Source (FFmpeg) plays it directly. TS carries timestamps (raw Annex-B H.264 would not) and can carry AAC audio later.
  - Our own small TS muxer in Kotlin (PAT/PMT/PES/PCR), pure and unit tested.
  - Rejected: MJPEG (high bitrate, unusable over Bluetooth; kept only as a latency fallback), RTSP (more protocol for no gain), WebRTC (huge dependency).
- **Camera2 + MediaCodec, no CameraX**, no third-party libraries. One capture session with two outputs: the encoder's input Surface and a preview SurfaceView.
  - Defaults: back camera, 720p (720x1280 portrait) @ 30 fps, about 6 Mbit/s (USB/Wi-Fi) or 1.2 Mbit/s (Bluetooth). 1080p and 60 fps as options when the camera supports them.
  - Encoder: no B-frames, 1 s keyframe interval, `PARAMETER_KEY_REQUEST_SYNC_FRAME` when a client connects. Continuous-video autofocus; optional video stabilisation.
- **Stream server:** plain `ServerSocket`; an HTTP/1.0 response header followed by an endless TS stream.
  - Each new client gets PAT/PMT + SPS/PPS and starts at the next keyframe.
  - Several clients at once (OBS + a test player).
  - A slow client has a bounded queue; on overflow it drops frames until the next keyframe instead of building up latency.
  - `/` returns a tiny status page, so "can the PC reach the phone?" can be checked in a browser.
- **Encode only while a client is connected** (the preview can keep running), to save battery and heat.
- **Portrait activity and portrait stream** (user request): Camera2 → SurfaceTexture → OpenGL pass (`camera/FrameRotator.kt`) → encoder surface. The SurfaceTexture transform matrix already contains the sensor rotation, so front and back are upright with no extra code (checked by comparing a stream frame with the phone preview). 720p = 720x1280. 60 fps only at 720p.
- **No audio in v1.** OBS Virtual Camera is video-only and meeting apps use the PC's microphone. That keeps the runtime permissions to the camera only. AAC in the same TS is a later option (adds RECORD_AUDIO).
- **Permissions:** CAMERA (runtime; or `adb shell pm grant`), FOREGROUND_SERVICE + FOREGROUND_SERVICE_CAMERA (foreground service of type `camera`, started from the foreground Activity), INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, POST_NOTIFICATIONS, WAKE_LOCK. Nothing else.
- **Keep alive:** `FLAG_KEEP_SCREEN_ON` while streaming, plus a "dim screen" black overlay to save power. The foreground service keeps MagicOS from killing the app; the setup guide links to the battery-optimisation exemption (and the adb `cmd deviceidle whitelist +com.cablecam` / `cmd appops set com.cablecam RUN_ANY_IN_BACKGROUND allow` trick).

## Architecture
```
Android app (Kotlin)                                          PC (Windows/Linux/macOS)
 Camera2 ──┬─→ preview SurfaceView                             OBS Studio
           └─→ MediaCodec H.264 ─→ TsMuxer ─→ StreamServer       Media Source: http://<phone-ip>:8765/cam.ts
                                               0.0.0.0:8765  ←── (USB tethering | Wi-Fi | Bluetooth PAN | adb forward)
 StreamService (foreground, type camera)                         → Start Virtual Camera → Teams / Zoom / Meet
```

## App modes (UI)
Header button **Mode**: *Cable (USB)* / *Wireless (Wi-Fi / Bluetooth)*. The server is the same in both; the mode picks which address is shown, the bitrate preset and the instructions.
- **Cable:**
  - Detects the tethering interface (`rndis0` / `ncm0` / `usb0` via `NetworkInterface`, plus `ConnectivityManager` link properties) and shows its URL in large text with a Copy button.
  - "Open tethering settings" button (the Settings `TetherSettings` component, falling back to `ACTION_WIRELESS_SETTINGS`). The app can't turn tethering on itself; that needs a privileged permission.
  - A collapsible "adb forward" alternative showing the `127.0.0.1` URL.
- **Wireless:** lists the addresses that exist (Wi-Fi `wlan0`; hotspot `ap0` / `wlan1` / `swlan0`; Bluetooth PAN `bt-pan`), each with its URL. Bluetooth shows the low-bitrate preset and an "enable Bluetooth tethering" hint.
- Other controls: front/back camera, resolution, fps, bitrate, torch, start/stop, status line (clients connected, fps, kbit/s). Settings persist in SharedPreferences. Dark mode via `Theme.DeviceDefault.DayNight` + `values-night`.

## OBS setup (the whole PC side; also goes into the README and the in-app guide)
1. OBS → Sources → **+** → **Media Source** → uncheck **Local File**.
2. **Input:** the URL from the app, e.g. `http://192.168.x.y:8765/cam.ts`. **Input format:** empty (or `mpegts`).
3. **Network buffering** 0 MB, **Reconnect delay** 1 s, **Use hardware decoding when available** on, **Restart playback when source becomes active** off. If this OBS version has an **FFmpeg Options** field: `fflags=nobuffer flags=low_delay` (to verify that the field exists and helps).
4. For Teams/Zoom/Meet: OBS → **Start Virtual Camera**, then choose "OBS Virtual Camera" in the meeting app. On Windows the virtual camera needs a one-time admin step to register it (the normal OBS installer does it; the scoop package prints a note about running its register script as admin). OBS by itself needs no admin.

Optional later convenience: the app exports an OBS scene-collection JSON with the source preconfigured (the URL still needs editing when the tether IP changes). Only if manual setup proves annoying.

## Status (2026-11-15)
Legend: **verified** = confirmed by the user on real hardware; **built** = implemented, builds, unit-tested where possible, not confirmed on hardware; **todo** = not done.

| Phase | State | Notes |
| --- | --- | --- |
| 0. Hardware spikes | **partly** | Stream over `adb forward` **verified by me, not by the user in OBS**: ffprobe from WSL decodes 1080p H.264 from the phone. Over Wi-Fi from WSL (192.168.1.3 to 192.168.1.2) TCP 8765 timed out while RethinkDNS's VPN (`tun0`) was on, and worked (status page, ffprobe 1080p H.264) once the user stopped it. USB tethering, Bluetooth PAN, OBS and latency: **todo** |
| 1. Skeleton | **built** | |
| 2. Camera → encoder → TS → HTTP | **built** | unit tests incl. ffmpeg decode; camera + encoder + server run on the phone (screenshot, ffprobe via adb forward) |
| 3. Cable mode | **built** | link detection, URL/copy, tethering shortcut, adb forward URL, dim overlay; tethering itself not tried |
| 4. Wireless mode | **built** | Wi-Fi/hotspot/Bluetooth links, per-link bitrate; not tried on hardware |
| 5. UI / settings | **built** | camera, size, fps, bitrate, stabilisation, torch, guide; no per-setting hardware checks |
| 6. Robustness | **built** | camera retry, encoder fallback formats, thermal back-off, encoder linger; none tested under stress |
| 7. Release | **built** | `scripts/build-release.sh`, README; signed APK to `C:\Users\Public\CableCam\` |

## Small-step plan
### Phase 0: spikes on hardware (before the real app)
1. **USB tethering:** enable it on the phone; record the interface name and IPs on both sides (`adb shell ip addr`, Windows `ipconfig`). Check the PC can reach a port on the phone (a tiny test server, or the Phase 2 build). Check whether it works with mobile data off. Repeat on the work PC.
2. **Bluetooth tethering:** can Windows join the phone's PAN, and what throughput does it get.
3. **Minimal stream:** Camera2 → MediaCodec → TS → HTTP, played with `ffprobe`/`ffplay` from WSL (on the LAN through mirrored networking), then in OBS on Windows. Measure glass-to-glass latency by filming a stopwatch on the PC screen.

### Phase 1: skeleton
Gradle project following `~/Code/cable-mouse-app` conventions (AGP 8.13.0, Kotlin 2.2.20, compile/target SDK 35, minSdk 29, JVM 17, arm64), package `com.cablecam`, plain Views built in code, reuse `ui/CrashReport.kt`. `git init`.

### Phase 2: pipeline
- `camera/CameraController.kt`: Camera2 session, preview + encoder surfaces, front/back, fps range, AF/stabilisation.
- `stream/VideoEncoder.kt`: MediaCodec H.264, capability query (`MediaCodecInfo` profiles/levels/bitrate modes) with fallbacks, sync-frame requests.
- `stream/TsMuxer.kt`: pure Kotlin. JVM unit tests: 188-byte packets, PAT/PMT CRC32, PES header and PTS encoding, PCR, continuity counters, adaptation-field stuffing. Check real output with `ffprobe`.
- `stream/StreamServer.kt`: HTTP, client fan-out, per-client bounded queue and keyframe drop policy, status page.
- `service/StreamService.kt`: foreground service type `camera`, notification with Stop.

### Phase 3: Cable mode
Tethering interface detection, URL display and copy, tethering settings shortcut, adb-forward instructions, keep-screen-on + dim overlay.

### Phase 4: Wireless mode
Wi-Fi / hotspot / Bluetooth PAN detection, bitrate presets per link.

### Phase 5: UI / settings
Camera switch, resolution / fps / bitrate, torch, status line, in-app OBS guide, dark mode, launcher icon.

### Phase 6: robustness
OBS reconnects (reconnect delay), camera errors and disconnects, encoder restart on settings change, thermal status listener (lower bitrate/fps when hot), battery-whitelist guidance.

### Phase 7: release
`scripts/build-release.sh` (tests + signed APK + checksums), README with the OBS steps. Newest APK to `C:\Users\Public\CableCam\app-release.apk`.

### Later / optional
AAC audio in the TS, portrait output, zoom/exposure controls, MJPEG endpoint for an OBS Browser Source (latency fallback), OBS scene-collection export.

## Risks / unknowns (test early)
- **VPN on the phone blocks inbound connections** (seen with RethinkDNS on the user's phone). The app warns when a `tun*`/`ppp*`/`wg*` interface is up.
- **USB tethering on the HONOR X9c:** interface name; subnet (Android 11+ randomises tethering subnets, so `192.168.42.x` is not guaranteed); whether it can be enabled without mobile data; whether the PC can reach the phone's port.
- **Work PC:** a tethering network adapter may be blocked by policy or flagged by the security software (endpoint security software, which reports to an admin). Test there first; never try to evade it. adb forward is the fallback, and may also be unavailable there.
- **Internet routed through the phone:** with mobile data on, Windows may send its traffic over the tethered link (metered). Mitigation: mobile data off while tethered, or document it. Changing interface metrics needs admin.
- **Changing tether IP:** the OBS URL must be edited when it changes; adb forward gives a stable URL.
- **Latency of the OBS Media Source** (FFmpeg buffering): target < 300 ms glass-to-glass. If too slow: tune keyframe interval and TS flushing, then fall back to MJPEG + Browser Source.
- **Bluetooth PAN:** whether MagicOS offers Bluetooth tethering, Windows support and throughput on the user's PCs; may only be usable at low resolution.
- **MagicOS:** killing the camera or foreground service, thermal throttling during long calls, camera access with the screen off (we keep the screen on with a dim overlay instead).
- **MediaTek encoder quirks:** supported profiles/levels and bitrate modes; query capabilities and fall back.

## Verification
- Unit tests: `gradle :app:testDebugUnitTest` (TS muxer, interface/URL helpers, presets).
- Stream from WSL: `ffprobe -v error -show_streams http://<phone-ip>:8765/cam.ts` and `ffplay -fflags nobuffer http://<phone-ip>:8765/cam.ts`.
- Phone: install over adb Wireless debugging, `pm grant com.cablecam android.permission.CAMERA` and `POST_NOTIFICATIONS`, `am start`, screenshots with `adb exec-out screencap -p`.
- Hardware and OBS behaviour (USB tethering, Bluetooth, the OBS source, Virtual Camera in Teams/Zoom/Meet, latency) is marked **verified** only when the user confirms it.
