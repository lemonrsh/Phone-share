# Phone Share

![Phone Share icon](docs/icon-512.png)

Phone Share (formerly "scrcpy Android") is an Android-to-Android viewer for a remote Android device connected over Wi-Fi. The bundled server source is pinned to the upstream [Genymobile/scrcpy `v5.0.1` tag](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1). The app uses a small TCP transport adapter so it can connect directly from an Android device without a desktop `adb forward` process.

App ID (package name): **`com.lemonrsh.phoneshare`** (from 5.0.1-r20; earlier versions used `org.las2mile.scrcpy`, so r20 installs as a new app next to them: uninstall the old one, and allow this phone again on the other device the first time, because the new app has a new ADB key).

## Editions

Every release is built from the same source in two editions (Gradle product flavors). They share the app id and signing key, so either one installs over the other and keeps your settings.

| Edition | Release tag | What it adds | APK size |
|---|---|---|---|
| **standard** | `v5.0.1-rNN` | Connect to and control other phones by IP address. No Cloudflare. | small |
| **cloudflared** | `v5.0.1-rNN-cloudflared` | Everything in standard, plus **Share this phone over the internet** and connecting with share codes. Bundles `cloudflared` (`app/src/cloudflared`). | about 40 MB larger |

## Features

- **Start-up floating window question**: if Android's "Display over other apps" permission is missing, the app asks once per launch: **Go to settings** (opens the permission page for Phone Share) or **Do it later**. It is needed for the floating window and auto-float.
- **Start-up ADB question** (after the floating window question): when ADB over Wi-Fi (port 5555) is off on this phone, the app asks once per launch: **Enable ADB** (one tap with root, otherwise it shows how to turn it on) or **Nah, I just want to control another phone**, which just opens the app. Tick **Don't ask again** to stop the question.
- **"Connected to this phone" notification on the shared phone** (r21): while another device mirrors this phone, this phone shows a notification that stays for the whole session ("Pixel 8 is connected to this phone · It can see and control your screen"), with a **Disconnect** button that ends the session right away. It goes away by itself when the session ends. Needs Phone Share r21+ installed on both phones (no root); on Android 13+ allow notifications (the app asks when you turn on ADB 5555 or Cloudflare sharing).
- **"Phone Share is running" notification** with a **Stop** button whenever a session runs, full screen or in the floating window. On Android 13+ the app asks for the notification permission the first time you tap Connect.

- Full-screen mirroring or a YouTube-style floating player (see below).
- **Settings page**: the main screen only has the connection status, **Connect**, the address box and sharing. The **gear button** in the top right corner opens **Settings** with ADB on this device, Appearance and window, the green **Normal** and blue **Advanced** settings, and Logs. The back arrow or Back returns to the main screen.
- Green interface (light and dark), so the white status-bar icons are always readable.
- **Clear** button next to the address box empties the IP address or share code.
- **Recommended defaults**: 480p (854x480), 1 Mbps, 30 fps, H.264, audio on and playing on both devices (`--audio-dup`). They are applied once when updating to r14; after that every change is remembered. **↺ Use recommended settings** in the Normal box restores them at any time.
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
- **Auto floating window**: while mirroring full screen, pressing Home, opening Recents or switching apps moves the live session into the floating window without reconnecting (can be turned off under Appearance and window).
- Options are split into a green **Normal settings** box and a blue **Advanced settings** box. Both start collapsed; tap a title to show or hide its options. Normal holds the everyday settings, including **Play audio on both devices** (`--audio-dup`) and **Keep the remote screen awake** (`--keep-active`).
- Debug APKs are signed with a fixed project key (`app/debug.keystore`), so updates install over the previous version and keep all saved settings.
- The main screen shows the connection state (**Connected to …** / **Disconnected**), and a message appears when a session connects or ends, in full-screen and floating mode. While connected, the top button turns into **Disconnect**.
- **Share this phone over the internet (cloudflared edition only)**: on the phone to be mirrored, tap **Start sharing**. The app starts a bundled `cloudflared` [quick tunnel](https://try.cloudflare.com) (no Cloudflare account) and shows a share code such as `blue-river-cat.trycloudflare.com#482913` with **Copy code** and **Send…** buttons. On the other phone, paste the code into the address box and tap **Connect**; it works from any network, no same Wi-Fi or VPN needed. The code changes every time sharing starts, and the 6-digit PIN protects it.
  - How it works: the shared phone runs a PIN-checked WebSocket bridge on 127.0.0.1 to its own ADB (5555) and scrcpy (7007) ports, and cloudflared publishes it. The controlling phone opens each connection as a `wss://` WebSocket and needs nothing extra.
  - Needs ADB over Wi-Fi on port 5555 on the shared phone (`adb tcpip 5555`, or root); the app says so if it is off. Tap **Allow** on the shared phone the first time.
  - Quick tunnels are Cloudflare's free testing service: no uptime guarantee, and video goes through Cloudflare, so expect more delay than on the same Wi-Fi.
  - `cloudflared` is built by CI as a static Linux binary (arm64-v8a, armeabi-v7a, and x86_64 for Waydroid/emulators, where translated ARM binaries crash; no Android linker or Bionic dependency, so it starts on every Android version) and bundled as `libcloudflared.so`; it is not in the repository. Android has no `/etc/resolv.conf`, so the app passes the phone's DNS servers to it (`tools/cloudflared/android_dns.go`).
- **Who is mirroring this phone**: when another phone with this app (r11 or newer) mirrors this phone, and this phone has the app too, the top of its main screen shows **"<phone name> is connected to this phone"** with the controller's IP and start time, plus a red **Disconnect** button that ends that session. No root is needed: the scrcpy server reports itself on a loopback-only port (127.0.0.1:27200–27204), so only apps on this phone can see it. Sessions started by desktop scrcpy or older versions of this app are not shown.
- **Set up everything with root** (Settings › ADB on this device, rooted phones only): one tap and one root prompt turn on developer options, ADB and ADB over Wi-Fi on port 5555 (kept after reboot), allow the floating window and notifications, remove battery restrictions and stop Android 11+ from forgetting allowed phones after 7 days. The box always shows the live status (checked each time Settings opens), so after a force stop or reboot it says **Everything is set up** instead of asking again; the button then reads **Run setup again**. ADB authorization itself is never bypassed.
- **ADB on this device (port 5555)**: shows whether this phone accepts ADB over Wi-Fi and its address, so another device can mirror it. With root, one tap turns it on (optionally across reboots) or off. Without root, Android only allows it from a computer (`adb tcpip 5555`); the app explains the steps.
- Remote audio playback (AAC, Opus, FLAC or raw) for compatible target devices (Android 11 or newer).
- Settings are remembered on the phone.
- The server is only uploaded when the copy on the target differs, so reconnecting is fast. If the target has the same version of this app installed, the server is copied out of that app on the target instead of being uploaded (much faster over Cloudflare).
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
./gradlew assembleStandardDebug      # standard edition
./gradlew assembleCloudflaredDebug   # cloudflared edition (needs app/src/cloudflared/jniLibs, built by CI)
```

The Gradle build compiles the v5.0.1 server module and packages its dex files as `scrcpy-server.jar` inside the viewer APK. APKs are written under `app/build/outputs/apk/<edition>/<debug|release>/`.

`assembleRelease` currently produces an unsigned release APK. Configure a private signing key in your local Gradle environment before distributing a release build; do not commit signing credentials. The **Android APK** GitHub Actions workflow builds and tests both APK variants on pull requests and pushes to `main`. Dispatch it manually with **Publish GitHub release** enabled to publish two releases from the same commit: `<tag>` (standard edition, marked latest) and `<tag>-cloudflared`, each with an unsigned release APK and a debug APK signed with the fixed project key.

## Licensing

- The upstream scrcpy server is Apache-2.0 licensed; see `server/LICENSE`.
- The Android viewer and remaining project files are licensed under GPL-3.0; see `LICENSE`.
