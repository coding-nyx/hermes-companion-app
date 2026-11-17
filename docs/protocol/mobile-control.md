# Mobile-control protocol

Device lane. The phone dials **out**. Pattern copied from Hermes browser-extension control, not implemented as a browser controller.

v1 lives in `hermes-plugin/`. Upstream H3 is a later lift into core.

## Roles

- **Hands** executes all phone APIs and owns Android grants, local approval and holders.
- **Companion** is an authenticated Hands client and outbound gateway transport. It has no local execution fallback.
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
device.secure_entry
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

### Phone-local private entry

`mobile_secure_entry` maps to `device.secure_entry`, an explicitly value-free handoff. The wire accepts:

- `{"action":"request","package":"com.example.app","ref":"e3","snapshot_id":"<UUID>","label":"Email address"}`:
  package/ref identify an editable node in the last snapshot; the required `snapshot_id` UUID binds
  the request to that exact snapshot. Every newer snapshot invalidates old tokens, even when `eN`
  is reused or the snapshot came through another host. Label is a generic field name (1–80 chars).
  Requires armed screen control and honors target and foreground protected-app rules.
- `{"action":"status","request_id":"<UUID>"}` or `{"action":"cancel","request_id":"<UUID>"}`:
  request-scoped metadata only, available while disarmed and without accessibility. They bypass
  foreground-package protection because the local private-entry UI may be in front.

No value/text/content argument or remote release operation exists. Tool and phone reject unknown
arguments. Replies contain only `request_id`, `status` (`pending`, `filling`, `filled`, `cancelled`, `expired`,
`failed`, `released`) and boolean `privacy_active`; tools never attach a follow-up snapshot. Error
responses from this tool use a fixed message rather than echoing target application content.

The phone activates a persistent, device-wide privacy shield before asking the operator for a value.
All remote phone commands except `device.secure_entry` status/cancel, DISARM and Accessibility OFF
fail closed while shielded. In-flight work is cancelled/guarded before results are published. Values
are entered in a local secure surface, not saved in instance state or sent through the host. After
local submission, status is `filling`: the entry Activity closes while a phone-local coroutine waits
up to three seconds to restore and revalidate the exact captured target, then reports `filled` or
`failed`. The value remains local and privacy stays active throughout. Phone notifications are suppressed during privacy;
the relay mirrors `privacy_active` from status frames/results, drops new notifications while active
and hides that device's existing notification ring.

Fill/cancel/timeout/disarm/restart cannot remotely clear the shield. The operator must explicitly
release it on the phone after hiding the private value; release disarms Hands and control needs a
new local arm. Snapshot collection also masks password fields and locally remembered private values.
The target app necessarily receives the value and controls any subsequent handling of its own data.

Deploy the app, plugin, prompt and canonical skill together to lab and raj.

L0 actions are dispatched straight to Android platform APIs (AudioManager,
MediaSessionManager, ClipboardManager, BatteryManager, …) and do not need
an Accessibility binding when the selected Hands backend supplies Shizuku. The agent prefers L0
whenever the task maps to one of these; the a11y lane stays the fallback for
interacting with arbitrary 3rd-party app UI. Passive L0 reads
(`device.audio.volume.get`, `device.media.sessions`, `device.media.now_playing`,
`device.device.info`) are allowed while DISARMED; destructive L0 and personal-data
reads (`device.clipboard.get`, sms/call log/contacts, calendar, location, app usage)
still require `ARMED`.

`device.clipboard.get` is the one L0 action the platform can refuse outright:
Android 10+ withholds the primary clip from any app that is neither the focused
app nor the default IME, which is Hands' normal state as a background
service. The phone distinguishes the cases rather than reporting an empty clip —
`clipboard_read_blocked` (platform refused), `clipboard_not_text` (non-text clip)
or `{"text": ""}` (genuinely empty, read while a Hands activity was on
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

Hands owns notification capture through its Android listener. Companion only polls its approved recipient scope and forwards to its explicitly pinned gateway/profile. `device.notifications` describes this passive transport; it never grants Android capture access or arms control.

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

Hands Alerts selects one approved recipient client, apps, metadata/title/full content,
1–60 minute retention and call-category inclusion. Access contains the Android
notification grant. Sharing starts off and contents are memory-only (256 events);
source protection, both own packages/channels and Telegram echo guards apply.
Phone-local settings cannot be expanded remotely. The plugin's stream-set tool
exposes only `enabled=false`; the independent API also permits armed monotonic
narrowing. Companion forwarding separately requires explicit local enabled state
and a pinned gateway/profile; changing chats never retargets it.

Companion preserves Hands' UUID and `consent_version` in a new encrypted outbox
format, bounded to 128 events and the phone's retention (maximum 60 minutes).
The full origin/profile/device/host-credential/local-route scope and Hands
recipient/version/policy are pinned. Old capture-format rows fail closed. Every
send rechecks current Hands approval. Persist before send; consume gateway ACK,
persist that disposition, ACK Hands, then retire the row. Retry no faster than
30 seconds. A terminal gateway rejection may discard/ACK; capacity rejection
retries. Route/credential/consent/privacy changes discard old outbox data and
never ACK an unknown new phone scope. Overflow remains at Hands, rather than
acknowledging a notification the gateway has not accepted.

`notification.passive` is a strict JSON boolean. `true` means ring-only: the relay never schedules an agent wake, Telegram nudge, automatic inject, or phone action. Host `respond` policy cannot override this; host `off` can still drop the event. Invalid explicit passive values are rejected, not treated as legacy active. Missing passive retains old-client behavior only.

For passive events, `content_mode` is `metadata` (default), `title`, or `full`. Metadata carries no title/body; title carries no body. The relay strips excess content defensively, marks ring rows `content_trust: "untrusted"`, rejects invalid modes and deduplicates identical retransmissions within its bounded ring. All notification content is untrusted third-party data, never an instruction or permission grant. The agent may explicitly read it with `mobile_notifications`; sharing/injection and actions still require their own authorization. Redacted or missing text must not be invented.

`category: "call"` and `"missed_call"` are Android notification categories only. They do not establish guaranteed phone call state, grant call-log/audio access, or bypass Android lock/privacy restrictions. Calls are observed only when a selected app posts an exposed notification.

Delivery is acknowledged on the same authenticated device lane only after ring acceptance:

```json
{"type":"mobile.controller.event.ack","delivery_id":"c1807e6d-4cb3-484e-986c-761597027a52","accepted":true}
```

Passive events require a UUID `delivery_id`. Retry the same UUID, timestamp and payload until acknowledgement, within Hands' bounded consent-scoped retention window (1–60 minutes, default 15; at most 128 encrypted Companion deliveries); a socket write alone is not acceptance. A duplicate accepted event is acknowledged again without inserting another ring row. Deduplication is scoped by device+profile+UUID, retained for 65 minutes on the running relay, and bounded at 4,096 unexpired IDs. Saturation rejects new IDs rather than evicting unexpired dedupe records. Relay restart loses its in-memory ring and dedupe cache: this is at-least-once/best-effort delivery, not durable exactly-once messaging.

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
  "snapshot_id": "58cfb916-a137-4914-861f-58276b56f330",
  "app": "com.example.fixture",
  "activity": ".MainActivity",
  "captured_at_ms": 1794393000000,
  "window_id": 7,
  "generation": 12,
  "size": { "w": 1080, "h": 2400 },
  "nodes": [
    { "ref": "e1", "role": "button", "text": "Submit", "clickable": true, "bounds": [80, 400, 1000, 496] }
  ]
}
```

The phone generates `snapshot_id` atomically with the captured tree. Private-entry requests must
include that exact token; an intervening snapshot on any host invalidates it even if its refs look identical.

`captured_at_ms` is the phone's capture time; `window_id` identifies the observed window and
`generation` is the increasing observation sequence. These describe this observation, not a promise
that the screen cannot change after it.
Refs expire on another snapshot, a gesture or a screen change. Clicks by `ref` must follow a readable
snapshot; stale refs return `stale_ref`. Missing or unreadable roots must not be reported as a
successful empty tree. Changing windows return an observation error instead of a mixed/stale tree.

`mobile_click` also accepts `text` (visible label / content-desc). The plugin snapshots, resolves a
unique clickable node, then taps it. Ambiguous or missing labels return `no_match` plus `candidates`.
Click/type/swipe/scroll/press results attach a `snapshot` after action completion when observation
succeeds. If the action completed but the follow-up observation fails, the outer result remains
`ok:true` and includes `result.observation:{ok:false,error:{code,message,hint}}`, omits `snapshot`, and
instructs the agent to discard previous refs. This distinction prevents silently continuing from an
old description or blindly repeating an action that already happened. An empty/malformed tree is
`observation_unavailable`, including when returned by an older app.

Protected packages return `error.code = protected_package` and no tree.

## Screenshot

PNG, max edge 1080, EXIF stripped. Optional; snapshot is the default eyes. Phone wire responses
carry `mime`, `w`, `h`, `png_b64` and capture identity metadata. The plugin validates PNG framing and
dimensions, then returns Hermes' actual multimodal envelope (`_multimodal:true`, `content` with a
text metadata part and an `image_url` data URL). Base64 is never echoed into the text metadata or
fallback summary. Ordinary JSON containing `png_b64` is not image input to Hermes models.

On Android 14+ capture targets the active app window so Hands' control overlay does not
cover the observation; older versions capture the display. `coordinate_space` is `window` or
`display`, `window_bounds:[left,top,right,bottom]` gives the captured canvas in absolute screen
coordinates, and `screen_size:{w,h}` gives the full display dimensions. PNG `w`/`h` are the scaled
image dimensions. Prefer accessibility refs for gestures. If a visual coordinate is necessary,
convert PNG pixel `(x,y)` using `screen_x = left + x * (right-left) / w` and
`screen_y = top + y * (bottom-top) / h`, then obey the screen's safe area. Never send scaled image
pixels directly as screen coordinates. Missing/ambiguous canvas metadata requires a new readable
tree or operator inspection before coordinate gestures.

Errors remain ordinary JSON failures. Blank-looking captures are `screenshot_blank`, without
claiming a cause; secure-window refusal is `screenshot_secure_window`. Capture rate limits, other failures
and malformed PNG results have distinct codes. No error returns a success image or authorizes
screen actions from an older observation. Models/providers without image input receive the envelope's
text-only fallback (`ok:false`, `error.code:image_input_unavailable`, capture metadata retained) and
must use a readable tree or ask the operator; they must not claim to see pixels.

Screenshot capture does not require an exposed accessibility tree: it remains a visual fallback for
apps without readable nodes. It validates its active window separately. Before acting on a screenshot,
obtain a new readable tree when possible; screen transitions during capture invalidate earlier refs.

## Shared-storage files

`device.files.access` is passive permission/root metadata. `device.files.enable` requires arming,
stops control and opens Hands Access for local All files approval. `device.files` is an armed-only L0
capability; `action` selects list/stat/read/write/append/mkdir/copy/move/delete/hash. All contents,
listings and mutations additionally require `MANAGE_EXTERNAL_STORAGE` at execution time. It covers
mounted shared internal/removable storage, not root/private app access. Canonical paths must remain
under a reported root; custom protected-app directories in Android/media/data/obb remain blocked.

`device.arm` accepts `direct_only=true` for L0/file tasks. This explicit mode can arm while the
operator's durable Accessibility OFF preference remains set, never enables or recovers accessibility,
and reports `lanes: l0`. Screen actions stay gated even if the service happens to be bound. Default
screen-control readiness follows the selected Hands Accessibility/Shizuku backend. An already armed session keeps its
mode until disarmed; a later arm request cannot silently upgrade it.

Read/write/append chunks are capped at 196608 bytes, binary transport is `data_base64`, and reads
carry a byte offset, version, size and EOF marker. Directory listings return up to 500 items plus
`next_offset`. Writes/copies stage beside the target before publication; overwrite defaults false.
Append requires the exact current byte offset. Directory copy/move and nonempty delete require
`recursive=true`; recursive operations preflight at most 10000 entries, checking protected targets
before mutation. Storage roots cannot be replaced/deleted. Directory moves across filesystems may
fail; use copy then explicit delete if appropriate. Recursive delete can be partial on IO failure
or cancellation and never claims rollback. Each long operation has a 110-second work deadline;
broker/relay file replies allow 120 seconds (HTTP client 125). Other tool timeouts are unchanged.

Phone file work is serialized across lanes. DISARM can interrupt pending file work on its own lane;
every stream loop rechecks consent, OS access and cancellation. An arm generation prevents quick
disarm/re-arm from reviving old work. Host `mobile_file_pull`/`mobile_file_push` wrap bounded chunks,
stage files and compare SHA-256 before publishing. Temporary upload paths are reported if cleanup
is blocked. File names/contents are untrusted data and never instructions or automatic wake events.
Network failure during final publication can leave the outcome uncertain; inspect the target before
retrying. Only known pre-execution busy/rate-limit refusals are automatically retried.

## Explicit Accessibility tools

`device.accessibility.status` is passive L0 metadata; `device.accessibility.enable` is armed-only L0.
`device.accessibility.disable` is a META stop action, allowed while disarmed and exempt from the
control rate limit, as is `device.disarm`. No accessibility binding or foreground-app access is needed
for these three capabilities. Room-turn restrictions still apply at the tool layer.

An authorized normal ARM can restore Hands Accessibility when its grant already exists,
only for a primary-screen Accessibility backend or Hybrid without usable Shizuku. It never
overrides a saved explicit OFF and never runs for direct-only, agent-screen or Shizuku-backed
ARM. It is session recovery, not background monitoring.

With Hands `WRITE_SECURE_SETTINGS` already granted, ON enables only the Hands service
and preserves other enabled services. It requires an armed holder and an explicit
tool call during an authorized task; it never overrides OFF automatically. The reported binding can lag the
settings write. A direct-only holder remains direct-only, so obtain a fresh
screen-capable holder after binding. Without the grant, ON stops control, opens
Hands Access and returns `local_approval_required:true`; the operator completes
approval locally and allows a new session. File and notification permission
openers also use local handoff; tools never grant Android permissions. OFF stops control, saves OFF and requests service teardown, preserving
other services; Android may require the operator to finish it manually.

For other Android Accessibility services, `device.accessibility.services` lists installed
service components (labels are untrusted app-supplied data) and `device.accessibility.service.set` accepts exactly `component` and
Boolean `enabled`. Both require ARM; mutation requires Hands `WRITE_SECURE_SETTINGS`,
checks the installed component and protection rules, rejects Hands itself and preserves
other services. `device.secure_settings.list` and `device.secure_settings.set` support only
`display_inversion` and `show_keyboard_with_hardware_keyboard`; both require ARM and the
write requires the same grant. Both service toggles and these setting changes apply phone-wide,
including the primary display when an agent screen is selected. There is no raw settings key or shell execution.

`screen_control_available` is the screen-lane readiness field, including
Shizuku. Fall back to `a11y_bound` only for older protocols that omit it.
`a11y_bound=false` is compatible with Shizuku control/private entry. Live caches
never override the phone's per-command authorization. Local approval/private-entry
results clear the cached armed state, including metadata-only replies.

Hands Access owns SMS/provider/storage/runtime and special-access permissions.
`device.permissions` and `mobile_status.permissions` expose grant/restriction
metadata. Companion permissions do not authorize Hands. An installer-restricted
permission remains denied; do not use shell/adb to bypass it.

Intent tools that can open the physical phone expose `allow_primary_handoff`.
With the agent target they refuse without explicit true for a user-requested
handoff. This flag never authorizes automated permission/credential approval.

## Arming

An armed session holds an already-on screen awake across app switches using a visible
`TYPE_APPLICATION_OVERLAY` window with `FLAG_KEEP_SCREEN_ON` and a bounded `PARTIAL_WAKE_LOCK` for
CPU execution. The overlay includes an `AWAKE · STOP` control with at least a 48 dp touch target.
The implementation must not rely on deprecated `SCREEN_BRIGHT_WAKE_LOCK` alone: Samsung can disable
that lock after inactivity. A screen hold requires `Settings.canDrawOverlays` for the app owning it;
missing permission fails with `permission_denied` and an `overlay_permission_required` message,
without claiming a successful hold. The operator grants **Display over other apps** through
Hands **Access → Floating screen permission**. No tool silently grants this permission.

`device.arm` accepts `duration_seconds` (integer 30–7200,
default 3600) and returns `lease_id`, `idle_timeout_seconds` and `keep_awake` with its live arm/lane
state. Each successful explicit arm acquires a distinct holder lease, even when already armed, so
concurrent hosts/turns do not share one cleanup token. Accepted control commands while armed renew the hold and idle deadline; status reads and
standalone-hold commands do not extend control consent. `device.status` is
passive, allowed disarmed and returns actual phone state; the host must not report an optimistic
cached ARMED value. A freshly attached LiveDevice starts with armed=false.

Hermes runtimes with turn lifecycle hooks bind a successful `mobile_arm` to the owning turn. The
plugin renews `device.arm.renew` with that exact `lease_id` at most 30 seconds apart (sooner for short
leases), independently of model
tool calls or reasoning. This internal action requires an already armed matching generation and
cannot arm a disarmed phone. Turn completion/interruption/session end stops renewal and sends a
conditional `device.disarm` for that holder only; the phone disarms after the last holder ends.
Stale cleanup cannot remove a newer holder, and finishing one host/turn cannot stop another's work. The tool
reports `working_lease_managed` so older runtimes without hooks do not claim this guarantee. They
retain the bounded phone idle lease. Agents should arm before reasoning and must not fabricate
tool keepalives.

`device.display.keep_awake` (`mobile_keep_awake`) has `action=status|set`, with status as the default.
Set requires `enabled`; enabling accepts an independent `duration_seconds` lease with the same
30–7200 bounds/default 3600. This explicit narrow META operation is allowed while disarmed and
does not arm or enable Accessibility. Disabling releases only the standalone hold; an armed hold
continues. Private entry allows status/disabling but blocks enabling. Disabling bypasses rate limits.
Explicit `device.disarm` and arm idle expiry release the arm hold; a standalone hold remains
independently bounded until disabled/expired. The visible STOP control, phone notification kill
switch and service teardown release the holds and remove their overlays. Manual screen-off revokes
the current holds; screen-on/unlock never restores them, and background renewals cannot reacquire
revoked holds. A new hold needs an explicit request after live status and operator consent are
checked. Android also removes windows/wake locks on process death. No screen-timeout settings are
changed. These holds do not wake or unlock a sleeping phone or override the power button.

`device.display.brightness.get` (`mobile_brightness_get`) is a passive L0 read of configured level,
automatic mode and `WRITE_SETTINGS` grant. `device.display.brightness.set` (`mobile_brightness_set`)
requires ARMED and Modify system settings access. Arguments are optional `level` (integer percentage
1–100) and `auto` (boolean), with at least one required. A level selects manual mode and conflicts
with `auto=true`. Missing permission returns `permission_denied` naming `WRITE_SETTINGS`; use
`mobile_settings_open(page="write_settings")` for the operator's grant page. No Accessibility or
foreground-package grant is needed. In auto mode the configured value is not actual panel luminance.

Phone states: `DISARMED` | `ARMED` | `EXECUTING`.

- Control and personal-data tools fail closed when not `ARMED`; documented passive/meta tools
  and the explicit standalone hold are narrow exceptions.
- The plugin prompts agents to refresh live phone status before arming and control.
- Idle lease expiry, notification kill switch and permission revocation → `DISARMED`.
- Background renewal never re-arms; explicit `mobile_arm` is required after disarm.

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
| `a11y_unavailable` | Selected screen backend unavailable or holder is direct-only |
| `privacy_active` | Phone-local private entry shields remote reads/actions until local release |
| `privacy_unavailable` | Persistent privacy protection could not be established; no value entry starts |
| `request_not_found` | Private-entry UUID is unknown to this phone/lane |
| `observation_unavailable` | No readable current accessibility tree |
| `screen_unavailable` | Android did not expose a readable active window |
| `screen_unstable` | Window changed while acquiring the observation |
| `screen_off` | Display is off; the operator must wake it |
| `screen_locked` | Phone is locked; the operator must unlock it |
| `observation_cancelled` | Observation was cancelled before delivery |
| `gesture_timeout` | Gesture completion is unconfirmed; do not repeat blindly |
| `gesture_cancelled` | Android cancelled a gesture that may have partly executed |
| `screenshot_blank` | Capture appears blank; no trustworthy screen evidence |
| `screenshot_secure_window` | Android refused capture of a secure window |
| `screenshot_rate_limited` | Android refused captures too close together |
| `screenshot_invalid_display` | Android could not capture the requested display |
| `screenshot_invalid_window` | Captured window is no longer valid |
| `screenshot_failed` | Capture failed for another specific platform reason |
| `screenshot_timeout` | Capture did not finish within its deadline |
| `invalid_screenshot` | Invalid PNG data or inconsistent dimensions from the phone |
| `image_input_unavailable` | Screenshot was captured but the model received only a text fallback |

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

## Arming does not imply Accessibility

The running Hands service permits authenticated control; `device.arm` acquires a
controller-owned holder. Screen readiness is enforced separately per action.
`direct_only:true` intentionally blocks screen input even if a backend is ready.
Without it, authorized Shizuku can provide `lanes:l0+l1` and
`screen_control_available:true` while `a11y_bound:false`. Accessibility OFF is
never silently overridden. The selected agent display must be created explicitly;
missing or invalid display identity never falls back to the physical screen.

Companion has no local arming service or wake-lock owner. Hands owns physical
holds, privacy, cancellation, permission signatures and holder expiry.
A normal scoped release retires only that holder; local/global Stop also clears
Hands' passive notification sharing. Review the [Hands API](hands-http-api.md)
for independent client, permission, notification and private-entry contracts.
