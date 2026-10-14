# Room delivery recovery and navigation — October 15, 2026

This increment preserves the restored rich-text composer and keeps paid realtime-provider setup disabled. Raj remains excluded. Existing plugin responsibilities and routes remain installed; these changes require no plugin deployment.

## Delivery changes

Room sends now enter a separate encrypted, versioned delivery journal before network submission. Pending or uncertain sends survive app/manager recreation and are not restored as a second keyboard send. Identical unconfirmed text has atomic admission protection. A lost response, malformed sequence or server failure produces an UNCERTAIN row with CHECK, rather than silently returning the message to the composer. Explicit rejection permits deliberate retry, with a newer draft preserved.

CHECK reads host history without resubmission. It reconciles a positive sequence receipt or exactly one new matching user row when a known baseline and one matching pending record establish evidence. Older identical rows, missing baselines and ambiguous matches do not prove delivery. The existing room protocol has no reliable client identifier; stronger server-side correlation/idempotency remains necessary for concurrent identical submissions from different devices.

An echo adopts one pending row, not every row with the same text. Initial history cannot duplicate an in-flight local row. Late errors cannot downgrade persisted receipt evidence. Async updates retain host/profile/room/navigation ownership. Corrupt delivery storage does not hide a separately readable draft. Successful receipt records are removed after adoption; unconfirmed records are never automatically replayed or pruned.

The journal is separate from existing transcript and recovery databases, so earlier APKs do not need a database downgrade. Writes run off the UI thread and are durable before submission. Missing legacy timestamps remain unknown; new timestamps are explicit and serialization preserves them. A full-suite failure exposed a dynamic-default timestamp omission, which was fixed before the final package gate.

## Navigation changes and device journey

Chats returns to the thread list when leaving a conversation. Connection/profile/tab changes no longer open implicit blank composers. An explicit Continue unsent draft entry restores the local draft for its host/profile. New remains an explicit action. The S22 journey used the production shell and real draft/session manager with an isolated HTTP fixture; it verified rich drafts across destinations, local draft recovery, New intent, visible formatting/voice controls and keyboard/back behavior. Only read requests reached the fixture; no agent prompt or microphone action occurred.

The first keyboard test used the Activity back dispatcher and bypassed normal IME handling. It was corrected to send Android Back key events. A subsequent run waited out a relocked screen; the final normal-unlock run passed. UI interactions required an armed test owner and foreground focus. No secure lock-screen bypass was used.

## Validation

- 521 unit tests passed; release lint passed with zero errors, 54 warnings and two informational findings.
- Minified release build passed.
- Final S22 recovery run: 16/16 passed, including encrypted journal recreation, owner isolation, lost acknowledgments, history-only checking, invalid receipts, duplicate prevention and safe explicit retry.
- S22 navigation journey: 1/1 passed in 6.866 seconds.
- Rich-text composer was preserved; real spoken tests remain deferred.

Installed debug APK: versionCode 1 / versionName 0.1.0, SHA-256 `f8ee310d997f63c72002f73280d50382a5664704dd2955b55849b6fd794565c3`. Immutable artifact: `/private/tmp/companion-remaining-20261014/room-recovery-final.apk`. The preceding verified transport APK remains at `webrtc-bounded.apk` in that directory (SHA-256 `22d168a5d4dcd4ee023d564a37ed3f63e8ab12583e219dd5f919327099dd04cb`). Sideload the preceding file with `adb install -r` for rollback; the new journal is retained but ignored by that APK. Pending room records must be checked on the new APK before deliberately resubmitting from an older one.

Evidence: `room-recovery-final-milestone.log`, `room-recovery-final-device.log` and `navigation-flow-final-device.log` in the same task directory. Operator host/profile preferences were not changed.

## Remaining work

Unknown native thread-creation outcomes and stronger room correlation remain. Native creation compatibility must not treat a general business error as proof that a request did not run. Durable compression/late outcomes, live context, approval recovery, richer composer/rendering, native authentication, callbacks/Telecom, host migration and plugin reduction remain on the parity backlog. The tested navigation subset does not complete deep-link ownership, agent-detail return paths, accessibility or tablet validation.
