# Bundled scrcpy server source

This module is based on the Android server from [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy), tag `v5.0.1` (released 2026-10-08). The upstream Android server sources are under `src/main`; their Apache-2.0 license is in `LICENSE`.

The Android viewer has no desktop `adb forward` process, so this repository adds an optional direct TCP transport to the upstream server's normal local-socket transport. The adapter is in `device/DesktopConnection.java`; byte-stream output is supported by `device/Streamer.java` and `control/ControlChannel.java`. `Options` and `Server` pass the app's `tcp_port`/`tcp_client` settings into that transport. The remaining scrcpy server implementation is retained from the tagged upstream source.
