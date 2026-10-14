# Hermes Companion

Native Android app for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com). Hermes stays on your computer. The phone is a window — and, when you arm it, a pair of hands.

## Setup

You need: Hermes already running on a Mac/Linux box, an Android 12+ phone, and both on the same Tailscale (or LAN).

For **chat and threads**, connect directly to a reachable native Hermes dashboard, usually
**port 9119**. Start the dashboard on the host and expose it through your authenticated
LAN/Tailscale setup or existing reverse proxy. A loopback-only dashboard is not reachable
from the phone simply by entering its port. The Companion plugin is optional for this mode.
Host telemetry also uses the native dashboard first. Optional room support is discovered
separately, so a native host does not show unsupported room creation or fetch plugin room/peer lists.

For **Hands, notification streaming, custom rooms, and the agent console**, use the
plugin relay setup below on **port 9120**. These extensions still need the plugin.

### 1. Computer — install the optional phone integration

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

Origin for the full plugin integration:

```
http://<that-ip>:9120
```

Tap **CONNECT**. Threads and chat should load.

For native chat without the plugin, use your dashboard address instead, for example
`http://<that-ip>:9119` or your HTTPS dashboard URL. Password authentication is supported;
native OAuth remains a separate implementation item.

If the origin is empty or `127.0.0.1`, the app warns you — that address is the phone itself.

### 3. Pair once for phone integration

Pairing is needed for the plugin's phone tools and rooms. Native chat does not need pairing.

1. Phone → **Device** → **PAIR**. A 6-character code appears.
2. On the computer:

```bash
hermes companion approve ABC123
```

Phone shows **PAIRED**. This is stored on the host (`~/.hermes/companion-devices.json`). You do not pair again after a restart.

### 4. Chat

Use Threads / Chat like the desktop. Same profiles, same sessions.

**NEW** reserves a host session immediately; Hermes persists its conversation when the first
message arrives. Tap the chat title for rename, pin, archive, and branch. During an active
turn, entering text changes SEND to **STEER**; an empty draft shows **INTERRUPT**.

Message labels distinguish **queued**, **sending**, **sent**, **failed**, and **checking
delivery**. Sent means Hermes acknowledged the input, even if generating its reply later
fails. Failed sends offer Retry. An uncertain send offers Check and is not automatically
submitted again. Authoritative final responses and reasoning traces are rendered separately.

### 5. Hands (optional)

Only if you want Hermes to tap the phone:

1. **Accessibility** → Installed apps → Hermes Companion (Samsung: Installed apps, not the top-level list).
2. Allow **overlay** and **notifications** when the app asks.
3. Device tab → **ARM**. Overlay: `HERMES HAS HANDS`.

In a Hermes chat: `mobile_status` → `mobile_arm` → `mobile_snapshot` → tap/type → `mobile_disarm`.

Stop anytime: notification **DISARM**, Device **DISARM**, double volume-down, or `mobile_disarm`.

### 6. Rooms (optional)

A **room** puts two or more profiles in one thread with you. Each agent keeps its own memory, tools and model; the host runs their turns and streams them to the phone.

1. Threads → **NEW ROOM** → pick profiles → **CREATE**. Rooms need a **paired** phone (step 3) and the relay in proxy mode (`HERMES_COMPANION_STANDALONE=0`, the systemd default).
2. Post like a normal message. Every participant answers once; `@COD` / `@OPS` chips address one agent. Agents can hand off with `@GLYPH` or stay silent with `PASS`; the round cap (1–4) stops the chatter.
3. **INTERRUPT ALL** stops every in-flight turn.

Same from the host: `hermes companion room create "triage" coder ops` then `hermes companion room post <id> …`. Protocol: [docs/protocol/rooms.md](docs/protocol/rooms.md).

## Everyday commands

```bash
hermes companion list
hermes companion approve CODE
hermes companion revoke DEVICE_ID
hermes companion lanes
hermes companion room list|create|post|history|interrupt|delete
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
| Gestures say `protected_package` | Check the Device tab's custom protected-package rules. |

## Docs

| Doc | What |
|---|---|
| [hermes-plugin/README.md](hermes-plugin/README.md) | Plugin internals, relay, tests |
| [docs/PLAN.md](docs/PLAN.md) | Vision and slices |
| [docs/protocol/operator.md](docs/protocol/operator.md) | Dashboard JSON-RPC |
| [docs/protocol/mobile-control.md](docs/protocol/mobile-control.md) | Device-node frames |
| [docs/protocol/rooms.md](docs/protocol/rooms.md) | Agent rooms (group chat) routes, events, turn policy |
| [docs/reviews/2026-10-13-android-parity-implementation.md](docs/reviews/2026-10-13-android-parity-implementation.md) | Native chat reliability and implemented Android parity |
| [docs/reviews/2026-10-13-phased-implementation-status.md](docs/reviews/2026-10-13-phased-implementation-status.md) | Phased native speech/context rollout, capability evidence and testing gates |
| [docs/reviews/2026-10-14-draft-recovery.md](docs/reviews/2026-10-14-draft-recovery.md) | Encrypted conversation drafts, S22 process recovery and current APK checkpoint |

minSdk 31. Sideload only (not Play Store). Tag `v*` publishes the APK via GitHub Actions (set repo secrets `HERMES_KEYSTORE_B64`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS`, `HERMES_KEY_PASSWORD` for a release-signed build).

`http://` origins are accepted only for LAN / Tailscale hosts (loopback, RFC 1918, `100.64.0.0/10`, `.local`, `.ts.net`, …). A public host must use `https://`.
