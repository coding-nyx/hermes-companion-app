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

## Tests

```bash
python3 -m unittest discover -s tests -q
```
