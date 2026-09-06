# Slice 1 + Slice 2 notes (feat/multi-gateway-mobile-control)

## Landed

### Slice 1 — Multi-gateway
- `selectConnectChoice` clears `password` before connect so leftover UI passwords cannot skip saved per-host creds.
- Settings **DISCONNECT FROM HOST** wired: cancel connect/sync/watch/fleet, clear active operator UI, stop stay-connected; device lanes kept while ARMED.
- `probeFleet` replaces `hostHealth` (no forever-merge of stale hosts); empty fleet clears the map.
- Fleet foreground gate uses `MutableStateFlow` + `first { it }` instead of a 250ms spin.
- A8.3 leftovers: concrete password cross-host bug fixed; stores already host-scoped from A8.5 (no rewrite).

### Slice 2 — Mobile Hands
- A6.7 multi-device: metadata on register, `companion-devices.json` fields (`name/model/manufacturer/os_version/is_default/last_seen/extra_protected`), rich `GET /companion/device/lanes`, `POST /companion/device/default|rename`, broker/live resolve (1 device auto; multi without default → `ambiguous_device`), tools `mobile_devices` / `mobile_select_device` + optional `device=` on mobile_*, CLI `lanes|default|rename`.
- App sends Build metadata + custom protected packages on lane register; HANDS friendly label editor.
- Minimal built-in denylist restored (Settings/permission/installer/keychain + authenticators/password managers); custom list kept; synced to host via register `protected_packages`.
- Biometric: suppress `lockIfEnabled` while Activity Result picker outstanding; `BiometricGate` calls `onFail` when busy.

## Deferred / out of scope
- Older-page history REST fallback.
- OpenClaw.
- Full S22 QA (checklist below).
- A8.1 Room hosts table; full A8.3 Room partition (already origin-keyed for transcripts).

## S22 checklist (manual)
- [ ] Connect picker: switch hub ↔ lab without leftover password skipping saved creds.
- [ ] Settings disconnect returns to Connect; stay-connected off; fleet chips still update.
- [ ] Pair two phones (or phone + emulator): `hermes companion lanes`, `mobile_devices`, `mobile_snapshot(device=…)`.
- [ ] Multi without default → ambiguous_device; set default via CLI/tool.
- [ ] Rename label on HANDS; appears in lanes.
- [ ] Built-in denylist blocks Settings/authenticator; custom add/remove still works.
- [ ] Camera/photo/file pickers do not trip biometric lock mid-pick.
- [ ] Biometric prompt while busy surfaces failure (no silent no-op).
