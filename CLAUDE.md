# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Android CDP (Chrome DevTools Protocol-style) — a remote control and automation system for Android devices. Three components:

1. **`android-cdp/server.kts`** — Kotlin script dashboard running on macOS. Discovers all ADB-connected Android devices, shows live screenshots, and provides tap/swipe/key/text input and UI inspection. Serves a web UI on port 9222.

2. **`android-cdp/agent-app/`** — Android app (CDP Agent) that runs directly on the device. Uses MediaProjection for screen capture, runs HTTP server on port 9223, and connects outbound to the relay server via WebSocket for internet-wide access.

3. **`android-cdp/relay/`** — Node.js WebSocket relay server. Bridges Android agent and browser viewer over the internet. Agent connects outbound via WebSocket (works from any network, no port forwarding needed). Serves a web dashboard matching the server.kts UI style. Deployable to Render.com.

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

### Relay Server (local or Render.com)
```bash
cd android-cdp/relay
npm install
node server.js
# Dashboard at http://localhost:8080
# Expose via ngrok: ngrok http 8080
```

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
- Minimal Android app: `MainActivity` + `CdpService` (foreground service) + `RelayClient`
- `CdpService` uses `MediaProjection` + `ImageReader` for screen capture (bypasses FLAG_SECURE via system permission prompt)
- Runs embedded HTTP server on port 9223, same API pattern as server.kts
- `RelayClient` connects outbound via OkHttp WebSocket to relay server — works from any network (WiFi, 4G, etc.)
- Demand-driven: only streams JPEG frames when relay reports viewers > 0
- Auto-reconnect with exponential backoff (1s→2s→4s→...→30s cap)
- Handles relay commands: tap, swipe, key, text, launch, and queries (apps, recents, current)
- Input injection via `Runtime.exec("input tap/swipe/keyevent")`
- Requires Android 10+ (API 29)
- Dependencies: OkHttp 4.12.0 (for WebSocket)

### relay (WebSocket Relay Server)
- Node.js with `ws` library (single dependency)
- WebSocket endpoints: `/agent` (Android devices connect here), `/viewer` (browsers connect here)
- Text frames = JSON commands, binary frames = JPEG screenshots (zero-copy forward, no base64)
- Query/response pattern: viewer sends query (apps, recents, current) → relay forwards to agent → agent responds → relay routes back to viewer
- Serves static dashboard from `public/index.html` — same sidebar layout as server.kts (controls, running apps, all apps with search, log)
- `render.yaml` for one-click deploy to Render.com free tier
- Relay URL configured in `RelayClient.kt` (`DEFAULT_RELAY_URL` constant)

### FLAG_SECURE Handling
- Regular `screencap` returns empty bytes for protected apps (banking apps etc.)
- server.kts can fall back to scrcpy (uses SurfaceControl via app_process)
- agent-app uses MediaProjection which captures after user grants permission once

## Distributing the APK via ngrok
```bash
# Option 1: Copy APK to relay public dir (shares single ngrok tunnel)
cp android-cdp/agent-app/app/build/outputs/apk/debug/app-debug.apk android-cdp/relay/public/
# APK available at: https://<ngrok-id>.ngrok-free.app/app-debug.apk

# Option 2: Separate server
cd android-cdp/agent-app/app/build/outputs/apk/debug
python3 -m http.server 8888 &
ngrok http 8888
# APK URL: https://<ngrok-id>.ngrok-free.app/app-debug.apk
```

## Relay Workflow (Internet Remote Control)
```
Android (any network) ──WebSocket──▶ Relay (localhost/Render.com) ◀──WebSocket── Browser (anywhere)
```

1. Start relay: `cd android-cdp/relay && node server.js`
2. Expose: `ngrok http 8080` → get public URL
3. Update `RelayClient.kt` `DEFAULT_RELAY_URL` to `wss://<ngrok-or-render-url>/agent`
4. Build & install APK, tap Start Server on device
5. Open relay dashboard in browser → device appears → click to control

**Important:** When ngrok URL changes, you must update `DEFAULT_RELAY_URL` in `RelayClient.kt` and rebuild the APK. For production, deploy relay to Render.com for a stable URL.

## SDK Location

Android SDK is at `~/android-sdk` (not the default Android Studio path). Configured in `~/.zshrc` as `ANDROID_HOME`.
