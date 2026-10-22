# Mobile-control protocol

Device lane. The phone dials **out**. Pattern copied from Hermes browser-extension control, not implemented as a browser controller.

v1 lives in `hermes-plugin/`. Upstream H3 is a later lift into core.

## Roles

- **Phone** executes. Source of truth for armed/disarmed.
- **Plugin** brokers. Exposes `mobile_*` tools to the agent.
- **Agent** never talks to AccessibilityService itself.

## Pairing

1. Phone shows a 6-character code (and later a QR of the same payload).
2. Operator confirms on the host: `hermes companion approve <CODE>` or the plugin slash command.
3. Plugin stores `device_id`, public key, bound `profile`, issued device credential.
4. Phone stores the credential in the Android Keystore.
5. Code TTL 10 minutes, single consume. Re-pair rotates the credential.

Revoke drops both operator and device lanes for that device.

## Connect

1. `POST /companion/device/register` with `{ protocol_version, device_id, profile, capabilities[] }`.
2. Plugin returns a single-use ticket, 30s TTL, filtered capability allowlist.
3. Phone opens `wss://{host}/companion/device/ws` with subprotocols:
   - `hermes-mobile-control-v1`
   - `hermes-mobile-control-ticket.{ticket}`
4. Ticket is never a query parameter.

Unknown / expired / reused tickets fail before upgrade.

## Capabilities (allowlist)

```
device.noop
device.snapshot
device.screenshot
device.click
device.type
device.swipe
device.scroll
device.press
device.open_app
device.apps
device.wait
device.arm
device.disarm
device.notifications

# L0 (direct API) — do not require a11y and are not scoped to the foreground app.
device.audio.volume.get
device.audio.volume.set
device.audio.ringer
device.media.sessions
device.media.now_playing
device.media.control
device.clipboard.get
device.clipboard.set
device.device.info

# L0 notification actions — act on a shade notification, not its UI.
device.notifications.actions
device.notifications.action
device.notifications.reply
device.notifications.dismiss
device.notifications.snooze
```

Anything else is stripped at register and denied at command time.

L0 actions are dispatched straight to Android platform APIs (AudioManager,
MediaSessionManager, ClipboardManager, BatteryManager, …) and do not need
the `CompanionAccessibilityService` to be bound. The agent prefers L0
whenever the task maps to one of these; the a11y lane stays the fallback for
interacting with arbitrary 3rd-party app UI. Passive L0 reads
(`device.audio.volume.get`, `device.media.sessions`, `device.media.now_playing`,
`device.device.info`) are allowed while DISARMED; destructive L0 and personal-data
reads (`device.clipboard.get`, sms/call log/contacts, calendar, location, app usage)
still require `ARMED`.

`device.clipboard.get` is the one L0 action the platform can refuse outright:
Android 10+ withholds the primary clip from any app that is neither the focused
app nor the default IME, which is Companion's normal state as a background
service. The phone distinguishes the cases rather than reporting an empty clip —
`clipboard_read_blocked` (platform refused), `clipboard_not_text` (non-text clip)
or `{"text": ""}` (genuinely empty, read while a Companion activity was on
screen). Agents wanting the text a user is looking at should read the focused
node over the L1 lane instead.

## Frames

Hermes → phone

```json
{
  "type": "mobile.controller.command",
  "command_id": "c1",
  "action": "device.snapshot",
  "arguments": { "include_system_ui": false },
  "tool_call_id": "call_1",
  "profile": "default",
  "device_id": "dev_…"
}
```

Phone → Hermes

```json
{
  "type": "mobile.controller.result",
  "command_id": "c1",
  "ok": true,
  "result": { }
}
```

or `{ "ok": false, "error": { "code": "disarmed", "message": "device is DISARMED" } }`.

Cancel: `mobile.controller.cancel` with the `command_id`. Late results are ignored.

Detach: `mobile.controller.detach` on the authenticated socket. Socket close without detach is a recoverable disconnect; pending work keeps its original deadline.


## Notification stream (phone → host)

Capability `device.notifications` is optional: include it on register only while the phone has the Notification Listener bound and live stream armed. Ticket may omit it when the stream is off.

New receivers advertise `server_features: ["passive_notification_stream_v1", "notification_delivery_ack_v1"]` in the authenticated `mobile.controller.hello` and the informational `/companion/health` response. The phone must see the feature on the selected authenticated lane before sending the new consented stream. Absence means pause and request a receiver update, not downgrade to legacy wake behavior.

Phone → Hermes event frame (not a command result):

```json
{
  "type": "mobile.controller.event",
  "event": "notification",
  "device_id": "dev_…",
  "profile": "default",
  "ts_ms": 1757160000000,
  "notification": {
    "key": "sha256-or-sbn-key",
    "package": "com.example.mail",
    "title": "…",
    "text": "…",
    "category": "msg",
    "ongoing": false,
    "clearable": true,
    "passive": true,
    "delivery_id": "c1807e6d-4cb3-484e-986c-761597027a52",
    "content_mode": "full"
  }
}
```

New phone filtering is explicit app allowlist plus protected packages and self/Telegram echo exclusions. New installs and incompatible legacy consent start disabled. Enabling or retargeting requires the phone UI consent flow; remote `device.notifications.stream.set` may disable only, not enable or change the destination. The phone pins an explicit gateway and profile; changing chats must not change this route. Pending events must not cross routing/consent boundaries.

`notification.passive` is a strict JSON boolean. `true` means ring-only: the relay never schedules an agent wake, Telegram nudge, automatic inject, or phone action. Host `respond` policy cannot override this; host `off` can still drop the event. Invalid explicit passive values are rejected, not treated as legacy active. Missing passive retains old-client behavior only.

For passive events, `content_mode` is `metadata` (default), `title`, or `full`. Metadata carries no title/body; title carries no body. The relay strips excess content defensively, marks ring rows `content_trust: "untrusted"`, rejects invalid modes and deduplicates identical retransmissions within its bounded ring. All notification content is untrusted third-party data, never an instruction or permission grant. The agent may explicitly read it with `mobile_notifications`; sharing/injection and actions still require their own authorization. Redacted or missing text must not be invented.

`category: "call"` and `"missed_call"` are Android notification categories only. They do not establish guaranteed phone call state, grant call-log/audio access, or bypass Android lock/privacy restrictions. Calls are observed only when a selected app posts an exposed notification.

Delivery is acknowledged on the same authenticated device lane only after ring acceptance:

```json
{"type":"mobile.controller.event.ack","delivery_id":"c1807e6d-4cb3-484e-986c-761597027a52","accepted":true}
```

Passive events require a UUID `delivery_id`. Retry the same UUID, timestamp and payload until acknowledgement, within the phone's bounded consent-scoped retention window (2, 15 or 60 minutes, default 15; at most 128 queued events); a socket write alone is not acceptance. A duplicate accepted event is acknowledged again without inserting another ring row. Deduplication is scoped by device+profile+UUID, retained for 65 minutes on the running relay, and bounded at 4,096 unexpired IDs. Saturation rejects new IDs rather than evicting unexpired dedupe records. Relay restart loses its in-memory ring and dedupe cache: this is at-least-once/best-effort delivery, not durable exactly-once messaging.

A negative acknowledgement has `accepted:false` and `code`: `policy_blocked`, `invalid_event`, `delivery_id_conflict`, or `dedupe_capacity`. Only capacity is potentially transient; do not retry a policy/malformed/conflicting event indefinitely. Invalid/missing UUIDs cannot be acknowledged and are dropped. Clients must require **both** advertised features, must not downgrade, and must discard expired events or events invalidated by consent, app removal or destination changes.

Status meta (reuse `mobile.controller.status`):

```json
{
  "type": "mobile.controller.status",
  "device_id": "dev_…",
  "notifications_stream": true,
  "notifications_listener_bound": true
}
```

Relay keeps an in-memory ring (~100 events / device). Operator debug: `GET /companion/device/notifications?device_id=&limit=`.

## Snapshot

Return a compact accessibility tree, not a raw dump.

```json
{
  "app": "com.example.fixture",
  "activity": ".MainActivity",
  "size": { "w": 1080, "h": 2400 },
  "nodes": [
    { "ref": "e1", "role": "button", "text": "Submit", "clickable": true, "bounds": [80, 400, 1000, 496] }
  ]
}
```

Refs are session-local until the next snapshot. Clicks by `ref` must follow a snapshot; stale refs return `stale_ref`.

`mobile_click` also accepts `text` (visible label / content-desc). The plugin snapshots, resolves a unique clickable node, then taps it. Ambiguous or missing labels return `no_match` plus `candidates`. Successful click/type/swipe/scroll/press results include a fresh `snapshot` so the agent does not reuse dead `@eN` refs.

Protected packages return `error.code = protected_package` and no tree.

## Screenshot

PNG, max edge 1080, EXIF stripped. Optional; snapshot is the default eyes.

## Arming

Phone states: `DISARMED` | `ARMED` | `EXECUTING`.

- Tools fail closed when not `ARMED`.
- Plugin injects the “you have a phone” system hint **only** while `ARMED`.
- Idle 300s, notification kill switch, permission revoke, or WS drop → `DISARMED`.
- Hermes cannot re-arm.

While `ARMED` the phone **must** show: persistent notification `HERMES HAS HANDS` and a 1px overlay frame + `LIVE` chip.

## Fail-closed codes

| code | meaning |
|---|---|
| `disarmed` | Hands off |
| `no_device` | Nothing paired |
| `protected_package` | Deny list |
| `stale_ref` | Click/type without a fresh snapshot |
| `no_match` | `text=` did not uniquely match a node |
| `no_focus` | `mobile_type` with no focused field — click the input first |
| `click_failed` | Pixel tap did not land |
| `capability_denied` | Action not in the ticket allowlist |
| `timeout` | Command exceeded budget (15s / screenshot 10s) |
| `a11y_unavailable` | Service not bound |

## Rate limit

Plugin: 10 commands/sec per device. Excess → `timeout`/`rate_limited`, not a queue that grows.

## Audit

Host: `{HERMES_HOME}/companion-audit.jsonl`. Fields: time, device_id, profile, action, foreground_app, ok, error code. Never the typed text of `device.type`.

## Arm-first (agent procedure)

See `hermes-plugin/skills/hermes-companion/SKILL.md` (ships with the plugin).

Agents must **`mobile_arm` before snapshot/gestures**, and preferably before long reasoning, so the phone does not hit the lock screen while the model is still thinking. `mobile_status` / `mobile_devices` / `mobile_select_device` remain allowed while disarmed; control actions fail closed with `disarmed` (+ hint to call `mobile_arm`) until armed. Do not auto-arm.

## Notification action keys

The stream never gives the host a platform notification key: `device.notifications` emits a
truncated SHA-256 of `StatusBarNotification.key`. The phone keeps a bounded (256-entry,
insertion-ordered) map from that hash back to the real key, populated only for notifications that
passed the stream's protected-package and denylist filters — so the action commands inherit that
filtering and cannot reach a protected app's notification. Entries are dropped when the
notification is removed, or evicted oldest-first past the cap; both cases report
`unknown_notification`, and a notification that has left the shade reports `notification_gone`.

`device.notifications.reply` uses the notification's own `RemoteInput` action, filling every
declared result key and setting the results source to free-form input. It refuses to fire a
text-expecting action without text, and `device.notifications.action` refuses text-expecting
actions outright — sending one with no bundle is what delivers an empty message.

## Per-app notification policy

`hermes-plugin/notif_policy.py` resolves one of three modes per package: `off`, `silent`,
`respond`. Unlisted packages are `respond` — apps are enabled by default.

Two layers, host overriding phone:

1. **Phone base** — the operator's Device-tab rules, synced at register as `extra_protected`.
   Enforced on the phone, so these notifications never leave the device.
2. **Host override** — `~/.hermes/companion-notif-policy.json` (`HERMES_COMPANION_NOTIF_POLICY`
   to relocate), with an optional per-profile map that beats the host-wide one. The legacy
   `HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES` env list is honoured as an implicit `silent`
   floor so existing deployments do not start waking on Telegram again; any explicit override
   beats it.

Resolution, lowest precedence first: default `respond` → legacy env `silent` → host-wide rule →
per-profile rule. A phone-side suppression then wins unconditionally, because the event never
arrives; `describe()` flags that case as `unreachable_override` instead of reporting a mode that
cannot be observed.

Enforcement is at one choke point, `RelayState.push_notification`: `off` returns before the ring
(so a muted app is absent from `device.notifications` too), `silent` rings without waking, and
only `respond` reaches `maybe_wake_for_notification`. `agent_wake` re-checks the policy itself so
a direct caller cannot bypass a `silent` rule.

## Arming does not imply accessibility

`device.arm` used to be refused with `a11y_unavailable` whenever the accessibility service was
unbound, and every destructive L0 action requires `ARMED` — so on a phone where the user never
granted accessibility, no L0 write could run at all. That reintroduced exactly the dependency the
direct-API lane exists to remove.

Arming is now the user's consent plus the kill switch (`HandsService`, which is a foreground
service and needs no accessibility), and capability is enforced per action. `device.arm` succeeds
either way and reports which lanes the session has:

```
{"armed": true, "a11y_bound": false, "lanes": "l0"}     // direct API only
{"armed": true, "a11y_bound": true,  "lanes": "l0+l1"}  // a11y tree available too
```

`DeviceLanePolicy.reject()` still answers `a11y_unavailable` for `device.snapshot` / `click` /
`type` / `swipe` / `scroll` / `press` in an `l0`-only session, so nothing gained UI-driving power.
