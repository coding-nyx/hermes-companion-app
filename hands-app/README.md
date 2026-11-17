# Hands (`app.hermes.hands`)

Standalone Android control service and UI. Hands executes phone automation, screen observation, telephony, SMS, notifications, files, and system tools independently of any specific AI model, gateway, or framework.

## Features

- **iOS Frosted Glass Theme**: Translucent multi-layer acrylic cards, specular lighting, chamfered borders, and clean typography.
- **Agent Screen Support**: Run tasks on an isolated virtual display (via Shizuku on Android 14+) while using your physical screen normally.
- **Encrypted Remote API**: Native HTTPS JSON server on port `8766` with self-signed TLS identities and SHA-256 fingerprint verification over LAN / Tailscale.
- **Local Loopback API**: High-speed loopback HTTP server on `127.0.0.1:8765` for Companion and ADB tunnels.
- **In-App Pairing Workflow**: Share connection instructions directly to your agent; review and approve connection requests in-app before granting revocable bearer tokens.
- **Arm-First Security Boundary**: Physical phone state monitoring, protected app blocking, and explicit controller lease management.

## Build and Install

```sh
# Build debug APK
./gradlew :hands-app:assembleDebug

# Sideload to connected phone
adb install -r hands-app/build/outputs/apk/debug/hands-app-debug.apk
```

## Secure Settings Grant (Recommended)

To enable automated Accessibility service recovery and secure system settings management without manual system-settings handoffs:

```sh
adb shell pm grant app.hermes.hands android.permission.WRITE_SECURE_SETTINGS
```

## Running Unit and Render Tests

```sh
./gradlew :hands-app:testDebugUnitTest
```
