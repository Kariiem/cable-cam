# Cable Cam

Android app that turns the phone's camera into a webcam source for OBS, without a watermark (replaces DroidCam's OBS integration). The phone encodes H.264 and serves it as MPEG-TS over HTTP; OBS's built-in **Media Source** opens `http://<phone-ip>:8765/cam.ts`. OBS's own **Virtual Camera** then feeds Teams/Zoom/Meet. No PC software besides OBS.

Two modes: **Cable (USB)** = USB tethering (fallback: `adb forward tcp:8765 tcp:8765` → `http://127.0.0.1:8765/cam.ts`), and **Wireless** = Wi-Fi LAN / phone hotspot / Bluetooth tethering (PAN). Same server in both; the mode picks the shown address, bitrate preset and instructions.

- Docs: `PLAN.md` (design, OBS setup, phases, status table, risks). Keep it updated when the design or status changes. `README.md` has the user steps.

## Status (2026-11-15)
All phases are implemented and unit-tested; the app runs on the phone and the stream decodes with ffprobe through `adb forward`. Not yet confirmed by the user: USB tethering, Bluetooth PAN, OBS Media Source, Virtual Camera, latency. Over Wi-Fi the stream decodes from WSL, but only with the phone's VPN (RethinkDNS) stopped; with it on, inbound TCP times out (the app warns about VPNs). See PLAN.md for details.

## Layout
- `app/`: package `com.cablecam`; `ui/` (MainActivity, Guide, `CrashReport.kt`), `camera/` (CameraController, FrameRotator = GL pass that feeds the encoder), `stream/` (VideoEncoder, TsMuxer, StreamServer, Access), `net/Links.kt`, `settings/` (Settings, Presets), `service/StreamService.kt`
- `scripts/build-release.sh`: tests + signed APK + checksums into `dist/`

## Toolchain and commands
- See the global notes (`~/.claude/CLAUDE.md`): JDK env vars, `~/opt/android-sdk` via git-ignored `local.properties`, system `gradle` (not `./gradlew`), known-good versions (AGP 8.13.0, Kotlin 2.2.20, SDK 35, JVM 17), adb over Wireless debugging.
- Reference project `~/Code/cable-mouse-app`: build files, `keystore.properties` signing, `scripts/build-release.sh`, `ui/CrashReport.kt`.
- Build/test: `gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest`.
- Stream checks from WSL (on the LAN through mirrored networking; ffmpeg is installed): `ffprobe -v error -show_streams http://<phone-ip>:8765/cam.ts`, `ffplay -fflags nobuffer http://<phone-ip>:8765/cam.ts`.

## Working agreements
- Use git; commit in functional blocks. After every commit the app must build and the tests must pass; verify the committed state, not just the dirty tree. Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Outward-facing actions (push, tag, release) only when the user asks.
- Files for the user to test go in `C:\Users\Public\CableCam\` (`/mnt/c/Users/Public/CableCam/`): only the newest build, unversioned names (`app-release.apk`).
- The user's work PC runs endpoint security software, which reports to an admin; a tethering adapter may be blocked or flagged there. Never suggest evading it.
- Don't claim hardware or OBS behaviour without the user's confirmation; mark things verified in `PLAN.md` only when they are.
- No root, no admin, no drivers, no PC-side software: don't add features that need them (the one-time OBS Virtual Camera registration is OBS's own install step).
- Minimal permissions: camera only at runtime; no audio unless the user asks.

## Conventions
- Kotlin, plain Views built in code, **no Compose**, **Camera2 + MediaCodec (no CameraX)**, no third-party libraries. minSdk 29, target/compile SDK 35, arm64.
- The phone is the server and OBS the client: never require an inbound port or firewall rule on the PC.
- Keep `TsMuxer` pure and JVM-testable.
- Foreground service type `camera`, started from the foreground Activity; keep the screen on while streaming (dim overlay to save power).
- Edge-to-edge at targetSdk 35: apply system-bar insets to the root view; call `window.insetsController` only after `setContentView`.
- Any change to the stream format, port or URL must update `PLAN.md`, the README and the in-app OBS guide.
