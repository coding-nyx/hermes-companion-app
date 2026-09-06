# Slice 1 + Slice 2 notes (feat/multi-gateway-mobile-control)

## Empty built-in denylist (`fix/empty-builtin-denylist`)
- `DeviceLanePolicy.PROTECTED_PACKAGES` and `hermes-plugin/broker.py` `PROTECTED_PACKAGES` are empty; `isProtected` / `is_protected` still merge custom extras.
- Device tab copy: `BLOCKLIST empty · add packages to protect` / `BLOCKLIST  N custom` (no "N built-in").
- Stream filter: self-package + FGS channels still suppressed; do not rely on built-in package list.


## Telegram stream suppress (`feat/telegram-stream-suppress`)
- Built-in `NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES`: `org.telegram.messenger`, `.web`, `.beta`, `org.thunderdog.challegram`.
- Filters NLS forward path only; `DeviceLanePolicy.PROTECTED_PACKAGES` / broker `PROTECTED_PACKAGES` stay empty (Hands can control Telegram).
- Wake skip default aligned via `agent_wake.STREAM_SUPPRESS_PACKAGES` (dual list — keep in sync).
- Device tab: `STREAM MUTE  N built-in (Telegram)` near STREAM; BLOCKLIST remains Hands custom-only.


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
- Built-in denylist emptied (`PROTECTED_PACKAGES = emptySet()` / broker `frozenset()`); protection is custom-only via StickyStore + register `protected_packages`. Notification stream still blocks Companion self package/FGS channels.
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
- [ ] Custom denylist add/remove protects Settings/authenticator when added; UI shows empty built-in (0).
- [ ] Camera/photo/file pickers do not trip biometric lock mid-pick.
- [ ] Biometric prompt while busy surfaces failure (no silent no-op).
