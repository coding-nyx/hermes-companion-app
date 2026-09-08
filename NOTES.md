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



## Thread rail ordering + UX (A18.9 / A18.10, 2026-09-08)
- Rail bug was data, not Compose: float-second timestamps parsed as 0 (`longOrNull`), Room vs domain tie-breaks in opposite directions (visible flip cache → remote), REST capped at 20 rows on the real dashboard, sparse `sessions.changed` patches overwriting rows, and "no `ended_at` ⇒ unread" lighting every standalone row.
- One comparator (`SessionLists.comparator(ThreadSort)`) everywhere; Room `ORDER BY` mirrors it. `listSessions` = union(RPC, REST) merged per id. `SessionRef` now carries `createdAtEpochMs` / `messageCount` / `source`; Room v5.
- Default sort is CREATED newest-first (operator request). Sort is sticky (`StickyStore.threadSort`); the filter is screen-local.
- Plugin: `hermes_store.list_sessions` filters archived/hidden in SQL, page 500, `source`; standalone rows carry `started_at`, `limit` honoured on REST + RPC, `sessions.changed` includes `started_at`. **Deploy plugin to lab and raj with the APK** (AGENTS.md rule) — old plugin still works, just without `started_at` on new-thread events.
- S22 pending: order matches `hermes sessions list` on lab (proxy) and raj (standalone); no flip after ↻; sort persists across relaunch.
- **Live findings (S22, 2026-09-08):** dashboard REST is `limit ≤ 100` + `offset` + `total` (422 on 500 → client now pages). Hermes hides child sessions everywhere (`list_sessions_rich(include_children=False)`): coder's 60 untitled `subagent` rows are invisible to the gateway, the dashboard REST and the web UI alike, so the phone's 53 is the host's own number. Standalone relay listed only named profiles: root `HERMES_HOME` is Hermes' `default` and was dropped whenever `profiles/<name>` existed; hub-11's relay runs as `HERMES_HOME=…/profiles/bishop`, so `hermes_root()` walks up like `hermes_cli.profiles` does (A18.11, fixed + deployed to lab/hub-11; raj-13766 needs a manual `install.sh`, ssh refused). Phone "switched host/profile on its own" during the pass — root cause was the test driver, not the app: `adb shell monkey -p … 1` injects one random touch after launching, which hit gateway SWITCH / profile rows / the TELEGRAM chip. Launch with `adb shell am start -n app.hermes.companion/.MainActivity` for device passes.
- A18.12: `□ TELEGRAM □ ARCHIVED` chips after the sort chips. ARCHIVED = sticky + refetch with `archived=include` / `include_archived`; TELEGRAM = screen-local `bySource`. raj-13766 `default` really has 6 live rows (276 archived) — the toggle is how you reach the rest.
- A18.13 UX pass: robot launcher icon (`RobotMark` shares the geometry), `SwitchingPane` replaces the body during a host switch, `LinkPill` (SYNC / LINK) in the header, `StatusStrip` error slot for tabs without one, `ActionButton`/`ToggleRow`/`SectionHeader`/`KeyValueRow` in core-design, profiles tab as rows. Fonts: `core-design/res/font` is empty despite A7.9 — IBM Plex is not actually bundled.


## Rooms v2 (P22, 2026-09-08)
- Host: `rooms.py` rewritten — `Policy{mode converse|moderated|bounded, max_turns, max_minutes, cooldown, moderator, hands}`, floor selection (`_select_next`: addressed → not-spoken-since-topic → least-recent, cooldown), quiet / paused(budget|time|stall|operator) states persisted, operator posts interleave (no 409), approvals forwarded (`room.approval` + `POST …/approval`, turn waits on `ApprovalWait`), `continue` widens `room.budget_extra`, `summarize` runs one tagged turn, `PATCH` rename/policy, `guard` for hands. Runners: `UpstreamTurnRunner` (dashboard ws), `ChatQTurnRunner` (`hermes chat -Q`, text only, whole recent transcript each turn), `PeerTurnRunner` (peers.py NDJSON stream). `peers.py`: grant/add/authorized, `RemoteTurnService` serving `/companion/peers/turn`.
- Relay: `Authorization: Peer host_id:secret` only opens `/companion/peers/turn*`; loopback still bypasses everything (so a same-box peer test cannot exercise the gate — `CompanionAuthGateTests` does). `room.ready` carries `seq` + `room`. Wake: devices register `wake_topic` (their ntfy URL for that host); relay POSTs `{type: room.quiet|room.paused|room.approval|room.mention, room_id, session_id=room_id, profile, title}` when no phone watches the room.
- Phone: `RoomSessionManager(clients, RoomSeenStore, …)` — incremental resync via `room.ready.seq` → `history?after=`, unread = `room.seq > sticky roomSeen`, approvals reuse `state.approval` (speaker prefix in the strip), `RoomBar` (chips tap=mention / long-press=remove, `+`, floor pill, PAUSE/STOP or CONTINUE/SUMMARIZE, RENAME, pinned summary), `RoomFloorStrip` under the transcript, create sheet with templates/mode/budget/moderator/hands/opening line + linked-peer profiles, gateway tab `LINK HOSTS` (phone-brokered grant→add), room wake pings open the room (`WakePolicy.ROOM_TYPES`, deep link `&room=`).
- Test fixtures: `FakeDashboard` replies may be `{"approval": {...}, "reply": "..."}` to exercise approvals; one-line JSON for ws frames.
- **S22 pass (2026-09-08):** rooms v2 live on lab (proxy) + hub-11 (standalone, `hermes chat -Q` via `HERMES_BIN` drop-in `~/.config/systemd/user/hermes-companion-relay.service.d/hermes-bin.conf`). Findings: (1) LINK HOSTS needs the phone **paired** with the other host too — the grant call is a `/companion/*` route; error now reads `pair this phone with the host`. Paired the S22 with hub-11 (`dev_c84416cb…`, profile bishop) via the relay approve route. (2) ASH's long tool chains hit the 180 s turn timeout → default raised to 300 s (`policy.turn_timeout_s`). (3) Tool rows in rooms were blank on expand: dashboard `tool.*` payloads carry `preview`/`args`/`output`, not `detail` → `rooms.tool_detail()`; phone keeps the start detail when the completion is blank. (4) Text-only remote speaker label is `bishop · Hub11`. Peer names are sanitised gateway-book names (`Hub11`, `PrimaryHost`).


## Coding-agent sessions (P23, 2026-09-08)
- Host `agents.py`: `ToolDiscovery` (registry claude/codex/cline/agy (Antigravity; gemini dropped 2026-09-08, deprecated)/aider/opencode/shell, `which` over PATH + `HERMES_COMPANION_AGENT_PATHS` + npm/local/cargo/bun/homebrew bins, 60 s cache, `?refresh=1`), `resolve_cwd` allow-list ($HOME, HERMES_WORKSPACE, HERMES_HOME, `HERMES_COMPANION_AGENT_ROOTS`, /tmp), `Tmux` wrapper (`hc-<id>` sessions, `remain-on-exit`, capture `-p -e -J`), `AgentSessions` registry (`companion-agents.json` 600 + `.log` audit), cap `HERMES_COMPANION_AGENT_MAX` (4). Routes `/companion/agents/tools|sessions|sessions/<id>[/pane|keys|interrupt|kill]`, ws `/companion/agents/events?session_id=&cols=&rows=` (pane diffs ≤4 Hz). Device revoke → `kill_all`.
- Phone: `AnsiText` (SGR only; CSI/OSC stripped) → `AnnotatedString` per line, cursor block; `AgentSessionManager` streams the pane (reconnect + REST fallback), `AgentPaneWidths` 60/80/100/120 resize the tmux window; `AgentConsoleScreen` replaces the term tab body (SESSIONS | SHELL segments, quick shell kept).
- Structured mode (A23.3/A23.4): `StructuredProcess` keeps one `claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages --permission-prompt-tool stdio` per session (stdin stays open; prompts are `{"type":"user",…}` lines carrying the claude `session_id` after init). Wire → `agent.ready|user|delta|tool.start|tool.complete|approval|approval.resolved|turn.start|turn.end|exit`, kept in an in-memory transcript (`GET …/transcript?after=`) and fanned out over the same `/companion/agents/events` ws (first frame `agent.ready.replay`). `control_request can_use_tool` blocks the reader until `POST …/approval {request_id, decision}` (600 s → deny). **`--permission-prompts host` alone is not enough** — without `--permission-prompt-tool stdio` the CLI auto-denies ("requested permissions … but you haven't granted it yet"); with stdio and a *closed* stdin you get `AbortError: Stream closed`. Codex has no long-lived exec protocol, so `CodexStructuredProcess` spawns `codex exec --json --skip-git-repo-check --cd <cwd> [resume <thread_id>] <prompt>` per turn (events `thread.started/item.started|completed/turn.completed`; no approvals — the sandbox decides). Structured sessions do not need tmux; relay exit kills them (`AgentSessions.shutdown`), tmux panes survive.
- Phone: `AgentEvent` (core-model) → `AgentTranscript.reduce` (domain; deltas extend the streaming row, a tool row closes it, approval held until resolved, cost summed) → `StructuredView` in `AgentConsoleScreen` (YOU / CLAUDE|CODEX markdown / tool rows expand to output / approval strip ALLOW·DENY / SEND↔STOP). `NEW` panel offers CHAT vs TERMINAL when the tool has a `structured` mode; START no longer needs tmux for CHAT. Session rows say `chat · <cwd>` and `idle` / `needs you`.
- Directory picker (user ask 2026-09-08): `GET /companion/agents/dirs` (`agents.list_dirs`, rooted in `allowed_roots()`, `..` stops at a root edge, root chips WORKSPACE/HOME/HERMES/TMP deduped by resolved path, recents from the session registry). Phone: `BROWSE` next to DIRECTORY opens an inline `DirPicker` (root/recent chips, `..`, path with ⎇ when git, hidden toggle, rows enter a folder, `USE <name>` picks it and fills the field). The text field stays for typing.
- Install gotcha: newer `hermes plugins enable` prompts "Allow this plugin to replace built-in tools?" and hangs a non-interactive install — `install.sh` now passes `--no-allow-tool-override` (with `</dev/null`) when the flag exists.
- Gotchas: the `hermes companion` wrapper on lab only knows the v1 subcommands (`room`/`peer`/`agent` missing) — Hermes seems to register plugin CLI verbs from a cached copy; `python3 ~/.hermes/plugins/hermes-companion/cli_main.py …` always works. Kotlin sources must not contain raw ESC bytes (tooling rejects them) — build them from `\u001B`.
