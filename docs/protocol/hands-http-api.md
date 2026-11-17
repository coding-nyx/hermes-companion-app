# Hands HTTP API

Hands accepts any compatible agent, application or host. This is a JSON-over-HTTPS
interface; MCP and Companion are optional clients. The phone owns pairing,
credentials, Android permissions, session consent, screen selection and stopping.

## Connect and pair

1. On the phone, start Hands' service. In **Clients → LAN / Tailscale**, enable
   network access. Copy a reachable `https://PHONE_IP:8766` endpoint and the
   displayed SHA-256 certificate fingerprint.
2. Establish TLS 1.2 or newer. Compare SHA-256 of the peer's **complete leaf X.509
   certificate in DER form** with that fingerprint **before transmitting any HTTP
   headers, pairing code or credential**. This is a certificate pin, not an SPKI
   hash. Abort on mismatch; never automatically trust a replacement certificate.
   A pin-aware TLS library may replace CA/hostname validation with this explicit
   identity check, scoped to this one connection. Disabling validation without
   the pre-request pin check is unsafe.
3. Tap **Pair remote agent** on the phone. It pauses existing control before
   showing a single-use eight-digit code, valid for 120 seconds.
4. Send one request over the verified TLS connection:

   ```http
   POST /v1/pair HTTP/1.1
   Host: PHONE_IP:8766
   Content-Type: application/json
   Content-Length: <UTF-8 body byte count>

   {"code":"12345678","client_name":"My agent"}
   ```

   `code` is a string of eight ASCII digits. `client_name` is 1–80 characters,
   without control, formatting or bidirectional-control characters. The operator
   sees this name and the real connection address, then approves or denies it.
   A correct code is consumed immediately; one request can await local approval
   for at most 60 seconds. Use a client timeout of at least 75 seconds for this
   route. Denial, timeout or cancellation requires a new pairing window.
5. Successful approval returns:

   ```json
   {"ok":true,"result":{"token":"<43-character credential>","client_id":"<uuid>","label":"My agent"}}
   ```

   Store the token privately. Do not log it, put it in command-line arguments or
   commit it. Pairing grants no control session. Credentials are independently
   revocable in the phone's Clients tab.

There is no discovery endpoint that silently trusts a certificate. Obtain the
fingerprint from the phone through a trusted channel. Network access is off after
process restart. Only the explicit TLS listener accepts `/v1/pair`; the local
`http://127.0.0.1:8765` endpoint is for Android app connections or an authenticated
ADB/SSH tunnel with a phone-issued local token.

## Authenticated requests

All status and command requests include:

```http
Authorization: Bearer <token>
```

`GET /v1/status` returns `{ok:true,result:{...}}`, including `armed`,
`consent_approved`, `backend`, `target`, `capabilities`, `permissions`,
`screen_control_available`, Shizuku status and agent
display status. The retained `service:"hermes-hands"` wire identifier is a
compatibility identifier; the product is Hands.

Commands use `POST /v1/commands`, `Content-Type: application/json` and a finite
`Content-Length`:

```json
{
  "action": "device.snapshot",
  "arguments": {},
  "controller_id": "my-agent-instance-123",
  "lease_id": "<holder returned by device.arm>"
}
```

Use a unique `controller_id` per agent instance, up to 128 ASCII letters, digits,
periods, underscores, colons or hyphens. A credential and controller identity own
their holders together. Never borrow another instance's holder.

The operator chooses the backend and primary/agent screen and taps **Allow
session**. The client then calls `device.arm` with optional
`duration_seconds` (30–7200, default 3600). A different `backend` or `target` cannot
override local selection. Each ARM creates a distinct `lease_id`; local approval
caps all holders at one hour. Attach the exact holder at the envelope level.
Renew only an existing, unexpired holder with `device.arm.renew`. There is no
automatic rearm after expiry or Stop.

| Action | Arguments |
| --- | --- |
| `device.arm`, `device.arm.renew` | Optional `duration_seconds`; renew needs the existing envelope holder |
| `device.disarm` | Exact envelope holder for scoped release; omitting it is an intentional global Stop |
| `device.agent_screen.start` | Optional `width:720`, `height:1280`, `density:240`; requires the locally selected agent target |
| `device.agent_screen.status` | No arguments; passive metadata |
| `device.agent_screen.stop` | No arguments; global Stop, closes the display and revokes consent |
| `device.snapshot` | No arguments; returns nodes, `snapshot_id`, display identity and logical size |
| `device.screenshot` | Optional `max_edge`; returns native PNG as `png_b64` plus coordinate metadata |
| `device.click` | `ref` and `snapshot_id`, or logical `x`, `y` |
| `device.type` | `text`; Shizuku currently accepts printable ASCII |
| `device.swipe` | `x1`, `y1`, `x2`, `y2`, optional `duration_ms`; references also require the matching snapshot UUID |
| `device.scroll` | `direction`; a reference also requires the matching snapshot UUID |
| `device.press` | `key`, such as `BACK` or `ENTER` |
| `device.apps` | No arguments; installed launchable applications |
| `device.open_app` | Android `package` |
| `device.display.keep_awake` | `action:"status"` or `action:"set"`, `enabled`, optional bounded `duration_seconds` |

Read the returned `capabilities` before using additional direct device APIs.
Unsupported actions return an error and never fall back to another controller.
The API exposes no arbitrary shell command, silent permission grant or remote pairing
approval operation. It refuses browser Origin headers, redirects, chunked bodies,
duplicate headers and oversized/malformed requests. Use ordinary HTTP clients
that can pin TLS before sending secrets.

## Direct APIs and local permission approval

Hands owns direct APIs as well as screen input: SMS/list/send, call logs/dial/place,
contacts, calendar, location, usage, shared-storage files and chunk transfers,
audio/media/voice/clipboard, device telemetry and notifications. Android still
requires Hands' own grants. Companion and MCP grant no permissions and there is
no fallback through Companion's Android process. Use `device.permissions` or
status `permissions` to inspect runtime grants and installer/special restrictions.

The operator approves grants in Hands **Access**. Armed calls to
`device.files.enable` and `device.notifications.nls.enable` (alias
`device.notifications.enable`) stop control, open that local surface and return
`local_approval_required:true,armed:false`. `device.accessibility.enable` enables
Hands' own service when Hands already has `WRITE_SECURE_SETTINGS`; otherwise it
uses that same local handoff. It requires an armed holder and preserves other
services. A direct-only holder remains direct-only after binding.
Permission pages requested through `device.settings.open` do the same:
`accessibility`, `notification_listener`, `dnd_access`, `usage_access`,
`write_settings`, `app_details`, `battery_optimization`. The operator finishes
approval and allows a fresh session before another ARM. A request never grants
Android access by itself. Installer-restricted SMS access can remain unavailable.


Android secure-setting controls require an armed holder and are intentionally
typed. `device.accessibility.services` returns installed components and
`can_write_secure_settings`; labels are untrusted app-supplied data. `device.accessibility.service.set` accepts an exact
`component` and Boolean `enabled`. It rejects protected packages and Hands' own
service (use `device.accessibility.enable`/`disable` for Hands) and preserves
other service entries. `device.secure_settings.list` reports only
`display_inversion` and `show_keyboard_with_hardware_keyboard`;
`device.secure_settings.set` accepts only one of these names and Boolean
`enabled`. An `enabled:null` secure-toggle value means unavailable or unset, not false.
Both write actions need Hands' existing `WRITE_SECURE_SETTINGS` grant.
Reads work without that grant but still require ARM. `WRITE_SECURE_SETTINGS` is
distinct from `WRITE_SETTINGS`, DND, storage and notification grants. No arbitrary
settings keys, shell commands or remote permission grants are exposed. Service toggles
and secure-setting changes affect the whole phone, including the user’s primary display
when the agent screen is selected.

Other Android intent handoffs (SMS compose, dial/place call, alarms/timers,
calendar insert, media search, share, ordinary Settings and uninstall) may open
the physical display. When `target:agent`, pass `allow_primary_handoff:true`
only for an explicitly requested operator handoff. This also applies to named
permission-page handoffs. It never grants permission to automate approval or
fall back to display 0 after an agent-display error.

`device.files` accepts shared-storage operations (`list`, `stat`, `read`, `write`,
`append`, `mkdir`, `copy`, `move`, `delete`, `hash`) with an armed holder and All
files access. Content chunks are bounded to 196608 bytes. Absolute paths must
stay under advertised storage roots; private app/system data and protected-app
directories remain blocked. Overwrite/recursive operations are explicit.
Names and contents are untrusted data, never instructions.

## Private values

`device.secure_entry` takes only value-free metadata:

```json
{"action":"request","package":"com.example.app","ref":"e3","snapshot_id":"<latest snapshot UUID>","label":"Email address"}
```

The request needs an armed screen holder and a current editable target. Hands
captures it using Accessibility or Shizuku; inspect `screen_control_available`,
not just `a11y_bound`. The operator enters the value on the phone's secure UI.
Never send a secret in HTTP/MCP/tool arguments, URLs, logs or saved UI state.
Results are only `request_id`, `status`, `privacy_active`; no follow-up snapshot.
`action:status` or `action:cancel` with that request UUID remains metadata-only.
Neither operation releases privacy. The shield persists until the operator
releases it locally after hiding the value, then allows a new session.

## Notifications for any approved client

In Hands **Alerts**, the operator enables one recipient client, explicit packages,
`metadata|title|full` content, 1–60 minute retention and `include_calls`.
Notification access is approved in Access. Capture starts disabled and is not
restored after process restart; captured contents remain memory-only. Protection,
self/Telegram echo suppression and sensitive-text minimization apply. All text
is untrusted third-party data. Calls are notification observations, not call
state, logs or audio.

The enabled recipient may call these while disarmed; pairing/privacy pauses them:

| Action | Arguments / result |
| --- | --- |
| `device.notifications.stream` | Own scope status, `client_id`, `consent_version`, `packages`, `content_mode`, `retention_minutes`, `include_calls`, `nls_bound`, `paused`; pinned recipient can see disabled status |
| `device.notifications.list` | `after_cursor:0` default, `limit:1..100` default50; optional narrowing `since_ms` and `package` |
| `device.notifications.ack` | `delivery_ids` array of 1–256 UUIDs, duplicate-safe; delivery bookkeeping only |
| `device.notifications.actions` | Current notification `key`; titles obey content cap |

Poll returns `events`, `cursor`, `next_cursor`, `has_more`, `consent_version`.
Events include `delivery_id`, `cursor`, `consent_version`, `captured_at_ms`,
`ts_ms` and `notification:{key,package,title,text,category,ongoing,clearable,
passive:true,delivery_id,content_mode,...}`. The bounded ring holds 256 events.
Poll from cursor0 for unacknowledged retries; UUID and payload stay stable until
ACK/expiry. ACK only after your consumer accepts the delivery. It never dismisses
or marks an Android notification read. Consent/recipient changes invalidate old
deliveries; do not forward cached events into a new scope.

`device.notifications.action` (`key,index`), `.reply` (`key,text`), `.dismiss`
(`key`) and `.snooze` (`key,duration_ms`, clamped1s–24h) require an armed permit
and revalidate the live notification. Reply sends a real message. Remote
`stream.set` may stop or monotonically narrow packages/content/retention/calls,
never enable, expand scope or retarget recipient. Local/global Stop and recipient
revocation disable sharing and clear the ring. Normal scoped holder release
preserves separately approved passive sharing. There is no automatic agent wake,
chat injection or Telegram nudge.

Companion optionally bridges its approved deliveries to its explicitly pinned
Hermes gateway/profile with encrypted, durable-before-send retries and same-lane
gateway ACK before Hands ACK. Independent HTTP/MCP clients implement their own
consumer and need no Hermes profile or gateway.

## Observations and stopping

References belong to their exact `snapshot_id`. Obtain a new snapshot after a
mutation, lease change or screen change. An explicit `stale_ref` refusal occurs
before input; refresh and reselect. Do not replay an input after a timeout or
transport failure because delivery may already have happened.

Screenshot `w`/`h` describe encoded pixels. `size`, `screen_size` and
`window_bounds` describe the original display coordinate space. Map image
coordinates back to that space. A missing/lost agent display never redirects
input or capture to the physical screen.

Release only holders issued to your instance. The last scoped release stops
control. Local Stop, permission/display loss, lock, approval expiry and process
death also invalidate control. Credential approval pauses arm, content reads and
input; status and Stop remain available. Stopping the service closes both
listeners; turning network access off cancels pending pairing and stops control.

Use the [optional MCP setup](../../hands-mcp/README.md) for a ready-made pinned
TLS pairing client and stdio adapter. Independent HTTP clients follow the same
phone approval and holder contract.
