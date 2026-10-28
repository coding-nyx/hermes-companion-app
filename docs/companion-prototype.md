# Companion multi-agent prototype

This branch introduces the Companion name and a shared mobile workspace for Hermes, OpenClaw and Codync. It is an evaluation build, not a declaration of production parity across the three runtimes.

## Try it

Build and sideload the separate `Companion Prototype` app:

```sh
./gradlew :app:assembleDebug -PhermesPreview=true -PhermesDeviceAbi=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The preview application ID is `app.hermes.companion.preview`. Its credentials, permissions, database and device pairing are independent of the stable application. The stable application ID, existing Hermes links, class namespaces and signing configuration remain compatible. A production rename does not require replacing the application ID or re-pairing existing Hermes installations.

On first launch, choose **Hermes**, **OpenClaw** or **Codync**. Existing Hermes installations can open the new workspace from the toolbar menu's **Connections** item. **Explore sample workspace** opens an isolated, interactive example containing all three runtime identities and a mixed-runtime group. The persistent sample banner identifies it; sample messages never reach a host, start an agent or run tools. The sample backend resets when it is recreated.

Bots is the default destination. Rows open the whole conversation, show compact runtime icons and previews, and support search and group filtering. New groups accept an optional name and derive it from the selected bots. The floating navigation bar disappears in every shared chat. The idle composer is a single 48 dp input; formatting and expanded editing appear while active, and multiline drafts survive leaving and reopening a conversation.

## What is connected

| Area | Hermes | OpenClaw | Codync |
| --- | --- | --- | --- |
| Transport | Existing dashboard / Companion relay | Native gateway WebSocket operator protocol 3–4 | Native signed ephemeral encrypted channel v1, pairing link v3 |
| Agent roster / history / text chat | Implemented | Implemented | Implemented |
| Native groups | Host-owned Hermes groups | Not advertised by this adapter | Host-owned Codync groups |
| Bot creation | Native profile creation | Requires an advertised method and granted admin scope | Native `createBot` command |
| Approvals / stop | Native session and exact group attempt coordinates | Session/run-scoped chat and execution approvals | Native permission option IDs and `stop` |
| Voice / device tools / advanced settings | Existing complete Hermes workspace, opened from Connections | Not enabled in this prototype adapter | Not enabled in this prototype adapter |
| Mixed-runtime groups | Interactive sample only; live coordination is a later rollout stage | Interactive sample only | Interactive sample only |

A gateway method being advertised is not a grant to administer it. OpenClaw agent creation is hidden without the required admin scope. The prototype requests read, write and approval access, not admin access. OpenClaw's node role and Android command executor are deliberately not advertised by this chat adapter. No chat connection arms the phone or subscribes it to notifications.

Hermes keeps the native canonical `Bot Chat` session title in the host database. The UI resolves the agent's display name separately. Native group speakers use member identities and names. Existing Hermes rooms, phone controls, notification consent and voice continue through the existing workspace; the Hermes plugin protocol is unchanged by this prototype.

## Connections and delivery

The neutral contracts live in `core-backend`; Hermes's adapter uses `data-remote`. Provider-specific wire fields stay inside adapters. The encrypted Android vault stores public connection metadata separately from credentials and private identity seeds. Owner keys include the runtime and physical connection identity; proxy paths are part of the identity. Codync pins the computer's signing identity and retains its direct routes and relay address. Public HTTP is refused; LAN/Tailscale HTTP and public HTTPS are accepted.

Connection and conversation epochs fence asynchronous work. Drafts, cached transcripts and pending operations belong to one owner and thread. OpenClaw uses the native idempotency key; Codync uses its client nonce; Hermes groups retain their event identity within a send. A lost acknowledgement does not trigger an automatic replay. Unconfirmed delivery survives reopening a conversation and requires a history check / explicit review. Unknown creation outcomes require refreshing the roster before another creation attempt.

## Local OpenClaw validation

The operator's Mac runs OpenClaw **2026.10.7**, protocol 4, at `127.0.0.1:18789`. The prototype's native adapter successfully completed its signed device handshake, listed **six agents**, and read an isolated conversation's history. No agent prompt or host configuration change was needed for this read-only check.

The gateway is loopback-only. For a development session with an ADB-connected S22:

```sh
adb -s <S22> reverse tcp:18789 tcp:18789
```

Then enter `http://127.0.0.1:18789` in the prototype's OpenClaw setup and enter the gateway credential privately. The tunnel lasts only while ADB is connected. A normal untethered rollout needs the operator's chosen authenticated private-network or HTTPS route; do not change the gateway bind settings just to make a prototype work.

The opt-in JVM smoke test reads the local gateway credential without logging it:

```sh
# Create a private 32-byte seed at /tmp/companion-prototype-live-seed first.
COMPANION_LIVE_OPENCLAW=1 ./gradlew :core-backend:test --tests '*OpenClawLocalTest' --rerun-tasks
```

Ordinary CI skips this test. Protocol fixtures separately verify signed OpenClaw challenges, issued device tokens, session/run filtering, chat snapshots/finals, Codync's published crypto vectors, replay/tamper rejection, encrypted pairing/reconnect, group creation, exact permission IDs, stop commands and host-scoped delivery state. They do not replace live Codync validation.

## Rollout gates

1. **This prototype:** shared contracts, explicit connections, Companion naming, common bot/chat/group surfaces, native chat adapters and visual review in dark/light/large text.
2. **Live adapter acceptance:** the S22 local-OpenClaw roster/history and shared UI smoke checks are complete; remaining work includes a real Codync computer and direct/relay reconnect/revocation cases; host-version compatibility matrix. Keep the stable Hermes app installed during this stage.
3. **Feature parity:** OpenClaw node session and command inventory mapped to the existing Android consent/protection gates; Codync voice and supported device capabilities; native models, attachments and management controls. Implement each capability before showing a corresponding control. Keep notification sharing explicit and passive.
4. **Live mixed-runtime rooms:** add a host coordinator with explicit participant ownership, turn budgets, cancellation, persisted operations and approval routing. Never run the orchestrator in a phone UI coroutine or present a sample group as a live room.
5. **Production release:** migrate existing connection metadata idempotently, run upgrade and device tests, update store/docs/artwork and repository branding, then merge. Keep stable application IDs, signing keys and Hermes compatibility. The repository rename and store publishing are separate rollout actions.

## Primary references and attribution

- [OpenClaw native Android gateway](https://github.com/openclaw/openclaw/tree/main/apps/android/app/src/main/java/ai/openclaw/app/gateway)
- [OpenClaw protocol schemas](https://github.com/openclaw/openclaw/tree/main/packages/gateway-protocol/src)
- [Codync Android core](https://github.com/leepokai/Codync/tree/main/apps/android/core/src/main/kotlin/com/codync/android/core)
- [Codync host bot API](https://github.com/leepokai/Codync/blob/main/host/src/api/bots.rs)
- [Codync published relay vectors](https://github.com/leepokai/Codync/blob/main/docs/reference/fixtures/remote-relay-vectors.json)

The reduced Codync crypto/framing code carries source attribution and modification notices. Distribution licenses are in `third_party/` and inside the app's **Connections → Open-source notices**. OpenClaw's Android node app is a protocol reference; the new adapter does not claim to be its full node implementation.

## Validation record — 2026-10-28

- The full Android Gradle test task passed for the separate preview application: 1,387 recorded test executions, zero failures. The opt-in OpenClaw test is skipped in ordinary runs; the complete 13-test backend suite was also run with that live check enabled against the local gateway.
- Release lint and the R8-minified release build passed. ARM64 debug and instrumentation packages built successfully with the separate preview application ID.
- Seven prototype render/interaction cases cover setup, bot creation, optional group names, full rosters, chat navigation, draft restoration and the existing Hermes entry point. Full screens and open forms were inspected in dark, light and 2× text modes.
- Final APK installed and its installed SHA-256 verified on the S22 (SM-S901E, Android 16 / API 36). Both instrumentation cases passed in 21.023 seconds: shared roster/group/composer/navigation flows across dark, light and enlarged text, plus live OpenClaw roster and isolated history through the explicit ADB tunnel. Captures were taken after keyboard transitions settled, the light palette was checked from pixels, and the complete send target remained visible. Test theme overrides were confined to the app process and restored afterward. Phone control is disarmed.
- Live Codync and live mixed-runtime rooms remain unverified / unimplemented respectively, as shown in the capability matrix.

Reviewed screens: [dark bots](design/companion-prototype/bots-dark.png), [light bots](design/companion-prototype/bots-light.png), [OpenClaw setup](design/companion-prototype/openclaw-setup.png), [bot form](design/companion-prototype/bot-form-dark.png), [group form](design/companion-prototype/group-dark.png), [large-text group](design/companion-prototype/group-large.png), [chat editing](design/companion-prototype/chat-editing.png), [connections](design/companion-prototype/connections-dark.png).

Physical acceptance captures: [S22 bot roster](design/companion-prototype/s22-bots-dark.png), [S22 light group form](design/companion-prototype/s22-group-light.png), [S22 large-text composer with keyboard](design/companion-prototype/s22-chat-large.png). The current saved OpenClaw connection uses the ADB development tunnel; an untethered private HTTPS route remains a rollout task.
