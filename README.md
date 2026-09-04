# Hermes Companion

Native Android app for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com). Hermes stays on your computer. The phone is a window — and, when you arm it, a pair of hands.

## Setup

You need: Hermes already running on a Mac/Linux box, an Android 12+ phone, and both on the same Tailscale (or LAN).

### 1. Computer — install the plugin

From this repo:

```bash
./hermes-plugin/install.sh
# or: hermes plugins install coding-nyx/hermes-companion-app/hermes-plugin
#     hermes plugins enable hermes-companion
```

That copies the plugin into `~/.hermes/plugins/hermes-companion` and enables it.

Start a **new** Hermes session (or restart the dashboard). The plugin opens **port 9120** so the phone can reach Hermes.

Find the address the phone should use:

```bash
tailscale ip -4        # preferred
# or: hostname -I      # LAN
```

### 2. Phone — install the app

- **Release:** sideload `HermesCompanion-*.apk` from [Releases](https://github.com/coding-nyx/hermes-companion-app/releases).
- **From source:** `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (R8-minified). It is debug-signed unless you provide a key via `HERMES_KEYSTORE_PATH` / `HERMES_KEYSTORE_PASSWORD` / `HERMES_KEY_ALIAS` / `HERMES_KEY_PASSWORD` env vars or a gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) at the repo root.

Allow unknown sources, install, open the app.

Origin (not dashboard `:9119`):

```
http://<that-ip>:9120
```

Tap **CONNECT**. Threads and chat should load.

If the origin is empty or `127.0.0.1`, the app warns you — that address is the phone itself.

### 3. Pair once

1. Phone → **Device** → **PAIR**. A 6-character code appears.
2. On the computer:

```bash
hermes companion approve ABC123
```

Phone shows **PAIRED**. This is stored on the host (`~/.hermes/companion-devices.json`). You do not pair again after a restart.

### 4. Chat

Use Threads / Chat like the desktop. Same profiles, same sessions.

### 5. Hands (optional)

Only if you want Hermes to tap the phone:

1. **Accessibility** → Installed apps → Hermes Companion (Samsung: Installed apps, not the top-level list).
2. Allow **overlay** and **notifications** when the app asks.
3. Device tab → **ARM**. Overlay: `HERMES HAS HANDS`.

In a Hermes chat: `mobile_status` → `mobile_arm` → `mobile_snapshot` → tap/type → `mobile_disarm`.

Stop anytime: notification **DISARM**, Device **DISARM**, double volume-down, or `mobile_disarm`.

## Everyday commands

```bash
hermes companion list
hermes companion approve CODE
hermes companion revoke DEVICE_ID
hermes companion lanes
```

In chat: `mobile_status` `mobile_arm` `mobile_disarm` `mobile_snapshot` `mobile_click` `mobile_type` `mobile_swipe` `mobile_scroll` `mobile_press` `mobile_open_app` `mobile_apps` `mobile_wait` `mobile_screenshot`.

## If something fails

| Symptom | Fix |
|---|---|
| Connect times out | Origin must be `:9120` on the Hermes host, not `:9119`. Plugin session must be running. |
| `127.0.0.1` / localhost | That's the phone. Use Tailscale/LAN IP. |
| PAIR does nothing / WAITING forever | Plugin not loaded. New Hermes session, then `hermes companion approve`. |
| `hermes companion` unknown | Plugin CLI loads on the next session after `install.sh`. |
| ARM disabled | Accessibility + notifications must be on. Hermes cannot grant Accessibility. |
| Gestures say `disarmed` | `mobile_arm` or Device **ARM**. |
| Gestures say `protected_package` | Banking / authenticator / Settings / System UI are blocked. |

## Docs

| Doc | What |
|---|---|
| [hermes-plugin/README.md](hermes-plugin/README.md) | Plugin internals, relay, tests |
| [docs/PLAN.md](docs/PLAN.md) | Vision and slices |
| [docs/protocol/operator.md](docs/protocol/operator.md) | Dashboard JSON-RPC |
| [docs/protocol/mobile-control.md](docs/protocol/mobile-control.md) | Device-node frames |

minSdk 31. Sideload only (not Play Store). Tag `v*` publishes the APK via GitHub Actions (set repo secrets `HERMES_KEYSTORE_B64`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS`, `HERMES_KEY_PASSWORD` for a release-signed build).

`http://` origins are accepted only for LAN / Tailscale hosts (loopback, RFC 1918, `100.64.0.0/10`, `.local`, `.ts.net`, …). A public host must use `https://`.
