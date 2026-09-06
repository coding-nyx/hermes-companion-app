# Agent conventions — hermes-companion-app

## hermes-companion skill ships with the plugin

- Canonical skill path: `hermes-plugin/skills/hermes-companion/SKILL.md`
- Skill updates **ship with plugin** changes — same PR and same deploy to **lab** and **raj**.
- `hermes-plugin/__init__.py` `register()` loads the skill via `ctx.register_skill` from that path. Do **not** fork a second copy under `~/.hermes/skills` or elsewhere.
- When changing `mobile_*` behavior, update together:
  1. `hermes-plugin/skills/hermes-companion/SKILL.md`
  2. `hermes-plugin/__init__.py` `_prompt()` / `tools.armed_hint()`
  3. `hermes-plugin/broker.py` guards (META / armed / protected)
  4. Optional: `docs/protocol/mobile-control.md`

## Arm-first

Agents must call `mobile_arm` before snapshot/gestures (and before long reasoning that would leave the phone idle/locked). Status/devices/select/arm/disarm remain allowed while disarmed; control tools refuse with `disarmed` + hint.

## Deploy

- App: assemble debug APK; sideload to the operator phone.
- Plugin: `hermes-plugin/install.sh` (or rsync) into each host's `~/.hermes/plugins/hermes-companion`, then restart the gateway / companion relay so skill + prompt reload.

## Notification stream (A13.2)

- Phone streams shade notifications (denylist) to a configurable gateway+profile; agent reads via `mobile_notifications` and may explicitly `mobile_notifications_inject`.
- Skill + `_prompt()` document the workflow; never auto-inject into Telegram/chat.
- Deploy plugin to **lab** and **raj** together with app changes that touch stream protocol/tools.
