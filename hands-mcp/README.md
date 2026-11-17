# Hands JSON API and optional MCP adapter

Hands is a standalone Android control service. Any authorized agent, application
or host can use its authenticated TLS JSON API from any language with HTTP/TLS
support. No SDK, MCP runtime, Hermes installation, Companion account, gateway or
model provider is required. Pairing is approved on the phone; starting the
Hands service is the control gate, with no separate session approval.

This directory provides an **optional** MCP stdio adapter for clients that prefer
MCP tools. Python 3.10+ is its only host dependency; direct API clients do not
need Python.

The phone executes operations and owns Android permissions, service availability,
protected applications, display selection and the exclusive controller lease.
This adapter translates MCP tools to the phone's authenticated HTTP API. It does
not run arbitrary shell commands.

See the [complete generic HTTP API](../docs/protocol/hands-http-api.md) for client-independent pairing and command details.

## Use the JSON API from any client

1. Enable the phone's **LAN / Tailscale** connection and compare the HTTPS
   certificate's SHA256 fingerprint with the value displayed locally on the
   phone. Verify that pin during TLS setup **before sending HTTP headers or
   credentials**.
2. While **Pair remote agent** is open, send `POST /v1/pair` with
   `{code,client_name}`. The code is eight ASCII digits and the client label is
   1–80 characters. The operator approves that named client on the phone.
3. Keep the returned token private and send it in
   `Authorization: Bearer <token>` for status and control requests. Creating a
   credential does not arm control.
4. After the operator starts the service and selects a backend/display, read
   `GET /v1/status`, then acquire a holder with `POST /v1/commands` and
   `action: "device.arm"`. Use your stable controller identity and the returned
   `lease_id` for subsequent commands. Release only your owned holder when done.

The [HTTP contract](#http-contract) below describes the shared API. The Python
pairing helper and MCP configuration below are conveniences, not requirements
for authorizing another app, agent or host.

## Pair directly over LAN or Tailscale

Direct connections use the phone's optional HTTPS listener on port 8766. They do
not require ADB or a gateway. Both devices must already be reachable on
the same LAN or Tailscale network. Plain HTTP is rejected for every remote
address, including Tailscale addresses.

1. Open Hands and use **Start service**. In **LAN / Tailscale**, enable the
   remote connection and use **Pair remote agent**. Copy the HTTPS endpoint and
   SHA256 certificate fingerprint displayed on the phone. They are separate
   fields; no pairing URI or QR parser is required.
2. Prepare a private directory outside source repositories. Its parent must be
   owned by you and not writable by other users:

   ```sh
   mkdir -p ~/.config/hands
   chmod 700 ~/.config/hands
   ```

3. Start pairing from the host, replacing the address and fingerprint with those
   shown locally on the phone:

   ```sh
   python3 /absolute/path/to/hands-mcp/hands_mcp.py \
     --pair \
     --endpoint https://192.168.1.50:8766 \
     --server-fingerprint PHONE_SHA256_FINGERPRINT \
     --client-name "Laptop agent" \
     --token-file ~/.config/hands/laptop-token
   ```

   For Tailscale, use the phone's reachable Tailscale IP or DNS name in the same
   `https://…:8766` endpoint. The fingerprint accepts 64 hex digits, colon-separated
   bytes, or the phone's groups separated by ASCII spaces. It is a hash of the
   complete TLS certificate, not a public-key/SPKI hash.
4. Enter the eight-digit code at the hidden terminal prompt, then approve the
   named client on the phone. The pairing window lasts 120 seconds; an accepted
   request waits up to 60 seconds for local approval. The CLI allows 75 seconds for
   the request and never automatically retries it. Codes are single-use; after a
   denial, timeout or lost response, start a new pairing window.
5. The CLI exclusively creates the token file with owner-only `0600` permissions and
   prints client metadata plus an MCP configuration. It never prints the token
   or pairing code. Existing token files are rejected **before** sending a
   pairing request. Add the printed configuration to your MCP client, or use:

   ```json
   {
     "mcpServers": {
       "hands": {
         "command": "python3",
         "args": [
           "/absolute/path/to/hands-mcp/hands_mcp.py",
           "--endpoint", "https://192.168.1.50:8766",
           "--server-fingerprint", "PHONE_SHA256_FINGERPRINT",
           "--token-file", "/absolute/path/to/laptop-token"
         ]
       }
     }
   }
   ```

For a headless host, replace the terminal prompt with
`--pairing-code-file /private/path/to/code-file`. That file must be a small regular
file with owner-only `0600` permissions containing just the eight digits. Delete
your temporary code file after use. Pairing codes and bearer tokens have no
command-line-value flags.

The TLS certificate pin is checked **before any HTTP headers, bearer token or
pairing body are sent**. A mismatch closes the TLS connection. Compare changed
fingerprints locally on the phone; the CLI never discovers or trusts a new
certificate automatically. The phone retains its identity in Android Keystore;
clearing app data or reinstalling can require a new pin. Without a supplied pin,
ordinary HTTPS control connections retain normal CA and hostname validation.
Direct pairing always requires an explicit pin.

Pairing adds one client credential and does not arm control. Select the intended
backend and **Agent screen** or primary target and start the service.
The agent calls `mobile_status` and `mobile_arm` afterward. The separate screen
requires Android 14+ and running, locally authorized Shizuku. If phone approval
succeeds but the token cannot be saved, revoke that named client on the phone
before starting a new pairing window.

## Connect through a local ADB or SSH tunnel

### Android Permissions & Secure Settings Setup

Grant required runtime permissions in Hands under the **Access** tab. For automated Accessibility service recovery and system settings management without manual system-settings handoffs, grant `WRITE_SECURE_SETTINGS` via ADB:

```sh
adb shell pm grant app.hermes.hands android.permission.WRITE_SECURE_SETTINGS
```

1. Install and open Hands. Grant only the Android permissions needed for
   the selected backend. Shizuku authorization is required for the Shizuku
   backend and separate agent display.
2. Use **Start service**, then **Local connection → Create local token** and copy the token to a private
   file on the host. Keep this file outside the source repository and restrict it to its owner
   (`chmod 600 /absolute/path/to/hands-token`). **Create local token** adds a new
   client credential; existing credentials remain valid. Revoke a named client
   individually to remove only its access. **Revoke all clients** explicitly
   invalidates every client, including Companion, so reconnect any clients you
   want to keep afterward.
3. Forward a host loopback port to the phone's loopback API. With one connected
   phone:

   ```sh
   adb forward tcp:8765 tcp:8765
   ```

   With multiple phones, select one explicitly:

   ```sh
   adb -s PHONE_SERIAL forward tcp:8765 tcp:8765
   ```

   The same command works with an already paired wireless ADB connection. The
   adapter does not start ADB or open a network listener itself. For a remote
   host, forward through an authenticated tunnel to a host loopback address.
   Plain HTTP is accepted only for loopback endpoints. Direct LAN/Tailscale
   connections use the pinned HTTPS pairing flow above.
4. Add the stdio server to your MCP client's configuration. Use absolute paths:

   ```json
   {
     "mcpServers": {
       "hands": {
         "command": "python3",
         "args": [
           "/absolute/path/to/hands-mcp/hands_mcp.py",
           "--endpoint", "http://127.0.0.1:8765",
           "--token-file", "/absolute/path/to/hands-token"
         ]
       }
     }
   }
   ```

   `HANDS_TOKEN` is also supported for clients that manage secrets through
   environment variables. Do not put the token in command-line arguments or a
   committed client configuration.
5. Start the Hands service and select the backend and primary/agent target. The
   agent calls `mobile_status`, then `mobile_arm` to acquire its controller lease.
   A remote arm request cannot silently grant Android permissions.

Remove only the forwarding rule you created when finished:

```sh
adb forward --remove tcp:8765
```

## Tools

The shipped [tool catalog](tool_catalog.json) contains **79 typed tools** and
**76 device action mappings**. It is standalone source data: the adapter never
imports a Companion/Hermes plugin, gateway, or Android source checkout at runtime.
Discovered tools describe the common API; the phone's returned capabilities,
local permissions, service state and backend readiness remain authoritative.

| Tool | Purpose |
| --- | --- |
| `mobile_status`, `mobile_devices` | Inspect the one connected phone |
| `mobile_arm`, `mobile_disarm` | Acquire/release an exclusive controller lease |
| `mobile_keep_awake` | Manage a separate bounded physical-screen hold without arming |
| `mobile_brightness_get`, `mobile_brightness_set` | Read/set physical display brightness |
| `mobile_snapshot`, `mobile_screenshot` | Observe the selected display |
| `mobile_click`, `mobile_type`, `mobile_swipe`, `mobile_scroll`, `mobile_press` | Act on that display |
| `mobile_open_app`, `mobile_apps` | Launch/list applications |
| `mobile_agent_screen_start`, `mobile_agent_screen_status`, `mobile_agent_screen_stop` | Manage the separate agent display |
| `mobile_accessibility_status/enable/disable`, `mobile_accessibility_services/service_set`, `mobile_secure_entry` | Hands and explicitly selected installed Accessibility services; value-free private entry |
| `mobile_secure_settings`, `mobile_secure_settings_set` | List/set supported secure toggles with verified Android read-back |
| `mobile_sms_list/send/compose`, `mobile_calls_log/dial/place`, `mobile_contacts_search` | Permission-gated messages, calls and contacts |
| `mobile_audio_volume_get/set`, `mobile_audio_ringer`, `mobile_media_sessions/now_playing/control`, `mobile_clipboard_get/set` | Direct audio, media and clipboard APIs |
| `mobile_calendar_list/add`, `mobile_location_last`, `mobile_app_usage`, `mobile_dnd/set` | Provider data and Do Not Disturb |
| `mobile_speak/stop/status`, `mobile_alarm_set`, `mobile_timer_set`, `mobile_media_play_search`, `mobile_share`, `mobile_settings_open`, `mobile_app_uninstall` | Speech and explicit Android handoffs |
| `mobile_files_access/enable`, `mobile_files`, `mobile_file_pull/push` | Shared-storage management and explicit-path verified transfers |
| `mobile_notifications`, `mobile_notifications_ack/clear`, `mobile_notifications_nls/enable`, `mobile_notifications_stream/set`, `mobile_notification_actions/action/reply/dismiss/snooze` | Hands-owned consented polling and notification actions |

Each MCP process points to one phone endpoint. Host/gateway device selection,
rooms, notification injection, host wake policy and per-profile relay policy are
excluded. In particular, `mobile_notifications` reads `device.notifications.list`
on the phone, not a gateway's notification ring.

Secure settings tools require `mobile_arm`; writes need Hands’ separately granted `WRITE_SECURE_SETTINGS`. The supported secure toggles are display inversion and showing the keyboard with a hardware keyboard. The latter is platform-dependent and an unverified change is an error. These changes are phone-wide, including the physical display, not confined to the agent display. Wi-Fi, Bluetooth, airplane mode and location do not become generally writable through this grant. `WRITE_SETTINGS` and Do Not Disturb access remain separate Android grants.

Provider reads containing personal data and mutations require `mobile_arm`.
Safe metadata and explicitly scoped notification polling/ACK may work disarmed;
the phone still enforces local recipient consent and pauses during private entry
or pairing. Permission requests without the necessary Android grant pause/disarm and open Hands' local Access panel; they do not grant themselves Android permission. With separately granted `WRITE_SECURE_SETTINGS`, an armed agent can enable Hands Accessibility without that handoff. An explicit OFF remains in force until an explicit enable request; a direct-only holder remains direct-only. Private-entry tools accept and
return only request/status metadata: values are never model-visible arguments or
results, and cancel cannot release the local privacy shield.
Private entry drops this bridge's working holders immediately; after the user
ends privacy locally, the agent must explicitly call `mobile_arm` again.

When target is **agent**, Activity handoffs such as SMS compose, dial, calendar
insert, share, settings and uninstall refuse with `primary_handoff_required`
unless `allow_primary_handoff=true` is explicitly supplied. That flag authorizes
the requested handoff onto the user's primary display; it is never added
automatically. Direct SMS/provider/file/media operations retain their own gates.

Notification polling accepts `after_cursor`, `limit`, and optional narrowing
`since_ms`/`package` filters. Events carry a stable UUID `delivery_id`, cursor,
consent version and passive/untrusted notification metadata. Acknowledge with
`mobile_notifications_ack(delivery_ids=[...])`; duplicate ACK calls are safe.
The operator selects the recipient client, apps, content and retention locally.
Remote stream changes can only narrow or disable that approved scope. Polling
never wakes an agent, injects chat, enables sharing or changes recipients.

`mobile_files` supports list/stat/read/write/append/mkdir/copy/move/delete/hash.
Chunks are limited to 196608 decoded bytes; append requires the expected byte
offset and versioned reads reject source changes. `mobile_file_pull/push` require
both the requested phone path and an explicit absolute `local_path`; there are
no implicit host file reads or destinations. They transfer bounded chunks,
verify SHA256 before publishing and return metadata instead of file bytes.
Overwrite is opt-in. The default total cap is 256 MiB, configurable up to 1 GiB
with `max_transfer_bytes`. Stopping or losing permission ends the transfer;
cleanup never rearms or broadens permission, and a temporary upload may remain
if cleanup is no longer authorized.

Long speech waits retain `wait_ms` up to 120000 with a bounded 125-second HTTP
timeout. File operations also use a bounded 125-second timeout. ARM uses at least
10 seconds so the phone can finish its bounded Accessibility recovery; all other
commands retain the configured timeout. These overrides never retry a request or
recreate a holder.

`mobile_arm` accepts `backend` (`accessibility`, `shizuku`, `hybrid`), `target`
(`primary`, `agent`) and an optional `duration_seconds` between 30 and 7200 (default
3600). Omitting
backend and target keeps the phone's selection. Check `mobile_status` first and omit both fields by default. If specified, these fields assert the current selection; they do not switch it. For separate-screen work, verify `target=agent` first and ask the operator to select Agent screen in Hands if necessary. `target_mismatch` or `backend_mismatch` means the requested value differs from the selection, not that session approval is needed. With WRITE_SECURE_SETTINGS and no explicit Accessibility OFF, a screen-capable primary Accessibility or Hybrid holder can recover Hands Accessibility when required. Recovery never runs for direct-only holders, the agent display or the Shizuku backend. A direct-only holder stays direct-only; the same client can acquire a new holder after the service binds. Display dimensions are logical
pixels and must come from the current observation, not a scaled image guess.

Screenshots are MCP image content blocks with separate JSON metadata. Base64
pixels are not repeated as text. Input, response and image sizes are bounded.
The bridge never retries an action after a timeout or connection error: the
action might already have happened. Observe the phone again before deciding
whether to repeat it.

Each bridge process has a unique controller identity. Each successful explicit
ARM creates its own holder ID, kept in memory and attached to subsequent control
calls. A second MCP process does not inherit these holders. The background worker
renews only already-issued holder IDs while the MCP process remains alive,
including while a model reasons between tools. It never calls ARM to recover a
lost session. Renewals occur at most 30 seconds apart, sooner for short leases.

`working_lease_managed=true` in arm/status results reports active process-based
renewal. Renewal cannot override a local stop, privacy shielding, token
revocation or a changed backend/display. A terminal refusal ends renewal.
A failed transport request is not replayed; `working_lease_error` reports why
background management stopped. The phone's own bounded expiry remains in force.

`mobile_disarm` and clean stdin closure attempt to release every holder owned by
this process, each using its exact holder ID. They cannot release a newer foreign
holder. End your MCP process or disarm when work is done. If the process or
connection dies, phone-side expiry and the local kill switch remain authoritative.
`mobile_agent_screen_stop` is a separate explicit **global stop**: it closes the
agent display and disarms Hands, including other controllers, and may run while
disarmed. New work requires a new holder while the Hands service is running.

`mobile_keep_awake(action="set", enabled=true, duration_seconds=3600)` requests a
separate hold on an already-on physical screen without arming or enabling
Accessibility. Its duration is 30–7200 seconds. Read it with `action="status"` and
release it with `action="set", enabled=false`; normal disarm leaves this independent
hold intact. It never wakes/unlocks the phone or overrides its power button.
Brightness writes require an armed holder and Android Modify system settings
access. They use `level=1..100` for manual mode or `auto=true/false`.

## HTTP contract

Status and control requests include `Authorization: Bearer <pairing-token>`. Redirects and
environment-configured HTTP proxies are disabled so a pairing credential cannot
be forwarded to a different endpoint.

The optional direct listener uses HTTPS on port 8766; the tunnel listener remains
HTTP on `127.0.0.1:8765`. `POST /v1/pair` is the direct TLS pairing route and takes
`{"code":"eight ASCII digits","client_name":"client label"}` without a bearer.
It requires the open phone pairing window, a single-use code and explicit local
approval. Success contains `{ok:true,result:{token,client_id,label}}`; only the
private token file receives the returned token. Pairing does not grant a control
lease. The same authenticated command/status API works after
pairing on either transport.

`GET /v1/status` returns:

```json
{"ok":true,"result":{"armed":false,"backend":"hybrid","target":"agent"}}
```

`POST /v1/commands` receives:

```json
{
  "action": "device.click",
  "arguments": {"x": 120, "y": 240},
  "controller_id": "a-process-specific-uuid",
  "lease_id": "the-lease-returned-by-device.arm"
}
```

Actions use `device.*`, including `device.agent_screen.start/status/stop`. Arm
returns `result.lease_id`; errors use
`{"ok":false,"error":{"code":"...","message":"...","hint":"..."}}`.
Screenshot results contain `mime: "image/png"`, `png_b64`, `w`, `h` and display
identity/coordinate metadata. These responses must be JSON objects, not JSON
strings nested inside the result.

## Verification

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s hands-mcp/tests -v
```

The tests use a real local HTTP server and cover MCP framing/lifecycle, bearer
authentication, redirects, strict arguments, lease isolation/cleanup, phone-side
consent failures and PNG image delivery. Local TLS tests generate a temporary
self-signed certificate using `openssl` and verify zero HTTP bytes reach a
mismatched certificate, unchanged CA validation, one-shot pairing, private atomic
token storage and secret-free CLI output. No keys are committed; `openssl` is
needed only for those tests. They do not substitute for on-device
Shizuku/display/network testing.

Protocol references:
[stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#stdio),
[MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).
