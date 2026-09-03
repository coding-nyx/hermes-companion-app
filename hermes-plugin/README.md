# hermes-companion plugin

Install on the Hermes host:

```bash
hermes plugins install /path/to/hermes-companion-app/hermes-plugin
hermes plugins enable hermes-companion
hermes tools enable mobile
```

Then from a chat: the `mobile_*` tools appear only while a phone is paired **and ARMED**. Disarmed or unpaired calls fail closed.

## CLI

```bash
hermes companion pair          # print a 6-char code (phone shows the same)
hermes companion list
hermes companion approve K7M2QX
hermes companion revoke <device_id>
```

(`register_cli_command` wiring lands when this directory is dropped into `~/.hermes/plugins/`.)

## Tests

```bash
python3 -m unittest discover -s hermes-plugin/tests -q
```

No Hermes install required. Broker tests use a mock device.

## Config (profile `config.yaml`)

```yaml
companion:
  enabled: true
  idle_disarm_sec: 300
  screenshot_max_px: 1080
```
