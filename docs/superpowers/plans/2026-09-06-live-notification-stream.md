# Live notification stream — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (preferred) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While Hermes Companion is active on Android, keep a persistent foreground notification, stream **live phone notifications** (all packages except protected/denylist) to a **configurable gateway origin + profile**, store/relay them on the host, and expose them to the Hermes agent as **context + tool** so the agent **decides** whether to inject into the active thread on that gateway (e.g. Telegram chat). No auto-inject.

**Architecture:** Phone `NotificationListenerService` (opt-in) → filter via `DeviceLanePolicy` + `StickyStore.protectedPackages` → device-lane WSS event frames to the chosen origin → hermes-plugin relay ring buffer → `mobile_notifications` tool (+ system-prompt hint) → agent optionally injects into active session. FGS persistent notif (StayConnected / stream service) proves the process is alive. Config UI picks target gateway + profile (sticky prefs).

**Tech stack:** Kotlin / Compose / FGS + `NotificationListenerService` (app), OkHttp `DeviceSocket` lane, hermes-plugin Python relay/tools/skill, SharedPreferences sticky prefs.

**Repo:** `/home/pacman/Projects/hermes-companion-app` @ `main`  
**Machine:** arch (`b3b6fb3e-09ae-4aa4-b7a5-abe7201911bb`)  
**Tracks:** A13.2 (Notification Listener Service) — this plan is the concrete design for that work item.  
**Constraint:** PLAN ONLY — do not implement until Nyx nods. Do **not** merge unrelated PRs.

**Policy defaults (Nyx 2026-09-06):**
1. **Filter:** stream **all** notifications **except** protected/denylist packages (built-in `DeviceLanePolicy.PROTECTED_PACKAGES` ∪ custom `StickyStore.protectedPackages`). No allowlist-only mode in v1.
2. **Agent inject:** agent receives notifs as **context/tool**; agent **decides** whether to inject into the active thread. Never auto-post into Telegram/chat.

---

## Investigation snapshot (2026-09-06 IST)

### Existing pieces to reuse

| Area | What exists | Pointers |
|---|---|---|
| Persistent FGS “app active” | `StayConnectedService` — `HERMES CONNECTED · <host>`, id 17, `dataSync`, sticky refresh on host switch | `app/.../StayConnectedService.kt`; manifest `app/src/main/AndroidManifest.xml` |
| Armed FGS + kill switch | `HandsService` — `HERMES HAS HANDS · <lanes>`, DISARM via `DisarmReceiver` | `feature-device/.../HandsService.kt`, `DisarmReceiver.kt` |
| Overlay / NOTIFY / A11Y checklist | Device tab ENABLE rows; `POST_NOTIFICATIONS` runtime (API 33+) | `feature-device/.../DeviceScreen.kt`; `MainActivity.kt` permission launchers |
| Protected denylist | Built-in set + custom merge; fail-closed `protected_package` for hands | `domain/.../DeviceLanePolicy.kt`; `StickyStore.protectedPackages`; broker `is_protected` |
| Device lane WSS | Phone dials out; ticket subprotocol; result frames only from phone today | `docs/protocol/mobile-control.md`; `DeviceSocket.kt`; `DashboardClient.openDeviceLane`; `DeviceNodeCoordinator` |
| Status frames (host already accepts) | Relay `on_frame` handles `mobile.controller.status` → `live_meta` | `hermes-plugin/relay.py` (`on_frame`, `_apply_live_meta`) |
| Sticky origin + profile | Per-host `profileFor` / `setProfile`; `lastGoodOrigin`; phone-wide denylist | `data-local/.../StickyStore.kt`, `HostKeys.kt` |
| Plugin tools + prompt/skill | `mobile_*` tools; `_prompt()` + `hermes-companion` skill | `hermes-plugin/tools.py`, `schemas.py`, `__init__.py`, `skills/hermes-companion/SKILL.md` |
| Planned stub | A13.2 NLS “forward to agent memory or wake bus” — not built | `docs/WORK_ITEMS.md` A13.2; `docs/PLAN.md` line ~197 / A13.2 |

### Gaps (why this plan)

- **No `NotificationListenerService`** in tree or manifests (`BIND_NOTIFICATION_LISTENER_SERVICE` absent).
- **`DeviceSocket.send` only emits `mobile.controller.result`** — no phone→host event/status helper for notif payloads.
- **No capability** `device.notifications` / no relay ring / no `mobile_notifications` tool.
- **StayConnected** keeps process alive when toggle on; streaming notifs needs an explicit “stream enabled” FGS story (may reuse StayConnected channel or add a dedicated low-importance channel — see P0).
- **Inject path** must be agent-gated (tool or explicit session append), not a silent webhook into the active Telegram thread.

---

## Architecture

```
┌─ Android (Hermes Companion) ─────────────────────────────────────┐
│  StayConnectedService / NotifStream FGS                          │
│    persistent: HERMES CONNECTED · <host> · stream on|off         │
│  NotificationListenerService (opt-in system setting)             │
│    onNotificationPosted → filter(protected?) → enqueue           │
│  StickyStore: streamOrigin, streamProfileId, streamEnabled       │
│    (defaults: lastGoodOrigin + profileFor(origin))               │
│  Device lane WSS (paired host = stream target)                   │
│    frame: mobile.controller.event { kind: notification, ... }    │
└───────────────────────────────┬──────────────────────────────────┘
                                │ WSS (same ticketed lane)
┌───────────────────────────────▼──────────────────────────────────┐
│  hermes-plugin relay                                             │
│    on_frame → NotifRing (per device_id, capped, TTL)             │
│    GET /companion/device/notifications (debug/operator)          │
│    live_meta.notifications_stream: on|off                        │
└───────────────────────────────┬──────────────────────────────────┘
                                │ tools / prompt
┌───────────────────────────────▼──────────────────────────────────┐
│  Hermes agent (profile bound to that gateway)                    │
│    system prompt hint when stream live                           │
│    mobile_notifications(limit|since) → recent events             │
│    agent DECIDES → inject into active thread (session tool /     │
│      gateway send) OR ignore / summarize only                    │
│    NEVER auto-inject                                             │
└──────────────────────────────────────────────────────────────────┘
```

### Target gateway + profile

- Stream binds to **one** paired origin + profile (configurable in app).
- Defaults: `sticky.lastGoodOrigin` (else `origin`) + `sticky.profileFor(origin)`.
- Prefs (phone-wide stream toggle; per-host target optional — prefer **per-host** keys via `HostKeys` for multi-gateway):
  - `notif_stream_enabled` (bool, default false until NLS granted + user arms stream)
  - `notif_stream_origin` / fallback lastGood
  - `notif_stream_profile.<hostKey>` / fallback `profileFor`
- Device lane for that origin must be **paired + connected**. If target host lane is down, buffer briefly on phone (small ring) then drop oldest — do not spill to another host.

### Default filter policy

```
emit = package not in (PROTECTED_PACKAGES ∪ sticky.protectedPackages)
       AND streamEnabled
       AND NLS bound
       AND not self-posted companion channels (stay / hands / wake)  // avoid feedback loops
```

- **v1 = denylist only** (stream everything else). No per-app allowlist UI yet (P2 polish may add mute list beyond protected).
- Reuse `DeviceLanePolicy.isProtected` / `normalizePackage` semantics (exact + `prefix.*`).
- Strip or redact: notification **actions reply text** out of scope; do not forward full messaging body if package is protected (already dropped). For allowed packages, forward title + text + package + postTime + key id (hash) — no bitmaps in v1.
- Never log OTP-looking payloads to companion-audit beyond package + truncated title (same spirit as “never typed text of device.type”).

### Agent context / inject (decision on agent)

| Layer | Behavior |
|---|---|
| Prompt | When stream live: short section “Phone notification stream is ON for profile X. Call `mobile_notifications` to read recent events. Only inject into the active chat if the user would want it; do not dump every shade event.” |
| Tool `mobile_notifications` | Returns recent ring entries (time, package, title, text, device_id). Read-only. |
| Tool / action `mobile_notifications_inject` (P1) **or** reuse host session send | Agent-chosen: post a **summarized** user/system note into the **active session** for that profile. Requires explicit tool call. |
| Auto | **Forbidden.** Relay must not push SSE/chat messages into sessions on receive. |

---

## Protocol additions (device lane)

Extend `docs/protocol/mobile-control.md` (keep ticketed WSS; no new socket).

### Capability

Add `device.notifications` to phone register allowlist (`DeviceLanePolicy.CAPABILITIES` + relay filter). Ticket may omit it if NLS off; phone re-registers when stream enabled.

### Phone → host frame

```json
{
  "type": "mobile.controller.event",
  "event": "notification",
  "device_id": "dev_…",
  "profile": "default",
  "ts_ms": 1757160000000,
  "notification": {
    "key": "sha256-or-sbn-key",
    "package": "com.telegram.messenger",
    "title": "…",
    "text": "…",
    "category": "msg",
    "ongoing": false,
    "clearable": true
  }
}
```

Also reuse / extend `mobile.controller.status` for stream meta:

```json
{
  "type": "mobile.controller.status",
  "device_id": "dev_…",
  "notifications_stream": true,
  "notifications_listener_bound": true
}
```

### Host → phone (optional P1)

`device.notifications_pause` / resume as commands — nice-to-have; v1 can be phone-side toggle only.

### Relay storage

- In-memory ring: ~100 events / device, drop oldest; optional persist `~/.hermes/companion-notifications.jsonl` (P2).
- `on_frame`: if `mobile.controller.event` + `notification` → push ring; update `live_meta[device].notifications_stream`.
- Operator debug: `GET /companion/device/notifications?device_id=&limit=` (auth same as other companion routes).

---

## Android implementation sketch (no code yet)

### 1. FGS persistent notification (app active + stream)

- **Baseline:** keep `StayConnectedService` as the “companion active” tell when stay-connected is on.
- **Stream:** when `notif_stream_enabled`, ensure an FGS is running (either fold into StayConnected refresh text `stream on · <profile>`, or dedicated `NotifStreamService` low-importance channel `notif-stream`, id TBD ≠ 17/31). Prefer **fold into StayConnected** if stay is already on; if user enables stream without stay, start a minimal FGS so OEM does not kill NLS delivery.
- Title pattern: `HERMES CONNECTED · <host>` / subtitle `stream → <profile>` when streaming.
- Ongoing, non-dismissable while stream on; action **STOP STREAM** (does not disarm hands).

### 2. `NotificationListenerService`

- New: `feature-device/.../CompanionNotificationListener.kt` (or `app/...`).
- Manifest: `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` + service with `NOTIFICATION_LISTENER_SERVICE` intent-filter + optional meta XML.
- Device tab row: `NLS   on|off` + `ENABLE NLS` → `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (cannot auto-grant).
- `onNotificationPosted`: map to DTO → filter → `NotifStreamBus.emit` → coordinator sends frame on target lane.
- `onNotificationRemoved`: ignore in P0 (optional P2).
- Ignore packages: companion’s own (`applicationId`), plus denylist.

### 3. Wiring

- `DeviceNodeCoordinator` (or sibling `NotifStreamCoordinator`): owns enable flag, target origin/profile, connects send path via `DashboardClient` / `DeviceSocket.sendEvent(...)`.
- Extend `DeviceSocket` with `sendRaw` / `sendEvent` (not only `DeviceResult`).
- Re-register capabilities when stream toggled so ticket includes `device.notifications`.

### 4. Config UI

- Device tab (near denylist / permissions) or Connect/Gateway sheet:
  - Toggle **Live notification stream**
  - **Target gateway** (paired hosts only)
  - **Target profile** (profiles for that host)
  - Status: NLS bound · lane live · last event age
- Persist via `StickyStore` (+ `HostKeys`).

### 5. Privacy

- Default denylist = existing protected set (banking, authenticator, Settings, System UI, password managers, …) + user custom.
- Document: enabling NLS exposes shade content from **non-protected** apps to the chosen Hermes host.
- No forwarding of group conversation full history dumps beyond the single notification payload Android gives.
- Audit line: time, device_id, package, ok — **not** full text (P0); optional truncated title hash.

---

## Plugin / agent surface

| Piece | Change |
|---|---|
| `plugin.yaml` / `schemas.py` | Add `mobile_notifications` (+ P1 `mobile_notifications_inject` or document using existing session-send if Hermes exposes it) |
| `tools.py` | Handlers read relay ring; inject tool posts one agent-authored summary into active session for bound profile |
| `relay.py` | Event ingest + ring + live_meta |
| `__init__.py` `_prompt()` | Stream hint when any device has `notifications_stream` |
| `SKILL.md` | Section: read stream → decide → inject only when useful; never spam Telegram |
| Tests | `test_relay.py` status/event frames; tool unit tests |

**Inject semantics (P1):**  
`mobile_notifications_inject({ "text": "…", "session_id?": "…", "notification_key?": "…" })`  
- Resolves active session for profile if `session_id` omitted (gateway-specific — use standalone/operator RPC already used for chat).  
- Marks injected keys to avoid duplicate inject in the same ring window.  
- Failure modes: `no_session`, `stream_empty`, `not_allowed` (if inject disabled in prefs — future).

---

## Success criteria

1. With Companion active + stream ON + NLS granted, shade shows ongoing connected/stream notification naming **host · profile**.
2. A non-protected app notification on the phone appears in relay ring within ~2s while device lane is live to the configured origin.
3. Protected / custom-denylist package notifications **never** appear in the ring (unit + device check).
4. Companion’s own FGS/wake notifications do not echo into the stream.
5. `mobile_notifications` returns those events to the agent on that gateway/profile.
6. **No** chat/Telegram message is created unless the agent explicitly calls inject (or equivalent); with only stream ON, active thread stays quiet.
7. Switching target gateway/profile updates sticky prefs and subsequent events land only on the new origin’s ring.
8. Disabling stream or revoking NLS stops frames; FGS text reflects `stream off`.

---

## Phased tasks

### P0 — Stream + FGS (ship filter + host ingest; agent read optional stub)

- [ ] **P0.1** Spec: update `docs/protocol/mobile-control.md` with `device.notifications`, `mobile.controller.event` notification payload, status fields.
- [ ] **P0.2** Android: `CompanionNotificationListener` + manifest; Device tab NLS row + ENABLE settings intent; resume check in `MainActivity` / DeviceScreen (mirror A11Y/OVERLAY/NOTIFY).
- [ ] **P0.3** Filter helper: `NotificationStreamPolicy.shouldForward(package, extraProtected)` reusing `DeviceLanePolicy.isProtected`; exclude self channels.
- [ ] **P0.4** Sticky prefs: stream enabled + target origin + profile (`StickyStore` / `HostKeys`).
- [ ] **P0.5** FGS: fold stream state into `StayConnectedService.refresh` (or minimal `NotifStreamService`); STOP STREAM action; host·profile in text.
- [ ] **P0.6** `DeviceSocket.sendEvent` + coordinator path; capability `device.notifications` on register when enabled.
- [ ] **P0.7** Relay: ingest event frames → per-device ring; `live_meta`; `GET /companion/device/notifications`; tests in `hermes-plugin/tests/test_relay.py`.
- [ ] **P0.8** Config UI: toggle + gateway + profile pickers (paired hosts / profiles list).
- [ ] **P0.9** Device verify (S22): non-protected fires → ring; Bitwarden/Settings suppressed; own FGS not echoed.

**P0 exit:** host can observe live notifs from phone; persistent notif visible; denylist honored. Agent inject **not** required yet (read via HTTP/debug OK).

### P1 — Agent context + decide-to-inject

- [ ] **P1.1** Tool `mobile_notifications` (+ schema, `plugin.yaml`, handlers, tests).
- [ ] **P1.2** System prompt section + skill paragraph: stream ON → poll tool → **agent decides** inject vs ignore.
- [ ] **P1.3** `mobile_notifications_inject` (or thin wrapper over session message API) — **explicit only**; dedupe by notification key; no auto path in relay.
- [ ] **P1.4** Verify on lab/raj: Telegram (or companion chat) receives inject **only** after tool call; flood of shade events does not spam the thread.

**P1 exit:** agent can read stream as context and choose to inject into active thread on that gateway/profile.

### P2 — Polish

- [ ] **P2.1** Optional mute list (beyond protected) / rate-limit per package.
- [ ] **P2.2** Persist jsonl audit; operator UI strip for recent notifs.
- [ ] **P2.3** `onNotificationRemoved`; coalescing (same key updates).
- [ ] **P2.4** Pause/resume command from agent; battery / DND respect notes in README.
- [ ] **P2.5** Cross-link WORK_ITEMS A13.2 → this plan; mark deliverable done when P1 lands.

---

## Files map (expected touch set)

```
docs/protocol/mobile-control.md
docs/superpowers/plans/2026-09-06-live-notification-stream.md  # this file
docs/WORK_ITEMS.md                                            # A13.2 pointer only when implementing

domain/.../DeviceLanePolicy.kt                                # capability + shared filter hook
data-local/.../StickyStore.kt                                 # stream prefs
data-remote/.../DeviceSocket.kt                               # sendEvent
data-remote/.../DashboardClient.kt                            # optional helper
app/.../StayConnectedService.kt                               # stream subtitle / fold FGS
app/.../DeviceNodeCoordinator.kt                              # or NotifStreamCoordinator
app/.../MainActivity.kt / CompanionShell.kt                   # permission + UI wiring
feature-device/.../CompanionNotificationListener.kt           # new
feature-device/.../DeviceScreen.kt                            # NLS + stream config
feature-device/.../AndroidManifest.xml                        # NLS service
app/.../AndroidManifest.xml                                   # merge permissions if needed

hermes-plugin/relay.py
hermes-plugin/tools.py
hermes-plugin/schemas.py
hermes-plugin/plugin.yaml
hermes-plugin/__init__.py
hermes-plugin/skills/hermes-companion/SKILL.md
hermes-plugin/tests/test_relay.py
```

---

## Non-goals (this plan)

- Notification **reply** / action buttons from Hermes (explicitly out of v1 per PLAN.md).
- Auto-inject into Telegram or any session.
- iOS.
- Reading protected-package notifications “for the agent’s convenience”.
- Replacing ntfy wake pipeline (`WakeNotifier`) — complementary, not a substitute.

---

## Risks / notes

| Risk | Mitigation |
|---|---|
| OEM kills NLS / FGS | Persistent FGS; document battery exceptions (same as Hands) |
| Secret leakage via shade | Denylist-first; audit without bodies; user-facing consent copy on ENABLE NLS |
| Feedback loop (own notifs) | Hard-exclude companion notification channels / package |
| Multi-host misfire | Sticky target origin; never fan-out to all paired lanes |
| Agent spam | Prompt + skill; inject is explicit tool; optional P2 rate limits |

---

## Order of work reminder

P0 stream+FGS → P1 agent tool + decide-to-inject → P2 polish.  
Do not implement until Nyx nods. Do not merge unrelated PRs.
