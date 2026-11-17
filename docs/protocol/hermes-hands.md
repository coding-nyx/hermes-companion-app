# Hands

Hands is the standalone Android phone controller (`app.hermes.hands`). Any agent,
application or host that implements its authenticated HTTPS JSON API can connect.
Authorization stays on the phone. No model provider, Hermes agent, profile,
Companion account or MCP SDK is required. The `hands-mcp` stdio adapter is an
optional client; Companion is another client with its existing gateway transport.
See the [generic HTTP API](hands-http-api.md) for an independent implementation.

## Ownership and compatibility

`hands-app` owns local approval, client credentials, controller leases, protected
packages, the control foreground service and the agent preview. `hands-engine`
owns the optional Shizuku UserService. Existing Android toolboxes and design
components are shared libraries, not copied into another source tree.

Companion's Device tab connects through Hands' local approval Activity and keeps
the returned credential encrypted with Android Keystore. Companion is exclusively
a client: every phone command uses Hands, and unavailable/unsupported actions
never execute in the old Companion process. Android grants do not migrate between
application packages. Existing users must approve Hands and its permissions;
Companion's historical grants cannot authorize it.

Hands exposes screen and agent-display control, device/audio/media/clipboard
APIs, SMS and call-log/contacts/calendar/location/usage APIs, shared-storage files
and verified chunk transfers, phone-local private entry, notification polling and
actions, brightness, telemetry and bounded screen holds. Read `capabilities` for
the running version; an unavailable API returns an explicit error. The same JSON
API serves Companion, MCP and independently implemented clients.

## Android grants and private values

Hands **Access** shows runtime grants, special access and installer restrictions.
Only the operator approves Android access. `device.permissions` and status return
metadata; they do not grant access. Files, SMS/history and other personal-data
operations require both Hands' Android grant and an armed holder. Denial or an
installer restriction cannot be bypassed through Companion, shell or another app.

`device.files.enable` and `device.notifications.nls.enable` are armed requests
for local approval. They stop control, open Hands Access and return
`local_approval_required:true,armed:false`. `device.accessibility.enable` uses
Hands' existing `WRITE_SECURE_SETTINGS` grant to enable only its own accessibility
component through an explicit tool call when needed for an authorized task; without that grant it uses the same local-approval
handoff. A direct-only holder does not gain screen control after the service binds;
obtain a fresh screen-capable holder.
Permission pages opened through `device.settings.open` also hand off to Access;
ordinary system Settings remain explicit physical-display intents. The operator
finishes locally and allows a fresh session. Permission and client
approval surfaces are protected by the same command-draining boundary.


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

To grant Hands the permission once from a computer already authorized for Android
debugging, the operator can run:

```sh
adb -s PHONE_SERIAL shell pm grant app.hermes.hands android.permission.WRITE_SECURE_SETTINGS
```

Hands Access reports whether its own grant is present. The user must allow a new
control session after a grant changes; an agent cannot grant it through the API.

`device.secure_entry` requests a generic field label plus the exact current
package/ref/snapshot UUID. It accepts no value argument. The operator enters the
value on Hands' secure local surface; metadata reports pending/filling/filled or
failure. Accessibility or Shizuku may capture the field; `a11y_bound=false` alone
is not a refusal when `screen_control_available=true`. Values never enter HTTP,
MCP, logs or saved UI state. The target application necessarily receives them.
A persistent privacy shield blocks remote reads/input and notification delivery
until the operator releases it locally after hiding the value. Status/cancel
cannot release it. A fresh local session is required after release.

Android intent handoffs (compose, dial/call, share, calendars, alarms, settings,
uninstall) can open the physical phone. With the agent target they require
`allow_primary_handoff:true` for an explicitly requested operator handoff. That
flag never authorizes approving permissions or using display 0 as a fallback.

## Phone-owned notifications

Hands **Alerts** pins sharing to one approved client ID, an explicit app set,
metadata/title/full content, 1–60 minute retention and optional call categories.
Hands' notification-access grant is managed in Access. Capture is disabled by
default and after process restart. Settings persist; captured contents do not.
Custom protected apps, Hands/Companion self-echo channels and built-in Telegram
clients are excluded. Content is untrusted third-party data, never instructions.

The memory ring holds at most 256 deliveries with stable UUIDs and monotonic
`consent_version`. Only the locally enabled recipient may passively poll/ACK
while disarmed; ACK is delivery bookkeeping and never dismisses a notification.
Action/reply/dismiss/snooze and remote scope changes require an armed permit and
live eligibility checks. Remote changes may narrow scope or stop it, never enable,
change recipient or expand the phone's approval. Privacy/pairing pause reads;
local/global Stop and recipient revocation clear subscription and ring. Normal
scoped holder release stops control while preserving separately approved passive
sharing. Notification categories do not establish call state, audio or log access.

Companion separately requires explicit forwarding consent and a pinned
gateway/profile. It polls Hands; it owns no Android notification listener.
It encrypts at most 128 queued deliveries before sending, pins host credentials
and both consent scopes, preserves UUID/payload, rechecks Hands before each send
and retries no faster than 30 seconds. Gateway acceptance (or terminal policy
rejection) is persisted before ACKing Hands. Scope/route/privacy changes discard
old queued data without forwarding it elsewhere. Old capture-outbox formats do
not restore. Passive forwarding requires both receiver feature flags and never
wakes an agent, nudges Telegram or injects into chat. Delivery remains bounded,
best effort and at least once; it is not a durable exactly-once service.

## Backends and screen selection

- **Accessibility:** the phone's main screen, using Android Accessibility.
- **Shizuku:** Android shell privileges through an explicitly authorized
  UserService. No arbitrary shell command tool is exposed to agents.
- **Hybrid:** Accessibility on the main screen when available; otherwise Shizuku.
  The separate agent screen always uses Shizuku. A failed mutation is never
  automatically repeated through another backend.

The independent screen requires Android 14 or newer and a running, authorized
Shizuku service. A trusted virtual display uses its own focus and disables
stealing the main display's top focus. Creation fails if Android does not preserve
those flags. Input, task launches, observations and screenshots are explicitly
display-targeted. Destroying the display destroys its task content instead of
moving it to the operator's screen. Losing the display never targets display 0.
The floating preview uses a non-focusable window and can be dragged or collapsed.
The privileged observation connection requests interactive windows on all
displays; `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` was required for readable secondary
windows on the tested Android 16 device. An empty tree must not be diagnosed as
keyguard alone or treated as permission to use another display.

Not every third-party application permits a second instance or another display.
Existing single-task applications on the main display are refused. Home/Recents
are unavailable on the agent display because they can invoke global launcher
behavior. Shizuku text input currently accepts printable ASCII; unsupported text
returns an explicit error. Protected screenshot layers are refused. These
restrictions must not be bypassed with generic shell commands.

## Consent, holders and stopping

Local **Allow session** grants a one-hour maximum approval for the selected
backend and screen. Remote `device.arm` cannot change that selection or grant
approval. One controller (client token plus controller identity) owns control at
a time. Every ARM returns a distinct holder UUID. A reasoning heartbeat and an
explicit tool can therefore hold independent leases for the same controller.

Holder duration defaults to 3600 seconds, accepts 30–7200 seconds, and is capped
by local approval. `idle_timeout_seconds` reports the actual cap. Renewal requires
an existing, unexpired holder and never restores revoked approval. Releasing a
holder cannot release another holder; the last release stops control. Local Stop,
global authenticated stop, permission loss, display loss and process death revoke
control. Shizuku cancellation invalidates the execution epoch and kills active
input, preventing an old command from starting new input after cancellation.

Screen references include a snapshot UUID and are invalidated by screen changes,
lease changes and screen selection. Screenshot metadata distinguishes encoded
image dimensions from logical display dimensions. Agents must map coordinates
using that metadata.

`device.display.keep_awake` is independent of arming. It accepts `action:status`
or `action:set`, `enabled` and optional `duration_seconds` (30–7200, default3600).
It cannot wake or unlock the phone. The shared physical-screen hold uses a
visible, non-focusable **AWAKE · STOP** overlay with `FLAG_KEEP_SCREEN_ON`, plus a
bounded partial CPU wake lock. Overlay permission, an already-on display and an
unlocked phone are required. Its Stop action cancels the hold and control;
screen-off, permission loss or unexpected window removal also revoke it.
Queued renewal cannot restore a revoked owner: fresh local session approval or
an explicit standalone enable must call `beginSession` first.

Armed control uses this bounded physical-screen hold for either target because
both require an unlocked phone. Approved sessions waiting for a controller hold
only the CPU; they do not automatically hold the operator's display.
Keep-awake status distinguishes the armed physical hold from the independently
requested standalone hold and reports actual screen/keyguard state. Ordinary
disarm leaves an independently requested standalone hold intact; explicitly
release it, use the visible Stop control or stop the connection service when
finished. Stopping the connection service releases every hold.

The 65-second live MCP idle check described below is not a long-duration Samsung
reliability result. Runs longer than 15 minutes belong to the coordinated
reliability work and remain pending.

## MCP transport

Hands always keeps its local API on `127.0.0.1:8765`. Its Clients tab can explicitly
enable a second, encrypted listener on network port `8766` for direct LAN and
Tailscale access. Remote access starts off after process restart and stops with
the connection service. Turning it off stops control and any pending pairing.

The network listener uses TLS 1.2/1.3 with a persistent, non-exportable Android
Keystore identity. The phone shows its certificate's SHA-256 fingerprint and
current IPv4 network addresses, including VPN/Tailscale interfaces. The MCP
client checks that fingerprint during the TLS handshake, before transmitting a
pairing code or bearer. Plain HTTP to a non-loopback address is refused.

Every status/control request needs an independently revocable bearer token.
Tokens are delivered once, retained only as digests in
encrypted private preferences, excluded from backup, and never logged. Browser
origins, redirects, duplicate headers, oversized requests and chunked bodies are
rejected. The API has bounded worker and socket lifetimes.

Creating another MCP token adds a client credential; it does not revoke existing
ones. **Revoke** removes one client and stops its active control. **Revoke all
clients** invalidates every client,
including Companion's connection token. Those clients must reconnect afterward.

- `GET /v1/status` returns `{ok:true,result:{...}}`.
- `POST /v1/commands` accepts
  `{action,arguments,controller_id,lease_id?}`.
- Errors use `{ok:false,error:{code,message}}`.
- Only the TLS listener accepts `POST /v1/pair` with `{code,client_name}` before
  authentication. A phone operator must first open a 120-second, single-use
  eight-digit pairing window. A matching request consumes the code and shows
  its name and actual peer address for local approval. The request waits at most
  60 seconds; only approval issues `{token,client_id,label}` to that connection.
  Wrong codes, requests, pending state and socket work are bounded. Denial,
  timeout, screen lock and listener shutdown close pairing without granting
  control. Pairing does not grant session consent or an armed holder.
  Opening any credential approval revokes control, drains accepted commands and
  pauses arm, content reads and input before the code or approval controls appear.
  Status and Stop remain available. Android app approval and manual token dialogs
  use the same boundary; stale activities cannot close a newer approval.
- Scoped disarm carries the exact owned `lease_id` at the envelope level; omitting
  it is an intentional global stop. Companion validates its per-host/profile
  holder set before forwarding a scoped lease.

Use direct pinned TLS over LAN/Tailscale, or an existing authorized ADB/SSH
tunnel to the local API. No cleartext network listener is created. The MCP adapter
uses native image content for screenshots and renews only its own issued holders
while its stdio process remains alive. Phone-side approval expiry remains the
absolute limit. See [the MCP setup instructions](../../hands-mcp/README.md).

## Build

```sh
./gradlew :hands-app:assembleDebug
adb -s PHONE_SERIAL install -r hands-app/build/outputs/apk/debug/hands-app-debug.apk
```

Shizuku is an optional external application. Install an official signed release,
start it through Android Wireless Debugging (or an authorized computer), and
approve Hands locally. Installing Hands alone does not grant shell privileges.

## Validation checkpoint — 17 November 2026

On a Samsung S22 running Android 16/API 36 with locally authorized Shizuku, the
real engine probe and both HTTP instrumentation acceptance tests passed. The
HTTP tests used a disposable `app.hermes.hands.test` application and checked
snapshot/ref controls, ASCII text, pixel controls, scrolling, PNG/display metadata,
primary-task isolation, invalid/missing-display refusal, scoped holder cleanup
and local-stop invalidation.

A real stdio MCP process also passed its synthetic-scene smoke test, including
65 seconds idle with holder renewal and scoped release. Hands' own main Control
and Clients tabs remained interactive during the secondary-display run. This is
evidence for that tested configuration and synthetic application, not a claim
that every third-party application supports independent instances. At that checkpoint, automated
MCP coverage was 63 tests and engine policy/session coverage was 10 tests.

The final native UI, including network setup and pairing forms, was reviewed in
dark/light themes and enlarged text. Companion's local approval and relay
roundtrip passed. Final real Android acceptance passed all five tests, including
AndroidKeyStore TLS 1.2/1.3 and the pre-existing-controller approval boundary.
Direct Tailscale pairing/control and independent generic HTTPS access over LAN
passed, as did wrong-pin refusal, per-client revocation, listener shutdown and a
fresh ARM → physical hold → scoped disarm check. The interrupted extended idle
monitor does not establish a 16.5-minute guarantee.

See the [implementation checkpoint](../reviews/2026-11-17-hermes-hands.md) for
recorded checks, deployment scope and compatibility limits.

At the complete API migration checkpoint, verification passed 133 Hands tests, 18 engine tests, 24 client
tests, 95 device-module tests, 400 Companion tests, 85 MCP tests and 306 plugin tests. Final S22
acceptance passed 11 native tests, including generated file transfer, synthetic
notification consent isolation and exact private-node fill. Companion’s installed encrypted connection passed
an additional status-only native smoke. A separate real
stdio MCP client discovered all 75 tools and passed synthetic input, native PNG
and SHA256-verified binary push/pull. SMS/provider success and notification reply
use synthetic fixtures; no user message history was read or real message sent.
See the implementation checkpoint for precise scope and deployment status.

The subsequent secure-settings parity checkpoint expanded the MCP catalog to 79
tools. Native S22 acceptance exercised Hands Accessibility OFF, explicit enable
and armed recovery, service discovery and a reversible supported setting; a real
stdio MCP client verified discovery, the own-service guard and a setting change.
See the implementation checkpoint for the scope and current deployment status.
