# Companion device validation and native integration increment

Validated on 13 October 2026 on the connected Samsung S22 (SM-S901E), Android 16, using wireless ADB. The final debug APK was installed with `adb install -r`, preserving existing app data. The last installed build includes the completion-state and late-history fixes described below.

The installed package matches the final APK byte for byte. SHA-256: `fd2c0268d191d431eb86298ad4784978ad9833324ec4e921934902beaa2501f5`.

## Test environments

The saved operator connection was `http://100.85.151.99:9120`, profile `knight`. Basic thread creation, sending, rename, and pin were exercised through that existing relay.

Direct native tests used a temporary SSH forward to lab's loopback dashboard on port 9119 plus `adb reverse`. The phone connected to `http://127.0.0.1:19119`. Native chat requests bypassed the Companion relay, and that endpoint had no Companion health route (the native dashboard returned its HTML SPA for the unknown path). Existing paired phone lanes were retained independently. Lab reported Hermes 0.21.5; its plugin was not changed, disabled, or redeployed.

Synthetic prompts requested fixed `ADB_*_1013` replies and no tools. Host transcript reads verified exact prompt counts. Existing user threads were not used for test prompts.

## Confirmed device results

| Scenario | Evidence / outcome |
| --- | --- |
| APK update and cold launch | Installation succeeds; saved host/profile and existing sessions survive. |
| NEW | Opens a real host session before its first prompt. |
| Live send | Host receives the synthetic prompt; UI shows `sent`, working/interrupt state, then the final answer. |
| Double Send tap | One matching thread and one user prompt in the host transcript; one final answer; UI returns to idle. |
| Thread history and re-entry | Existing synthetic messages load after leaving/reopening the conversation and after an APK/process restart. |
| Rename | New title appears in the header and native host session record. |
| Pin | Native host stores `pinned: true`; the thread appears under PINNED in the rail. |
| Branch | Copies the source's four-message history into a distinct session, leaving the source intact. |
| Native connection | Opens the same host/profile history and sends a new prompt without routing through Companion. |
| Native host telemetry | CPU, memory, disk, platform, uptime, and process data appear on the phone using `/api/system/stats`. |
| Optional room discovery | Native thread rail keeps NEW and omits NEW ROOM when Companion support is absent. |
| Native file attachment | Android document picker selects a synthetic text file; native staging produces a host reference and inline attached context containing the test marker; the final reply arrives and the UI returns to idle. |
| Dictation lifecycle | MIC enters listening; a second tap stops it. The original typed draft survives both transitions. Acoustic recognition accuracy was not measured. |
| Archive / unarchive | Host stores each mutation; default rail hides the archived thread, ARCHIVED shows it, and unarchive returns its host flag to false. |
| Offline send and process death | Stopping only the temporary test tunnel produces a visible queued bubble. Force-stop and reconnect preserve the input; the host receives exactly one copy and stores the final reply. |
| Crash monitoring | No new AndroidRuntime crash during the test window. Recent process exits are package updates and the explicit force-stop; no new crash or ANR entry. |

The synthetic source session is `20261013_134337_89bc76`; its branch is `20261013_135721_6663fc`; the double-send session is `20261013_140239_ce39ca`; the attachment session is `20261013_141413_297f52`. Branching intentionally copies prior messages, so copied history is not counted as a duplicate submission. These four test sessions were archived and unpinned after validation to keep the normal rail clear.

The original host/profile and archive filter were restored. The temporary SSH forward, ADB reverse, synthetic phone file, and phone UI-dump file were removed. The temporary `127.0.0.1:19119` gateway bookmark remains in the saved gateway list; it is not the saved connection target and its tunnel is stopped. The phone locked during final UI cleanup, so the original archive-filter setting was restored through ADB while the app was stopped. Existing encrypted credentials were not edited.

## Defects found and fixed

1. Lab rejects the optional `idempotency_key` on `session.branch_stored`. The client now retries without that field only after an explicit parameter-validation refusal naming the field. Lost responses and unrelated validation failures never repeat the mutation.
2. Native `message.complete` can arrive before the host clears `running`. Immediate history catch-up could restore a phantom thinking state. Terminal catch-up now preserves completion, and a late open-history page cannot overwrite a newer turn's busy state. Regression tests reproduce both races.

## Next migration increment implemented

Following Conduit's separation of native transport from optional host capabilities:

- Host metrics use the native API first. An optional legacy fallback requires an explicitly unsupported native route and verified Companion availability. Authentication and server failures remain failures of the original service.
- Optional integration discovery has available, unavailable, and unknown states. Definitive results are cached for the connection; transient failures remain retryable. An HTTP-200 HTML fallback is correctly treated as an absent extension.
- Room and peer refresh skip unsupported native hosts, and the app does not offer unsupported room creation there. Late discovery/catalog responses cannot update another host's room state.
- Host changes clear foreign room/peer state. Native chat connection remains independent of optional discovery.

## Automated validation

All 369 tests in the targeted app, transport, storage, domain, chat-rendering, and thread-rendering suites pass. The debug APK builds. New tests cover native metrics without plugin requests, explicit legacy fallback, authorization/server failure preservation, SPA fallback detection, transient discovery recovery, host scoping, branch compatibility without unsafe retries, unsupported room UI, and completion/history races.

## Limits and remaining work

ADB checks establish the specific behaviors above, not complete release certification. Incoming agent calls and native realtime voice are not implemented. Acoustic dictation accuracy, TTS quality, interruption, Bluetooth/headset routes, background microphone behavior, and locked-device calls require dedicated audio/device tests. Accessibility was off on this phone (`l0` only), so arbitrary app gestures were not validated. Approval expiry/batch behavior, native OAuth, full room migration, Git/file-browser native migration, long-duration background/battery behavior, and the second host remain separate checks.

The paired phone lane was unavailable during final cleanup (the disarm request returned HTTP 503). The app was restarted, which initializes its phone control state as disarmed. Saved operator host/profile restoration was verified through ADB; no claim is made that the Hands lane remained connected after cleanup.

Keep the existing host integration for Hands, phone notifications, custom rooms, and the coding-agent console until their required replacements pass. No plugin should be uninstalled as part of this increment.
