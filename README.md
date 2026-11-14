# scrcpy Android

An Android-to-Android viewer for a remote Android device connected over Wi-Fi. The bundled server source is pinned to the upstream [Genymobile/scrcpy `v5.0.1` tag](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1). The app uses a small TCP transport adapter so it can connect directly from an Android device without a desktop `adb forward` process.

## Features

- Full-screen mirroring or a YouTube-style floating player (see below).
- Light and dark settings screens.
- Selectable maximum video size, bitrate and frame rate.
- All scrcpy options in one **green options box**: the original video size/bitrate/frame rate, audio, no-control, wake/screen-power, stay-awake and show-touches settings, plus collapsible groups for every other scrcpy v5.0.1 server option that makes sense on a phone:
  - **Video**: codec (H.264, H.265, AV1), encoder name, codec options, downsize on error, ignore encoder constraints, size alignment.
  - **Audio**: codec (AAC, Opus, FLAC, raw), source (output, playback, microphones, voice call…), audio dup, bitrate, encoder, codec options.
  - **Control & behaviour**: two-way clipboard sync, start an app on connect, screen-off timeout, keep active, time limit, cleanup.
  - **Display & capture**: screen or camera source, display ID, capture orientation (rotate/flip/lock), angle, crop.
  - **Camera**: ID, facing, size, aspect ratio, frame rate, zoom, high speed, torch.
  - **Virtual display**: new display (size/dpi), destroy content, system decorations, IME policy.
  - **Advanced**: server log level.
  Values are validated before anything is sent to the target. Desktop-only flags (recording, window options, USB/OTG, HID keyboard/mouse) are not included.
- The main screen shows the connection state (**Connected to …** / **Disconnected**), and a message appears when a session connects or ends, in full-screen and floating mode.
- **ADB on this device (port 5555)**: shows whether this phone accepts ADB over Wi-Fi and its address, so another device can mirror it. With root, one tap turns it on (optionally across reboots) or off. Without root, Android only allows it from a computer (`adb tcpip 5555`); the app explains the steps.
- Remote audio playback (AAC, Opus, FLAC or raw) for compatible target devices (Android 11 or newer).
- Settings are remembered on the phone.
- The server is only uploaded when the copy on the target differs, so reconnecting is fast.
- Rotation and app switching keep the session alive (the decoder is rebuilt on the new surface and a fresh key frame is requested).
- The floating window runs as a foreground service (with a **Stop** action in its notification) so Android does not stop it in the background.

### Troubleshooting the connection

Open **Logs** at the bottom of the main screen (no root needed on either device):

- **Connection log**: every step of each attempt: TCP connect, ADB authorization, the shell commands sent, and everything the target's shell and the scrcpy server printed.
- **App log**: this app's own logcat, plus the stack trace of the last app crash, if there was one.
- **Target**: tap **Fetch from target** to read diagnostics from the other device over ADB: device and Android version, the installed server file, a scrcpy server self-test, and the relevant part of the target's logcat.

Every section has **Copy** and **Share** buttons. When the floating window shows an error, tap it to open the connection log.

If anything fails, the floating window stays open and shows the exact reason (swipe it away to close).

- **"ADB is not listening on …"**: ADB over TCP is not running on the target. Run `adb tcpip 5555`, or as root `su -c 'setprop service.adb.tcp.port 5555; stop adbd; start adbd'`.
- **"The target hung up during ADB authorization"** / **"Timed out waiting for ADB authorization"**: the target has not accepted this phone's ADB key. Unlock the target, keep **USB debugging** enabled and tap **Allow** on the *Allow USB debugging?* prompt. If no prompt appears, use *Developer options → Revoke USB debugging authorizations* and try again. On a rooted target, tap **Connection keeps failing? Authorize with root…** in the app and run the copied command on the target.
- Android 11+ **Wireless debugging** pairing codes and ports are **not supported**. Use classic ADB over TCP on port 5555 instead.
- **"The scrcpy server stopped: …"**: ADB worked, but the server exited on the target. The message includes the server's last output.

### Floating player

The floating window behaves like YouTube's picture-in-picture popup:

- Rounded card with a shadow that starts small in the bottom-right corner and keeps the remote screen's aspect ratio.
- **Move:** drag it (with remote control on, drag the small handle at the top, or use two fingers). Let go or fling it and it springs to the nearest side of the screen.
- **Resize:** pinch with two fingers, or double-tap (the handle) to switch between small and large.
- **Close:** there is no close button. Drag the window onto the ✕ target at the bottom of the screen, fling it down, or throw it off the left/right edge.
- **Control:** with remote control on, one finger on the video controls the remote device. Tap the handle to show Back / Home / Recents for a few seconds.

The Android viewer supports the common server-side options listed above. Desktop-only features such as recording to a desktop file, camera capture, USB/OTG input, virtual-display management and desktop window controls are not exposed by this Android app.

## Connect

1. Put both devices on a trusted Wi-Fi network.
2. Enable ADB over TCP/IP on the target device at port `5555`. On Android versions that only offer Wireless debugging, first enable the TCP/IP connection using an authorized ADB client; this app does not implement the QR/pairing-code flow.
3. Open the app, enter the target device's IP address (append `:port` if ADB listens on a port other than `5555`), and choose the video settings and options.
4. Choose full-screen or floating-window mode, then tap **Connect** (at the top of the screen).
5. Accept the ADB authorization prompt on the target device.

The viewer uses ADB over the local network and opens its scrcpy media connection on port `7007`. The server only accepts the media connection from the address this phone used for ADB. Only use it on a network you trust; the media stream itself is not encrypted.

Connection errors are reported in the app (for example ADB not listening, authorization not accepted, or the server's own error message if it stops). Press back twice to stop full-screen mirroring.

## Build

Requirements: JDK 17, Android SDK Platform 36, Android build-tools 36.0.0, and internet access to the Android/Gradle dependency repositories.

```sh
./gradlew assembleDebug
```

The Gradle build compiles the v5.0.1 server module and packages its APK-form server artifact as `scrcpy-server.jar` inside the viewer APK. The debug APK is written under `app/build/outputs/apk/debug/`.

`assembleRelease` currently produces an unsigned release APK. Configure a private signing key in your local Gradle environment before distributing a release build; do not commit signing credentials. The **Android APK** GitHub Actions workflow builds and tests both APK variants on pull requests and pushes to `main`. Dispatch it manually with **Publish GitHub release** enabled to attach the unsigned release APK and a debug-signed test APK; the CI-generated debug key is not stable for future updates.

## Licensing

- The upstream scrcpy server is Apache-2.0 licensed; see `server/LICENSE`.
- The Android viewer and remaining project files are licensed under GPL-3.0; see `LICENSE`.
