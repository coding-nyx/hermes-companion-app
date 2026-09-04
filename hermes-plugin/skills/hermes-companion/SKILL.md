---
name: hermes-companion
description: "Drive the paired Android Hermes Companion: arm/disarm, snapshot, tap, type, swipe."
version: 0.1.0
platforms: [linux, macos]
metadata:
  hermes:
    tags: [android, companion, mobile, accessibility, device-control, snapshot]
    related_skills: [android-wireless-adb-over-tailscale]
---

# Hermes Companion — phone as hands

Use the **hermes-companion** plugin tools (`mobile_*`) to look at and operate the paired Android phone. The phone is the source of truth for permissions. Do not use adb, scrcpy, or shell input for this job.

## When to use

- The user asks to tap, type, swipe, open an app, read the screen, or "use the companion".
- A task needs the physical phone (notifications, a specific app UI, on-device confirmation).
- You need to arm or disarm device control from this chat.

## Don't use

- Banking, authenticator, Play Store, Settings, or System UI — those return `protected_package`.
- When no phone is paired (`mobile_status` → `no_device`).
- Emulator / USB adb workflows (that's `android-wireless-adb-over-tailscale`).

Host CLI (outside chat): `hermes companion list`, `hermes companion approve CODE`, `hermes companion revoke DEVICE_ID`, `hermes companion lanes`.

## Always this order

1. **`mobile_status`** — paired? a11y? overlay? armed?
2. If unpaired: tell the user to PAIR on the Device tab (6-char code) and approve on the host. Stop.
3. If `a11y_bound` is false: tell the user to enable **Installed apps → Hermes Companion** in Accessibility. You cannot grant it. Stop.
4. If disarmed: **`mobile_arm`**. If that fails `a11y_unavailable`, stop and ask the user.
5. **`mobile_snapshot`** — work from `@eN` refs. Prefer snapshot over screenshot.
6. Gesture: `mobile_click` / `mobile_type` / `mobile_swipe` / `mobile_scroll` / `mobile_press` / `mobile_open_app` (from `mobile_apps`).
7. Snapshot again after each gesture if the UI changed.
8. **`mobile_disarm`** when the task is done, or immediately if the user says stop / that's enough / take your hands off.

## Arm / disarm from Hermes

| Tool | Effect | Phone gate |
|---|---|---|
| `mobile_arm` | Arms control. Overlay `HERMES HAS HANDS` should appear. | Accessibility must already be on. Hermes cannot grant a11y. |
| `mobile_disarm` | Instant kill switch. Same path as the notification DISARM and double volume-down. | Always allowed. |

The user can still disarm on the phone at any time. After they disarm, gestures fail closed (`disarmed`) until `mobile_arm` succeeds again.

## Fail-closed codes

`no_device` · `disarmed` · `a11y_unavailable` · `protected_package` · `stale_ref` · `rate_limited` · `capability_denied`

If you get `stale_ref`, snapshot again before clicking.

## Origin

The phone talks to the companion relay on the Hermes host (`:9120`), not a laptop SSH tunnel.
