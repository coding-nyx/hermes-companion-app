# hermes-companion plugin

Users: follow the [root README setup](../README.md#setup). This file is for the host plugin.

## Install

```bash
./install.sh
```

Copies into `~/.hermes/plugins/hermes-companion` and runs `hermes plugins enable hermes-companion`. Start a **new** Hermes session so tools, skill, CLI, and the `:9120` relay load.

From GitHub:

```bash
hermes plugins install coding-nyx/hermes-companion-app/hermes-plugin
hermes plugins enable hermes-companion
```

Do not pass a local filesystem path to `hermes plugins install` — it is treated as a GitHub URL.

## Continuous voice and Hermes updates

Voice support ships in `dashboard/` and loads through Hermes’s authenticated,
enabled-plugin API hook at `/api/plugins/hermes-companion/audio/*`. The relay
keeps `/api/audio/*` aliases for existing Companion clients. No Hermes source
patch is installed. Speech configuration and provider credentials stay on the host.

`install.sh` updates the relay and reloads an existing managed dashboard. It enables
Companion for the machine default and the active profile using Hermes’s config
writer; it does not change speech providers, voices, keys, or other plugins.
For another profile, run the installer after selecting it, or enable Companion
explicitly for that profile and restart the dashboard. Native API trust gates remain
in effect. The machine dashboard launcher explicitly selects the default profile.

Updating/reinstalling Hermes while preserving `$HERMES_HOME/plugins` and profile
configuration retains the integration. A full data-directory removal requires
installing/enabling Companion again. Reinstalling/updating the plugin restores all
voice code. Native handler signature changes fail with a compatibility error rather
than patching the new Hermes version. Uninstall/disable plus dashboard restart
removes the process-local adapters.

The original source overlay is retired once, with recognized source restored and
recovery copies moved to `$HERMES_HOME/state/companion-voice-legacy`. Unrecognized
source edits abort migration before either tracked core file is written. The pinned
upstream Edge protocol adapter repairs installed v1 clients in memory; it does not
replace dependencies or modify their installed files. Its source revision and LGPL
license are in `dashboard/edge_protocol/`.

Native integration tests run inside Hermes with:

```sh
scripts/run_tests.sh /path/to/hermes-companion/tests/native/test_voice_native.py -q
```

## After install

Phone origin: `http://<hermes-tailscale-or-lan>:9120`

```bash
hermes companion approve CODE
hermes companion list
hermes companion revoke DEVICE_ID
hermes companion lanes
```


## Companion CLI (Hermes 0.21 fallback)

Hermes Agent v0.21 may not expose `register_cli_command`, so `hermes companion …`
never appears. Use the bundled fallback (same subcommands: `lanes`, `approve`,
`default`, `rename`, `list`, `revoke`, `relay`):

```bash
python ~/.hermes/plugins/hermes-companion/cli_main.py lanes
python ~/.hermes/plugins/hermes-companion/cli_main.py approve CODE
python ~/.hermes/plugins/hermes-companion/cli_main.py default DEVICE
python ~/.hermes/plugins/hermes-companion/cli_main.py rename DEVICE_ID NAME
```

Or from a checkout: `python hermes-plugin/cli_main.py lanes`.
Relay URL override: `HERMES_COMPANION_RELAY_URL=http://127.0.0.1:9120`.

Pairing file: `~/.hermes/companion-devices.json` (mode 600).

## Boot persistence

`install.sh` (after copy) and `install-services.sh` pick the host supervisor:

- **Linux:** systemd user units (`hermes-companion-relay`, or dashboard + `hermes-agent-companion`)
- **macOS:** LaunchAgents `ai.hermes.companion-relay` (standalone) or `ai.hermes.dashboard` + `ai.hermes.companion` (proxy)

```bash
bash ~/.hermes/plugins/hermes-companion/install.sh
bash ~/.hermes/plugins/hermes-companion/install-services.sh
```

macOS logs: `~/Library/Logs/hermes-companion/`. Linux: `systemctl --user status hermes-dashboard hermes-agent-companion`.

## Relay

Dashboard is usually `127.0.0.1:9119`. The phone cannot use that. The plugin binds `0.0.0.0:9120` and proxies. If something else already owns `:9120`:

```bash
HERMES_COMPANION_RELAY=0
HERMES_COMPANION_RELAY_URL=http://127.0.0.1:9120
```

Standalone: `python3 -m relay`

### Connection mode from the phone

Device (including pairing) and Gateway show the host's actual connection mode. A paired
phone can select **Native Hermes** or **Standalone** and explicitly apply the change.
Native mode checks `profiles.list` on the configured dashboard before saving; a failed
check leaves the current mode intact. Pairing and Hands do not select or change routing.

The saved choice in `$HERMES_HOME/companion-connection.json` survives relay restarts and
takes precedence over the startup `HERMES_COMPANION_STANDALONE` environment variable.
With no saved choice, the existing environment/default still applies. The setting is
host-wide: changing it reconnects operator websockets while leaving device lanes live.
`POST /companion/connection/mode` requires loopback or a paired device credential and
`{mode, expected_mode}`; stale competing changes are rejected. The upstream address is
configured by the host, not accepted from a phone-supplied URL.

## Tests

```bash
python3 -m unittest discover -s tests -q
```
