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
- Hands built-in package denylist is **empty**; protect apps via Device tab custom rules. Stream always suppresses Companion's own package/FGS channels (self-echo) **and** built-in Telegram clients (`NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES`, mirrored in `agent_wake.STREAM_SUPPRESS_PACKAGES`) — stream/wake only, not Hands protect.
- Relay **agent wake** (P1.5): on ring accept, wake the sink profile with a strong chat `-Q` operating prompt (`HERMES_COMPANION_NOTIF_WAKE`, default on; modes `telegram`(default)/`cli`/`botchat`) plus a short human Telegram nudge. Prompt requires immediate `mobile_notifications`, no parroting, no invented bodies; inject only if warranted (default no). Skips Telegram messenger packages by default. **Never** auto-calls `mobile_notifications_inject`.
- Skill + `_prompt()` document the workflow; never auto-inject into Telegram/chat.
- Deploy plugin to **lab** and **raj** together with app changes that touch stream protocol/tools.

## Agent rooms (P21)

- Rooms live in the plugin (`hermes-plugin/rooms.py`); each participant profile gets a backing session titled `room:<id>` (hidden from the phone rail). Turns are mention-first then round-robin, capped by `max_rounds`; `PASS` stays silent. Agents must not call `mobile_*` control tools from a room turn (skill + `_prompt()` say so).
- `/companion/*` is gated: loopback or `Authorization: Companion <device_id>:<credential>`. Pairing handshake, register, health, ticket ws and media GET stay open. Deploy plugin + app together — an old app against a new plugin loses host tools until it re-pairs/updates.
- Relay diagnostics: `HERMES_COMPANION_DEBUG=1` logs every request with timing; 401s always log method/path/client/scheme.
- Protocol: `docs/protocol/rooms.md`.

