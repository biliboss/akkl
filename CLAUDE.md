# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Android CDP (Chrome DevTools Protocol-style) — a remote control and automation system for Android devices. Two components:

1. **`android-cdp/server.kts`** — Kotlin script dashboard running on macOS. Discovers all ADB-connected Android devices, shows live screenshots, and provides tap/swipe/key/text input and UI inspection. Serves a web UI on port 9222.

2. **`android-cdp/agent-app/`** — Android app (CDP Agent) that runs directly on the device. Uses MediaProjection for screen capture and runs its own HTTP server on port 9223. Works over WiFi without ADB/cable.

## Build & Run

### Dashboard (macOS)
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk"
export ANDROID_HOME="/Users/billiboss/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
kotlin android-cdp/server.kts
# Open http://localhost:9222
```

### Agent App (Android APK)
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"  # AGP requires JDK 17, not 25
export ANDROID_HOME="/Users/billiboss/android-sdk"
cd android-cdp/agent-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Important:** The Android build requires JDK 17. The Kotlin REPL/scripts can use JDK 25, but `./gradlew` must use JDK 17.

### ADB WiFi Connection
```bash
adb tcpip 5555
adb connect <device-ip>:5555
```

## Architecture

### server.kts (Dashboard)
- Single-file Kotlin script — no build system, runs directly with `kotlin`
- Embeds full HTML/CSS/JS dashboard inline as a string template
- Raw HTTP server using `java.net.ServerSocket` (no framework)
- All device interaction via ADB CLI subprocess calls (`ProcessBuilder`)
- Multi-device: discovers devices via `adb devices -l`, all API endpoints take `?serial=` parameter
- API routes under `/api/` (devices, screenshot, tap, swipe, key, text, launch, recents, apps, ui, current)

### agent-app (CDP Agent)
- Minimal Android app: `MainActivity` + `CdpService` (foreground service)
- `CdpService` uses `MediaProjection` + `ImageReader` for screen capture (bypasses FLAG_SECURE via system permission prompt)
- Runs embedded HTTP server on port 9223, same API pattern as server.kts
- Input injection via `Runtime.exec("input tap/swipe/keyevent")`
- Requires Android 10+ (API 29)

### FLAG_SECURE Handling
- Regular `screencap` returns empty bytes for protected apps (banking apps etc.)
- server.kts can fall back to scrcpy (uses SurfaceControl via app_process)
- agent-app uses MediaProjection which captures after user grants permission once

## Distributing the APK via ngrok
```bash
# Serve the APK directory
cd android-cdp/agent-app/app/build/outputs/apk/debug
python3 -m http.server 8888 &

# Expose publicly
ngrok http 8888

# The APK URL will be: https://<ngrok-id>.ngrok-free.app/app-debug.apk
# Open this URL on any Android device to download and install
```

## SDK Location

Android SDK is at `~/android-sdk` (not the default Android Studio path). Configured in `~/.zshrc` as `ANDROID_HOME`.
