# scrcpy Android

An Android-to-Android viewer for a remote Android device connected over Wi-Fi. The bundled server source is pinned to the upstream [Genymobile/scrcpy `v5.0.1` tag](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1). The app uses a small TCP transport adapter so it can connect directly from an Android device without a desktop `adb forward` process.

## Features

- Full-screen mirroring or a movable floating window.
- Light and dark settings screens.
- Selectable maximum video size, bitrate and frame rate.
- One **Scrcpy v5.0.1 options** section for video size/bitrate/frame rate, audio, no-control, wake/screen-power, stay-awake and show-touches settings.
- AAC audio playback can be enabled for compatible target devices (Android 10 or newer is recommended).
- Settings are remembered on the phone.
- The server is only uploaded when the copy on the target differs, so reconnecting is fast.
- Rotation, app switching and minimizing the floating window keep the session alive (the decoder is rebuilt on the new surface and a fresh key frame is requested).
- The floating window runs as a foreground service (with a **Stop** action in its notification) so Android does not stop it in the background.

The Android viewer supports the common server-side options listed above. Desktop-only features such as recording to a desktop file, camera capture, USB/OTG input, virtual-display management and desktop window controls are not exposed by this Android app.

## Connect

1. Put both devices on a trusted Wi-Fi network.
2. Enable ADB over TCP/IP on the target device at port `5555`. On Android versions that only offer Wireless debugging, first enable the TCP/IP connection using an authorized ADB client; this app does not implement the QR/pairing-code flow.
3. Open the app, enter the target device's IP address (append `:port` if ADB listens on a port other than `5555`), and choose the video settings and options.
4. Choose full-screen or floating-window mode, then tap **Start mirroring**.
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
