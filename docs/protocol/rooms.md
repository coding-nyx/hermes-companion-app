# Rooms protocol (agent group chat)

Plugin-owned routes on the companion relay (`:9120`). Rooms need the real dashboard upstream
(`HERMES_COMPANION_STANDALONE=0`); in standalone mode `POST /companion/rooms` returns
`503 rooms_unavailable` and `/companion/health` reports `"rooms": "unavailable_standalone"`.

## Auth

Everything under `/companion/` except the pairing handshake (`/companion/device/pair…`,
`/companion/device/register`), `/companion/health`, the ticket-gated `/companion/device/ws` and
`/companion/media/<id>` requires **either** a loopback caller (CLI, in-process agent) **or**

```
Authorization: Companion <device_id>:<credential>
```

from a paired phone. The app sends it automatically once a device credential exists for the host
(REST via interceptor, room events socket explicitly). Unauthorized → `401 {"error":"unauthorized"}`.

## Model

```
Room        { id, title, participants:[{profile, glyph}], policy:{max_rounds, turn_timeout_s},
              seq, message_count, busy, speaking, created_at, updated_at, last }
RoomMsg     { seq, speaker ("operator" | profile), glyph, role ("user"|"assistant"), text,
              ts_ms, round, turn_id, tools:[{name, detail}], passed, error }
```

`glyph` = first three letters of the profile id, upper-cased; the operator is `YOU`.

## REST

| Call | Body → Result |
|---|---|
| `GET /companion/rooms` | `{rooms:[Room…]}` |
| `POST /companion/rooms` | `{title?, participants:[profile…], policy?:{max_rounds}}` → `201 {room}` |
| `GET /companion/rooms/{id}` | `{room}` |
| `DELETE /companion/rooms/{id}` | interrupts, deletes; backing sessions stay in their profiles |
| `GET /companion/rooms/{id}/history?after=<seq>&limit=` | `{room, messages:[RoomMsg…], seq}` |
| `POST /companion/rooms/{id}/post` | `{text}` → `202 {seq, message}`; `409 room_busy` while a plan runs |
| `POST /companion/rooms/{id}/interrupt` | `{interrupted: bool}` |
| `POST /companion/rooms/{id}/participants` | `{add:[…], remove:[…]}` → `{room}` |

## Events — `GET /companion/rooms/events?room_id=<id>` (websocket)

Text frames, one JSON object each. Omit `room_id` to receive every room.

| type | fields |
|---|---|
| `room.ready` | `room_id` |
| `room.post` | `room_id, message` (operator line, echoed to all clients) |
| `room.turn.start` | `room_id, speaker, glyph, turn_id, round` |
| `room.delta` | `room_id, speaker, turn_id, text` |
| `room.tool.start` / `room.tool.complete` | `room_id, speaker, turn_id, name, detail[, duration_ms]` |
| `room.turn.end` | `room_id, speaker, turn_id, seq, passed, error, message` |
| `room.idle` | `room_id, seq` — the plan for the last operator post is finished |
| `room.interrupted` | `room_id` |
| `room.created` / `room.deleted` | `room_id[, room]` |
| `room.heartbeat` | every 15s of silence |

## Turn policy (v1)

1. Operator posts. `@GLYPH` / `@profile` mentions pick the speakers for round 1, in mention order;
   no mentions → every participant, in room order.
2. A reply that mentions another participant queues that participant for the next round, unless
   it is already queued (it will see the line anyway).
3. Hard stop at `max_rounds` (1–4, default 2). A reply that is exactly `PASS` is recorded as
   `passed` and never triggers anyone.
4. One plan per room at a time; `interrupt` cancels the queue and sends `session.interrupt` to the
   active backing session.

## What an agent receives

Each participant owns a backing session in its own profile, titled `room:<id> <title>` (hidden by
the phone's thread rail). A turn is a normal `prompt.submit` on that session whose text is the
room preamble (first turn only) plus every line the agent has not yet seen:

```
[room "ops + coder triage" · you are OPS · others: COD, YOU(operator)]
[rules: reply to the room in plain text; mention @COD to hand off; reply exactly PASS to stay silent; max 2 rounds per operator message; do not call mobile_* control tools from a room turn]
[room] YOU: gateway restarted twice, who owns the fix?
[COD ran terminal · journalctl -u hermes-gateway]
[room] COD: mint fails after 30s. @OPS can you pull the journal?
```

Other agents' tool calls arrive as one-line summaries, never raw output.

## CLI

```
hermes companion room list
hermes companion room create "ops + coder triage" coder ops --rounds 2
hermes companion room post r-abc123 gateway restarted twice, who owns it?
hermes companion room history r-abc123
hermes companion room interrupt r-abc123
hermes companion room delete r-abc123
```
