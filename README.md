# Hermes Companion

Native Android companion for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com). Greenfield. The agent stays on the host; the phone is a window and, when armed, a pair of hands.

**Void, signal, type.** Dark-only. One accent. Hairline chrome.

## What it is

1. **Operator surface** — threads, profiles, gateway, live chat, approvals. Hermes is source of truth.
2. **Device node** — opt-in accessibility control. Off by default. Kill-switch in the notification.

## Docs

| Doc | What |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | Vision, architecture, design, Hermes changes, work items |
| [docs/design/vision-board.html](docs/design/vision-board.html) | Visual board (open in a browser) |
| [docs/design/tokens.yaml](docs/design/tokens.yaml) | Color, type, space, motion |
| [docs/protocol/operator.md](docs/protocol/operator.md) | Dashboard JSON-RPC + REST the app actually calls |
| [docs/protocol/mobile-control.md](docs/protocol/mobile-control.md) | Device-node command frames |
| [hermes-plugin/README.md](hermes-plugin/README.md) | Host plugin (pairing + `mobile_*` tools) |

## Layout

```
app/                 Compose application
core-model/          Pure Kotlin models
core-design/         Tokens and chrome — no feature imports
data-local/          Room, encrypted prefs
data-remote/         REST + JSON-RPC
domain/              Use cases
feature-connect/     Host book, login
feature-threads/     Session list
feature-chat/        Transcript, composer, approvals
feature-profiles/    Switcher
feature-gateway/     Status HUD
feature-device/      Arming, overlay, AccessibilityService
hermes-plugin/       Python plugin for the Hermes host
docs/
```

## Build

Android Studio Ladybug+ or:

```bash
./gradlew :app:assembleDebug
```

minSdk 31 (Android 12). Sideload. The control-enabled build is not aimed at Play Store.

Plugin tests (no phone, no Hermes install required):

```bash
python3 -m unittest discover -s hermes-plugin/tests -q
```

## Status

Foundation: tokens, protocol, plugin skeleton, void boot screen. Operator chat and device hands are later slices — see the plan.
