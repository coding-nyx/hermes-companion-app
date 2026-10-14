---
name: hermes-companion
description: "Drive the paired Android Hermes Companion: arm-first, snapshot, tap, type, swipe, multi-device, plus direct-API tools (audio / media / clipboard / device info)."
version: 0.3.0
platforms: [linux, macos]
metadata:
  hermes:
    tags: [android, companion, mobile, accessibility, device-control, snapshot, arm-first, direct-api, media-control, audio, clipboard]
    related_skills: [android-wireless-adb-over-tailscale]
---

# Hermes Companion — phone as hands

Use the **hermes-companion** plugin tools (`mobile_*`) to look at and operate the paired Android phone. The phone is the source of truth for permissions. Do not use adb, scrcpy, or shell input for this job.

Skill lives at `hermes-plugin/skills/hermes-companion/SKILL.md` and **ships with the plugin** (same PR / same deploy to lab+raj). Do not fork a second copy outside the plugin.

## When to use

- The user asks to tap, type, swipe, open an app, read the screen, or "use the companion".
- A task needs the physical phone (notifications, a specific app UI, on-device confirmation).
- You need to arm or disarm device control from this chat.

## Don't use

- Banking, authenticator, Play Store, Settings, or System UI — those return `protected_package`.
- When no phone is paired (`mobile_status` → `no_device`).
- Emulator / USB adb workflows (that's `android-wireless-adb-over-tailscale`).

## Two lanes: a11y (L1) and direct API (L0)

The plugin exposes **two lanes** for controlling the phone. Prefer L0 whenever the action maps to a direct Android API; fall back to L1 (the snapshot/click/type a11y tree) only when the task is genuinely about arbitrary 3rd-party app UI.

| Lane | Tools | When |
|---|---|---|
| **L0 direct API** | `mobile_audio_volume_*`, `mobile_audio_ringer`, `mobile_media_*`, `mobile_clipboard_set`, `mobile_device_info` | Anything with a direct Android API (audio, media sessions, clipboard writes, device meta). Fast, structured, no a11y coupling, not scoped to the foreground app. Note clipboard *writes* are L0 but clipboard *reads* are not — see `mobile_clipboard_get` below. |
| **L1 a11y** | `mobile_snapshot` / `mobile_click` / `mobile_type` / `mobile_swipe` / `mobile_scroll` / `mobile_press` / `mobile_open_app` / `mobile_apps` | Tapping, typing, swiping inside an arbitrary 3rd-party app; reading its UI tree. Requires `CompanionAccessibilityService` to be bound. |

**Rule of thumb:** if the action could be answered with "set a value" or "read a value" from a system service, use L0. If it needs to find a button on a screen, use L1.

### L0 tool reference

| Tool | Returns / does | Allowed while disarmed? | Notes |
|---|---|---|---|
| `mobile_audio_volume_get` `stream=music\|ring\|notification\|alarm\|system\|voice_call` | per-stream `{stream, current, max}` (omit stream for all) | yes | direct `AudioManager` |
| `mobile_audio_volume_set` `stream=…` `level=0..max` (or `step=<delta>`) | flips the stream | no (destructive) | clamped; ignored on fixed-volume devices |
| `mobile_audio_ringer` `mode=normal\|vibrate\|silent` (omit to read) | current / new mode | no (destructive) | `silent` needs Do Not Disturb access — otherwise `policy_denied` |
| `mobile_media_sessions` | list of `{package, is_active, playback_state}` | yes | requires the user to grant Companion notification access (NLS) in Android Settings |
| `mobile_media_now_playing` `session_id=<owning package>` | `{title, artist, album, duration_ms}` | yes | same NLS gate |
| `mobile_media_control` `session_id=…` `action=play\|pause\|toggle\|next\|prev\|stop\|seek` (`position_ms=` for seek) | fires the transport action | no (destructive) | the session must currently be active |
| `mobile_clipboard_get` | plain text, or `clipboard_read_blocked` | no (personal data — clipboards hold passwords/OTPs) | **Usually fails.** Android 10+ refuses the read unless Companion is the focused app or the default IME, which it normally is not. To read what the user is looking at, `mobile_snapshot` and read the focused field instead. A successful read also shows the user a paste toast on Android 12+. |
| `mobile_clipboard_set` `text=…` | writes | no (destructive) | never log the value |
| `mobile_device_info` | `{manufacturer, model, sdk_int, screen_w/h, refresh_rate_hz, locale, battery_pct, ringer_mode, a11y_bound, nls_bound}` | yes | health / identity / connectivity check |
| `mobile_notifications_nls` | `{nls_bound, listener_component, enabled_listeners}` | yes | check whether the user has granted NLS yet |
| `mobile_notifications_nls_enable` | launches `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` and returns `{launched, intent, nls_bound}` | yes | the app cannot programmatically grant NLS (Android signature-only); the user must toggle Companion in the system page |
| `mobile_notifications_stream` | `{enabled, origin, target_origin, profile, nls_bound}` | yes | read the phone's current shade-stream config |
| `mobile_notifications_stream_set` `enabled=true\|false` `origin=…` `profile=…` | updates sticky + reconfigures the bus, returns the new state | no (destructive) | missing args → `missing_args`; bad `enabled` → `invalid_enabled`; if you set `origin`/`profile`, the resolved `target_origin` falls back to the active host when those are empty |
| `mobile_notification_actions` `key=…` | `[{index, title, accepts_text}]` | yes | the buttons on one notification; call this before firing anything |
| `mobile_notification_action` `key=…` `index=N` | presses a plain button | no (destructive) | refuses `accepts_text` buttons — those need reply, or the message goes out empty |
| `mobile_notification_reply` `key=…` `text=…` | sends an inline reply | no (destructive) | **sends a real message to a real person** — confirm the wording with the user first; never log the text |
| `mobile_notification_dismiss` `key=…` | clears it from the shade | no (destructive) | does not read or act on the content; the message stays unread in the app |
| `mobile_notification_snooze` `key=…` `duration_ms=N` | re-posts it later | no (destructive) | clamped 1s..24h |

The L0 lane **does not require** `CompanionAccessibilityService` to be bound and is **not** blocked by a protected foreground package (it's not scoped to the foreground). It still requires the phone to be paired (`mobile_status → ok` first) and the device lane to be live on the host the plugin is attached to.

### L0 error codes

`nls_unavailable` — user has not granted Companion notification access. Ask them to enable it in Settings → Notifications → Device & app notifications → Hermes Companion. Affects the `mobile_media_*` family only.
`policy_denied` — `mobile_audio_ringer mode=silent` failed because Do Not Disturb access is not granted. Ask the user to grant it in Settings.
`invalid_stream` / `invalid_mode` / `missing_stream` / `missing_level` / `missing_session` / `missing_args` — bad arguments. Check the table above.

Host CLI (outside chat): `hermes companion qr`, `hermes companion list`, `hermes companion approve CODE`, `hermes companion revoke DEVICE_ID`, `hermes companion lanes`.

## Always this order (arm-first)

1. **`mobile_status`** / **`mobile_devices`** — paired? a11y? overlay? armed? which lanes?
2. If multi-device or ambiguous: **`mobile_select_device`** (or pass `device=` on later calls).
3. If unpaired: tell the user to run `hermes companion qr` on the host to scan or tap the link, or tap PAIR on the Device tab and approve on host. Stop.
4. If `a11y_bound` is false: tell the user to enable **Installed apps → Hermes Companion** in Accessibility. You cannot grant it. Stop.
5. **`mobile_arm` immediately** — before long planning text and before other tools — so the phone does not lock / sleep while you think. Arming never needs accessibility; it returns `lanes`: **`l0+l1`** means the a11y lane is available too, **`l0`** means direct-API tools only. If you get `l0`, do not plan a snapshot/tap sequence — either use an L0 tool for the job or tell the user accessibility is off.
6. **`mobile_snapshot`** — work from `@eN` refs. Prefer snapshot over screenshot.
7. Gesture: `mobile_click` (`text=` label or `ref=@eN`) / `mobile_type` / `mobile_swipe` / `mobile_scroll` / `mobile_press` / `mobile_open_app` (from `mobile_apps`).
8. Clicks/types/swipes return a **fresh snapshot**. Use those new refs. Never reuse `@eN` from before the gesture.
9. **`mobile_disarm`** when the task is done, or immediately if the user says stop / that's enough / take your hands off.

**Allowed while disarmed:** `mobile_status`, `mobile_devices`, `mobile_select_device`, `mobile_arm`, `mobile_disarm`, `mobile_notifications`, `mobile_notifications_inject`.

**Require armed:** snapshot, click, type, swipe, scroll, press, open_app, screenshot, wait, the destructive L0 tools (volume set, ringer, media control, clipboard write, notification action/reply/dismiss/snooze, stream set), the personal-data reads (sms, call log, contacts, calendar, location, app usage, clipboard read) — and any other control. Control ops refuse with `disarmed` + hint to call `mobile_arm` first; they do not auto-arm.

**Arming and accessibility are separate.** Arming is the user's consent plus the kill switch; accessibility is a capability. In an `l0`-only armed session the direct-API tools work normally and only the L1 tools (snapshot/click/type/swipe/scroll/press) answer `a11y_unavailable`.

## Arm / disarm from Hermes

| Tool | Effect | Phone gate |
|---|---|---|
| `mobile_arm` | Arms control; returns `{armed, a11y_bound, lanes}`. Overlay `HERMES HAS HANDS` should appear. | Works with accessibility off — you then get `lanes: l0` and only the direct-API tools run. Hermes cannot grant a11y. |
| `mobile_disarm` | Instant kill switch. Same path as the notification DISARM and double volume-down. | Always allowed. |

The user can still disarm on the phone at any time. After they disarm, gestures fail closed (`disarmed`) until `mobile_arm` succeeds again.

## Phone notification stream

- Phone Device tab: enable **NLS** (system Notification access) + **STREAM**, pick target gateway+profile.
- Events land in the host relay ring (denylist: protected packages + Companion’s own notifs). **Telegram shade notifs are not streamed** (built-in echo guard). **No auto-Telegram dump.**
- When a notification is accepted for your profile, the relay **wakes** this agent with a strong operating prompt (configurable; default on). That wake is **not** an inject and must **not** be parroted as the reply.
- On wake: immediately call **`mobile_notifications`** (optional `profile=`), briefly tell Nyx what matters from the tool result, and only then consider inject.
- **`mobile_notifications`** — read recent events (allowed while disarmed; optional `profile=` filter).
- **`mobile_notifications_inject`** — explicit only: post your summary into the active session. Dedupes by `notification_key`. Default: do **not** inject. **Never auto-called.** Never invent notification bodies.

## Multi-device

- `mobile_devices` lists paired lanes + live/default.
- `mobile_select_device` sets the default lane.
- Control tools accept optional `device=` when you need a specific phone without changing the default.

## Agent rooms (group chat between profiles)

A user turn that starts with `[room "…" · you are XYZ …]` means the operator put you in a **room** with other Hermes profiles (possibly on other hosts: `BIS (hub-11)`). The relay runs the room; you only see the lines you have not seen yet, each as `[room] GLYPH: text` (`YOU` is the operator).

Rooms are **conversations**, not relay races:

- Reply in plain text when you have something useful to add or ask. Keep it short; other agents read it too.
- Ask a participant directly with `@GLYPH` (e.g. `@OPS`, `@ASH·H`). `@YOU` reaches the operator (it wakes their phone).
- You may disagree, ask follow-ups and change your mind. When the group has reached a conclusion, say so in one line and `PASS`.
- Reply **exactly** `PASS` when you have nothing to add. The room goes quiet when everyone has passed since the last real line.
- The header tells you how many turns are left before the operator is asked to continue; wrap up before it runs out.
- In a **moderated** room one participant chairs: it picks who speaks with `@GLYPH` and closes the topic with exactly `[END]`. If you are the moderator, do that.
- If you need an approval (a risky command, a clarification) just ask for it as usual: the room forwards it to the operator's phone and your turn resumes with the answer.
- **Hands**: only the room's hands holder (named in the rules line) may call `mobile_*` control tools during a room turn; everyone else gets `room_hands`. Status and notification reads are fine.
- Never invent lines for other participants; never repeat the `[room]` prefix in your reply.

## How not to fail (observe → act → verify)

Agents usually fail by guessing pixels, typing into nothing, or reusing dead refs. Do this instead:

1. **Arm first**, then snapshot. Do not plan in text while the phone can lock.
2. **Tap with `text=`** (the visible label) or `ref=@eN` from the latest tree. Do **not** invent coordinates when a node exists.
3. **Read the snapshot on the gesture result.** Those refs replace the previous tree. `stale_ref` means you used a dead `@eN` — snapshot (or use the returned tree) and pick a new one. Do not retry the same ref.
4. **Type:** click the input, confirm it is focused in the new snapshot, then `mobile_type`. `no_focus` means you skipped the click.
5. **Open app:** `mobile_apps` → `mobile_open_app` → `mobile_wait` 800–1500ms → `mobile_snapshot`. The launcher has not finished if you snapshot immediately.
6. If a tap does nothing: one snapshot, then a different node — do not spam clicks.
7. `no_match` on `text=`: the label was missing or ambiguous. Use `candidates` / the attached snapshot and click a unique `@eN`.
8. Never banking / authenticator / Settings (`protected_package`). Never adb/scrcpy as a workaround.

## Fail-closed codes

`no_device` · `disarmed` · `a11y_unavailable` · `protected_package` · `stale_ref` · `no_match` · `no_focus` · `click_failed` · `rate_limited` · `capability_denied`

Errors include a `hint` with the next tool to call. Follow it. If you get `disarmed`, call `mobile_arm` (do not invent shell/adb workarounds).

## Origin

The phone talks to the companion relay on the Hermes host (`:9120`), not a laptop SSH tunnel.

### Acting on a notification

Prefer this over the a11y lane whenever a notification is involved: it is one call, it cannot
mis-tap, and it does not pull the app to the foreground.

1. `mobile_notifications` → pick the notification, keep its `key`.
2. `mobile_notification_actions key=…` → see what buttons exist.
3. Plain button (`accepts_text: false`) → `mobile_notification_action key=… index=N`.
   Text button (`accepts_text: true`) → `mobile_notification_reply key=… text="…"`.

Keys are short-lived. `unknown_notification` or `notification_gone` means re-read
`mobile_notifications` rather than retrying the same key. Only notifications that reached the
stream are addressable, so a protected or denylisted app's notification cannot be acted on.

**Replying is not a reversible action.** `mobile_notification_reply` delivers the text to the
owning app exactly as if the user had typed it into the notification — it goes to a real person in
a real conversation. Confirm the exact wording with the user before sending, never send
placeholder or speculative text, and never invent a message body from the notification preview.

### Per-app notification policy

Three tiers, and every app is **respond** until told otherwise:

| Mode | Reaches `mobile_notifications`? | Wakes the profile? | Use it for |
|---|---|---|---|
| `respond` (default) | yes | yes | anything the user wants an agent to react to |
| `silent` | yes | **no** | chatty-but-useful apps — system UI, media, build bots. The agent still sees them when it looks. |
| `off` | **no** | no | apps that should not reach the host at all |

- `mobile_notification_policy` — read the effective policy, with the source of each rule.
- `mobile_notification_policy_set package=… mode=off\|silent\|respond\|default` — set or clear
  one rule. `profile=` scopes it to a single profile; omit for host-wide. Writes host config,
  not the phone, so it needs no ARM.

Host rules override the phone's own per-app rules **with one exception**: a package the phone
suppresses at source never reaches the host at all, so a host `respond` on it can never take
effect. The tools report that as `unreachable_override` with a warning rather than claiming a
mode you will never observe; clearing it requires the phone's Device tab.

If the user complains an agent keeps waking on something, `silent` is almost always the right
answer — it stops the wake without blinding the agent.
