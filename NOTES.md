# Slice 1 + Slice 2 notes (feat/multi-gateway-mobile-control)

## Empty built-in denylist (`fix/empty-builtin-denylist`)
- `DeviceLanePolicy.PROTECTED_PACKAGES` and `hermes-plugin/broker.py` `PROTECTED_PACKAGES` are empty; `isProtected` / `is_protected` still merge custom extras.
- Device tab copy: `BLOCKLIST empty · add packages to protect` / `BLOCKLIST  N custom` (no "N built-in").
- Stream filter: self-package + FGS channels still suppressed; do not rely on built-in package list.


## Telegram stream suppress (`feat/telegram-stream-suppress`)
- Built-in `NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES`: `org.telegram.messenger`, `.web`, `.beta`, `org.thunderdog.challegram`.
- Filters NLS forward path only; `DeviceLanePolicy.PROTECTED_PACKAGES` / broker `PROTECTED_PACKAGES` stay empty (Hands can control Telegram).
- Wake skip default aligned via `agent_wake.STREAM_SUPPRESS_PACKAGES` (dual list — keep in sync).
- Device tab: `STREAM MUTE  N built-in (Telegram)` near STREAM; BLOCKLIST remains Hands custom-only.


## Landed

### Slice 1 — Multi-gateway
- `selectConnectChoice` clears `password` before connect so leftover UI passwords cannot skip saved per-host creds.
- Settings **DISCONNECT FROM HOST** wired: cancel connect/sync/watch/fleet, clear active operator UI, stop stay-connected; device lanes kept while ARMED.
- `probeFleet` replaces `hostHealth` (no forever-merge of stale hosts); empty fleet clears the map.
- Fleet foreground gate uses `MutableStateFlow` + `first { it }` instead of a 250ms spin.
- A8.3 leftovers: concrete password cross-host bug fixed; stores already host-scoped from A8.5 (no rewrite).

### Slice 2 — Mobile Hands
- A6.7 multi-device: metadata on register, `companion-devices.json` fields (`name/model/manufacturer/os_version/is_default/last_seen/extra_protected`), rich `GET /companion/device/lanes`, `POST /companion/device/default|rename`, broker/live resolve (1 device auto; multi without default → `ambiguous_device`), tools `mobile_devices` / `mobile_select_device` + optional `device=` on mobile_*, CLI `lanes|default|rename`.
- App sends Build metadata + custom protected packages on lane register; HANDS friendly label editor.
- Built-in denylist emptied (`PROTECTED_PACKAGES = emptySet()` / broker `frozenset()`); protection is custom-only via StickyStore + register `protected_packages`. Notification stream still blocks Companion self package/FGS channels.
- Biometric: suppress `lockIfEnabled` while Activity Result picker outstanding; `BiometricGate` calls `onFail` when busy.

## Deferred / out of scope
- Older-page history REST fallback.
- OpenClaw.
- Full S22 QA (checklist below).
- A8.1 Room hosts table; full A8.3 Room partition (already origin-keyed for transcripts).

## S22 checklist (manual)
- [ ] Connect picker: switch hub ↔ lab without leftover password skipping saved creds.
- [ ] Settings disconnect returns to Connect; stay-connected off; fleet chips still update.
- [ ] Pair two phones (or phone + emulator): `hermes companion lanes`, `mobile_devices`, `mobile_snapshot(device=…)`.
- [ ] Multi without default → ambiguous_device; set default via CLI/tool.
- [ ] Rename label on HANDS; appears in lanes.
- [ ] Custom denylist add/remove protects Settings/authenticator when added; UI shows empty built-in (0).
- [ ] Camera/photo/file pickers do not trip biometric lock mid-pick.
- [ ] Biometric prompt while busy surfaces failure (no silent no-op).


## Chat streaming + role differentiation (`feat/telegram-stream-suppress`, 2026-09-07)
- Rows: `UserRow` (right-shifted hairline panel, `YOU`, tap = rewind) vs `AssistantRow` (2dp signal rail, `HERMES`, blinking `SignalCursor` + `LiveDot · streaming`). Tool rows inset under the rail; a running tool (`ChatMessage.toolRunning`) shows `● running · …`.
- `PendingRow` (`chat.pending`) fills the gap between send and first token / while a tool runs with no text. `[END]` pill becomes `● LIVE` while streaming.
- Data path: `Flow<ChatEvent>.coalesceDeltas()` (domain, 40ms frames, order-preserving) sits in front of `applyEvent`, so the UI re-parses markdown at a bounded rate instead of per token. `GatewaySocket` event buffer 128 → 8192 (DROP_OLDEST was eating tokens on bursts). SSE turn stream uses a no-read-timeout client (60s killed long tool calls).
- `toolRunning` is transient: set on `tool.start`, cleared on `tool.complete`, `finishStream`, and interrupt. Never persisted (`MessageEntity.toModel` defaults false).
- **Open (host):** `standalone.py` is a local-store stub — `_reply()` echoes the prompt, never calls Hermes, and the relay replays the finished reply in 48-char chunks (fake streaming). Real token streaming only exists via proxy to the dashboard (`HERMES_COMPANION_STANDALONE=0`, which the systemd unit already sets; the in-process relay started by `register()` defaults to standalone ON).
- **S22 live pass (2026-09-07, ASH profile, real host):** thinking row → `● streaming` + cursor → text grows over several frames → header settles. Found and fixed on device:
  - Tail-follow: `ChatScreen.followTail` keeps the newest row in view while streaming; only a drag away turns it off (DragInteraction), drag-to-bottom or the LIVE/[END] pill turns it back on. The old "only if exactly at end" check stopped following the moment a row grew.
  - Send always scrolls to the just-sent row (IME shrinks the viewport and left the list "away from end").
  - Reducer segments assistant text around tool rows (`assistantId`, `assistantId.2`, …). Before, post-tool deltas were appended to the pre-tool row so the transcript read text→text→tool until reopened.
  - `CompanionState.openSessionRef` fallback: a freshly created thread vanished from `sessions` on the next `session.list` refresh (host does not persist empty threads), so `openSession` was null and SEND was a silent no-op. `send()` now reports `send blocked · …` instead of returning quietly.


## Agent rooms P21 (`feat/agent-rooms`, 2026-09-07)
- Host: `hermes-plugin/rooms.py` (RoomStore `companion-rooms.json`, UpstreamWs, RoomEvents, RoomController). Routes `/companion/rooms…` + events ws; CLI `hermes companion room …`; skill/prompt rules. Tests `tests/test_rooms.py` (scripted async fake dashboard).
- **Auth gate** on `/companion/*`: loopback or `Authorization: Companion <device_id>:<credential>` from a paired device. Open: health, pair offer/status, register, ticket ws, media GET. Phone sets the header once a DeviceCred exists (interceptor + explicit header on the events ws — ws upgrades skip the REST interceptor). 401s are logged by the relay.
- Phone: `RoomSessionManager` shares `messages/draft/streaming` with the single-agent chat; `openRoomId` decides which is shown. Backing sessions (`room:<id>`) are hidden from the rail. Rooms refresh on connect / profile switch / Threads tab (pairing may happen after connect). Old host plugin (404) → no ROOMS section.
- **Relay proxy bug found on device (fixed):** `proxy_tcp` never sent FIN after a proxied response (close() with a thread still in recv), so OkHttp reused the pooled connection and the next request black-holed for its 60s read timeout. Connect went from 60–180s to ~3s. Regression test in `test_relay.py`. `HERMES_COMPANION_DEBUG=1` logs every relay request with timing.
- E2E (S22, local relay in proxy mode over `mock-dashboard`, via LAN then Tailscale `100.121.113.13:9120`): pair → NEW ROOM (coder+ops) → post → mention-first turn order (`@OPS` first), round cap, follow-tail, no crash. Mock agents echo their prompt, so transcripts look noisy; real agents reply normally.
- Known: markdown renderer italicises across `_` in agent text (`mobile_*`), pre-existing. The phone still has pairings/gateways for the two throwaway relay origins (192.168.0.11, 100.121.113.13) — harmless, can be forgotten from the connect picker.

