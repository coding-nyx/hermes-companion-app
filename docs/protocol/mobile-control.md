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
```

Anything else is stripped at register and denied at command time.

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
    "package": "com.telegram.messenger",
    "title": "…",
    "text": "…",
    "category": "msg",
    "ongoing": false,
    "clearable": true
  }
}
```

Filter is denylist-only: built-in protected packages ∪ custom denylist ∪ Companion's own package/channels. Never auto-inject into chat/Telegram; the agent reads via `mobile_notifications` and may explicitly call `mobile_notifications_inject`.

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
