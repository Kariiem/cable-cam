# Contributing to Cable Cam

Thanks for helping. The short version is in the README, section [Development](README.md#development): set up the toolchain, run `gradle :app:testDebugUnitTest`, and test on a real phone.

Ground rules:

- **Keep the PC side empty.** No PC software besides OBS, no driver, no admin rights, no inbound port or firewall rule on the PC. The phone is the server and OBS is the client.
- **No third-party libraries, no Compose, no CameraX.** Plain Views, Camera2, MediaCodec, OpenGL ES. Keep the permission list as short as it is.
- **Keep `TsMuxer` and `StreamServer` free of Android classes** so they stay unit-testable on the JVM.
- **Test what you change.** Pure logic gets a JVM unit test. Anything that touches the camera, the network or the encoder needs a run on a phone; say which phone and how you checked (screenshot, `scripts/probe_*.py` output, `ffprobe`).
- **Keep the docs in step.** A change to a URL, the port, a format or a default must update `README.md`, `PLAN.md` and the in-app guide (`ui/Guide.kt`) in the same pull request.
- **Small commits** that each build and pass the tests.

By contributing you agree that your work may be used under either license of the project (MIT or the Unlicense; see `COPYING`).
