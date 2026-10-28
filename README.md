<p align="center">
  <img src="docs/assets/readme-overview.svg" alt="Hermes Companion — Your self-hosted agent, on Android." width="100%" />
</p>

# Companion

A native Android companion for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com/). Browse threads, chat with profiles, use agent rooms and explicitly arm supported phone-control tools. The agent runs on your host; this app is its mobile interface.

This branch contains the **Companion multi-agent prototype**: explicit Hermes, OpenClaw and Codync connections, a shared bot/chat workspace and an isolated interactive sample. See the [prototype guide, capability matrix and rollout gates](docs/companion-prototype.md). Live mixed-runtime rooms and OpenClaw/Codync phone tools remain later rollout stages.

[Setup](#setup) · [Build](#build-from-source) · [Protocol docs](#documentation) · [Related projects](#related-projects)

## What it includes

| Area          | Features                                                   |
| ------------- | ---------------------------------------------------------- |
| Conversations | Threads, profiles, streamed chat and coding-agent sessions |
| Rooms         | Multi-profile conversations, mentions and interruption     |
| Pairing       | Host approval of a device pairing code                     |
| Device tools  | Status, snapshots and gestures when armed and permitted    |
| Notifications | Optional notification stream with configurable routing     |

This repository includes its own `hermes-plugin/` relay. It is distinct from the older archived `hermes-companion` prototype and the separate mailbox-oriented web/plugin projects.

## Setup

You need Android **12+**, a running Hermes host and a reachable host address over your intended LAN or private network.

For **native chat, threads and host telemetry**, connect to a reachable Hermes dashboard, usually port **9119**, or its HTTPS reverse-proxy URL. A loopback-only dashboard needs your existing authenticated LAN/Tailscale or proxy exposure before the phone can reach it. Native chat does not require the plugin or device pairing. Password authentication is supported; native OAuth remains pending.

For **Hands, notification streaming, custom rooms and the agent console**, use the optional bundled relay:

1. Inspect and install the bundled relay on the Hermes host:

   ```sh
   ./hermes-plugin/install.sh
   ```

2. Start a new Hermes session or restart its configured gateway/relay. The companion relay uses port **9120**; use its origin in the app for the full plugin integration. Keep the service behind your intended private network.
3. Install an APK from [Releases](https://github.com/coding-nyx/hermes-companion-app/releases), when available, or build it below. Enter the relay origin in the app. `127.0.0.1` on a phone means the phone itself.
4. Pair once for phone integration. You can run `hermes companion qr` on the host to scan/tap a pairing link, or open **Device → Pair** on the phone. Approve the displayed device code on the host:

   ```sh
   hermes companion approve <PAIRING_CODE>
   ```

5. Chat through Threads/Chat. Device control is optional: grant the required Android accessibility/overlay/notification permissions, then explicitly **Arm** it. Use **Disarm** to stop control. Review Device-tab protection rules; do not assume a built-in protected-app list.

**New** reserves a host session; Hermes persists its conversation when the first message arrives. Tap the chat title for rename, pin, archive and branch. A running turn offers **Steer** with text or **Interrupt** with an empty draft. Delivery labels distinguish queued, sending, sent, failed and uncertain input: **Sent** means acknowledged by Hermes, **Retry** follows an explicit rejection, and **Check** reads history without automatically resubmitting an uncertain send.

The rich-text composer keeps formatting visible and supports expanded draft editing. Tap the center **Voice** dock to start a continuous call in the selected conversation; long-press it to dictate. Hermes speech is the default, with explicit Android speech compatibility and host-configured realtime modes. Calls use a foreground microphone service through backgrounding; profile speech settings apply across Hermes clients on the next call. Model and reasoning controls apply to the selected conversation. See the [voice protocol and lifecycle](docs/protocol/voice.md) and [validation and current provider blockers](docs/reviews/2026-10-21-continuous-voice.md).

### Cyberpunk command center preview

The redesign adds a cyan/violet component system, static-by-default glitch accents, direct Gateway navigation, explicit Host/Agent context controls, and easier New chat, Model, Voice and permission access. Light/dark and enlarged-text Compose previews are included in the [implementation review](docs/reviews/2026-10-20-cyberpunk-redesign.md), alongside verification and remaining physical-device acceptance.

Native completion and approval alerts are local notifications for known sessions on the connected host/profile, not durable push when Android stops the app. Optional ntfy relay subscriptions reconnect and replay safely. Existing topic users must re-save their topic for the exact host address, including any proxy path. See the [gateway integration audit](docs/design/gateway-integration-audit.md).

The evaluation APK uses **Companion Prototype** (`app.hermes.companion.preview`) so it can coexist with the existing app. It has separate data, permissions and pairing, with no automatic migration. Keep the existing app installed; shared Hermes links may show an Android chooser. The preview is debug-signed, not a production update.

### Previous grouped interface checkpoint

The current interface uses consistent typography and spacing, grouped settings, focused detail sheets and system light/dark appearance across the existing app. Chat transcripts stay open, with vertical composer actions and the center Voice dock available through navigation.

The Grouped APK is installed on the S22 and its checksum is verified. The clean Companion publication snapshot passes **602 automated tests**, debug and minified release builds, and release lint with no errors (52 warnings). **S22 interaction checks, manual accessibility review and spoken recognition/conversation tests remain deferred.** This update does not enable incoming calls or retire remaining plugin services. Native session reads can recover a rotated loopback token once, within the connected host and proxy prefix; chat submissions are never replayed by this recovery. Exact scope, validation results, APK hashes and local rollback details are recorded in the [Grouped implementation checkpoint](docs/reviews/fixtures/2026-10-15-grouped-implementation.json).

See the [operator protocol](docs/protocol/operator.md) and [mobile-control protocol](docs/protocol/mobile-control.md) for the host/device contract.

## Build from source

Use JDK **17**, an Android SDK with API **35**, and the checked-in Gradle wrapper. Dependencies and versions are pinned in `gradle/libs.versions.toml`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

To build the side-by-side ARM64 evaluation app:

```sh
HERMES_VERSION_NAME=0.3.0-cyberpunk-preview.1 HERMES_VERSION_CODE=3 \
  ./gradlew test :app:lintDebug :app:assembleDebug \
  -PhermesPreview=true -PhermesDeviceAbi=arm64-v8a --no-parallel --max-workers=1
```

Its APK is `app/build/outputs/apk/debug/app-debug.apk`. Omitting `hermesPreview` preserves the stable application ID and name. Preview Firebase configuration, if supplied, must explicitly match the preview package; the stable client's configuration is not reused.

The release APK is under `app/build/outputs/apk/release/`. Without a configured release keystore, the build falls back to the debug signing key and warns; that is a sideload/testing artifact, not evidence of a production-signed release.

Provide signing values through `HERMES_KEYSTORE_PATH`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS` and `HERMES_KEY_PASSWORD`, or an ignored `keystore.properties` file. Never commit a signing key or password.

## Architecture

```mermaid
flowchart LR
  Phone[Android app] --> Hermes[Native dashboard and sessions]
  Phone --> Relay[Optional companion relay]
  Relay --> Hermes[Hermes profiles and sessions]
  Relay --> Rooms[Agent rooms]
  Relay --> Device[Paired and armed device tools]
```

Feature modules cover connect, threads, chat, profiles, gateway and device UI. Shared modules own models, design, domain and local/remote data. See `settings.gradle.kts` for the module list.

## Documentation

- [Desk Companion V1](desk-app/README.md) — separate Android 16+ tablet app with Hermes, native ChatGPT OAuth, native Grok through a private host OAuth broker, and swappable speech.

- [Plugin implementation and setup](hermes-plugin/README.md)
- [Product plan](docs/PLAN.md)
- [Cyberpunk redesign plan](docs/design/cyberpunk-redesign.md)
- [Cyberpunk implementation and verification](docs/reviews/2026-10-20-cyberpunk-redesign.md)
- [Gateway integration coverage and limits](docs/design/gateway-integration-audit.md)
- [Operator protocol](docs/protocol/operator.md)
- [Mobile control](docs/protocol/mobile-control.md)
- [Rooms](docs/protocol/rooms.md)
- [Agent sessions](docs/protocol/agent-sessions.md)
- [Contributor/agent conventions](AGENTS.md)
- [Current Conduit parity backlog](docs/reviews/2026-10-13-current-conduit-parity-backlog.md)
- [Grouped interface validation and rollback](docs/reviews/fixtures/2026-10-15-grouped-implementation.json)
- [Prior reliability validation and rollback](docs/reviews/2026-10-15-closeout.md)

## Related projects

- [Archived Android prototype](https://github.com/coding-nyx/hermes-companion) — historical work.
- [Web companion](https://github.com/coding-nyx/hermes-companion-web) and [platform plugin](https://github.com/coding-nyx/hermes-companion-plugin) — a separate mailbox/node implementation; pairing protocols and ports are not interchangeable.

No top-level project license file is present. This README describes source behavior; it does not certify a connected host, current release signing or hardware permissions.

Cleartext HTTP is accepted only for permitted LAN/private-network hosts; public hosts must use HTTPS. Credentials are scoped to the dashboard origin and reverse-proxy prefix.
