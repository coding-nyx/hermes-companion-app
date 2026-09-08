---
name: hermes-companion
description: "Drive the paired Android Hermes Companion: arm-first, snapshot, tap, type, swipe, multi-device."
version: 0.2.1
platforms: [linux, macos]
metadata:
  hermes:
    tags: [android, companion, mobile, accessibility, device-control, snapshot, arm-first]
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

Host CLI (outside chat): `hermes companion list`, `hermes companion approve CODE`, `hermes companion revoke DEVICE_ID`, `hermes companion lanes`.

## Always this order (arm-first)

1. **`mobile_status`** / **`mobile_devices`** — paired? a11y? overlay? armed? which lanes?
2. If multi-device or ambiguous: **`mobile_select_device`** (or pass `device=` on later calls).
3. If unpaired: tell the user to PAIR on the Device tab (6-char code) and approve on the host. Stop.
4. If `a11y_bound` is false: tell the user to enable **Installed apps → Hermes Companion** in Accessibility. You cannot grant it. Stop.
5. **`mobile_arm` immediately** — before long planning text and before other tools — so the phone does not lock / sleep while you think. If arm fails `a11y_unavailable`, stop and ask the user.
6. **`mobile_snapshot`** — work from `@eN` refs. Prefer snapshot over screenshot.
7. Gesture: `mobile_click` (`text=` label or `ref=@eN`) / `mobile_type` / `mobile_swipe` / `mobile_scroll` / `mobile_press` / `mobile_open_app` (from `mobile_apps`).
8. Clicks/types/swipes return a **fresh snapshot**. Use those new refs. Never reuse `@eN` from before the gesture.
9. **`mobile_disarm`** when the task is done, or immediately if the user says stop / that's enough / take your hands off.

**Allowed while disarmed:** `mobile_status`, `mobile_devices`, `mobile_select_device`, `mobile_arm`, `mobile_disarm`, `mobile_notifications`, `mobile_notifications_inject`.

**Require armed:** snapshot, click, type, swipe, scroll, press, open_app, screenshot, wait (and any other control). Control ops refuse with `disarmed` + hint to call `mobile_arm` first — they do not auto-arm.

## Arm / disarm from Hermes

| Tool | Effect | Phone gate |
|---|---|---|
| `mobile_arm` | Arms control. Overlay `HERMES HAS HANDS` should appear. | Accessibility must already be on. Hermes cannot grant a11y. |
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
