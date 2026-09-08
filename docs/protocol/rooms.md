# Rooms protocol (agent group chat) — v2

Plugin-owned routes on the companion relay (`:9120`). Rooms run agent turns through the dashboard
websocket when the relay proxies a dashboard (`HERMES_COMPANION_STANDALONE=0`, streaming) or through
`hermes chat -Q` when the host is standalone (text only, the reply lands whole). `/companion/health`
reports `"rooms": "ok" | "ok_text_only" | "unavailable_standalone" | "upstream_down"` and `"peers": [...]`.

## Auth

Everything under `/companion/` except the pairing handshake (`/companion/device/pair…`,
`/companion/device/register`), `/companion/health`, the ticket-gated `/companion/device/ws` and
`/companion/media/<id>` requires **either** a loopback caller (CLI, in-process agent) **or**

```
Authorization: Companion <device_id>:<credential>
```

from a paired phone. `/companion/peers/turn*` accepts **only** a peer credential
(`Authorization: Peer <host_id>:<secret>`, see *Peers*). Unauthorized → `401 {"error":"unauthorized"}`.

## Model

```
Room        { id, title, participants:[{id, profile, host?, glyph}], policy, mode,
              state: "idle"|"running"|"quiet"|"paused", pause_reason: ""|"budget"|"time"|"stall"|"operator",
              turns_used, budget, hands, moderator, approval?, summary?, seq, message_count, busy, speaking,
              created_at, updated_at, last }
Policy      { mode: "converse"|"moderated"|"bounded", max_turns (1–60, default 12), max_minutes (default 10),
              cooldown (0–3, default 1), moderator?, hands?, max_rounds (bounded only, 1–4),
              turn_timeout_s, approval_timeout_s }
RoomMsg     { seq, speaker ("operator" | participant id), glyph, role ("user"|"assistant"), text, ts_ms,
              round, turn_id, tools:[{name, detail}], passed, error, kind: ""|"summary", mentions_operator }
Approval    { request_id, kind ("approval"|"clarify"|"sudo"|"secret"), speaker, turn_id, command, choices, expires_in_s }
```

Participant ids are `profile` (local) or `profile@peer` (remote). `glyph` is the first three letters of
the profile, upper-cased; when two participants collide the host tag is appended (`ASH·L`, `ASH·H`).
The operator is `YOU`.

## REST

| Call | Body → Result |
|---|---|
| `GET /companion/rooms` | `{rooms:[Room…]}` |
| `POST /companion/rooms` | `{title?, participants:[id…], policy?}` → `201 {room}`; `409 peer_unknown` for an unlinked `profile@peer` |
| `GET /companion/rooms/{id}` | `{room}` |
| `PATCH /companion/rooms/{id}` | `{title?, policy?}` → `{room}` (policy merges into the current one) |
| `DELETE /companion/rooms/{id}` | interrupts, deletes; backing sessions stay in their profiles |
| `GET /companion/rooms/{id}/history?after=<seq>&limit=` | `{room, messages:[RoomMsg…], seq}` |
| `POST /companion/rooms/{id}/post` | `{text}` → `202 {seq, message}`. **Never 409**: a line posted while agents talk is appended and steers the floor |
| `POST /companion/rooms/{id}/pause` | soft stop after the current turn → `{pausing, room}` |
| `POST /companion/rooms/{id}/continue` | `{turns?: 6}` → `202 {room}`: widens the budget and resumes a paused/quiet room |
| `POST /companion/rooms/{id}/interrupt` | hard stop → `{interrupted}` |
| `POST /companion/rooms/{id}/approval` | `{request_id, decision}` → answers the pending agent approval; `409 no_pending_approval` |
| `POST /companion/rooms/{id}/summarize` | `{by?}` → `202 {room}`: one extra turn tagged `summary`, pinned |
| `POST /companion/rooms/{id}/participants` | `{add:[…], remove:[…]}` → `{room}` |
| `GET /companion/rooms/guard?profile=` | `{in_room_turn, allowed, hands, room_id}` — the hands gate used by `mobile_*` tools |

## Events — `GET /companion/rooms/events?room_id=<id>` (websocket)

Text frames, one JSON object each. Omit `room_id` to receive every room.

| type | fields |
|---|---|
| `room.ready` | `room_id, seq, room` — replay `history?after=<your seq>` from here |
| `room.post` | `room_id, message` (operator line, echoed to all clients) |
| `room.state` | `room_id, state, reason, turns_used, max_turns, budget, seq` — every transition |
| `room.turn.start` | `room_id, speaker, glyph, turn_id, round, turns_used, max_turns` |
| `room.delta` | `room_id, speaker, turn_id, text` |
| `room.tool.start` / `room.tool.complete` | `room_id, speaker, turn_id, name, detail[, duration_ms]` |
| `room.approval` | `room_id, speaker, turn_id, request_id, kind, command, choices, expires_in_s` — answer via `POST …/approval` |
| `room.approval.resolved` | `room_id, request_id, speaker, decision` (`timeout` / `interrupted` when nobody answered) |
| `room.turn.end` | `room_id, speaker, turn_id, seq, passed, error, message, turns_used` |
| `room.idle` | `room_id, seq, state, reason` — the plan is over (quiet, paused or idle) |
| `room.interrupted` | `room_id` |
| `room.updated` | `room_id, room` (rename / policy / participants) |
| `room.created` / `room.deleted` | `room_id[, room]` |
| `room.heartbeat` | every 15s of silence |

## Turn policy (v2)

**converse** (default) — the floor, not the rounds:

1. The *topic* is the last line with real content (operator or agent, not PASS).
2. Participants addressed in the topic (`@GLYPH`) speak first, in order, if they have not spoken since.
3. Otherwise every participant that has not spoken since the topic gets the floor, least-recent speaker
   first; nobody speaks twice in a row unless addressed (`cooldown`).
4. A participant that replies exactly `PASS` is skipped until someone else speaks or the operator posts.
5. The exchange goes **quiet** when everyone has passed since the last real line.
6. `max_turns` / `max_minutes` per operator post are circuit breakers: the room **pauses** with
   `reason: budget|time`; `continue` widens the window; a new operator line resets it.
7. A speaker repeating itself (near-duplicate of one of its last two lines, ≥ 40 chars) pauses the room
   with `reason: stall`.

**moderated** — the moderator speaks after the operator and after any reply that addresses nobody; it
picks the next speaker with `@GLYPH` and ends the topic with exactly `[END]`.

**bounded** — v1: mention-first, then round-robin, hard stop at `max_rounds`.

In every mode the operator may post at any time and `pause` (soft) / `interrupt` (hard) are available.

## Approvals

When an agent raises `approval.request` / `clarify.request` / `sudo.request` / `secret.request` during
a room turn, the turn waits (up to `approval_timeout_s`), the room emits `room.approval` and the room
shows it in `Room.approval`. `POST …/approval {request_id, decision}` answers it on the agent's own
session (`approval.respond` & co.) and the turn resumes. No answer → the turn ends with
`error: approval_timeout`.

## Peers (cross-host participants)

A room lives on one host. A participant `profile@peer` runs its turns on the peer relay:

```
POST /companion/peers/grant   {name}                          → 201 {grant:{host_id, secret, name}}   (on the host that will RUN turns)
POST /companion/peers         {name, origin, host_id, secret} → 201 {peer}                            (on the host that OWNS rooms)
GET  /companion/peers                                         → {peers:[…], grants:[…]}
GET  /companion/peers/{name}                                  → {peer, reachable, health}
DELETE /companion/peers/{name} · DELETE /companion/peers/grants/{host_id}
POST /companion/peers/turn    Authorization: Peer host_id:secret
     {turn_id, profile, session_id?, title, text, timeout_s, approval_timeout_s}
     → 200 application/x-ndjson: {type: session|delta|tool.start|tool.complete|approval|complete|error, …}
POST /companion/peers/turn/{turn_id}/approval {request_id, decision} · POST /companion/peers/turn/{turn_id}/interrupt
```

The phone, paired with both hosts, brokers grant → add in two calls; the CLI does the same with
`hermes companion peer grant NAME` / `peer add NAME ORIGIN HOST_ID SECRET`. Remote approvals ride the
same `room.approval` path. A peer that is down fails only its own turns (`error: peer_unreachable`).

## Operator wake

When no phone is watching a room, `room.quiet`, `room.paused`, `room.approval` and an `@YOU` mention
publish a small JSON ping (`type, room_id, session_id=room_id, profile, title`) to every paired
device's `wake_topic` (sent by the phone on `/companion/device/register`).

## What an agent receives

Each participant owns a backing session in its own profile, titled `room:<id> <title>` (hidden by the
phone's thread rail). A turn is a normal `prompt.submit` on that session whose text is the room
preamble (first turn) or a one-line header (later turns) plus every line the agent has not yet seen:

```
[room "ops + coder triage" · you are OPS · others: COD, BIS (hub-11), YOU(operator)]
[rules: this is a live conversation. Reply when you have something useful to add or ask; reply exactly PASS when you do not; address a participant with @GLYPH to ask them directly; @YOU reaches the operator; you may disagree, ask follow-ups and change your mind; when the group has reached a conclusion, say so in one line and PASS; do not call mobile_* control tools from a room turn (hands: @COD)]
[room] YOU: gateway restarted twice, who owns the fix?
[COD ran terminal · journalctl -u hermes-gateway]
[room] COD: mint fails after 30s. @OPS can you pull the journal?
```

```
[room OPS · 9 of 12 turns left before the operator is asked to continue · reply / @GLYPH / PASS]
[room] BIS (hub-11): the second restart was manual.
```

Standalone (text-only) hosts have no backing session; every turn carries the preamble plus the last
60 lines.

## CLI

```
hermes companion room list
hermes companion room create "ops + coder triage" coder ops bishop@hub-11 --mode converse --turns 12 --hands coder
hermes companion room post r-abc123 gateway restarted twice, who owns it?
hermes companion room pause|continue|summarize|approve r-abc123 …
hermes companion room mode r-abc123 moderated --moderator ash
hermes companion room participants r-abc123 --add knight --remove ops
hermes companion room rename r-abc123 new title
hermes companion room history|interrupt|delete r-abc123
hermes companion peer grant lab            # on hub-11
hermes companion peer add hub-11 http://100.88.4.63:9120 HOST_ID SECRET   # on lab
hermes companion peer list|check|remove|revoke …
```
