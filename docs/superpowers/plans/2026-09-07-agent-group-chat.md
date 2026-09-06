# Agent group chat (rooms) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (preferred) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator open a **room** on the phone where two or more Hermes profiles (agents) and the operator talk in one transcript. Each agent keeps its own identity, memory, tools and model; the room is a host-side construct that fans messages out and streams each agent's reply back with a speaker label. Agents can address each other, the operator can interrupt everything, and agent-to-agent chatter is bounded so a room can never run away.

**Architecture:** Room state lives in the **hermes-companion plugin** (host), next to pairing/relay state. Every participant profile gets a **backing session** in its own profile (a real Hermes session, so memory/tools/transcript are genuine). A `RoomController` runs turns: operator post → fan-out to participants per **turn policy** → each participant turn is a normal `prompt.submit` on its backing session, prefixed with the room delta it has not seen → reply streams back through the relay as `room.*` events with `speaker` → appended to the room transcript. The phone renders a room as a thread whose agent rows are labelled by profile glyph instead of `HERMES`.

**Tech stack:** Python plugin (`relay.py` routes under `/companion/rooms/*`, new `rooms.py`), dashboard JSON-RPC upstream client per profile (streams `assistant.delta`/`tool.*`), Kotlin/Compose app (`core-model` `RoomRef`/speaker, `data-remote` room client, `feature-chat` speaker rows, `feature-threads` ROOMS rail section).

**Repo:** `/home/pacman/Projects/hermes-companion-app` @ `main` (after v0.2.0)
**Tracks:** new **P21 — Agent Rooms** (A21.1–A21.6) in `docs/WORK_ITEMS.md`.
**Constraint:** PLAN ONLY until Nyx nods. Rooms need the real dashboard (`HERMES_COMPANION_STANDALONE=0` proxy); the standalone stub cannot run agent turns.

**Decisions baked in (challenge before build):**

| Decision | Choice | Why |
|---|---|---|
| Where the room lives | Host plugin, not phone, not a Hermes session | Phone is a window; plugin already owns pairing/relay/agent-wake and runs in the Hermes process. A Hermes session is single-agent by design. |
| Agent identity | One Hermes **profile** = one participant | Profiles already carry soul, model, skills, memory. No new agent abstraction. |
| Agent context | Backing session per participant, room delta injected as `[room] <GLYPH>: …` blocks before each turn | Agents keep long-term memory; the room does not fork a second identity. Same trick `mobile_notifications_inject` uses (`[phone notification]` prefix). |
| Turn policy v1 | **Mention-first, then round-robin**, max **N rounds** per operator post (default 2), agents may reply `PASS` | Bounded cost, no infinite loops, still lets agents build on each other. |
| Streaming | Reuse `assistant.delta` / `tool.start` / `tool.complete` shapes with an added `speaker` | Phone reducer/coalescer already handle this; only the label changes. |
| Design | One accent stays. Agents differ by **3-letter glyph** (`COD`, `OPS`, `ASH`) and **rail style** (solid / dashed / dotted), operator keeps the right-hand `YOU` panel | tokens.yaml: "do not invent a second green". |
| Hands in a room | v1: **no** phone control from a room turn | Two agents fighting over one screen is a safety problem; needs a controller-election design (A21.6). |
| Cross-host rooms | Single host in v1 | Multi-host adds identity + auth plumbing; phone already handles per-host lanes, revisit after v1 lands. |

---

## Investigation snapshot (2026-09-07 IST)

### Existing pieces to reuse

| Area | What exists | Pointers |
|---|---|---|
| Per-profile agent turn from the host | `agent_wake` runs `hermes chat -Q -p <profile>` (sync, text only) | `hermes-plugin/agent_wake.py` `_chat_q_argv`, `_run_chat_q` |
| Streaming agent turn from the host | Relay proxies dashboard WS; `prompt.submit` on a profile-scoped `/api/ws?profile=` streams `assistant.delta`, `tool.start/complete`, `message.complete` | `relay.py` `_proxy`, `docs/protocol/operator.md` |
| Append text to a session without a reply | `Operator.inject_context(sid, profile, text, role)` (standalone) and `mobile_notifications_inject` flow | `standalone.py:330`, `tools.py:337` |
| Host-side JSON store with lock | `PairingStore` (`companion-devices.json`, mode 600), `Operator._save` | `pairing.py`, `standalone.py` |
| Phone stream pipeline | `streamTurn` → `coalesceDeltas()` → `applyEvent` (segments around tools, `toolRunning`) → role rows | `DashboardClient.kt`, `ChatSessionManager.kt`, `ChatScreen.kt` |
| Role UI | `UserRow` / `AssistantRow` / `PendingRow` with `HERMES` label + rail | `feature-chat/.../ChatScreen.kt` (this branch) |
| Profile glyphs | `ProfileGlyph` (3-letter) | `core-design/.../Chrome.kt` |
| CLI surface | `hermes companion …` + `cli_main.py` fallback | `cli.py`, `cli_main.py` |
| Skill / system prompt hint | `_prompt()` + `skills/hermes-companion/SKILL.md` | `__init__.py` |

### Gaps (why this plan)

- No host object that spans profiles. Every session is `(host, profile, session_id)` and the app enforces "never mix profiles in one list".
- Plugin has no **streaming** dashboard client of its own; `agent_wake` shells out and gets text at the end. Rooms need `prompt.submit` over an upstream WS with event forwarding.
- `ChatMessage` has no `speaker`; `AssistantRow` hard-codes `HERMES`.
- No place in the phone UI for a room list, participant strip, or "who is typing".
- No loop guard: two agents replying to each other is currently unbounded by design.

---

## Architecture

```
┌─ Android ──────────────────────────────────────────────────────────┐
│ THREADS rail: … | ROOMS ▸ "ops+coder triage" (COD OPS ●)          │
│ Room chat: YOU panel | COD rail (solid) | OPS rail (dashed)         │
│   header: COD ● streaming   ·   typing strip: OPS thinking ···     │
│ Composer: SEND · @COD/@OPS mention chips · INTERRUPT ALL           │
└───────────────┬────────────────────────────────────────────────────┘
                │ /companion/rooms/* REST + WS events (relay :9120)
┌─ hermes-companion plugin (host) ───────────────────────────────────┐
│ rooms.py                                                           │
│   RoomStore  ~/.hermes/companion-rooms.json (mode 600)             │
│     Room{id,title,participants[profile],backing{profile→session}, │
│          transcript[RoomMsg{seq,speaker,text,tools[],ts}],policy}  │
│   RoomController                                                   │
│     post(operator text) → plan_turns(policy, mentions)             │
│     for each turn: build delta since participant.last_seen_seq     │
│       → upstream WS ?profile=P → prompt.submit(backing session)    │
│       → forward events as room.* {room_id, speaker, …}             │
│       → on complete: append RoomMsg, bump seq, maybe next turn     │
│     guards: max_rounds, PASS detection, cooldown, interrupt_all    │
│   UpstreamWs (per profile, lazy, reconnect) → dashboard :9119      │
└────────────────────────────────────────────────────────────────────┘
```

### Room protocol (plugin-owned, `/companion/rooms`)

| Call | Shape |
|---|---|
| `POST /companion/rooms` | `{title, participants:[profile…], policy?}` → `{room}` (creates backing sessions lazily, titled `room:<id>`) |
| `GET /companion/rooms` | `{rooms:[…]}` |
| `GET /companion/rooms/{id}/history?after=seq` | `{messages:[RoomMsg…], seq}` |
| `POST /companion/rooms/{id}/post` | `{text, mentions?:[profile], parts?}` → `{seq}`; starts the turn plan |
| `POST /companion/rooms/{id}/interrupt` | stops all in-flight turns (`session.interrupt` on each backing session) |
| `POST /companion/rooms/{id}/participants` | add/remove profile |
| WS `/companion/rooms/{id}/events` (or piggyback on the operator WS with `room_id`) | `room.turn.start{speaker}`, `room.delta{speaker,text}`, `room.tool.start/complete{speaker,…}`, `room.turn.end{speaker,seq,passed}`, `room.round.end{round}` |

Auth: same as operator lane (bearer/basic or ticket). **Also closes the open finding that `/companion/*` has no auth gate** — rooms must not ship on an unauthenticated prefix; A21.1 includes the gate.

### Turn policy v1

1. Operator posts. If the text contains `@GLYPH` mentions → mentioned agents speak in mention order; else all participants speak in room order (round 1).
2. After round 1, any agent whose reply mentions another participant triggers that participant (round 2). Unmentioned agents stay quiet.
3. Hard stop at `max_rounds` (default 2, room setting 1–4). A reply that is exactly `PASS` (or starts with `PASS —`) is recorded but does not count as a mention and is rendered as a muted `passed` row.
4. Operator `INTERRUPT ALL` cancels in-flight turns and clears the plan.
5. Agents are told the rules through a room preamble on their first turn and a short reminder each turn (see skill text).

### What an agent sees on its turn

```
[room "ops+coder triage" · you are OPS · others: COD, YOU(operator)]
[rules: reply to the room; @COD to hand off; reply PASS to stay silent; max 2 rounds]
[room] YOU: gateway restarted twice tonight, who owns the fix?
[room] COD: I see the ticket mint failing after 30s. @OPS can you pull journal?
```

The backing session is that agent's own Hermes session; everything above is injected as the user turn, so tools, memory and soul apply unchanged.

---

## Slices

### A21.1 · Host: room store + controller + auth gate (P1, ~3d)
- [ ] `rooms.py`: `RoomStore` (json, lock, mode 600), `Room`, `RoomMsg`, `Policy` dataclasses; `seq` monotonic.
- [ ] `UpstreamWs`: minimal dashboard WS client per profile (reuse `_ws_text`/`_ws_recv` from `relay.py`), `prompt.submit` + event iterator, `session.create` for backing sessions.
- [ ] `RoomController.post()` → turn plan (mention-first, round-robin, `max_rounds`, PASS), sequential execution, `interrupt_all`.
- [ ] Relay routes `/companion/rooms/*` + `room.*` event fan-out to connected operator WS clients.
- [ ] **Auth gate for `/companion/*` admin/command paths** (loopback or operator session); pairing offer/status stay open.
- [ ] Tests against `mock-dashboard/server.py`: two profiles, mention hand-off, round cap, PASS, interrupt. Python 3.10/3.12.

### A21.2 · Agent-side skill + prompt hint (P1, ~0.5d)
- [ ] `SKILL.md` section "Rooms": rules, `@GLYPH`, `PASS`, never call `mobile_*` control tools from a room turn (v1).
- [ ] `_prompt()` adds a room hint when the current session is a backing session (title prefix `room:`).

### A21.3 · Phone: models + client (P1, ~1.5d)
- [ ] `core-model`: `RoomRef`, `RoomPolicy`, `ChatMessage.speaker: String? = null` (null ⇒ `HERMES`), `ChatEvent` gains optional `speaker`.
- [ ] `data-remote`: `RoomClient` (REST + events), `RpcCodec` maps `room.*` → `ChatEvent(speaker=…)`.
- [ ] `ChatSessionManager`: room mode uses the same `coalesceDeltas()` → `applyEvent` path; segments keyed by `(turnId, speaker)`.
- [ ] Tests: reducer with interleaved speakers; codec mapping.

### A21.4 · Phone: UI (P1, ~2.5d)
- [ ] Threads rail: `ROOMS` section, row = title + participant glyph strip + live dot while a turn runs.
- [ ] Create room sheet: title, multi-select profiles (from `/api/profiles`), `max_rounds`.
- [ ] `AssistantRow` label = speaker glyph; rail style by participant index (solid/dashed/dotted); `PendingRow` shows `COD thinking ···` / `OPS running · terminal`.
- [ ] Composer: mention chips `@COD @OPS`, `INTERRUPT ALL` while any turn runs, `passed` muted row.
- [ ] Robolectric render tests: two speakers, one passing, one streaming; PNG captures.

### A21.5 · CLI + docs (P2, ~0.5d)
- [ ] `hermes companion room create|list|post|interrupt`; `cli_main.py` fallback.
- [ ] `docs/protocol/rooms.md`; README "Rooms" section; WORK_ITEMS status.

### A21.6 · Hands in rooms (P2, later)
- [ ] Controller election: exactly one participant may hold the phone; others get `room_hands_locked`. Needs broker + `DeviceLanePolicy` changes. Out of v1.

---

## Risks / open questions for Nyx

1. **Cost and runaway chatter.** Default `max_rounds=2` and PASS keep it bounded; do you want a per-room token/time budget too?
2. **Backing sessions clutter the thread rail.** They are real sessions titled `room:<id>`. Hide them behind the ROOMS section by title prefix, or show them?
3. **Host mode.** Rooms need the dashboard. The in-process relay defaults to standalone ON; ship rooms with a health check that says `rooms: unavailable (standalone)` rather than failing silently.
4. **Who sees tool calls.** Plan: other agents receive a one-line tool summary (`[COD ran terminal · journalctl …]`), not raw output. Operator sees full tool rows as today.
5. **Cross-host participants** (lab + raj): not in v1. Say so if you want it earlier; it changes A21.1's upstream client into a multi-origin one.
