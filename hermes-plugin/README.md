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

Pairing file: `~/.hermes/companion-devices.json` (mode 600).

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
