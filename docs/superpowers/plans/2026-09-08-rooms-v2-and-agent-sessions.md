# Rooms v2 + Coding-Agent Sessions — Plan

**Status:** PLAN ONLY (2026-09-08). Nothing here is built. Challenge the decisions table before any task starts.
**Builds on:** `docs/superpowers/plans/2026-09-07-agent-group-chat.md` (P21, rooms v1) · `docs/protocol/rooms.md` · relay PTY (`/companion/terminal/ws`, `terminal_pty.py`).
**Tracks:** **P22 — Rooms v2** (A22.1–A22.11), **P23 — Coding-Agent Sessions** (A23.1–A23.7), **P24 — Workspace resolution** (A24.1–A24.3), **P25 — Plugin web console** (A25.1–A25.3), **P26 — Agent Memory Maintenance & Modifications Module** (A26.1–A26.4), **P27 — Notification System v2: Enrichment, Thread Subscriptions & Host/Agent Routing** (A27.1–A27.4) in `docs/WORK_ITEMS.md`.

---

## 0. What v1 gives us today (read before proposing)

| Piece | State | Gap that matters |
|---|---|---|
| Room store + controller (`rooms.py`, 762 lines) | Persistent JSON store, mention-first / round-robin policy, `PASS`, interrupt, `max_rounds` 1–4, per-participant backing session | Turn **stops** on `approval_required` / `clarify` — the operator never sees the question. Needs the real dashboard (`STANDALONE=0`). |
| Events WS `/companion/rooms/events` | Full `room.*` stream, heartbeat; phone re-pulls the **whole** history (`after=0`) when the socket reconnects | No `lastSeenSeq`, so no unread and no incremental `after=`; nothing on screen says a resync happened; a turn interrupted mid-stream can leave a row "streaming" until the refetch lands. |
| Phone (`RoomSessionManager`, `ChatScreen` room mode) | Speaker rows by glyph + rail texture, mention chips, `INTERRUPT ALL`, pending row names the speaker, ROOMS rail section with live dot | No participant management in-room, no round indicator, no unread on ROOMS rows, no history paging, no rename, no attachments, `PASS` rows are just blank. |
| CLI `hermes companion room …` | list / create / post / history / interrupt / delete | No participants add/remove, no rename. |
| Agents' view | Preamble + unseen `[room] GLYPH: …` lines, tool calls summarised | Rules say "max N rounds", so agents front-load one reply each instead of conversing; no moderator; the operator is locked out (`409 room_busy`) while a plan runs and cannot be notified. |
| Hands in rooms (A21.6) | Forbidden by prompt | Needs controller election. |

---

## 1. Rooms v2 (P22)

### 1.1 Decisions

| Decision | Choice | Why |
|---|---|---|
| Approvals inside a room | **Pause the turn, forward the prompt to the phone**, resume with the answer | Today a room turn dies the moment an agent needs `approval`/`clarify`. Single-agent chat already has `ApprovalStrip` + `approval.respond`; the room just needs to carry `request_id` + speaker. Biggest functional hole in v1. |
| Standalone hosts | **Text-only turns via `hermes chat -Q -p <profile>`** (the `agent_wake` path) when there is no dashboard | Rooms today are dashboard-only. raj-13766 and hub-11 run standalone. Streaming is lost but the room works; the pending row already covers "thinking" without tokens. |
| Resync | `room.ready` carries `seq`; phone replays `history?after=<local seq>` (incremental) on every (re)connect and shows `RESYNCING` | Cheap, uses an endpoint that already exists; today the reconnect path refetches everything from seq 0 with no indicator. |
| Participants | Add/remove/rename **from the room header**, using the existing `POST …/participants` | No new host code for add/remove; rename is one new field. |
| Notify the operator | `@YOU` mention or `room.idle` → wake ping (existing ntfy/wake path) when the phone is not on that room | Rooms are async by nature; today you only learn a room finished by looking. |
| Summaries | `SUMMARIZE` asks a chosen participant (default: first) for a 5-line summary tagged `summary`; pinned at the top of the room | Long rooms are unreadable on a 360 dp screen. Keeps the "no second identity" rule — no hidden summariser agent. |
| Templates | Client-side presets (`triage`, `review`, `plan`, `standup`) that prefill title, participants, rounds and an opening post | Zero host work, saves the most taps. |
| Hands in rooms | One **hands participant** per room (chosen at create time or `@HANDS`-elected); only its turns may call `mobile_*` | Resolves A21.6 without a general election protocol: the room preamble names the holder, the broker refuses `mobile_*` from any other backing session. |
| Scheduled posts | Cron job that `POST`s to a room (`hermes companion room post` already exists) | Daily standups / nightly reviews without new machinery; reuses P10. |
| Turn model | **`converse` policy is the default**: agents keep talking until they fall silent; round caps become a *budget* circuit-breaker, not the goal (§1.4) | v1's "N rounds then stop" makes rooms feel like a relay race. A conversation should end because nobody has anything to add, not because a counter hit 2. |
| Cross-host rooms | **In v2**: the room lives on one host, remote participants run their turns on their own relay through a **peer link** the phone brokers (§1.5) | ASH on lab and BISHOP on hub-11 in one room is the point of having a fleet. The phone is already paired with both, so it can hand each relay a credential for the other without a CLI step. |
| Notification stream (STREAM) | **Phone switch only**: Companion → Device tab → NLS (system notification access) + STREAM, pointed at target gateway/profile. **No host on/off** (`device.stream_on/off`), not like arm/disarm. | Host cannot grant NLS and there is no device.stream_on/off on the host. Once flipped on the phone: agent can read the ring with `mobile_notifications` (works while disarmed) and gets woken when shade events land (Telegram and Gmail are silenced so we don't echo). Want it on, tap STREAM. Want it off, same place. |
| Model reasoning toggle | **Interactive toggle near model list** (`ModelBottomSheet` / Gateway switcher): enable/disable reasoning or adjust reasoning effort for reasoning-capable models | Today `REASONING` is only a static amber badge. Add an interactive toggle near the model list; wires reasoning state to turn stream payloads (`createSession`/`streamTurn`) and persists in `StickyStore`. |
| Profile global model toggle | **Toggle in profile list** (`ProfilesScreen` / `ProfileBottomSheet`): toggle whether a profile inherits the host's global model vs locking to a profile-specific model | Profiles can have their own model or inherit the host default. Add an interactive toggle per profile row in the profile list to toggle between global model inheritance and profile-scoped model override, syncing via host API. |
| Agent Memory & Modifications | **Dedicated module** in Companion (Profiles sheet / Agent tab): Memory view & maintenance (search, edit, prune, add, dream trigger) + Agent modifications (soul, prompt directives, skill toggles, model) | Gives the operator direct mobile inspection and curation of agent memory (fixing bad memories, pruning stale context) and persona/directive tweaks without raw file editing on the host. |
| Notification enrichment & actions | **MessagingStyle + Direct Reply + Inline Approvals**: stratified channels (`approvals`, `mentions`, `turns`, `cron`, `health`), rich avatars/snippets, shade quick reply via `RemoteInput`, one-tap `[APPROVE]/[DENY]` buttons | Replaces barebones single-channel text (`profile · sess-id`) with actionable rich notifications. Lowers operator latency by allowing responses and tool approvals directly from the notification shade without app cold starts. |
| Thread notification subscriptions | **Per-thread subscription tiers**: `ACTIONS_ONLY` (default), `WATCH` (all turn completions), `MUTED` (DND), and temporary `SNOOZE` (30m, 2h, tomorrow) | Prevents alert fatigue. Operators can watch active coding sessions while backgrounding noisy scrapers or subagent rooms. Bell icon in chat header + swipe/long-press in thread rail. |
| Host & agent notification routing | **Dual-layer host & profile filtering matrix in Settings**: host-level master toggles in Gateway Book + per-agent allowlist/priority toggles + host-side `/companion/device/notification-policy` sync | Puts the operator in full control of multi-host fleets (`lab`, `hub-11`, `raj-13766`). Filtered host-side to save battery/data, and enforced fail-safe client-side in `WakePolicy`. |

### 1.2 UX / UI changes (phone)

- **Room header**: title, participant glyph chips (tap = mention, long-press = remove), `+` to add, round pill `R1/2` while a plan runs, live dot on the speaker chip. Rename by tapping the title.
- **ROOMS rail rows**: last line preview, relative time (reuse `ThreadTime`), unread dot when `seq > lastSeenSeq`, busy dot as today. Sorted by `updated_at` desc.
- **Transcript**: `PASS` rows collapse to a one-line `COD passed` mute row; tool calls per turn fold into `▸ 3 tools` (tap to expand); operator-mentioned lines (`@YOU`) get a warn tick on the rail.
- **Approval strip** in rooms: `COD wants to run terminal · journalctl …  [ONCE] [DENY]` — same component as single chat, speaker-prefixed.
- **Composer**: mention chips stay; add `SUMMARIZE` and `ROUNDS ▾` (1–4) in the room overflow; `INTERRUPT ALL` unchanged.
- **Create sheet**: template row on top (`TRIAGE · REVIEW · PLAN · STANDUP · BLANK`), hands-participant selector, opening post field.
- **Loading / error**: `LOADING ROOM` skeleton on open, `RESYNCING` row after a socket drop, per-turn error stays inline (exists).

### 1.3 Work items

| Item | Scope | Est. |
|---|---|---|
| **A22.1 Approvals in rooms** | Host: on `approval.request`/`clarify.request` emit `room.approval {speaker, request_id, kind, command, choices}` and **wait** (turn timeout applies); `POST /companion/rooms/{id}/approval {request_id, decision}` → `approval.respond` on the speaker's upstream WS. Phone: `ApprovalStrip` in room mode, `RoomSessionManager.respondApproval`. Biometric gate via `PrivilegePolicy` as in single chat. | 1.5 d |
| **A22.2 Resync + unread** | `room.ready.seq`; phone `history?after=<lastSeenSeq>` on connect instead of `after=0`; `lastSeenSeq` per room in Room cache; unread dot on ROOMS rows + `RESYNCING` row. | 0.5 d |
| **A22.3 Participants + rename in-room** | Header chips, add sheet, `PATCH /companion/rooms/{id} {title}` (new), CLI `room rename`, `room participants add/remove`. | 0.5 d |
| **A22.4 Standalone rooms** | `RoomController.turn_runner` abstraction: `UpstreamWs` (streaming) or `ChatQRunner` (`hermes chat -Q -p P`, text only, tool summaries from `--json` if available). `/companion/health.rooms = "ok_text_only"`. | 1 d |
| **A22.5 Operator wake** | `@YOU` or `room.idle` while room not open → existing wake ping (`WakePing.type = "room.idle"`), deep link opens the room. | 0.5 d |
| **A22.6 Summaries + pinned card** | `POST …/summarize {by?}` runs one extra turn with a summary preamble, tags the `RoomMsg` `summary`; phone pins the latest summary above the transcript, `▸ show all`. | 0.5 d |
| **A22.7 Templates + opening post** | Client-only presets; create sheet posts the opening line after `201`. | 0.25 d |
| **A22.8 Transcript folding** | PASS rows, tool folding, `@YOU` tick, round pill. | 0.5 d |
| **A22.9 Hands participant** (closes A21.6) | `policy.hands = <profile>`; preamble names the holder; broker rejects `mobile_*` unless the calling session is the holder's backing session; `@HANDS` mention hands over (one at a time). | 1 d |

| **A22.10 Conversation policy** (§1.4) | `Policy.mode = bounded\|converse\|moderated`, budgets (`max_turns`, `max_minutes`), cooldown, stall detector, interleaved operator posts (no more `409 room_busy`), `PAUSE` vs `INTERRUPT`, `CONTINUE +N`. New preamble/rules text; phone: policy picker, budget pill, quiet row. | 2 d |
| **A22.11 Cross-host participants** (§1.5) | Peer store + phone-brokered peer pairing; remote turn service `/companion/peers/turn` (stream + approval + interrupt); `participants[].host`; glyph disambiguation; create sheet lists fleet profiles; CLI `peer add/list/remove`. Standalone peers need A22.4. | 3 d |
| **A13.2-STREAM Notification stream phone switch** | Clarify & lock boundary: STREAM is strictly a phone switch (Companion → Device tab → NLS + STREAM pointed at gateway/profile). No host `device.stream_on/off` (not like arm/disarm); host cannot grant NLS. When flipped: agent reads ring via `mobile_notifications` (works while disarmed) and gets woken on shade events (Telegram and Gmail silenced so we don't echo). Want it on, tap STREAM; want it off, same place. | 0.25 d |
| **A9.4 Model reasoning toggle** | Add interactive toggle near model list in `ModelBottomSheet` & `GatewayScreen` for reasoning-capable models (`model.reasoning`); replace static badge; wire to `CompanionState` + `createSession`/`streamTurn` payload; sticky preference in `StickyStore`. | 0.5 d |
| **A9.5 Profile global model toggle** | Add global model toggle per profile row in `ProfilesScreen.kt` & `ProfileBottomSheet.kt`; toggles whether profile inherits global host model vs profile-specific model; wires to host API (`/api/profiles/<id>/model`); refreshes profile list. | 0.5 d |

Order: **A13.2-STREAM** → **A9.4** → **A9.5** → A22.2 → A22.1 → **A22.10** → A22.3 → A22.8 → A22.7 → A22.5 → A22.4 → **A22.11** → A22.6 → A22.9.

### 1.3a Implementation checklist (Rooms v2, approved order 2026-09-08)

STREAM Phone Switch & Echo Boundary (Immediate Next Item)
- [x] Verify phone-only STREAM switch: Companion → Device tab → NLS (system notification access) + STREAM, pointed at target gateway/profile
- [x] Confirm no host commands: reject `device.stream_on/off` or host toggle (not like arm/disarm; host cannot grant NLS)
- [x] Verify agent read path: `mobile_notifications` reads ring while disarmed
- [x] Verify agent wake path: woken when shade events land, confirm Telegram (`STREAM_SUPPRESS_PACKAGES`) and Gmail are silenced so neither echoes
- [x] Confirm tap semantics: tap STREAM on phone to turn on, tap STREAM in same place to turn off

Model Reasoning Toggle Near Model List
- [x] `ModelBottomSheet.kt`: replace static amber `REASONING` badge with interactive toggle/effort chip near the model row / list header for reasoning-capable models
- [x] `GatewayScreen.kt` & `SettingsBottomSheet.kt`: render active reasoning state toggle
- [x] Wire state through `CompanionState` (e.g. `reasoningOverride` / effort), pass in `createSession` and `streamTurn`
- [x] Persist sticky reasoning preference in `StickyStore`

Profile Global Model Toggle in Profile List
- [x] `ProfilesScreen.kt`: add interactive global model toggle/chip per profile row (e.g. `GLOBAL` vs custom model) to toggle between inheriting global host model vs profile-scoped model override
- [x] `ProfileBottomSheet.kt`: render global model toggle in profile switcher sheet
- [x] `HostToolsController.kt` / `DashboardClient.kt`: wire toggle to host endpoint `POST /api/profiles/<id>/model` (clearing model override to revert to host default or setting specific model)
- [x] Refresh profile list and update meta line to reflect `global (<model>)` vs profile override

Host (`hermes-plugin`)
- [x] `rooms.py`: `Policy{mode, max_turns, max_minutes, cooldown, moderator, hands}` (parse both old `max_rounds` and new fields), `Room.state ∈ {idle, running, paused, quiet}`, `pause_reason`
- [x] Planner rewrite: floor selection (addressed → not-spoken-since → least-recent, cooldown), quiet detection, budget breakers → `paused`, stall detector, `continue(+n)`, `pause()` soft stop, operator posts interleave (no 409)
- [x] Approvals: `room.approval` event + wait, `POST …/approval`, resume via `approval.respond`/`clarify.respond` on the speaker's WS; timeout → `approval_timeout`
- [x] Prompt text v2 (`preamble`, per-turn header with budget and hands holder)
- [x] `room.ready` carries `seq`; `PATCH /companion/rooms/{id}` (title, policy); `POST …/summarize`
- [x] Standalone turn runner (`hermes chat -Q -p`), `available()` → mode string; health `rooms: ok|ok_text_only|unavailable`
- [x] Hands holder: `policy.hands`; broker refuses `mobile_*` from non-holder backing sessions (via `HERMES_SESSION_ID`/room map)
- [x] Operator wake: `@YOU` / `room.idle` / `room.paused` → wake ping when no phone is subscribed to that room
- [x] Peers: `peers.py` store, `POST /companion/peers/grant`, `POST /companion/peers`, `GET/DELETE`, `POST /companion/peers/turn` (stream, approval, interrupt), `participants[].host`, remote turn runner, glyph disambiguation
- [x] CLI: `room rename|participants|continue|pause|summarize`, `peer grant|add|list|remove`
- [x] Tests: policy unit tests (floor, quiet, cooldown, budget, stall, interleave), approval round-trip, standalone runner, peers auth + remote turn, PATCH/summarize
- [x] Docs: `docs/protocol/rooms.md` v2, SKILL.md + `_prompt()` rules text

Phone
- [x] `RoomRef{mode, maxTurns, turnsUsed, state, pauseReason, hands, lastSeenSeq}`; `ChatEvent.RoomApproval/RoomState`; RoomJson
- [x] `RoomSessionManager`: incremental resync (`after=lastSeenSeq`), `respondApproval`, `continueRoom`, `pauseRoom`, `rename`, `setParticipants`, `summarize`, unread tracking
- [x] Chat room mode: header chips (tap mention / long-press remove / `+`), budget pill, `PAUSE`, approval strip with speaker, quiet / paused / stalling rows with `CONTINUE +6` / `STOP`, PASS folding, tool folding, `@YOU` tick, pinned summary
- [x] Create sheet: mode picker, templates, opening post, hands picker, cross-host profiles (peers) + `LINK HOSTS`
- [x] ROOMS rail: preview, relative time, unread dot, host suffix on remote glyphs
- [x] Gateway tab: peers section (link / unlink / status)
- [x] Tests: reducer (approval, state rows), RoomJson, render test for room header/rows
- [x] S22 pass: lab room ASH+COD (converse → quiet, live operator posts, `+` participant), COD@lab + BIS@hub-11 (text-only, budget pause → CONTINUE). Not exercised live: an agent approval inside a room (no agent asked), PAUSE mid-turn (unit-tested).

### 1.4 Conversation policy — let them talk (A22.10)

**Problem with v1.** `max_rounds` is the *goal*: the planner walks mention → round-robin → stop at N. Agents are told "max 2 rounds per operator message", so they front-load everything into one reply and the room reads like sequential status reports. The operator cannot even speak while a plan runs (`409 room_busy`).

**v2 model — the floor, not the rounds.**

```
Policy { mode: "bounded" | "converse" | "moderated",
         max_turns: 12,        # circuit breaker per operator post (converse), not a target
         max_minutes: 10,      # wall-clock breaker
         cooldown: 1,          # an agent may not speak twice in a row unless addressed
         moderator?: profile,  # moderated mode only
         hands?: profile }     # A22.9
```

- **Who speaks next** (converse): (1) agents addressed by `@GLYPH` in the last message, in order; (2) otherwise every participant that has *not* spoken since the last message gets the floor, least-recent speaker first; (3) `cooldown` stops A→B→A→B ping-pong unless one addresses the other. A participant that replies `PASS` is skipped until someone else speaks or the operator posts.
- **How it ends** (converse): the exchange goes **quiet** when every participant has passed since the last non-PASS message. The phone shows a mute `room quiet · 7 turns` row; nothing else stops it. `max_turns` / `max_minutes` are breakers: when hit, the room **pauses** (not ends) with `budget reached · CONTINUE +6 / STOP`; the agents are told the budget in every prompt so they can wrap up on their own.
- **Stall detector**: two consecutive replies from the same speaker with > 0.9 similarity, or three turns with no mention and no new tool call → pause with `stalling · nudge or STOP`. Cheap `difflib.SequenceMatcher`, no model call.
- **Operator interleaving**: `post()` while a plan runs **appends** the line and re-plans instead of returning 409. The next agent's delta includes it (`[room] YOU: …`), and any queued speakers that were not addressed are dropped so the operator's line steers the floor. `PAUSE` (soft: finish the current turn, then stop) sits next to `INTERRUPT ALL` (hard).
- **Moderated**: the moderator speaks first after every operator post and after every quiet point; whoever it addresses speaks next; it ends the exchange with a line that is exactly `[END]`. Good for a standup with ASH chairing.
- **Prompt text** (replaces the v1 rules line):
  `[rules: this is a live conversation. Reply when you have something useful to add or ask; reply exactly PASS when you do not. Address a participant with @GLYPH to ask them directly. You may disagree, ask follow-ups and change your mind. When the group has reached a conclusion, say so in one line and PASS. Budget: 9 of 12 turns left. Do not call mobile_* control tools from a room turn (hands: ASH).]`
- **Agents replying at once** (later, `parallel_first_round`): after an operator post, every addressed agent starts its turn concurrently and the phone interleaves the streams by speaker. Deferred: the "lines you have not seen" delta needs per-turn snapshots first.
- **Phone**: create sheet gets `MODE  BOUNDED · CONVERSE ▾ · MODERATED` (+ moderator picker), budget pill `7/12` in the header while running, `room quiet` / `budget reached` / `stalling` rows with `CONTINUE +6` and `STOP` actions, `PAUSE` next to `INTERRUPT ALL`, composer never blocked.

### 1.5 Cross-host participants — ASH from lab, BISHOP from hub-11 (A22.11)

**Shape.** A room has exactly one **room host** (the relay that stores it and runs the planner). Each participant is `{profile, host?}`; `host` names a **peer** of the room host. Local participants run as today. A remote participant's turn is one call to the peer relay:

```
POST http://<peer>:9120/companion/peers/turn      Authorization: Peer <host_id>:<secret>
     {profile, session_id?, title, text, timeout_s}
  → 200 upgrade to WS (or chunked NDJSON): {type: session|delta|tool.start|tool.complete|approval|complete|error, …}
POST …/companion/peers/turn/{turn_id}/approval {request_id, decision}
POST …/companion/peers/turn/{turn_id}/interrupt
```

The peer creates/reuses the backing session **in its own profile** (memory and tools stay on the agent's home host), runs `prompt.submit` through its own dashboard (or the text-only runner on a standalone host — A22.4), and streams events back. The room host re-emits them as `room.*` with `speaker="bishop@hub-11"`, so the phone needs no new event types.

**Peer link (auth).** Relays trust each other through a **peer credential**, stored next to the device pairings (`~/.hermes/companion-peers.json`, mode 600). Two ways to create one:

1. **Phone-brokered (default)** — the phone is paired with both hosts, so it can carry the secret: `POST hub/companion/peers/grant {name:"lab", origin:"http://lab:9120"}` → hub mints `{host_id, secret}`; the phone posts that to `POST lab/companion/peers {name:"hub-11", origin, host_id, secret}`. One tap per direction in the gateway tab: `LINK HOSTS  lab ⇄ hub-11`. The secret never touches the phone's persistent storage.
2. **CLI** — `hermes companion peer grant lab` on hub prints a one-time code; `hermes companion peer add hub-11 http://hub:9120 CODE` on lab.

Peers are listed and revocable from both the gateway tab and `hermes companion peer list|remove`. Revoking a device pairing does **not** revoke peers (they are host-to-host).

**Identity in the transcript.** Glyphs stay three letters; when two participants share a glyph the host tag is appended (`ASH·L`, `ASH·H`). The preamble says `you are BIS (hub-11) · others: ASH (lab), YOU(operator)`; the phone's speaker label shows `BIS` with a `hub-11` mute suffix and the rail texture as today. Mentions accept `@BIS`, `@bishop`, `@bishop@hub-11`.

**Creating a cross-host room from the phone.** The create sheet lists profiles from the active host first, then from every saved gateway that is a known peer of the active host (`GET /companion/peers` + each peer's `/api/profiles`, cached). Picking a profile from a host that is not yet a peer shows `LINK HOSTS` inline. `POST /companion/rooms` with a `host` the room host does not know → `409 peer_unknown`.

**Failure handling.** A peer that is down fails only its own turns (`error: peer_unreachable`), the room continues; `room.turn.end` carries it as today. Approvals from remote agents ride the same A22.1 path (room host ↔ phone ↔ peer). Interrupt fans out to every active peer turn.

**Standalone peers.** hub-11 and raj-13766 run without a dashboard, so their turns are text-only via A22.4 until they run the dashboard; the phone's pending row covers "thinking" and the reply lands whole. Health: `/companion/health.peers = [{name, reachable, mode}]`.

**Not in scope**: rooms replicated across hosts, peer discovery over Tailscale, more than one room host per room.

---

## 2. Coding-agent sessions from the phone (P23) — "run Claude Code / Codex / Cline on the host, drive it from the companion"

### 2.1 Feasibility (checked 2026-09-08 on lab)

| Fact | Result |
|---|---|
| CLIs on lab | `claude` **2.1.263** and `codex` **0.144.1** at `~/.npm-global/bin`; **`cline` not installed**; `tmux` present. hub-11 / raj-13766: none of the three (not yet checked for tmux) → discovery must be per host and live. |
| Host plumbing already there | Relay PTY WebSocket `/companion/terminal/ws` (`terminal.input/resize/kill`, `terminal.data`) backed by `PtySession`, plus quick `terminal/exec`. `HERMES_WORKSPACE=/home/nyx` is the cwd. Everything under `/companion/*` is paired-device gated. `audit.py` exists. |
| Phone side | Console tab uses quick-exec only; **no terminal emulator** in the app. The chat pipeline (`coalesceDeltas` → `applyEvent` → role/tool rows, `ApprovalStrip`) is exactly the shape a structured agent stream needs. |
| Structured protocols | Claude Code: `claude -p --input-format stream-json --output-format stream-json` (bidirectional NDJSON: `assistant`/`user`/`result` messages, `control_request` for permission prompts). Codex: `codex app-server` (JSON-RPC over stdio: threads, turns, item deltas, approval requests) and `codex exec --json`. Cline: CLI is preview-only; treat as PTY-only. **Verify exact message shapes on lab before A23.3/A23.4.** |

### 2.2 Decisions

| Decision | Choice | Why |
|---|---|---|
| Where sessions run | On the host, as the relay user, **inside tmux** (`tmux new -d -s hc-<id> …`) | Survives phone disconnects and app kills; you can `tmux attach -t hc-<id>` from a laptop and see the same session. |
| How the phone sees a PTY session | **Snapshot rendering**, not a terminal emulator: host polls `tmux capture-pane -e -p -J` (~4 Hz while active, on-demand otherwise) and sends the pane as ANSI text; phone parses SGR colours only | A real VT emulator on mobile is a project in itself; a captured pane is stable, scrolls sensibly, and copes with TUIs (Claude's prompt, Codex's picker). Input goes back via `tmux send-keys`. |
| Structured sessions | Adapters for **Claude** (stream-json) and **Codex** (app-server) that translate to the existing `ChatEvent` shapes (`AssistantDelta`, `ToolStarted/Completed`, `Approval`, `Completed`) | Reuses the chat UI, markdown, tool rows, approval strip and biometric gate for free. This is where the mobile UX is actually good. |
| Order | PTY/tmux first (works for every tool, incl. cline/aider/agy later), structured second | PTY is the universal fallback and needs no protocol reverse-engineering. |
| Tool discovery | **Dynamic, per host, at request time** — the relay probes a registry of known tools (`claude`, `codex`, `cline`, `agy` (Antigravity), `aider`, `opencode`) with `which` + `--version`, honours `HERMES_COMPANION_AGENT_PATHS`, and the phone only offers what that host actually has | Nothing is assumed installed: lab has `claude`+`codex`, hub-11 and raj-13766 have neither today. Installing a CLI on a host makes it appear on the phone on the next probe with no app or plugin change. |
| Trust boundary | Same as the existing terminal: paired device + `/companion/*` auth; **plus** cwd allow-list (`HERMES_WORKSPACE`, `~/Projects/*`), max 4 live sessions, audit line per spawn/kill/approval, kill-all on pairing revoke, biometric gate before spawn and before any `approve` that runs a command | Coding agents can edit and run anything; the phone must not widen what the terminal already allows. |
| Where it lives in the app | The `term` tab becomes **Agent console**: `SESSIONS` list (tool glyph `CC`/`CX`/`CL`/`SH`, cwd, status, last line, age) + `NEW` (tool, cwd picker from `fs/tree`, prompt) + the existing quick shell underneath | No seventh tab. |
| Rooms convergence (later) | A coding-agent session can **join a room** as a participant of kind `claude`/`codex`: the room delta becomes its prompt, its stream-json/app-server output becomes room turns | Hermes agents and Claude Code in one transcript, hands-off-able with `@CC`. Only after A22.1 and A23.3. |

### 2.3 Protocol sketch (now `docs/protocol/agent-sessions.md`)

```
Session  { id, tool: "claude"|"codex"|"cline"|"shell", mode: "pty"|"structured",
           cwd, title, status: "starting"|"running"|"waiting_input"|"waiting_approval"|"exited",
           exit_code?, created_at, updated_at, last_line, tmux: "hc-<id>" }

GET    /companion/agents/tools                     → {tools:[{id, version, path, modes:[…]}]}
GET    /companion/agents/sessions                  → {sessions:[…]}
POST   /companion/agents/sessions {tool, mode, cwd, prompt?, args?} → 201 {session}
GET    /companion/agents/sessions/{id}/pane?cols=&rows=  → {ansi, cursor, status}      (pty)
POST   /companion/agents/sessions/{id}/keys {text|key}   → 202                          (pty)
POST   /companion/agents/sessions/{id}/prompt {text}     → 202                          (structured)
POST   /companion/agents/sessions/{id}/approval {request_id, decision} → 200
POST   /companion/agents/sessions/{id}/interrupt        → Ctrl-C / cancel turn
DELETE /companion/agents/sessions/{id}                  → kill tmux session
WS     /companion/agents/events?session_id=            → agent.pane {ansi}, agent.delta, agent.tool.*,
                                                          agent.approval, agent.status, agent.exit
CLI    hermes companion agent list|start|attach|keys|kill
```

### 2.4 Work items

| Item | Scope | Est. |
|---|---|---|
| **A23.1 Host: tool discovery + session registry + tmux runner** | `agents.py`: **tool registry** `{id, binaries:[…], version_flag, modes, install_hint, login_hint}` probed with `shutil.which` over `PATH` + `HERMES_COMPANION_AGENT_PATHS` (+ `~/.npm-global/bin`, `~/.local/bin`, `~/.cargo/bin`), cached 60 s, re-probed on `GET …/tools?refresh=1` and before every spawn (`409 tool_missing {install_hint}` if it vanished); `GET /companion/health.agents = {tmux, tools:[…]}`. Session registry JSON (mode 600), `tmux` spawn/kill/list, cwd allow-list, audit, cap; REST above; `hermes companion agent tools\|list\|start\|attach\|kill`. `shell` and `custom` (any command line) are always offered when `tmux` exists. Unit tests with a fake `which`/tmux. | 1.5 d |
| **A23.2 Phone: sessions list + PTY pane view** | `term` tab → Agent console; `NEW` sheet shows the host's **installed** tools with version (`CC claude 2.1.263`, `CX codex 0.144.1`), greys out known-but-missing ones with the install hint (`npm i -g @anthropic-ai/claude-code`), refreshes on open, and always offers `SHELL` / `CUSTOM`; `LOADING TOOLS` row while probing, `NO TMUX ON HOST` pane when tmux is absent; `AgentSession` model, client, `AgentSessionManager`; pane view: ANSI SGR → `AnnotatedString`, follow-tail, pinch/`cols` presets (60/80/100), input bar, key row (`ESC ⇥ ↑ ↓ ⏎ ^C y n`), status pill; `NEW` sheet with cwd picker from `fs/tree`. Loading: `STARTING`, `ATTACHING`, `RECONNECTING`. | 2 d |
| **A23.3 Claude adapter (structured)** ✅ 2026-09-08 (not under tmux — direct stdio child; `--permission-prompt-tool stdio`) | `claude -p --input-format stream-json --output-format stream-json --permission-prompt-tool …` (or `control_request` handling) under tmux; map to `ChatEvent`; approvals → `agent.approval`; render in chat UI with `CC` speaker. | 1.5 d |
| **A23.4 Codex adapter (structured)** ✅ 2026-09-08 (`codex exec --json` per turn + `exec resume`, app-server deferred) | `codex app-server` JSON-RPC: thread/turn lifecycle, item deltas, approval requests; same mapping. | 1.5 d |
| **A23.5 Safety + lifecycle** | Biometric gate on spawn/approve (`PrivilegePolicy`), kill-all on revoke, idle reaper (configurable), per-session audit view, `HERMES_COMPANION_AGENTS=0` kill switch. | 0.5 d |
| **A23.6 Notifications** | `waiting_approval` / `exited` → wake ping; deep link opens the session. | 0.5 d |
| **A23.7 Rooms convergence** | `participants[].kind = "claude"\|"codex"`; room delta → structured prompt; turns stream as room events; `@CC` hand-off. | 2 d (after A22.1, A23.3) |

Order: A23.1 → A23.2 (ship: any CLI via tmux) → A23.5 → A23.3 → A23.6 → A23.4 → A23.7.

### 2.5 Open questions (need Nyx)

1. **Which machine runs the coding agents?** lab has the CLIs today; hub-11 and raj-13766 need `claude`/`codex` installed. Sessions are per host like everything else.
2. **Auth for the agent CLIs**: Claude Code and Codex need their own logins on the host (done once in a tmux session from a laptop, or via the PTY view itself). Not something the phone should store.
3. **Cline**: keep PTY-only until its CLI stabilises? (Recommend yes.)
4. **Repo scope**: should `NEW` default cwd to the last repo touched by the Review tab (`git_workspace.py`) so a session opens in the project you were just diffing?

---

## 3. Suggested sequencing across both

1. **A13.2-STREAM Notification stream phone switch pass** — lock boundary: STREAM is strictly a phone-side switch in Companion → Device tab → NLS + STREAM (pointed at gateway/profile); no host `device.stream_on/off` or remote arm (not like arm/disarm); host cannot grant NLS; agent reads ring via `mobile_notifications` (works while disarmed) and gets woken on shade events (Telegram and Gmail silenced so we don't echo). Toggled on/off exclusively via STREAM on the phone.
2. **A9.4 Model reasoning toggle near model list** — replace static amber `REASONING` badge with an interactive toggle / effort selector in `ModelBottomSheet`, `SettingsBottomSheet`, and `GatewayScreen`; wire reasoning state through `createSession` and `streamTurn` payloads; persist in `StickyStore`.
3. **A9.5 Profile global model toggle in profile list** — add interactive global model toggle per profile row in `ProfilesScreen` and `ProfileBottomSheet` to switch between inheriting the host's global model and a profile-specific model override; wire to `POST /api/profiles/<id>/model`.
4. **A26.1–A26.4 Agent Memory Maintenance & Modifications Module** (§6) — host endpoints for profile memory CRUD + dream/consolidate trigger; phone Memory Maintenance UI (view, search, edit, prune, add, dream trigger); Agent modifications UI (soul/persona, prompt directives, skill toggles, model).
5. **A27.1–A27.4 Notification System v2** (§7) — multi-channel stratification (`approvals`, `mentions`, `turns`, `cron`, `health`), `MessagingStyle` enrichment with safe snippet fetch, shade direct reply (`RemoteInput`), one-tap inline approvals (`APPROVE`/`DENY`), thread subscriptions (`WATCH`, `ACTIONS_ONLY`, `MUTED`), host & agent routing matrix in settings.
6. **A22.2 + A22.1** (resync, approvals) — rooms become trustworthy for real work.
7. **A22.10 conversation policy** — the thing that makes rooms feel like a room.
8. **A23.1 + A23.2** — first shippable "Claude/Codex from the phone" via tmux, any tool.
9. **A22.3 / A22.8 / A22.7** — room ergonomics.
10. **A22.4 + A22.11** — standalone turns, then cross-host participants (hub-11 is standalone, so the order matters).
11. **A23.3 + A23.5** — Claude structured with approvals, safety.
12. **A22.5 / A22.6** — wake, summaries.
13. **A23.4, A23.6, A22.9, A23.7** — Codex, notifications, hands, convergence.


---

## 4. Workspace resolution (P24) — the relay points at the wrong directory

### 4.1 What is actually happening (checked 2026-09-08)

| Where | Value | Consequence |
|---|---|---|
| Relay `workspace_dir()` (`relay.py:84`) | `HERMES_WORKSPACE` env, else the relay's own cwd | **One path for every profile.** lab and hub-11 units both set `HERMES_WORKSPACE=/home/nyx`, so Review (git), console `exec`, the PTY and `fs/tree` all operate on the home directory whatever profile is active. |
| Hermes gateways | `WorkingDirectory=<profile home>`, `HERMES_HOME=<profile home>`, no `HERMES_WORKSPACE`; each profile has its own `workspace/` dir (`profiles/coder/workspace`, `profiles/bishop/workspace`); the terminal tool takes `config["cwd"]` (`cwd: .` in `config.yaml`) relative to that | The agent's "here" is its profile directory or its `workspace/`; the phone's "here" is `/home/nyx`. That is the mismatch you see. |
| Phone | `/companion/terminal/*`, `/companion/git/*`, `/companion/fs/*` are called **without `profile=`** | The relay could not pick the right directory even if it wanted to. |

### 4.2 Decisions

| Decision | Choice | Why |
|---|---|---|
| Source of truth | Resolve **per profile, in this order**: explicit `?cwd=` from the phone → profile `config.yaml` `terminal.cwd` when absolute → `<profile home>/workspace` if it exists → `<profile home>` → `HERMES_WORKSPACE` → relay cwd | Mirrors what the agent itself sees; the env var becomes the fallback it should have been. |
| Wire | Every workspace-scoped route takes `profile=` (like sessions already do) and optional `cwd=`; the response echoes the resolved `workspace` so the phone can show it | Same rule as sessions: omitting the profile is a leak-class bug. |
| Phone | Review, console and the future Agent console show `▸ /home/nyx/.hermes/profiles/coder/workspace` under the header with a **workspace picker**: per-profile default, recent repos (from `git_workspace.py` discovery of `~/Projects/*` and `~/.hermes/**/workspace`), free path | You can point Review at the repo you mean without touching a unit file. Choice is sticky per host+profile. |
| Guard | A picked `cwd` must be inside an allow-list (`$HOME`, `HERMES_WORKSPACE`, profile homes); `403 cwd_denied` otherwise | Same boundary the coding-agent sessions use (§2.2). |

### 4.3 Work items

| Item | Scope | Est. |
|---|---|---|
| **A24.1 Host: `resolve_workspace(profile, cwd)`** | New `workspace.py` (order above, allow-list, `discover_repos()`); every `/companion/{git,terminal,fs}` route reads `profile`/`cwd` and echoes `workspace`; `/companion/health.workspace = {default, per_profile:{…}}`; tests with temp homes. | 0.5 d |
| **A24.2 Phone: profile-scoped calls + picker** | `DashboardClient` git/terminal/fs calls carry `profile` (+ `cwd`); `HostToolsController` refetches on profile switch; `WorkspaceStrip` (path + `CHANGE ▾`) on Review and Console; picker sheet (default · recent repos · custom); sticky per host+profile. `LOADING WORKSPACE` row while the first git status lands. | 1 d |
| **A24.3 Units** | `install-services.sh` stops writing `HERMES_WORKSPACE=$HOME`; README documents the resolution order. Existing units keep working (env is now only the fallback). | 0.25 d |

---

## 5. Plugin web console (P25) — "ship a dashboard for the plugin"

**Verdict: not too much, if it stays one static file.** The relay already answers every question in JSON (`/companion/health`, devices, rooms, agent sessions, audit, peers, workspace); the Hermes dashboard has no plugin-panel API to hook into; and the standalone index today is a two-line stub (`standalone.py:208`). A single vanilla-JS page served from the plugin package, no build step, no framework, gets you a laptop-side view in about two days and stays cheap to maintain because it only renders endpoints the phone already depends on.

### 5.1 Decisions

| Decision | Choice | Why |
|---|---|---|
| Form | One file, `hermes-plugin/web/console.html` (+ `console.js`, `console.css`), served at **`/companion/ui/`** | No npm, no bundler, survives `rsync` deploys, diffable in review. |
| Auth | Loopback: open. LAN/Tailscale: **basic auth = the Hermes dashboard's `HERMES_DASHBOARD_BASIC_AUTH_*`** if set, else a token minted by `hermes companion ui-token` (24 h) | Reuses the credential you already type into the Hermes dashboard; never a second password. Device credentials stay phone-only. |
| Scope v1 (read + the few actions that matter from a laptop) | Health & drift · **pairing approvals** (the `hermes companion approve CODE` step, in a browser) · devices/lanes (rename, default, revoke) · peers (link/unlink, §1.5) · rooms (list, live transcript over the existing events WS, post, interrupt) · agent sessions (list, pane snapshot, kill) · audit tail · workspace map (per-profile resolved paths, §4) | Everything here is a wrapper over routes that exist or are planned; the console never grows its own state. |
| Not in v1 | Editing Hermes config, model switching, session browsing (that is the Hermes dashboard's job), chat with agents | Avoid a second Hermes dashboard. |
| Look | Same void/signal/mono tokens as the phone (`tokens.yaml` values inlined) | One product. |

### 5.2 Work items

| Item | Scope | Est. |
|---|---|---|
| **A25.1 Serve + auth** | `/companion/ui/` static route, loopback-open, basic-auth or `ui-token` for remote; `hermes companion ui` prints the URL and opens it; health/drift panel; pairing approve/deny panel (`POST /companion/device/approve` exists via CLI path → expose). | 0.75 d |
| **A25.2 Devices · peers · rooms · audit** | Tables over existing JSON; rooms tab subscribes to `/companion/rooms/events` and renders speaker rows; post + interrupt; audit tail (`audit.py`) with filter. | 1 d |
| **A25.3 Agent sessions · workspace** (after A23.1 / A24.1) | Sessions table with pane snapshot (same `capture-pane` text) and kill; workspace map per profile with the resolution chain. | 0.5 d |

Placement in the overall order: **A24.1 + A24.2** early (it is a correctness bug and the Agent console depends on the picker), **A25.1** right after A22.1/A22.10 so rooms can be watched from a laptop while the conversation policy is tuned, A25.2/A25.3 as their dependencies land.

---

## 6. Agent Memory Maintenance & Modifications Module (P26)

### 6.1 Motivation & Scope
Hermes profiles accumulate long-term memories across conversations, but today memory is completely opaque and unmanageable from the phone:
- **Hallucinated / incorrect memories**: When an agent remembers an incorrect fact, the operator has no mobile mechanism to view, correct, or prune it without SSH-ing into the host.
- **Memory maintenance & dreaming**: Memory growth introduces duplicate or outdated facts. Triggering consolidation (`memory-dream` or pruning passes) currently requires host CLI access.
- **Agent persona & directive adjustments**: Customizing an agent's persona (`SOUL.md`), operational directives, or installed skill enablements currently requires editing host YAML files.

This module delivers a mobile-first **Agent & Memory Management surface** in the Companion app (accessible via the Profiles tab and profile inspector sheets).

### 6.2 Architecture & Wire Protocols

Relay endpoints (paired device or loopback authorized):
```
# Memory Maintenance & Inspection
GET    /companion/profiles/{id}/memory?q=&type=&limit=          → {memories: [{id, content, type, created_at, updated_at, source, confidence}]}
POST   /companion/profiles/{id}/memory {content, type?, tags?}    → 201 {memory}
PATCH  /companion/profiles/{id}/memory/{mem_id} {content, tags?}  → 200 {memory}
DELETE /companion/profiles/{id}/memory/{mem_id}                  → 204
POST   /companion/profiles/{id}/memory/dream                     → 202 {job_id, status: "running"}
GET    /companion/profiles/{id}/memory/dream/status              → {status: "idle"|"running"|"completed", last_run, stats: {consolidated, pruned}}

# Agent Modifications (Persona, Directives, Skills)
GET    /companion/profiles/{id}/agent                            → {soul, system_directives, skills: [{id, name, enabled}], model}
PATCH  /companion/profiles/{id}/agent {soul?, system_directives?, skills?, model?} → 200
```

### 6.3 Phone UI Surface (Companion)

Opening a profile from `ProfilesScreen.kt` (or tapping an inspector action) opens the **Agent & Memory Studio**:

1. **MEMORY Tab**:
   - **Search & Filter**: Monospace search bar (`filter memories`) + filter chips (`ALL · FACTS · PREFERENCES · EPISODIC · RECENT`).
   - **Memory Cards**: Monospace cards showing memory content, creation/update timestamp, source badge (e.g. `chat:sess-123`, `manual`), and confidence pill.
   - **Inline Edit Modal**: Tap a memory card to edit its content, correct erroneous facts, or update tags.
   - **Prune & Delete**: Swipe-to-delete or explicit `DELETE` action with confirmation to remove obsolete entries.
   - **Manual Injection**: `+ ADD MEMORY` button allowing the operator to directly inject critical facts/preferences into the agent's long-term store.
   - **Dream / Consolidate Action**: Header action `DREAM NOW` triggers host-side memory consolidation, displaying live status (`DREAMING · consolidating 12 memories...`) and a summary card when finished.

2. **AGENT Tab (Modifications)**:
   - **Soul / Persona Editor**: Monospace markdown editor for the agent's core persona (`SOUL.md`), previewable directly on mobile.
   - **Directives & Constraints**: Text area for custom system prompt rules (e.g. tone, response length, specific constraints).
   - **Skills & Tools Toggles**: List of installed host skills with instant on/off switches per profile.
   - **Model Configuration**: Model picker with reasoning toggle and global model inheritance toggle (§1.1).

### 6.4 Work Items

| Item | Scope | Est. |
|---|---|---|
| **A26.1 Host: Profile Memory & Agent Config Endpoints** | `hermes-plugin`: Memory store reader/editor (`hermes_store.py` / memory DB wrapper); endpoints `GET/POST/PATCH/DELETE /companion/profiles/{id}/memory`, `POST .../memory/dream` (dispatch `memory-dream` task); `GET/PATCH /companion/profiles/{id}/agent` (soul, directives, skill states); auth + audit logging. | 1.0 d |
| **A26.2 Phone: Memory Maintenance UI (View, Search, Edit, Prune, Add)** | `feature-profiles` / `feature-memory`: Memory view tab, search & filter bar, memory cards, inline edit sheet, delete confirmation, manual memory creation dialog; `DashboardClient` memory methods; Room cache. | 1.5 d |
| **A26.3 Memory Dream / Consolidation Trigger & Status** | Trigger `memory-dream` from mobile header; poll dream status; show consolidation report (synthesized facts, pruned duplicates); error toast / status indicator. | 0.5 d |
| **A26.4 Phone: Agent Modifications UI (Soul, Directives, Skill Toggles)** | Agent tab in Profile Studio: Markdown editor for `SOUL.md` / persona, directives text field, skill toggle list, save action with dirty-state confirmation. | 1.0 d |

---

## 7. Notification System v2 — Enrichment, Thread Subscriptions & Host/Agent Routing (P27)

### 7.1 Motivation & Scope
Today's notification pipeline is barebones and unstratified:
- **Zero snippet/content**: Notifications display generic text (`coder · sess-123`) using Android's system info icon (`WakeNotifier.kt`). Operators cannot tell whether an agent is asking a quick clarification or proposing a major file deletion without opening the app.
- **No inline actionability**: Answering a question or approving a tool requires tapping the notification, waiting for cold launch and WebSocket reconnection, and navigating to the prompt.
- **Unstratified alert volume**: All pings route through a single `"wake"` channel (`IMPORTANCE_HIGH`), meaning ambient room events buzz with the same urgency as critical tool approvals.
- **No thread-level control**: High-velocity rooms or agent loops cannot be silenced or placed into "watch mode" per thread.
- **Multi-host notification flood**: In a fleet (`lab`, `hub-11`, `raj-13766`), there is no way in settings to authorize specific hosts or profiles to send notifications, resulting in cross-host noise.

### 7.2 Architecture & Wire Protocols

#### 1. Dual-Layer Filtering & Routing
```
[Host Event: Turn Done / Clarify / Approval]
                   │
                   ▼
       [Host Relay: Policy Check] ◄── POST /companion/device/notification-policy
       (Drops muted profiles/sessions)
                   │
                   ▼ (ntfy/gotify SSE or WS)
         [WakePing: Metadata Only]
        (type, session_id, profile, origin)
                   │
                   ▼
     [Companion: WakePolicy Filter] ◄── StickyStore (Host & Agent Allowlist)
       (Client-side fail-safe check)
                   │
                   ▼ (If permitted & LAN/Tailscale reachable)
     [Safe Snippet Fetch: GET /companion/notifications/{id}/snippet]
                   │
                   ▼
    [Enriched Notification: MessagingStyle]
   [Channels: Approvals / Mentions / Turns / Cron]
       [Actions: Reply | Approve | Deny | Mute]
```

#### 2. Wire Endpoints
```http
# Device Notification Policy Registration (Host-Side Filtering)
POST   /companion/device/notification-policy
       {
         "muted_profiles": ["scraper", "telegram-mirror"],
         "muted_sessions": ["sess-old-archive"],
         "priority_threshold": "actions_only" | "all" | "mentions_only"
       }                                            → 200 {status: "ok"}

# Safe Snippet Fetch (Authenticated Tail Preview)
GET    /companion/notifications/{session_id}/snippet
       ?type=&speaker=&max_chars=200                → 200 {
                                                        "title": "Fix memory leak",
                                                        "speaker": "knight",
                                                        "avatar": "robot_mark",
                                                        "snippet": "Found 3 references in cache. Should I prune?",
                                                        "kind": "clarify" | "approval" | "turn_complete",
                                                        "choices": ["Yes, prune", "Cancel"]
                                                      }

# Background Direct Reply & Approval Endpoints
POST   /api/sessions/{session_id}/turn               { "prompt": "<reply text>" }
POST   /companion/rooms/{room_id}/approval           { "request_id": "...", "decision": "approve"|"deny" }
```

### 7.3 Mobile UI Surfaces

1. **Rich Android Notifications (`MessagingStyle`)**:
   - **Sender**: Speaker glyph chip + profile + host name (e.g. `[K] knight · lab`).
   - **Body**: Real conversation preview / question snippet fetched safely via authenticated tail call.
   - **Direct Reply (`RemoteInput`)**: Reply inline without opening the companion app; processed in background by `DirectReplyReceiver`.
   - **Inline One-Tap Approvals**: `[✓ APPROVE]` and `[✕ DENY]` buttons directly on the notification for tool calls and room approvals (safety gate redirects to biometric check if command matches sensitive denylist).
   - **Stratified Channels**:
     - `hermes.approvals`: `IMPORTANCE_HIGH` (heads-up, distinct urgent haptic).
     - `hermes.mentions`: `IMPORTANCE_DEFAULT` (sound, standard alert).
     - `hermes.turns`: `IMPORTANCE_LOW` (silent, watch mode).
     - `hermes.cron`: `IMPORTANCE_HIGH` (alarms).
     - `hermes.health`: `IMPORTANCE_MIN` (silent background).

2. **Thread Notification Controls**:
   - **Chat Header Bell Action** (`ChatScreen.kt`):
     - `🔔` = Watch Mode (notify on every turn completion).
     - `🔔·` = Actions Only (notify on questions, approvals, errors; default).
     - `🔕` = Muted (silent, no shade pings).
   - **Temporary Snooze Sheet**: Tapping bell allows quick snooze: `[ 30m ] · [ 2h ] · [ UNTIL TOMORROW ]`.
   - **Thread Rail Actions** (`ThreadsScreen.kt`): Mute/unmute swipe action or context menu; muted indicator icon `🔕` on thread rows.
   - **Persistence**: Stored in Room database (`thread_notification_prefs` table).

3. **Notification Hub in Settings**:
   - Accessible via `SettingsBottomSheet.kt` and dedicated Notification Hub:
     - **Global Toggles**: Master notifications pause, rich snippet previews (hide on lock screen for privacy), direct reply enable.
     - **Gateway Host Policies**: Per-host notification toggle (`Allow Notifications from this Host: ON/OFF`) in Gateway Book.
     - **Agent Allowlist Matrix**: Per-profile toggles grouped by host (`knight: ON`, `coder: APPROVALS ONLY`, `scraper: OFF`).
     - **Link to OS Notification Channels**: Direct deep link to Android system settings for Hermes Companion channels.

### 7.4 Work Items

| Item | Scope | Est. |
|---|---|---|
| **A27.1 Multi-Channel Stratification & Notification Enrichment** | Channels: `approvals`, `mentions`, `turns`, `cron`, `health`; `MessagingStyle` notification builder in `WakeNotifier.kt`; speaker avatar glyph, profile & host tags, visual priority badges; safe authenticated snippet fetch (`GET /companion/notifications/{id}/snippet`). | 1.0 d |
| **A27.2 Shade Actionability: Direct Reply & Inline Approvals** | Direct Reply `RemoteInput` in notification; `DirectReplyReceiver` background turn dispatch via `DashboardClient`; inline `[APPROVE]` and `[DENY]` actions for `approval.request` and `room.approval`; safety gate (sensitive actions route to biometric unlock). | 1.0 d |
| **A27.3 Thread Notification Subscriptions & Muting** | Thread subscription modes (`WATCH`, `ACTIONS_ONLY`, `MUTED`, `SNOOZE`); chat header bell selector; thread rail swipe/long-press mute action; `ThreadNotificationPref` Room DB entity & DAO; sync state to host. | 1.0 d |
| **A27.4 Gateway Host & Agent Notification Routing in Settings** | Notification settings hub; host-level master toggles in Gateway Book; profile allowlist matrix; dual-layer filtering: host-side `/companion/device/notification-policy` sync + fail-safe client-side check in `WakePolicy.kt`. | 1.0 d |


