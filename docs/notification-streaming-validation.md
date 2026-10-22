# Notification streaming acceptance checklist

The stream shares only newly observed notifications from explicitly selected apps to one explicitly selected paired gateway/profile. It is off by default. Call-category notifications are best-effort notification events, not telephony state, call audio, or call logs.

## Automated coverage

- Explicit allowlist, content minimization and conservative OTP suppression.
- Legacy preferences fail closed; no current-host/profile fallback; exact origin path routing.
- Durable-before-send ordering, ACK-only removal, retry timing and scope identity.
- Encrypted atomic outbox recreation, replacement, corruption rejection and size bound.
- Pre-outbox overflow accounting and retired notification action handles.
- Passive/ACK receiver capability negotiation, strict acknowledgements and late-ACK saturation.
- Receiver deduplication through the phone's maximum retention, rejection and passive behavior.
- Searchable app selection, removal, empty states and large-font layout.

## Physical-device checks still required

These checks need the Android app and compatible gateway receiver, a paired test device, and explicit permission/consent from its owner. Automated tests do not prove OEM background reliability.

1. Start with sharing off. Grant Notification access manually, select only a test app, choose an explicit paired gateway/profile, leave metadata mode selected and confirm sharing. Generate a notification. Verify one passive inbox event and acknowledgement, with no title/body and no agent wake or Telegram forwarding.
2. Generate call and missed-call notifications from the selected app. Verify classification. Dismiss one and verify no fabricated call-ended event. Disable Call notifications and re-confirm sharing; repeat.
3. Test title/full modes only with synthetic content. Verify OTP-like messages and secret-visibility notifications remain metadata-only. Test Android's own redaction without attempting to bypass it.
4. Disconnect network after send but before ACK, then reconnect. Verify retry keeps its delivery ID and receiver deduplication prevents a second inbox insertion. Restart the app process after capture and durable save; verify unexpired events retry to the same route.
5. Toggle Wi-Fi/mobile data, lock the screen, background the app, and navigate away from chat. Check queued/acknowledged/expired/overflow/rejected counters and visible errors. Repeat under battery saver and the device manufacturer's background restrictions.
6. Remove an app, change route/profile/content/retention, or stop sharing while offline. Verify sharing pauses and pending events are discarded, never replayed to the new route. Re-enable only after fresh confirmation.
7. Revoke Android Notification access. Verify sharing pauses and pending events are discarded. A transient listener service disconnect with the permission still granted should not discard already captured events.
8. Generate more than 128 pending events and more than 64 rapid capture callbacks. Verify overflow is visible. Exceed the chosen 2/15/60-minute retention and verify expiry is visible.
9. Connect to an older receiver without both advertised capabilities. Verify stable paused/update-required status, with no repeated reconnect loop or silently continuing capture.
10. Test TalkBack, large fonts and narrow screens: app names, checkbox state, search, selected unavailable packages, gateway/profile selection, privacy mode and confirmation should remain reachable and understandable.

## Delivery limits

The phone outbox is encrypted, bounded to 128 events, and retained for the selected interval (15 minutes by default). Oldest overflow and expired events are intentionally discarded and counted during the current app session. Events not observed while Android has stopped or disconnected the notification listener cannot be reconstructed; the app does not scrape notification history. An event can also be lost if the process dies before its capture callback reaches durable storage.

An acknowledgement means the receiver accepted the event into its bounded in-memory inbox, not durable exactly-once delivery or that an agent read it. Receiver restart loses its inbox/deduplication state. The receiver deduplication horizon is 65 minutes, longer than the phone's maximum 60-minute retention. Capacity rejection is visible and retried rather than evicting live deduplication identifiers.

No app can promise unrestricted operation through force-stop, OS/OEM background restrictions, permission revocation, or device/network outages. Encrypted expired files are physically pruned when the app next runs; TTL does not wake a stopped process to erase storage.

Android 15+ also limits this app's `dataSync` foreground service to six hours in a 24-hour background window. The timeout handler stops promptly and pauses sharing rather than restarting around the OS budget. Opening the app resets Android's budget; the user must confirm sharing again. See [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
