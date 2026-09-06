---
name: hermes-companion
description: "Drive the paired Android Hermes Companion: arm-first, snapshot, tap, type, swipe, multi-device."
version: 0.2.0
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
7. Gesture: `mobile_click` / `mobile_type` / `mobile_swipe` / `mobile_scroll` / `mobile_press` / `mobile_open_app` (from `mobile_apps`).
8. Snapshot again after each gesture if the UI changed.
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

## Fail-closed codes

`no_device` · `disarmed` · `a11y_unavailable` · `protected_package` · `stale_ref` · `rate_limited` · `capability_denied`

If you get `stale_ref`, snapshot again before clicking. If you get `disarmed`, call `mobile_arm` (do not invent shell/adb workarounds).

## Origin

The phone talks to the companion relay on the Hermes host (`:9120`), not a laptop SSH tunnel.
