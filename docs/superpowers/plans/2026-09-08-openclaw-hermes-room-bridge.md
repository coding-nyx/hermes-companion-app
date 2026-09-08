# Native OpenClaw ↔ Hermes Room Bridge — Plan

**Status:** PLAN ONLY (2026-09-08). Nothing here is built.
**Tracks:** **P28 — Hermes ↔ OpenClaw Agent Room Bridge** (A28.1–A28.4) in `docs/WORK_ITEMS.md`.
**Depends on:** A22.11 cross-host room participants.
**Distinct from P19:** P19 makes the Android companion an OpenClaw operator/node client. P28 connects Hermes-hosted rooms to OpenClaw agents; it does not add OpenClaw as a phone gateway or expose the phone's `mobile_*` tools to OpenClaw.

---

## 1. Decision

Build a new native OpenClaw plugin, `hermes-room-bridge`, alongside the existing Hermes plugin. Do not convert `hermes-companion`: a compatible content bundle can map skills and MCP tools, but the bridge needs native OpenClaw HTTP ingress, persistent subagent sessions, agent-event subscriptions and exact-run cancellation.

Hermes remains authoritative for rooms, participants, floor control and the shared transcript. OpenClaw remains authoritative for its agents, model/tool policy, sandbox and approvals. The first release supports same-machine, LAN and Tailscale/private hosts only.

## 2. OpenClaw plugin

Create `openclaw-hermes-room-bridge/` with a TypeScript ESM runtime, built JavaScript distribution, unit tests, README, bundled room-etiquette skill, `package.json` and `openclaw.plugin.json`.

- Pin v0.1 to OpenClaw 2026.8.2 because the native plugin API is experimental. Declare startup activation, strict configuration, CLI ownership, skills and every registered tool in the manifest.
- Store reciprocal peer credentials as SecretRef-aware configuration. Never print secrets from list, health or check commands.
- Expose only explicitly allowed OpenClaw agent IDs. A Hermes participant such as `main@openclaw` maps to the exact configured OpenClaw agent `main`; unknown agents fail closed.
- Add `openclaw hermes-room grant|add|list|check|remove|revoke` for reciprocal peer setup. Hermes continues to use `hermes companion peer grant|add|list|check|remove|revoke`.

### 2.1 Hermes-to-OpenClaw turns

Register plugin-managed-auth HTTP routes on the OpenClaw Gateway:

```text
POST /companion/peers/turn
POST /companion/peers/turn/{turn_id}/interrupt
POST /companion/peers/turn/{turn_id}/approval
```

The turn request is protocol v2:

```json
{
  "protocol_version": 2,
  "turn_id": "turn-id",
  "room_id": "room-id",
  "participant_id": "main@openclaw",
  "profile": "main",
  "session_id": "optional prior OpenClaw session key",
  "title": "room title",
  "text": "Hermes room prompt",
  "timeout_s": 180,
  "approval_timeout_s": 600
}
```

- Authenticate with `Authorization: Peer <host_id>:<secret>` using constant-time comparison.
- Create a deterministic plugin-owned OpenClaw session key from peer host, room and participant; resume it on later turns. Reject a supplied session key that is not the stored binding for that tuple.
- Start the selected agent with its normal configured model, tools, sandbox and approval policy. Do not request a model override or weaken tool policy.
- Return Hermes-compatible NDJSON events: `session`, assistant `delta`, sanitized `tool.start`, sanitized `tool.complete`, and terminal `complete` or `error`.
- Never forward reasoning, raw tool parameters/results, credentials or command output.
- OpenClaw approvals stay in OpenClaw. Do not emit an actionable Hermes approval event; the approval endpoint returns `409 approval_owned_by_openclaw`.
- Interrupt only the exact active `runId` through OpenClaw's `sessions.abort`, then emit a terminal interrupted result. Never use a global or session-wide abort when a run ID is available.
- Keep an in-memory active-turn registry and a short-lived durable terminal-result cache keyed by authenticated peer plus `turn_id`. A retry replays a terminal result and never starts duplicate work; an active duplicate returns `409 turn_in_progress`.
- Enforce bounded request bodies, deadlines, per-peer/global concurrency, NDJSON backpressure and cleanup on disconnect/restart.

### 2.2 OpenClaw-to-Hermes initiation

Register two optional tools:

- `hermes_rooms_list`: list only rooms on configured Hermes peers in which the calling OpenClaw agent has a mapped participant.
- `hermes_room_post`: post text, including `@GLYPH` mentions, as the calling agent's mapped participant.

Infer the author from trusted OpenClaw tool context/session identity; never accept an arbitrary participant or author argument. Reject posting from that agent's currently active bridged turn so its normal assistant reply remains the only room contribution for that turn.

The bundled skill explains room etiquette, `PASS`, mentions, local approval ownership and when the initiation tools are appropriate.

## 3. Hermes protocol extension

Extend the existing peer implementation without changing current Hermes-to-Hermes behavior.

- Add `protocol_version`, `room_id` and `participant_id` to outbound remote-turn bodies. Accept v1 peers that ignore the additive fields.
- Send peer authentication on health checks instead of relying on public health.
- Add peer-authenticated endpoints:

```text
GET  /companion/peers/rooms
POST /companion/peers/rooms/{room_id}/post
```

- Room listing returns only rooms containing a participant owned by the authenticated peer.
- Participant post accepts text only. Resolve the author from the authenticated peer plus the requested mapped agent, verify membership, preserve external-agent authorship, append one room message and feed it through the existing mention/floor scheduler.
- Reject unknown rooms, non-members, mismatched peer suffixes, empty/oversized text, paused/deleted rooms and concurrent duplicate posts with typed errors.
- An OpenClaw-originated line may wake or schedule Hermes participants exactly like another agent line; it must never be recorded as an operator message.

## 4. Security and failure behavior

- Default to private-host-only origins: loopback, RFC1918, CGNAT/Tailscale, link-local, IPv6 ULA/link-local and approved private DNS suffixes. Resolve and re-check addresses at connection time to prevent public-host or DNS-rebinding escapes.
- Permit cleartext HTTP only for those private destinations; use normal certificate validation for HTTPS.
- Credentials grant only peer-turn and joined-room operations. They do not grant OpenClaw admin access, arbitrary Hermes REST access, agent impersonation or mobile control.
- A failed OpenClaw participant turn fails only that participant and does not stop the Hermes room. Timeouts and disconnects are terminal, typed and safe to retry by `turn_id`.
- Local OpenClaw approval waits may outlive a Hermes observation timeout, but they are never auto-approved or answered with Hermes credentials.

## 5. Work items

| Item | Scope | Est. |
|---|---|---:|
| **A28.1 OpenClaw native plugin scaffold & security** | Package/manifest/build, SecretRef configuration, allowed-agent mapping, reciprocal CLI, private-origin validation and bundled skill. | 1.0 d |
| **A28.2 Hermes-to-OpenClaw room turns** | Authenticated NDJSON ingress, persistent subagent binding, event translation, idempotency, exact-run interruption and failure handling. | 2.0 d |
| **A28.3 OpenClaw-to-Hermes room tools** | Optional joined-room list/post tools, trusted author inference, loop prevention and outbound client. | 1.5 d |
| **A28.4 Hermes protocol extension & integration verification** | v2 turn fields, authenticated room list/participant post, compatibility tests, packaging and live reciprocal smoke test. | 1.5 d |

Order: **A28.1 → A28.2 → A28.3 → A28.4**.

## 6. Verification and acceptance

- OpenClaw unit tests cover manifest/registration, SecretRef configuration, authentication, allowlists, session continuity, event translation, idempotency, response backpressure, exact-run interruption, local approval ownership, private-host validation and identity-spoof prevention.
- Hermes tests cover v1 compatibility, v2 payloads, authenticated health, filtered room listing, participant posting, invalid membership/peer suffixes, mention scheduling, quiet-room wake and concurrent activity.
- Live smoke against OpenClaw 2026.8.2:
  1. Link one Hermes relay and one OpenClaw Gateway in both directions.
  2. Create a room containing a Hermes profile and `main@openclaw`.
  3. Verify multi-turn context, streamed text/tool status, `PASS`, mentions and exact-run interruption.
  4. From a normal OpenClaw session, list joined rooms and mention a Hermes participant with `hermes_room_post`.
  5. Trigger an OpenClaw approval and verify it remains owned by OpenClaw without bypass or relay deadlock.
- Run the OpenClaw build, validation, pack/install/inspect and runtime smoke checks, then the complete Hermes plugin suite in an environment that permits loopback sockets.
- Preserve and integrate the existing in-progress room/peer work; do not replace or fork it.

## 7. Deferred

- Publishing the plugin to ClawHub.
- Public-internet ingress, reverse-proxy deployment and cross-organization federation.
- Direct OpenClaw-to-Hermes profile calls outside rooms.
- Exposing the phone's `mobile_*` tools directly to OpenClaw; that remains P19/A19.4 work.
