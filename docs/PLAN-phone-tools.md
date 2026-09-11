# Phone tools — direct-API lanes

**Status:** P0 slice landed 2026-09-08; inventory below is the roadmap.
**Scope:** Android-only; the Companion app is the executor.
**Cross-verified:** against developer.android.com / source.android.com on 2026-09-08. Every
row is tagged with its verification level — see [Verification tags](#verification-tags).

## Three lanes, not two

The original framing was "direct API, fall back to a11y". Cross-checking against the docs
shows there is a third tier that matters more than the a11y lane for most tasks, because it
needs no special grant at all:

| Lane | What it is | Cost to the user | Agent-visible? |
|---|---|---|---|
| **L0 — direct API** | In-process platform call (`AudioManager`, `MediaSessionManager`, `ContentResolver`, …) | A permission grant, once | Silent, synchronous, structured result |
| **L2 — intent handoff** | `startActivity` into the owning app (`ACTION_SET_ALARM`, `ACTION_SENDTO`, `ACTION_IMAGE_CAPTURE`) | Nothing, usually; the app's own UI appears | Fire-and-confirm; result comes back via the app, not the call |
| **L1 — a11y** | `CompanionAccessibilityService` drives the UI tree | Accessibility grant (heavy, scary) | Slow; re-solves the tree per call |

Preference order is **L0 → L2 → L1**. L2 beats L1 whenever an intent exists: it is one call,
it cannot mis-click, and it needs no accessibility grant. L1 stays the universal fallback for
arbitrary third-party app UI that exposes neither an API nor an intent.

A consequence worth stating plainly: **L2 is not silent.** It puts an app on screen, and on
Android 10+ it needs a background-activity-start exemption to work while the phone is idle.
Companion already declares `SYSTEM_ALERT_WINDOW`, which is one of the documented exemptions,
so the L2 lane is viable here — but every L2 tool must report to the agent that the phone's
screen changed. [doc: Android 10 privacy changes]

## Naming

- Phone side: `device.<area>.<action>` (matches the existing `device.snapshot`).
- Plugin side: `mobile_<area>_<action>` (matches the existing `mobile_*`).

## Namespace reconciliation (Nyx's list, 2026-09-08)

A 50-line `device.*` namespace was proposed. Three things fall out of diffing it against what
is shipped and against this inventory.

**1. `device.share` appears twice** — 50 lines, 49 unique. No other duplicate.

**2. It is a rename of the whole surface, not an extension.** Only **3 of 49** names match a
shipped capability exactly (`device.audio.ringer`, `device.media.sessions`,
`device.media.control`). The other 24 shipped capabilities are absent from the list, and the
overlapping ones are spelled differently:

| Proposed | Shipped today | Shape change |
|---|---|---|
| `device.apps.list` | `device.apps` | rename |
| `device.apps.launch` | `device.open_app` | rename |
| `device.notifications.list` | `device.notifications` | rename |
| `device.audio.volume` | `device.audio.volume.get` + `.set` | **2 actions collapse to 1** (verb moves into args) |
| `device.clipboard` | `device.clipboard.get` + `.set` | **2 actions collapse to 1** |
| `device.screen.capture` | `device.screenshot` | rename |
| `device.device` | `device.device.info` | rename |
| — | `device.media.now_playing` | dropped (folded into `sessions`?) |
| — | `device.notifications.nls`, `.nls.enable`, `.stream`, `.stream.set` | absent from the list |
| — | `device.snapshot`/`click`/`type`/`swipe`/`scroll`/`press`/`wait`/`noop`/`arm`/`disarm` | the L1 lane + meta, absent from the list |

The collapsing cases are the ones that matter: `L0_PASSIVE` is keyed on the action name, and it
is what lets a read run on a disarmed phone. `device.audio.volume` as one action cannot be both
passive (get) and arm-gated (set) — the gate would have to move from the action name into
argument inspection on both the phone and the broker, which is a real change to
`DeviceLanePolicy.reject()` and `Broker.dispatch()`, not a rename. Same for `device.clipboard`.
**Recommendation:** keep get/set split as separate actions, adopt the rest of the scheme.

**3. Renaming is a breaking protocol change.** The phone declares `CAPABILITIES` at register and
the plugin mirrors it in `ALLOWLIST`; per `AGENTS.md` an old app against a new plugin loses host
tools until it re-pairs. So a rename needs either a simultaneous app+plugin deploy to **lab** and
**raj**, or — safer — the phone accepting old names as aliases for one release. Do the aliases.

**Coverage:** of the 49 unique names, 13 are shipped under another name, 27 are already in this
inventory under a different heading, and **9 were missing** — now added below:
`device.apps.default` (§D), `device.audio.mic` (§B), `device.camera.list` (§N),
`device.screen.audio` (§O), `device.voice.record` (§P), and §U for `device.ime`,
`device.accounts`, `device.activity`, `device.schedule`.

## Capability allowlist (phone-side, `DeviceLanePolicy.CAPABILITIES`)

The full grantable set, with the lane each action runs on. Anything not in this list is
stripped at register and denied at command time.

```
device.noop
device.snapshot                     L1
device.click                        L1
device.type                         L1
device.swipe                        L1
device.scroll                       L1
device.press                        L1
device.open_app                     L1   (L2 in effect — explicit launch intent)
device.apps                         L1   (a11y-free API; still on the service — move to L0)
device.wait                         both
device.screenshot                   L1   (a11y takeScreenshot, API 30)
device.arm                          meta
device.disarm                       meta
device.notifications                event stream (read-only)
device.audio.volume.get             L0   passive
device.audio.volume.set             L0
device.audio.ringer                 L0   (SILENT needs ACCESS_NOTIFICATION_POLICY)
device.media.sessions               L0   passive, needs NLS
device.media.now_playing            L0   passive, needs NLS
device.media.control                L0   needs NLS
device.clipboard.get                L0   passive — but the platform refuses it; see §F
device.clipboard.set                L0
device.device.info                  L0   passive
device.notifications.nls            L0   passive
device.notifications.nls.enable     L0   passive (opens the Settings page)
device.notifications.stream         L0   passive
device.notifications.stream.set     L0
```

`L0_NO_A11Y` — every `L0` action above. These skip the a11y-bound gate *and* the
protected-package gate (they target a stream or a session handle, never the foreground app).
`L0_PASSIVE` — the `passive` subset, allowed while `DISARMED`. Both sets are mirrored in
`hermes-plugin/broker.py`; `DeviceLanePolicyTest` and `test_broker.py` assert
`L0_PASSIVE ⊆ L0_NO_A11Y ⊆ CAPABILITIES` on each side.

## Declared permissions

**Rule: a permission ships in the same change as the tool that uses it.**

An earlier pass got this backwards — it declared the whole inventory up front (location,
contacts, calendar, SMS, call log, telephony, media, sensors, usage stats, system settings: 42
permissions in the merged manifest) and granted 21 of them on the operator's phone, while **not
one had a code path**. An agent asked to take a photo or read contacts still correctly answered
"those aren't APIs I have", because no tool existed. Held permissions with no caller are pure
attack surface for zero capability: an exploit of the app would have carried `READ_SMS` and
`READ_CALL_LOG` on a daily-driver phone, for a feature that did not exist.

Corrected 2026-09-09 — the merged manifest is back to **15 permissions**, every one backed by a
caller, and the dangling runtime grants were revoked from the device:

| Permission | Used by |
|---|---|
| `ACCESS_NOTIFICATION_POLICY` | `device.audio.ringer` — needed to reach SILENT; `DirectToolbox.ringerSet` gates on `isNotificationPolicyAccessGranted` (SPECIAL: user grants it in Settings) |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | `VoiceInputManager`, `WakeWordService` |
| `CAMERA` | the chat attachment flow in `MainActivity` |
| `SYSTEM_ALERT_WINDOW` | Hands overlay (and the background-activity-start exemption the L2 lane will need) |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`, `WAKE_LOCK`, `VIBRATE`, `INTERNET`, `DISABLE_KEYGUARD`, `USE_BIOMETRIC` | pre-existing app features |

The accessibility and notification-listener binds are declared on their services, not as
`uses-permission`.

`<queries>` follows the same rule and is back to three entries — the launcher intent
(`mobile_apps` / `mobile_open_app`), `RecognitionService` and `TTS_SERVICE`. Note
`ActivityResultContracts` (`PickVisualMedia`, `TakePicture`) resolve without an entry, which is
why the chat attachment flow never needed `READ_MEDIA_*` either.

### What each unbuilt tool will need

The per-area tables below carry the permission, API, min SDK and constraint for every tool in the
inventory — that is the reference to consult when a tool is written. Two findings are worth
keeping in view because they are already settled:

- **Hard-restricted permissions are obtainable here.** `adb install` allowlists them: on
  2026-09-09 `READ_SMS`, `SEND_SMS` and `READ_CALL_LOG` all reported
  `granted=true, flags=[… RESTRICTION_INSTALLER_EXEMPT]`. The platform is not the blocker for
  §G/§H; only the missing tools are. They have since been revoked and undeclared.
- **`scripts/grant-phone-tool-permissions.sh`** grants only the implemented set (3 runtime
  permissions today) and prints the Settings deep links for the special ones. Extend it with each
  tool.

## Verification tags

- **[doc]** — verified this session against a fetched developer.android.com / source.android.com page.
- **[ref]** — standard platform reference, not re-fetched this session. Treat as high-confidence but unverified.
- **[device]** — cannot be settled from docs; needs the S22 device pass. These are called out explicitly.

---

# Inventory

## A. Device & system state — L0, no permission

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_device_info` | `Build`, `BatteryManager`, `DisplayMetrics` | — | 1 | **Shipped.** |
| `mobile_battery` | `BatteryManager.getIntProperty`, `ACTION_BATTERY_CHANGED` | — | 21 | Level/charging/health/temp. Folded into `device_info` today; split out if the agent polls it. [ref] |
| `mobile_thermal` | `PowerManager.getCurrentThermalStatus` | — | 29 | Useful before handing an agent a long a11y run. [ref] |
| `mobile_power_state` | `PowerManager.isInteractive`, `isPowerSaveMode`, `isDeviceIdleMode` | — | 23 | Tells the agent whether the screen is on before it tries L1. [ref] |
| `mobile_locale` | `Locale.getDefault`, `Configuration` | — | 1 | Already in `device_info`. |
| `mobile_uptime` | `SystemClock.elapsedRealtime` | — | 1 | Cheap liveness signal. [ref] |

**IMEI / serial are not available.** `Build.getSerial()`, `TelephonyManager.getImei()`, `getDeviceId()`
and `getMeid()` require `READ_PRIVILEGED_PHONE_STATE` on Android 10+, which "third-party apps from
Google Play cannot declare". Use the pairing `device_id` as identity instead. [doc: Android 10 privacy changes]

## B. Audio & volume — L0

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_audio_volume_get` | `AudioManager.getStreamVolume` / `getStreamMaxVolume` | — | 1 | **Shipped.** Passive; allowed while disarmed. |
| `mobile_audio_volume_set` | `AudioManager.setStreamVolume` / `adjustStreamVolume` | — | 1 | **Shipped.** Absolute `level` or relative `step`. |
| `mobile_audio_ringer` | `AudioManager.setRingerMode` | `ACCESS_NOTIFICATION_POLICY` **for SILENT only** | 1 | **Shipped.** Special permission — user grants it in Settings; NORMAL/VIBRATE work without it. [ref] |
| `mobile_audio_dnd` | `NotificationManager.setInterruptionFilter` | `ACCESS_NOTIFICATION_POLICY` | 23 | Real DND control, distinct from ringer mode. Gate on `isNotificationPolicyAccessGranted`. [ref] |
| `mobile_audio_route` | `AudioManager.getDevices`, `setCommunicationDevice` | — / `MODIFY_AUDIO_SETTINGS` | 23 / 31 | Read which sink is active (speaker/BT/wired). [ref] |
| `mobile_audio_mic` | `AudioManager.setMicrophoneMute` / `isMicrophoneMute` | `MODIFY_AUDIO_SETTINGS` | 1 | Mic *mute*, not mic capture. Historically this only reliably affects the in-call mic and is widely ignored outside a call — **[device]** before promising it. [ref] |
| `mobile_vibrate` | `Vibrator.vibrate(VibrationEffect)` | `VIBRATE` | 26 | Already declared in the manifest. [ref] |

## C. Media sessions — L0, needs the notification listener

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_media_sessions` | `MediaSessionManager.getActiveSessions` | enabled `NotificationListenerService` (or `MEDIA_CONTENT_CONTROL`, signature-only) | 21 | **Shipped.** Returns `[]` when NLS is unbound. |
| `mobile_media_now_playing` | `MediaController.getMetadata` | same | 21 | **Shipped.** |
| `mobile_media_control` | `MediaController.TransportControls` | same | 21 | **Shipped.** play/pause/toggle/next/prev/stop/seek. |
| `mobile_media_volume` | `MediaController.setVolumeTo` | same | 21 | Per-session volume, distinct from the stream volume. [ref] |
| `mobile_media_queue` | `MediaController.getQueue`, `skipToQueueItem` | same | 21 | Many apps publish an empty queue. [device] |
| `mobile_media_play_search` | `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` | — | 21 | **L2.** "Play <artist> on Spotify" without touching the UI — no session needed, so it works when nothing is playing yet. `EXTRA_MEDIA_FOCUS` + `SearchManager.QUERY`. [doc: common intents] |

`getActiveSessions` is the whole reason NLS matters beyond the notification stream: it is the
supported third-party path to transport control. `MEDIA_CONTENT_CONTROL` is signature-level and
not obtainable by a sideloaded app. **`media_play_search` (L2) is the missing piece today** —
without it the agent can only control an app that is *already* playing.

## D. Apps & packages

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_apps` | `PackageManager.queryIntentActivities(MAIN/LAUNCHER)` | `<queries>` declaration | 1 | **Shipped**, and the manifest already declares the MAIN/LAUNCHER `<queries>` intent, so the list is *not* truncated on Android 11+. Currently a method on the a11y service — **move to L0**, it needs no a11y. [doc: package visibility] |
| `mobile_open_app` | `PackageManager.getLaunchIntentForPackage` + `startActivity` | — | 1 | **Shipped** (L2 in effect). |
| `mobile_app_info` | `PackageManager.getPackageInfo` | `<queries>` / `QUERY_ALL_PACKAGES` | 1 | Version, install time, permissions. [ref] |
| `mobile_app_usage` | `UsageStatsManager.queryUsageStats` | `PACKAGE_USAGE_STATS` (special, Settings grant) | 21 | "What has the user been doing" — strong context for an agent. [ref] |
| `mobile_app_uninstall` | `Intent.ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` | `REQUEST_DELETE_PACKAGES` | 26 | **L2**, user confirms. [ref] |
| `mobile_apps_default` (read) | `Telephony.Sms.getDefaultSmsPackage`, `TelecomManager.getDefaultDialerPackage`, `PackageManager.resolveActivity` | — | 19 | Which app currently *is* the default handler. [ref] |
| `mobile_apps_default` (request) | `RoleManager.isRoleAvailable` / `isRoleHeld` / `createRequestRoleIntent` | — | 29 | **L2.** `RoleManager` only requests a role for *this* app — it cannot query or change another app's role, so "make Chrome the default browser" is not expressible. Splitting read from request is unavoidable. [ref] |

Package visibility: `<queries>` "provides adequate package visibility for most use cases";
`QUERY_ALL_PACKAGES` is Play-policy-restricted. Companion is sideloaded, so `QUERY_ALL_PACKAGES`
is *available* — but the `<queries>` route is already sufficient for the launcher list and keeps
the app Play-eligible. Don't add it without a concrete need. [doc: package visibility]

## E. Notifications — L0 read/act, needs NLS

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_notifications` | `NotificationListenerService` stream | `BIND_NOTIFICATION_LISTENER_SERVICE` | 18 | **Shipped** (event stream + denylist). |
| `mobile_notifications_nls` / `_nls_enable` | `Settings.Secure` read; `ACTION_NOTIFICATION_LISTENER_SETTINGS` | — | 21 | **Shipped.** NLS cannot be granted programmatically — the tool opens the right Settings page. |
| `mobile_notifications_stream` / `_stream_set` | Companion's own sticky store | — | — | **Shipped.** Routes the stream to a gateway+profile. |
| `mobile_notification_action` | `Notification.Action.actionIntent.send()` | NLS | 19 | Fire a notification's own button ("Mark as read", "Snooze"). Much better than an L1 tap. [ref] |
| `mobile_notification_reply` | `Notification.Action` + `RemoteInput` | NLS | 24 | **Reply to a chat message without opening the app.** The highest-value unshipped tool in this table. [ref] |
| `mobile_notification_dismiss` | `NotificationListenerService.cancelNotification` | NLS | 21 | [ref] |
| `mobile_notification_snooze` | `snoozeNotification` | NLS | 26 | [ref] |
| `mobile_notify_post` | `NotificationManager.notify` | `POST_NOTIFICATIONS` | 33 | Agent → human channel on the phone itself. Already declared. [doc: Android 13 behavior changes] |

## F. Clipboard — **read is not an L0 tool**

| Tool | Android API | Permission | Min SDK | Status |
|---|---|---|---|---|
| `mobile_clipboard_set` | `ClipboardManager.setPrimaryClip` | — | 1 | **Shipped.** Writes are not background-restricted. Android 13+ shows the user a copy confirmation with a preview. [doc: copy-paste] |
| `mobile_clipboard_get` | `ClipboardManager.getPrimaryClip` | — | 1 | **Broken by design on Android 10+ — see below.** |

> "Unless your app is the default input method editor (IME) or is the app that currently has
> focus, your app cannot access clipboard data on Android 10 or higher."
> — [doc: Android 10 privacy changes]

Companion reads the clipboard from a background service, so it is neither the focused app nor
the IME: `getPrimaryClip()` returns null on every supported device (`minSdk` here is 31). The
shipped `clipboard_get` therefore always yields empty. Three real options, none free:

1. **Focus-grab activity** — launch a 1px transparent activity, read, finish. Needs a
   background-activity-start exemption; `SYSTEM_ALERT_WINDOW` is already declared, which is one.
   Visible as a flicker, and trips the Android 12+ "pasted from your clipboard" toast. [doc]
2. **Ship an IME** — the default-IME exemption. Large UX commitment; wrong shape for this app.
3. **L1 a11y read** — pull the focused node's text instead of the clipboard. Different semantics
   (reads the field, not the clip) but usually what the agent actually wanted.

**Decision:** keep `clipboard_set` as L0, demote `clipboard_get` to explicitly-unsupported with a
`clipboard_read_unsupported` error that names option 3, and stop advertising it in the skill.
Revisit option 1 only if a task genuinely needs the clip rather than the field.
Note `getPrimaryClipDescription()` does *not* fire the toast, so clip *metadata* (has-text,
mime type, timestamp) remains cheaply readable — but the description is also gated by the same
background restriction, so it is null in practice too. [doc: copy-paste]

## G. SMS / MMS

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_sms_list` | `Telephony.Sms.CONTENT_URI` via `ContentResolver` | `READ_SMS` — **hard-restricted** | 19 | See the allowlist note below. |
| `mobile_sms_send` | `SmsManager.sendTextMessage` | `SEND_SMS` — **hard-restricted** | 4 | Silent send, no UI. [ref] |
| `mobile_sms_compose` | `ACTION_SENDTO` with `smsto:` + `EXTRA_TEXT` | **none** | 1 | **L2.** No restricted permission at all — the messaging app opens pre-filled and the user hits send. [doc: common intents] |
| `mobile_mms_*` | `Telephony.Mms` | `READ_SMS` | 19 | Attachment handling is a project of its own. Defer. |

**The hard-restriction is the crux.** SMS and Call Log are *hard-restricted* permissions:

> "Hard restrictions: Apps can't be granted permissions that aren't allowlisted."
> "Examples of hard-restricted permission types include SMS and Call Log permissions."
> "Allowlisting happens during installation, and when … the installer (such as Google Play Store)
> marks the permission as allowlisted."
> — [doc: AOSP runtime permissions]

So `READ_SMS`/`SEND_SMS` are not merely a runtime prompt: the *installer* must allowlist them, or
the grant is refused no matter what the user taps. Two separate gates, often conflated:

- **Platform gate** — installer allowlisting (above). Whether `adb install` allowlists restricted
  permissions by default could not be settled from the docs. **[device]** — verify on the S22 in
  the same pass, because the whole `sms_list`/`sms_send` design depends on the answer.
- **Play-policy gate** — a Play-distributed app needs the default-SMS-handler role. Companion is
  sideloaded, so this does not apply *today*, but adopting `ROLE_SMS` is a large UX commitment
  (Companion would become the user's messaging app).

**Decision:** ship `mobile_sms_compose` (L2) first — it is unrestricted, needs no role, and covers
"text Amma that I'm running late". Treat `sms_list`/`sms_send` as P2, blocked on the [device] check.

## H. Calls

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_call_dial` | `ACTION_DIAL` with `tel:` | **none** | 1 | **L2.** Opens the dialer pre-filled; user presses call. [doc: common intents] |
| `mobile_call_place` | `ACTION_CALL` with `tel:` | `CALL_PHONE` | 1 | Places the call directly. [doc: common intents] |
| `mobile_call_state` | `TelephonyManager.getCallStateForSubscription` | `READ_PHONE_STATE` | 31 | "Is the user on a call" — worth having before the agent does anything noisy. [ref] |
| `mobile_call_log` | `CallLog.Calls` | `READ_CALL_LOG` — **hard-restricted** | 1 | Same allowlist gate as SMS. [doc: AOSP runtime permissions] |
| `mobile_call_answer` / `_end` | `TelecomManager.acceptRingingCall` / `endCall` | `ANSWER_PHONE_CALLS` / default-dialer role | 26 | `endCall` needs the dialer role on Android 9+. [ref] |

A foreground service that continues a call needs `foregroundServiceType="phoneCall"` +
`FOREGROUND_SERVICE_PHONE_CALL`, and either `MANAGE_OWN_CALLS` or `ROLE_DIALER`. Not needed for
dial/place. [doc: FGS service types]

## I. Contacts — L0

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_contacts_search` | `ContactsContract.CommonDataKinds.Phone` | `READ_CONTACTS` | 1 | Normal runtime permission — **not** restricted. Resolves "call Amma" → a number. [ref] |
| `mobile_contacts_get` | `ContactsContract.Contacts` | `READ_CONTACTS` | 1 | [ref] |
| `mobile_contact_insert` | `ACTION_INSERT` on `Contacts.CONTENT_URI` | **none** | 1 | **L2**, user confirms. [ref] |

Results are no longer ordered by interaction frequency: "Starting in Android 10, the platform
doesn't keep track of contacts affinity information." Rank client-side if it matters.
[doc: Android 10 privacy changes]

## J. Calendar

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_calendar_list` | `CalendarContract.Events` | `READ_CALENDAR` | 14 | Normal runtime permission. [ref] |
| `mobile_calendar_insert` | `ACTION_INSERT` + `Events.CONTENT_URI` | **none** | 14 | **L2** — no permission needed at all; the calendar app opens pre-filled. Prefer this over `WRITE_CALENDAR`. [doc: common intents] |
| `mobile_calendar_write` | `ContentResolver.insert` | `WRITE_CALENDAR` | 14 | Silent write. Only if the L2 confirm step is unacceptable. [ref] |

## K. Alarms & timers — L2, trivially cheap

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_alarm_set` | `AlarmClock.ACTION_SET_ALARM` | `com.android.alarm.permission.SET_ALARM` | 9 | Normal (install-time) permission, no prompt. `EXTRA_SKIP_UI` makes it silent. [doc: common intents] |
| `mobile_timer_set` | `AlarmClock.ACTION_SET_TIMER` | `com.android.alarm.permission.SET_ALARM` | 9 | `EXTRA_LENGTH` in seconds, `EXTRA_SKIP_UI`. [doc: common intents] |
| `mobile_alarms_list` | `AlarmClock.ACTION_SHOW_ALARMS` | `SET_ALARM` | 19 | Shows the UI only; there is no read API for alarms. [ref] |

`EXTRA_SKIP_UI` is what makes this a genuinely silent tool despite being an intent — the clock app
sets the alarm without coming to the foreground. Best cost/benefit in the whole inventory: one
install-time permission, no runtime prompt, no a11y, no NLS.

## L. Location

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_location_get` | `FusedLocationProviderClient.getCurrentLocation` | `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | 21 | Play Services dependency; `LocationManager` is the AOSP fallback. [ref] |
| `mobile_location_last` | `getLastLocation` | same | 21 | Cheap and usually enough. [ref] |

A long-running location FGS needs `foregroundServiceType="location"` +
`FOREGROUND_SERVICE_LOCATION`, location services enabled, one of COARSE/FINE (while-in-use), and
`ACCESS_BACKGROUND_LOCATION` for background use. A one-shot read from the existing
`specialUse` service does **not** need any of that. [doc: FGS service types]

## M. Connectivity

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_network_state` | `ConnectivityManager.getNetworkCapabilities` | `ACCESS_NETWORK_STATE` | 21 | Transport, metered, validated. [ref] |
| `mobile_wifi_state` | `WifiManager.isWifiEnabled` | `ACCESS_WIFI_STATE` | 1 | [ref] |
| `mobile_wifi_toggle` | — | — | — | **Not available.** `setWifiEnabled` is a no-op for third-party apps on Android 10+; the supported path is `Settings.Panel.ACTION_WIFI` (**L2**). [ref] |
| `mobile_wifi_scan` | `WifiManager.getScanResults` | `ACCESS_FINE_LOCATION` **or** `NEARBY_WIFI_DEVICES` | 1 / 33 | Android 10 added the location requirement; Android 13 added `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"` as the non-location route. [doc: Android 10 + 13] |
| `mobile_bluetooth_state` | `BluetoothAdapter` | `BLUETOOTH_CONNECT` | 31 | [ref] |
| `mobile_bluetooth_toggle` | — | — | — | **Not available.** `BluetoothAdapter.enable()`/`disable()` "are deprecated and always return `false`" for non-Device-Owner apps. Use the Settings panel (**L2**). [doc: Android 13 behavior changes] |
| `mobile_airplane_toggle` | — | — | — | **Not available** to third-party apps. Settings intent only. [ref] |

Toggling radios is the clearest "L0 does not exist" cluster in the inventory — the platform closed
these deliberately. Don't build a11y fallbacks that poke Settings toggles either; surface the
Settings panel and tell the agent a human must finish.

## N. Camera & torch

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_torch` | `CameraManager.setTorchMode` | **none** (no `CAMERA` needed) | 23 | Genuine L0, no permission, works from the background. [doc: search — Android 6.0 APIs] |
| `mobile_torch_strength` | `turnOnTorchWithStrengthLevel` | none | 33 | [ref] |
| `mobile_photo_capture` | `MediaStore.ACTION_IMAGE_CAPTURE` | **none** | 1 | **L2** — the camera app handles the permission and the shutter. [doc: common intents] |
| `mobile_camera_list` | `CameraManager.getCameraIdList` + `getCameraCharacteristics` | **none** to enumerate | 21 | Lens facing, flash availability, resolutions. No `CAMERA` grant needed until you *open* a device. [ref] |
| `mobile_video_capture` | `MediaStore.ACTION_VIDEO_CAPTURE` | **none** | 3 | **L2**, same shape as the photo intent. [doc: common intents] |
| `mobile_photo_capture_silent` | CameraX / `camera2` | `CAMERA` + FGS `camera` | 21 | **Blocked while idle.** The `camera` FGS type carries a while-in-use restriction: it "cannot start from background or `BOOT_COMPLETED` receiver". An agent asking for a photo on an idle phone will fail. [doc: FGS service types] |

The while-in-use restriction is the important finding here: a remote agent **cannot** silently
take a photo on an idle phone. Only the L2 route (camera app, human present) is real. Same shape
for the microphone — see the voice section.

## O. Screen capture

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_screenshot` | `AccessibilityService.takeScreenshot(int, Executor, TakeScreenshotCallback)` | a11y (`canTakeScreenshot`) | 30 | **Shipped**, L1 — needs a11y bound but no MediaProjection consent. Signature confirmed in the API 30 diff. [doc: API diff 30] |
| `mobile_screen_record` | `MediaProjection` | FGS `mediaProjection` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 21 | Per-session user consent via `createScreenCaptureIntent()` before the service starts. Heavy; defer. [doc: FGS service types] |
| `mobile_screen_audio` | `AudioPlaybackCaptureConfiguration` + `AudioRecord` | `RECORD_AUDIO` **and** MediaProjection consent | 29 | Captures other apps' playback. Heavily fenced — see below. [doc: playback capture] |

`takeScreenshot` via a11y is strictly better than MediaProjection for the agent's purposes: no
per-session consent dialog. The a11y grant is already the price of the L1 lane.

`screen.audio` is narrower than it sounds. The *playing* app must use `USAGE_MEDIA`, `USAGE_GAME`
or `USAGE_UNKNOWN` and allow `ALLOW_CAPTURE_BY_ALL`, and "the most restrictive capture policy is
always applied". Two consequences: apps that ship
`android:allowAudioPlaybackCapture="false"` are silently unrecordable, and
**`USAGE_VOICE_COMMUNICATION` cannot be captured at all** — so recording a phone or VoIP call is
not possible by this route. [doc: playback capture]

## P. Voice — TTS is L0, STT is foreground-bound

The app already has `TextToSpeechEngine`, `VoiceInputManager` and `VoiceStreamEngine`, plus
`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` and a `microphone` FGS. These are wired to the
wake-word path, not exposed as tools.

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_speak` | `TextToSpeech.speak` | **none** | 4 | Genuine L0 — makes the phone talk from a background service. Engine already exists; only the tool wrapper is missing. [ref] |
| `mobile_speak_stop` | `TextToSpeech.stop` | none | 4 | [ref] |
| `mobile_voices` | `TextToSpeech.getVoices` | none | 21 | [ref] |
| `mobile_listen` | `SpeechRecognizer` + `RecognitionListener` | `RECORD_AUDIO` | 8 | Must be driven from the main thread. `VoiceInputManager` already does this. [ref] |
| `mobile_listen_support` | `SpeechRecognizer.checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` | — | 33 | Added in API 33 — confirmed in the API diff, alongside `triggerModelDownload(Intent)` and `ERROR_CANNOT_CHECK_SUPPORT`. [doc: API diff 33] |
| `mobile_listen_ondevice` | `createOnDeviceSpeechRecognizer` | `RECORD_AUDIO` | 31 | **Not** an API 33 addition — the API 33 diff does not list it, so it predates 33. Pin the exact level before relying on it. [device] |
| `mobile_voice_record` | `MediaRecorder` / `AudioRecord` | `RECORD_AUDIO` + FGS `microphone` | 1 | Raw capture to a file, distinct from STT. Same while-in-use ceiling as STT — cannot start on an idle phone. [doc: FGS service types] |

The manifest already declares the `android.speech.RecognitionService` `<queries>` intent, so
recognizer discovery is correct on Android 11+.

**`mobile_speak` is the sleeper win**: no permission, no a11y, works while idle, and it gives the
agent a voice channel to a human standing near the phone. Ship it in P1.

**STT has the same while-in-use ceiling as the camera**: the `microphone` FGS type "cannot start
from background or `BOOT_COMPLETED` receiver". An agent cannot open the mic on an idle phone —
the wake-word path works because the service is already running with the mic held.
[doc: FGS service types]

## Q. Files & media

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_media_list` | `MediaStore` query | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | 33 | `READ_EXTERNAL_STORAGE` no longer works on Android 13+; existing grants auto-upgrade. [doc: Android 13 behavior changes] |
| `mobile_media_pull` | `ContentResolver.openInputStream` | same | 1 | Reuses the existing media GET route. [ref] |
| `mobile_media_push` | `MediaStore.createWriteRequest` / SAF | — | 30 | [ref] |
| `mobile_file_pick` | `ACTION_OPEN_DOCUMENT` | **none** | 19 | **L2**, returns a durable `content:` URI. [doc: common intents] |
| `mobile_share` | `ACTION_SEND` | none | 1 | **L2.** [doc: common intents] |

On Android 14+, partial photo access is expressed by `READ_MEDIA_VISUAL_USER_SELECTED`; without
adopting it the app runs in compatibility mode. Prefer the system photo picker where possible.
[doc: Android 14 behavior changes]

## R. Sensors

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_sensors_list` | `SensorManager.getSensorList` | — | 3 | [ref] |
| `mobile_sensor_read` | `SensorManager.registerListener` | — | 3 | One-shot read of accelerometer/light/proximity. [ref] |
| `mobile_steps` | `Sensor.TYPE_STEP_COUNTER` | `ACTIVITY_RECOGNITION` | 29 | [ref] |
| `mobile_heart_rate` | `Sensor.TYPE_HEART_RATE` | `BODY_SENSORS` (+ `BODY_SENSORS_BACKGROUND`, **hard-restricted**) | 20 / 33 | Background body sensors need installer allowlisting, same gate as SMS. [doc: Android 13 behavior changes] |

## S. Display & settings

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_brightness_get` | `Settings.System.SCREEN_BRIGHTNESS` | — | 1 | Read is free. [ref] |
| `mobile_brightness_set` | `Settings.System.putInt` | `WRITE_SETTINGS` (special, Settings grant) | 1 | [ref] |
| `mobile_rotation` | `Settings.System.ACCELEROMETER_ROTATION` | `WRITE_SETTINGS` | 1 | [ref] |
| `mobile_settings_open` | `Settings.ACTION_*` / `Settings.Panel.ACTION_*` | **none** | 1 | **L2.** The honest answer for everything the platform closed off (Wi-Fi, Bluetooth, airplane mode, NLS, DND access). [ref] |
| `mobile_lock` | `DevicePolicyManager.lockNow` | device-admin receiver | 8 | Requires the user to activate Companion as a device admin. [ref] |

## T. Input — L1 a11y, the fallback lane

`device.snapshot` / `click` / `type` / `swipe` / `scroll` / `press` / `wait`. **Shipped.**
Unchanged by this plan; these stay the universal fallback. Two doc notes that affect them:

- On Android 14+, implicit intents are "only delivered to exported components", so any
  `open_app`-style handoff must use an explicit intent with the package set. The existing
  `getLaunchIntentForPackage` path is already explicit. [doc: Android 14 behavior changes]
- Context-registered receivers on Android 14+ must pass `RECEIVER_EXPORTED` or
  `RECEIVER_NOT_EXPORTED`. [doc: Android 14 behavior changes]

---

## U. Identity, IME, activity & scheduling — mostly closed

The four remaining names from the proposed list. Three of them the platform has largely shut,
which is the useful answer.

| Tool | Android API | Permission | Min SDK | Notes |
|---|---|---|---|---|
| `mobile_ime` (read) | `InputMethodManager.getEnabledInputMethodList`; `Settings.Secure.DEFAULT_INPUT_METHOD` | — | 3 | Listing IMEs and reading the current one is fine. [ref] |
| `mobile_ime` (switch) | — | `WRITE_SECURE_SETTINGS` (**signature**) | — | **Not available.** Switching the IME needs a signature permission "not for use by third-party apps". The only third-party path is `showInputMethodPicker()` (**L2** — puts a chooser on screen for the user). [ref] |
| `mobile_accounts` | `AccountManager.getAccounts` | `GET_ACCOUNTS` | 5 | **Effectively closed since Android 8.** `GET_ACCOUNTS` is no longer sufficient: apps get no account access "unless the authenticator owns the accounts or the user grants that access". The supported path is `newChooseAccountIntent()` — the user picks one account, per request (**L2**). Do not model this as "list the user's accounts". [doc: Android 8 behavior changes] |
| `mobile_activity` (recognition) | `ActivityRecognitionClient` / `Sensor.TYPE_STEP_COUNTER` | `ACTIVITY_RECOGNITION` | 29 | Walking/driving/still inference. [ref] |
| `mobile_activity` (foreground app) | already covered | — | — | If "activity" meant *what is on screen*, that is `device.snapshot`'s `app` field and the lane's `foregroundApp` — no new tool. **Name is ambiguous; pick one.** |
| `mobile_schedule` | `AlarmManager.setExact` / `setExactAndAllowWhileIdle` | see below | 1 | Inexact alarms (`set`, `setWindow`) need no permission. Exact ones are fenced. [doc: Android 14 exact alarms] |

`schedule` is the one worth reading carefully before designing it. `SCHEDULE_EXACT_ALARM` is
"no longer being pre-granted to most newly installed apps targeting Android 13 and higher (will
be set to denied by default)" on Android 14+. `USE_EXACT_ALARM` is a normal, install-granted
permission but is Play-policy-restricted to calendar and alarm-clock apps. Three practical routes,
in order of preference:

1. **Inexact alarms** — no permission, and almost always what an agent reminder actually needs.
2. **Power allowlist** — apps on the battery-optimisation allowlist may call `setExact()`
   regardless. Companion arguably wants that exemption anyway to keep its lane alive.
3. **`canScheduleExactAlarms()` + `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`** — check, and send the
   user to Settings if denied. Never assume the grant.

Note this is distinct from §K: `device.alarm` (`ACTION_SET_ALARM`) asks the *clock app* to set a
user-visible alarm and needs only the install-time `SET_ALARM` permission, whereas `device.schedule`
is Companion scheduling its own callback. Both are worth having; they are not substitutes.

# Phasing

## P0 — shipped (2026-09-08)

Audio volume get/set, ringer, media sessions/now-playing/control, clipboard set, device info,
NLS status/enable, notification stream get/set. Lane policy split into `L0_NO_A11Y` (skips the
a11y and protected-package gates) and `L0_PASSIVE` (allowed while disarmed), mirrored in
`hermes-plugin/broker.py`.

**Two corrections fell out of this cross-check, both now fixed:**

1. **`clipboard_get` cannot read from the background on Android 10+.** It returned `""`, which an
   agent would read as "the clipboard is empty". `DirectToolbox.clipboardGet` now returns a
   `ClipRead` variant instead of `String?`, disambiguating *blocked* from *empty* by process
   importance (a resumed activity reports `IMPORTANCE_FOREGROUND`; a bare FGS reports
   `IMPORTANCE_FOREGROUND_SERVICE`). The lane maps that to `clipboard_read_blocked` /
   `clipboard_not_text` / `{"text": ""}`, and the skill now tells the agent to read the focused
   node over L1 instead. `clipboard_set` is unaffected — writes are not restricted.
2. **`DirectToolboxTestReceiver` was `exported="true"` in `app/src/main/`.** It shipped in release
   builds and let *any* installed app drive the L0 lane — including
   `device.notifications.stream.set`, which retargets the notification stream, so a malicious app
   could have redirected the user's notifications to a gateway of its own. Moved to
   `app/src/debug/` (source *and* manifest, so release omits it — verified in the merged
   manifests) and gated on `android.permission.DUMP`, which the adb shell holds and third-party
   apps cannot obtain. Its KDoc had claimed both protections while having neither.

## P1 — next, highest value per unit of work

| Tool | Why first |
|---|---|
| `mobile_speak` | No permission, works while idle, gives the agent a voice. Engine already exists. |
| `mobile_notification_reply` | Reply to a chat without opening the app. NLS is already granted. |
| `mobile_notification_action` / `_dismiss` | Same grant, immediate value, replaces fragile L1 taps. |
| `mobile_alarm_set` / `mobile_timer_set` | One install-time permission, `EXTRA_SKIP_UI` makes them silent. |
| `mobile_media_play_search` | Start playback when nothing is playing yet — today's blind spot. |
| `mobile_apps` → L0 | Drop the needless a11y coupling. |
| `mobile_torch` | No permission, API 23, background-safe. |

## P2

`mobile_contacts_search` (unlocks "call/text <name>"), `mobile_sms_compose` (L2, unrestricted),
`mobile_call_dial`, `mobile_calendar_list` / `_insert`, `mobile_location_last`,
`mobile_battery` / `_power_state` / `_thermal`, `mobile_app_usage`, `mobile_audio_dnd`,
`mobile_settings_open`.

## P3 / blocked

- `sms_list` / `sms_send` — blocked on the **[device]** installer-allowlist check.
- `call_log` — same gate.
- Silent camera / mic capture — blocked by the FGS while-in-use restriction; L2 only.
- `MediaProjection` screen recording — per-session consent UX.
- Radio toggles (Wi-Fi / BT / airplane) — closed by the platform; Settings panel only.
- `clipboard_get` focus-grab activity — only if a task truly needs the clip.

# Device pass (S22) — status 2026-09-08

Device: SM-S901E, **Android 16, `sdk_int` 36** (note: `compileSdk`/`targetSdk` are 35).
Deployed: debug APK sideloaded; plugin 0.3.0 on lab and hub-11, both relays confirmed restarted
after the file copy.

**Verified on device**

- **L0 runs with a11y unbound.** With `enabled_accessibility_services` empty, `device.device.info`,
  `device.audio.volume.get`, `device.notifications.nls`, `device.media.sessions` and
  `device.clipboard.get` all answered via the debug receiver, `device.info` self-reporting
  `a11y_bound=false`. This is the core claim of the L0 lane and it holds.
- **Q3 answered: the clipboard restriction is real on One UI / Android 16.** Backgrounded
  (launcher on top) → `clipboard_read_blocked`. Foregrounded → the actual text came back
  (`clipboard_set` "hermes-L0-roundtrip" → `clipboard_get` returned it verbatim). The pre-fix code
  returned `{"text": ""}` in the blocked case, which an agent would have read as "clipboard empty".
- **Passive vs destructive gate.** After an idle auto-disarm, `device.media.sessions` still
  answered while `device.open_app` was refused `disarmed` — the `L0_PASSIVE` split working live.
- **Media lane end to end.** Spotify session discovered, `now_playing` returned
  title/artist/album/duration, and `media.control play` flipped the session `paused → playing`
  (muted for the test, restored after).
- **Debug-receiver gate.** `android.permission.DUMP` is `prot=signature|privileged|development` on
  this device and the shell holds it, so adb can drive the smoke receiver and third-party apps
  cannot. (The negative case was not empirically proven — that needs a second unprivileged app.)

**Notification actions verified 2026-09-09** (§E, plugin 0.4.0 on lab + hub-11)

- Button enumeration against a real app: YouTube's media notification returned
  `Previous / Play / Next / Stop`, all `accepts_text: false`.
- `action` genuinely delivers: firing index 1 ("Play") flipped the YouTube media session
  `paused → playing` (muted for the test, restored after).
- `snooze` round-trip: readable → `snoozed_ms: 5000` → `unknown_notification` while snoozed →
  readable again after expiry, which also proves `forgetKey` on removal and that the hashed key
  is stable across the platform's re-post.
- `dismiss`: readable → `dismissed` → gone, and *stays* gone (the contrast with snooze is the point).
- Error paths all correct on device: `no_such_action` for a text action reached via `action`,
  for a missing index, and for a notification with no reply action; `unknown_notification` for a
  key that was never streamed.

**`reply` is implemented and unit-covered but NOT device-verified.** There is no way to
synthesise a notification carrying a `RemoteInput` action from adb (`cmd notification post` has
no action flag), and the only live targets are the user's real conversations — firing it would
send a real message to a real person. Its refusal paths are device-proven; the RemoteInput fill
path needs a deliberate test on a conversation the user picks.

**Per-app policy verified on device 2026-09-09** (plugin 0.5.0, lab + S22). Same package,
behaviour changed by policy alone — no app or phone-side change:

| Mode | Reached `device.notifications`? | Woke the profile? |
|---|---|---|
| `off` | no — ring count stayed 0 | no |
| `silent` | yes — ring count 1 | **no**, 0 wake lines in the journal |
| `respond` | yes — ring count 2 | yes — `agent wake scheduled … pkg=com.android.shell` + telegram nudge |

Test rules were cleared afterwards and the stream left off; the policy file is empty, so every
app is back to the `respond` default.

**Still open**

1. ~~Does `adb install` allowlist hard-restricted permissions?~~ **Answered 2026-09-09: yes.**
   With `READ_SMS`/`SEND_SMS`/`READ_CALL_LOG` declared, `adb shell pm grant` succeeded for all
   three and `dumpsys` reports `granted=true, flags=[… RESTRICTION_INSTALLER_EXEMPT]` for the
   primary user — exactly the installer-allowlist mechanism the AOSP doc describes. So §G SMS
   and `call_log` **are buildable** on a sideloaded Companion, with no default-SMS role. The Play
   default-handler policy remains a separate gate that only applies if this app is ever
   distributed there.
2. Exact min SDK of `createOnDeviceSpeechRecognizer`.
3. Which media apps publish a usable `getQueue()` — Spotify's queue was not inspected.
4. Whether `EXTRA_SKIP_UI` is honoured by the Samsung clock app.
5. hub-11 has **no paired device**, so its L0 lane is unproven end to end; the plugin itself was
   verified there (loads on Python 3.13.5, 30 schemas / 13 L0 tools / 30 handlers, set invariants
   hold, passive dispatch OK against a mock device).

**Design gap found (code-level, not device-verified):** `DeviceLanePolicy.reject()` refuses
`device.arm` with `a11y_unavailable` when a11y is unbound, and every destructive L0 action requires
`ARMED`. So on a phone where the user never granted accessibility, **no L0 write can ever run** —
the a11y dependency the L0 lane exists to remove is reintroduced by the arming gate. Decide in
A29.2 whether arming should be permitted for an L0-only session.

# Sources

- [Privacy changes in Android 10](https://developer.android.com/about/versions/10/privacy/changes) — clipboard background restriction, contacts affinity, device identifiers, background activity starts
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — per-type permissions and while-in-use restrictions
- [Package visibility filtering](https://developer.android.com/about/versions/11/privacy/package-visibility) and [Package visibility](https://developer.android.com/training/package-visibility)
- [Runtime permissions (AOSP)](https://source.android.com/docs/core/permissions/runtime_perms) — hard- vs soft-restricted, installer allowlisting
- [Permissions used only in default handlers](https://developer.android.com/guide/topics/permissions/default-handlers) — SMS / call-log role requirement for Play
- [Behavior changes: Android 13](https://developer.android.com/about/versions/13/behavior-changes-13) — `READ_MEDIA_*`, `NEARBY_WIFI_DEVICES`, `BODY_SENSORS_BACKGROUND`, Bluetooth enable/disable deprecation
- [Behavior changes: Android 14](https://developer.android.com/about/versions/14/behavior-changes-14) — FGS type enforcement, implicit intents, `RECEIVER_EXPORTED`, `READ_MEDIA_VISUAL_USER_SELECTED`
- [Common intents](https://developer.android.com/guide/components/intents-common) — alarm, timer, calendar, camera, SMS, dial, maps, documents
- [Copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) — Android 12 toast, Android 13 copy confirmation
- [API diff 30: AccessibilityService](https://developer.android.com/sdk/api_diff/30/changes/android.accessibilityservice.AccessibilityService) — `takeScreenshot` signature
- [API diff 33: SpeechRecognizer](https://developer.android.com/sdk/api_diff/33/changes/android.speech.SpeechRecognizer) — `checkRecognitionSupport`, `triggerModelDownload`
- [Schedule exact alarms are denied by default](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms) — `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`, `canScheduleExactAlarms()`, power-allowlist exemption
- [Playback capture](https://developer.android.com/media/platform/av-capture) — `AudioPlaybackCaptureConfiguration`, usage/policy fencing, `USAGE_VOICE_COMMUNICATION` excluded
- [Android 8.0 behavior changes](https://developer.android.com/about/versions/oreo/android-8.0-changes) — account access no longer granted by `GET_ACCOUNTS`
- [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager) — `isRoleHeld` / `createRequestRoleIntent` (API 29, self only)
