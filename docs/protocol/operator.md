# Operator protocol

The companion’s chat/sync lane. Hermes is source of truth. This is the subset we call — not a dump of every dashboard route.

Confirm paths against the running Hermes version during A0.5. Ticket mint in particular has moved across releases.

## Transports

| Role | URL | Auth |
|---|---|---|
| Probe | `GET {origin}/api/status` | none (public) |
| REST | `{origin}/api/*` | session cookie after login, or loopback token |
| Live | `GET {wsOrigin}/api/ws` | gated: `?ticket=` (single-use, ~30s). loopback: `?token=`. Always add `&profile={name}` when a named profile is active |

`origin` is the dashboard (`hermes dashboard`), typically `:9119`. Do **not** require the API server on `:8642`.

## Connect sequence

1. `GET /api/status` → `auth_required`, `auth_providers`, version, gateway block.
2. If `auth_required`:
   - password / `basic` → `POST /auth/password-login` `{provider, username, password}`; session cookies, never a loopback token.
   - `nous` / OIDC → not v1; show “use Tailscale + basic, or wait”.
3. Mint a WebSocket ticket: `POST /api/auth/ws-ticket` (cookie session). Use `?ticket=` once (~30s). Never reuse. Never send `?token=` in gated mode. Loopback only: `?token=` from the SPA when `auth_required` is false.
4. Open `/api/ws?ticket=…&profile=…`.
5. Wait for `gateway.ready`. Record `change_events`, `heartbeat`, and any process/instance id.
6. `session.list`. `GET /api/status` for HUD. Load profiles.

On close codes: `4401` ticket rejected; `4403` host/peer guard. Re-login and mint a fresh ticket. Never reuse a gated ticket.

Optional Companion integration is discovered independently through `/companion/health`.
Only a valid `relay: "ok"` report enables room/peer fetching. Missing routes and an HTML SPA
response with HTTP 200 mean unavailable; transport, authentication, and malformed JSON failures
remain unknown and can be retried. Discovery cannot block native chat connection. Host telemetry
uses `/api/system/stats` first; only an explicitly unsupported native route plus a verified
Companion extension allows the legacy metrics fallback.

## JSON-RPC shape

Newline-delimited JSON-RPC 2.0, same dialect as the Ink TUI.

Request:

```json
{"jsonrpc":"2.0","id":"w1","method":"session.list","params":{}}
```

Event (server → client):

```json
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{}}}
```

Heartbeat: `gateway.ping` → `{ "ok": true }`. Send on an interval only if `gateway.ready.payload.heartbeat` is true.

## Methods we call (v1)

| Method | When |
|---|---|
| `session.list` | Thread rail, catch-up |
| `session.create` | NEW or first-send draft promotion. Reuse an `idempotency_key` for a retry; retry without it only after explicit validation refusal on an older host. |
| `session.resume` | Bind stored/runtime ids; request `omit_messages`, `defer_history`, and `inline_images=false` where supported. Restore running/inflight state and pending approval. |
| `session.history` | Native params are only session id and profile. Clip/page this full transcript locally; do not send unsupported `limit` or `before`. |
| `session.interrupt` | Stop in-flight turn |
| `session.steer` | Composer guidance during an active turn |
| `session.branch_stored` | Non-destructive whole-conversation branch with an idempotency key |
| `image.attach_bytes` / `file.attach` | Stage native attachments before sending; files return a reference to append to the prompt |
| `prompt.submit` | Native send carries session id, profile, and text. Do not add unsupported `model`, `client_id`, or `parts`. Destructive rewind adds `truncate_before_row_id` + `confirm_truncate`. |
| `approval.respond` | Approval strip |
| `clarify.respond` | Clarify strip |
| `sudo.respond` / `secret.respond` | Same strip family |
| `gateway.ping` | Liveness |

Do **not** call from the phone in v1: `cli.exec`, `config.set`, `reload.env`, anything that writes `.env` or `config.yaml`.

## Events we render

| Event | UI |
|---|---|
| `gateway.ready` | HUD live; reset replay cursor if instance id changed |
| `message.delta` / `message.complete` | Deltas plus authoritative final text, status, and error; final-only replies are valid |
| `message.interim` | Seal an intermediate response while the agent keeps working |
| `reasoning.delta` | Separate collapsible reasoning row; never spoken as the final answer |
| `tool.start` / `tool.progress` / `tool.complete` | Tool rows |
| `approval.request` / `clarify.request` / `sudo.request` / `secret.request` | Strip. Expiry events clear only the matching `request_id` |
| `sessions.changed` | Reconcile thread list. Expected payload fields: `id`, `profile`, `op` (`upsert`/`delete`), `updated_at`, optional `started_at`/`title`. Without `id` or `profile` → full refetch (H4). Patches are **merged** into the known row (`SessionLists.merge`): a missing title or timestamp never erases what the rail already knows. |

## REST we call (v1)

| Call | Why |
|---|---|
| `GET /api/status` | HUD, auth mode, memory/disk pressure |
| `GET /api/auth/status` | Session still valid |
| `POST /api/auth/login` | Basic auth |
| `GET /api/sessions` | Catch-up / search pagination |
| `GET /api/sessions/{id}/messages` | History page (`limit` ≤ 500, optional `before`) |
| `GET /api/profiles` | Machine roster for the switcher. **No** `?profile=` — that would hide the other agents. |
| `GET /api/sessions?profile=&limit=100&offset=[&archived=include]` | Catch-up (`archived=include` only while the ARCHIVED chip is on). Required. Client also filters by `profileId`. Dashboard: default 20 rows, `limit ≤ 100` (422 above), returns `total`; the client pages `offset` until `total` / short page / 500 rows. The standalone relay mirrors the shape. The rail is the **union** of this and `session.list` keyed by id — the gateway hides `subagent` rows, REST has them; neither alone is exhaustive. |
| `GET /api/sessions/{id}/messages?profile=` | History. 403 if the session is not in that profile. |
| `POST /api/sessions/{id}/chat/stream?profile=` | Legacy SSE compatibility only; native chat uses `prompt.submit` over RPC. |
| `POST /api/sessions?profile=` | Legacy compatibility only. Native NEW uses `session.create` over RPC. |
| `GET /api/sessions/{id}/approval?profile=` | Pending approval/clarify. 404 if none. 403 if wrong profile. |
| `POST /api/sessions/{id}/approval?profile=` | `{request_id, decision}` (`once`/`deny`). JSON-RPC `approval.respond` is the live Hermes equivalent. |
| `GET /api/ws?profile=` | JSON-RPC. First event `gateway.ready` (`change_events`, `heartbeat`, `instance_id`). Gated mode: mint `POST /api/auth/ws-ticket` and pass `?ticket=`. |

Gateway start/stop is **not** M1.

## Session timestamps

Hosts disagree on units: Hermes `state.db` is `REAL` seconds (`1756750000.5`), the standalone relay sends int ms, the mock sends int seconds, some payloads are ISO-8601. The client accepts all four (`ProfileJson.epochMs`). `started_at`/`created_at` is the creation instant, `updated_at`/`last_activity_at`/`last_active_at` the activity instant; each falls back to the other. Rail order is decided once in the domain (`SessionLists.comparator`): chosen key desc → other timestamp desc → id desc. Room uses the same `ORDER BY` so cache and remote never disagree.

## Profile rule

`GET /api/profiles` and `GET /api/status` are machine-level.

Every session/chat/WS call includes `profile`. Omitting it is a data-leak class bug: the dashboard defaults to its boot profile.

A thread is keyed `(host, profile, session_id)`. The UI never mixes two profiles in one list. Switching profiles updates the header glyph, refetches `/api/sessions?profile=…`, and fail-closed-filters the payload. The last profile id is sticky.

## Offline

Room is a cache. On reconnect, subscribe to live events, reconcile the catalog and open history, then drain eligible unsent inputs. Uncertain attempts are reconciled before any retry. The host transcript is authoritative; drafts never upload until send.

The outbox removes an input on host acknowledgment, not on successful generation. Before
submission it persists the baseline durable user-row ids. A lost acknowledgment is reconciled
against a new matching row, not an identical historical message. An uncertain attempt is held
for Check; it is never blindly replayed. Exhausted or rejected sends remain available for
explicit Retry. Cache migration 6→7 preserves outbox rows.

Native REST history uses `limit`, `order=latest`, and `offset`; its pagination envelope
marks an already-paged response. The preceding page need not contain the newer-page anchor.
Legacy REST history that supports `before` remains a compatibility path.

Session mutations use profile-scoped `PATCH /api/sessions/{id}` with title, pinned, or
archived. A new native RPC session can be lazy until its first prompt; the app retains it on
the rail while waiting for the host catalog to include it.

## Rewind

Only when the user asks. Send `truncate_before_row_id` (preferred) + `confirm_truncate`. After success, rebind cached row ids from `survivor_user_row_ids`. Never keep truncate params on the next ordinary send.
