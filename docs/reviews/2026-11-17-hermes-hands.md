# Hands implementation checkpoint — 17 November 2026

Hands is the Android execution owner for the complete mobile toolset. Companion
is an authenticated client, alongside generic HTTPS clients and the optional
79-tool MCP adapter. Updated Hands and Companion APKs are installed on the S22.

## Ownership

- Hands owns SMS, calls/history, contacts, shared files, notification capture and
  actions, calendar/location/usage APIs, audio/media/clipboard/brightness,
  intent handoffs, speech, private entry, screen control and the agent display.
- Companion forwards every device action through its encrypted Hands connection.
  It has no mobile data permissions, accessibility/notification-listener/control
  services, private-entry controller, SMS permission controller or debug phone
  executor. Shared Android toolboxes remain libraries used by Hands.
- Hands Access contains Android grants. Armed permission requests stop control
  and hand off to local approval; granting access never implicitly rearms.
  Existing Android grants do not transfer between application packages.
- Hands Alerts selects one approved client, explicit apps, content level,
  retention and call categories. The bounded passive inbox has stable UUIDs,
  polling and ACK. Actions require an armed holder. Remote changes can narrow or
  stop approval; they cannot enable, expand or retarget it.
- Companion optionally forwards its approved inbox to a separately selected
  gateway/profile. Its encrypted bounded outbox persists gateway disposition
  before ACKing Hands, rechecks both scopes and never wakes or injects into chat. Explicit forwarded notification
  commands also require the enabled pinned route; approval withdrawal discards
  in-flight response bodies.
- Private values remain in secure native input and an exact-node local Binder
  handoff. HTTP/MCP accept metadata only. The durable privacy shield stays active
  until local release; control stays disarmed afterward.
- Protected package rules, including migrated `prefix.*` rules, remain enforced.
  Agent-display intents require explicit permission to open the physical display.
  Missing or lost agent displays never fall back to the phone screen.

## Migration verification checkpoint

| Area | Result |
| --- | --- |
| Hands policy, permissions, HTTP/TLS, notification ownership and native renders | 133 passed |
| Shizuku engine geometry, redaction, connection ownership and cancellation | 18 passed |
| Companion Hands client and holder isolation | 24 passed |
| Device module and migrated client-only UI | 95 passed |
| Companion full regression | 400 passed |
| MCP catalog, validation, transfers and TLS | 85 passed; 75 discoverable tools |
| Hermes plugin full regression | 306 passed |
| S22 native acceptance | 11 Hands tests + 1 Companion status-only installation test passed |
| Real stdio MCP acceptance | Passed; phone-side fixture also passed |
| APK builds and Hands debug lint | Passed |

The S22 runs Android 16/API 36 with locally authorized Shizuku. Companion’s
installed encrypted credential also passed a read-only authenticated status
request, with the migrated capabilities present and no local mobile grants or
control services. The two native Accessibility observation fixtures moved from
Companion to Hands, compile and require explicit opt-in/existing grants; they were
not enabled on this Shizuku-configured phone. Physical tests
used only Hands, a disposable synthetic app, generated files and injected
synthetic notification events. They verified:

- independent display input, reference and pixel gestures, ASCII text, scrolling,
  native PNG geometry, primary-display isolation, missing-display refusal and Stop;
- stable window identity across fresh snapshots, stale snapshot rejection,
  exact-node private fill after privacy cancellation, one-shot/released handles,
  redaction and display-loss refusal;
- real AndroidKeyStore TLS 1.2/1.3 and client-approval draining/epoch protection;
- a 196745-byte shared-file roundtrip, SHA256 and chunk/version validation,
  recipient isolation, notification clear/narrowing, passive delivery after scoped
  release/backend failure, and clearing on explicit global Stop;
- denied personal-data provider and SMS-send operations without reading a granted
  provider or sending a real message.

A real independently launched stdio MCP process discovered all 75 tools at this checkpoint, acquired
its own holder, clicked and typed into the synthetic agent app, received native
PNG content, uploaded/downloaded a generated 196745-byte binary with SHA256
`1b4a8a9d41d60158b71dc57f446f9e84a2d46461d76d48737c790a3fc314040d`,
received a local-consent refusal for unapproved notifications and released only
its holder. Its successful run took 7.3 seconds. Test files and credentials were
removed. The temporary all-files grant was restored to its prior default mode.

SMS/provider success paths and notification action/reply use synthetic unit
fixtures; no real SMS/call was placed and no user message history was read.
During local permission review the phone approved SMS reading; that grant was
preserved after the denied-permission fixture. SMS sending remains a separate
grant. Notification sharing remains off until the operator selects a recipient
and apps in Alerts.

## Secure-settings parity

Hands now exposes typed, armed-only discovery and controls for installed
Accessibility services and the supported secure settings, through both the
Hermes plugin and the standalone MCP adapter. The supported settings are display
inversion and the hardware-keyboard on-screen keyboard preference; the latter
depends on the Android device. These changes affect the physical phone globally.
Hands requires its own `WRITE_SECURE_SETTINGS` grant, which is shown in Access
along with the ADB grant command when absent. Its explicit Accessibility OFF
preference survives ordinary arming; an explicit enable can clear it. Other
services are preserved when changing Hands, and Hands itself cannot be changed
through the generic service setter.

The parity checkpoint passed 159 Hands unit/render tests, 9 targeted Companion
tests, 273 domain tests, 88 MCP tests and 313 plugin tests, plus the Hands debug
build and lint. On the S22, two opt-in native tests passed for explicit Hands
Accessibility enable/disable, durable OFF, armed recovery, service discovery and
a reversible secure-setting change. A real stdio MCP client discovered 79 tools,
checked the secure-settings grant and Hands service guard, and changed and
restored the keyboard preference. The original service list, global Accessibility
switch and tested settings were restored; the requested Hands grant remains in
place. The updated Access screen was inspected on-device in both themes at normal
and 2× text sizes.

## Observation identity correction

Real-device checks caught Android reallocating accessibility window IDs whenever
the last UiAutomation connection disconnected. Three otherwise identical
synthetic snapshots had window IDs 4805, 4825 and 4848, causing the strict guard
to reject every ref/private capture. The engine now retains one session-owned
connection with a 120-second idle limit and revision-safe cleanup. Exact private
capture transfers that connection into its own bounded handle. Session changes,
cancel, display loss and destruction release ordinary observations. Strict
package/window/node/display checks remain intact; the final probe kept window
5060 across repeated reads and successfully clicked the captured control.

## UI and delivery

Control, Clients, Access, Alerts, notification approval/app selection and private
entry were rendered and inspected in dark/light themes and at 2× text, including
320 dp layouts. Scrolling is clipped below the fixed navigation. The installed
Access screen was inspected in both themes and enlarged text; the Android SMS
permission route was exercised. Secure approval windows retain FLAG_SECURE and
produce black OS screenshots; their form layout was inspected through native
render fixtures. Companion's owner, destination, identity and forwarding consent
views retain shared angular chrome and 48 dp controls.

The final APKs, hashes, renders and test logs are retained outside the repository
in the task's `hermes-hands/full-migration` artifact directory. Generated build
outputs and test processes are cleaned after validation. User credentials are
preserved; test credentials and the disposable APK are removed. Control and
network access are off after checks, with the local Hands service available.
Original font scale, theme and display timeout are restored after review.

Current plugin changes, prompt and canonical skill are prepared locally. Applying
them to lab still requires the user's deployment/restart approval. The earlier
plugin sync/reload at 18:09:23–18:09:28 IST preceded that restriction; this complete
migration was not deployed afterward. Raj was not modified. No commits, tags or
pushes were made.

The independent display requires Android 14+ and Shizuku. Ordinary Shizuku typing
accepts printable ASCII, Home/Recents are refused on that display, and some apps
do not permit separate instances. Android grants and platform restrictions remain
authoritative. Long-duration Samsung idle reliability is not established by this
short functional acceptance run.
