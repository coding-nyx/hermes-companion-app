# Conversation draft recovery validation

The installed Companion APK now recovers each chat's rich-text draft and staged attachments when that thread is reopened. Drafts are isolated by dashboard URL including proxy path, profile and session. The restored composer layout and formatting controls remain intact.

## Implementation

- Versioned encrypted draft preferences use Android Keystore and a separate file. The transcript/outbox Room database stays at version 7, preserving compatibility with the preceding APK.
- Text edits, dictation and attachment updates are observed. Navigation, backgrounding and host/profile changes checkpoint the current composer before its state is cleared. Closing chat also clears visible attachments so they cannot leak into another conversation.
- First-send promotion transfers the local draft to its confirmed host session. Once the input enters the durable outbox, recovery clears submitted text and files while preserving later edits and newly added attachments. Recovering a draft never submits a prompt.
- New preserves the current composer until the host confirms creation. Rejected creation leaves its draft intact and clears the busy state for an explicit retry. Leaving during creation prevents its result from opening over another conversation.
- Newly staged files use private persistent storage instead of cache storage. Missing recovered files remain identifiable and show a remove/pick-again action. Corrupt draft records remain stored and surface a recovery error.

Room drafts are outside this increment. Room history and post callbacks still need host/room/selection-generation guards. Durable reconciliation of unknown session-creation outcomes remains pending.

## Validation

| Gate | Result |
| --- | --- |
| Full automated suites | 485 passed; zero failed or skipped. App 131, data-remote 84, data-local 26, domain 220, core-model 1, chat 15, threads 8. |
| Native contract integration | Rejected creation and explicit retry, recovered local first-send, delayed promotion after navigation, and preservation of newer local text passed. Existing delivery/history contract tests passed. |
| Draft regressions | Rapid switching before the state collector runs, attachment ownership, host/profile/proxy isolation, local draft recovery, manager recreation, room isolation, missing files and corrupt records passed. |
| Release | Lint: zero errors, 48 existing warnings and two informational findings. Minified release build passed; local release signing still uses the existing debug fallback. |
| S22 storage seed | Passed in 0.237 seconds. Two synthetic rich-text drafts and an attachment were saved; raw on-disk preferences contained neither the draft text nor profile identifier. |
| S22 fresh-process recovery | Passed in 0.079 seconds after ADB force-stop between separate instrumentation runs. Each thread recovered its own text/files; another host recovered none. |
| APK install | Installed successfully; device APK SHA-256 matches the saved build. |

Device tests used synthetic state, encrypted fixture preferences and a private fixture file. They submitted no host prompt, opened no operator UI and used no microphone. Fixture content was removed afterward. Keyboard/picker/navigation journeys on real host conversations remain a separate device acceptance gate.

SharedPreferences publishes changes immediately and schedules disk writes. The process-recovery test explicitly flushed those writes before force-stop; it does not establish preservation of a last keystroke during abrupt termination while its disk write is still pending.

## Installed checkpoint and rollback

- Version: 0.1.0, version code 1, debug APK.
- APK: `/private/tmp/companion-draft-recovery-20261014/verified.apk`
- SHA-256: `0097ea8e7627ddbccef482eb085e829b65f2c0e36690241c17b18418ca196e38`
- Rollback: `/private/tmp/companion-draft-recovery-20261014/rollback.apk`
- Rollback SHA-256: `fbd09da66c99e4a55d5c53e9eaef9eff0e60731dd53f5bda736d165403c20148`
- Logs, module XML results and sanitized validation metadata: `/private/tmp/companion-draft-recovery-20261014/`

Rollback command for the current ADB address: `adb -s 100.105.213.54:41303 install -r /private/tmp/companion-draft-recovery-20261014/rollback.apk`. The prior APK ignores the new encrypted draft file; it cannot display these recovered drafts. Persistent attachment files remain available when this APK is reinstalled.

The operator connection remains `http://100.85.151.99:9120`, profile `ash`. The instrumentation package was removed and the normal app relaunched. No plugin source, mobile control contract or host configuration changed; no plugin deployment was required. Raj remains excluded.

## Remaining sequence

Next conversation work: guard room callbacks, exercise uncertain delivery and session creation across reconnect/process recreation, then persist compression claims and reconcile late host outcomes. Native voice stays disabled pending provider acceptance; the last recorded lab Edge check failed with upstream 403. Spoken recognition/conversation tests remain deferred. Streaming implementation exists, but native-provider acceptance is pending.

The approved authentication, realtime, callback notifications, Telecom, native host migration, plugin reduction and broader navigation/readability phases remain on the [parity backlog](2026-10-13-current-conduit-parity-backlog.md).
