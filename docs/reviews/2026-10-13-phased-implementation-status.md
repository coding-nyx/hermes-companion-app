# Phased Companion implementation status

The requested plan is being implemented as separate increments with automated, build and S22 gates. This report distinguishes implemented code from enabled behavior and completed device validation. The restored rich-text composer remains unchanged. The operator explicitly deferred spoken recognition and complete spoken-conversation tests; Host speech remains disabled until those gates pass.

## Current rollout

| Phase | Status | Remaining gate or work |
| --- | --- | --- |
| 0 · Baseline | Lab baseline recorded; rollback APK and working-tree snapshot saved | Raj is excluded from live validation at the operator's instruction. A native host with the extension physically absent and a selectively capable host remain test environments to establish. |
| 1 · Native host speech | Capture/transcription/session/synthesis/playback implemented; Host rollout gated | S22 native speaker playback and microphone cancellation passed; operator confirmed clear playback. Spoken recognition and a complete conversation remain explicitly deferred. System remains the active backend. |
| 2 · Streaming audio | Native synthesis transport and PCM playback implemented; disabled | 459 automated tests and three S22 software/hardware-contract checks pass. Lab Edge synthesis currently returns upstream 403, so live PCM acceptance remains pending. Live transcription, route-aware interruption and route/echo checks remain. |
| Conversation recovery | Encrypted chat drafts and attachment recovery implemented; focused S22 storage gate passed | 485 automated tests and two S22 checks pass. Real keyboard/navigation journeys, unknown session-creation outcome reconciliation and room callback ownership remain. |
| 3a · Context controls | Native context inspection and refresh implemented; focused device gate passed | Live meter/resume hydration remain. Compression has a tested dedicated adapter but no operator control until durable pending/late acknowledgments and transcript reconciliation are implemented. |
| 3 · Remaining conversation controls | Not implemented in this increment | Session configuration, reasoning/fast controls, redirect, command catalog dispatch, approval recovery, delegated progress, image paste, expanded drafts and rendering improvements. |
| 4 · Native connection/authentication | Not implemented in this increment | Dashboard-first setup, accepted browser PKCE callback, origin-scoped Cloudflare headers and consolidated refresh. |
| 5 · Realtime voice | Not implemented | Android WebRTC, native SDP exchange, captions/mute/routes/minimized controls, deterministic teardown and delegated-request deduplication. Lab's live provider is unavailable because its required key is not configured. |
| 6 · Callback/Talk notifications | Not implemented | Durable watches, opt-in policy, invitation lifecycle/history, authenticated outcomes and FCM delivery. |
| 7 · Android incoming calls | Not implemented | Core-Telecom owner, foreground CallStyle notification, endpoint routing, arbitration and locked-screen/process-death validation. |
| 8 · Remaining host migration | Not implemented in this increment | Native groups/files/Git first, then remaining host features and opt-in local wake word; preserve unmatched room policies/history. |
| 9 · Plugin retirement | Not started | Remove routes only after replacement gates pass. No plugin was removed or deployed in this increment. |
| 10 · UX/readability redesign | Focused bottom-bar increment brought forward by operator; broader review remains | Chats/Agents/Device/More bar implemented. Navigation-flow redesign is backlog item 2; composer remains restored. See the current UX increment report. |

## Baseline and capability evidence

The baseline snapshot is `/private/tmp/companion-phased-baseline-20261013/`: `baseline.apk`, `working-tree.patch`, `status.txt` and nonignored untracked files. Unrelated changes were preserved; no reset, commit or PR was created. The snapshot includes preexisting plugin edits, which were not changed by this increment.

Rollback APK SHA-256: `54e5fa33c9b49981bb69ffee7d63b09fc355fd9e4ac72a227647a821f2dca512`.

The read-only [probe](../../scripts/probe_native_capabilities.py) captures response shapes and public schemas without saving tokens, credentials, chat contents or provider configuration. The [sanitized lab fixture](fixtures/2026-10-13-lab-native-capabilities.json) records Hermes 0.21.5 and the following states:

| Responsibility | Classification | Lab evidence |
| --- | --- | --- |
| Sessions/chat/history | Native | Earlier native prompt, resume, delivery and history contract/device tests; retain their recovery paths. |
| File transcription/synthesis | Native | Audio schemas captured; configured native speech transport and playback tested on S22. |
| Realtime provider | Native contract, currently unavailable | Voice status explicitly reports no configured OpenAI API key; do not classify the endpoint as unsupported. |
| Profiles/system metrics | Native | Authenticated native GETs succeeded. |
| Groups | Native capability advertised, migration unverified | `groups.capabilities` succeeds; driver present, persistent process false. This does not establish parity with plugin rooms. |
| Commands | Native capability advertised | `commands.catalog` succeeds; operator dispatch integration remains. |
| Files/Git | Native schemas; runtime scope unverified | File/Git schemas captured. Git status returns 422 without workspace parameters, so support remains unknown rather than unsupported. |
| Authentication | Native schemas, full flows unverified | Provider discovery succeeds; `/api/auth/me` returned 401 in the probe. Authentication failure is unknown capability, not absence. |
| Pairing/Hands/mobile tools | Required existing extension until replaced | No verified native replacement for the paired broker, skill registration and protected control policies. |
| Notification ingestion/explicit injection | Required existing extension until replaced | Preserve self/Telegram stream suppression and explicit injection policy. |
| Custom rooms/console policies | Compatibility extension until matched | Native group advertisement alone does not prove history, participants, approvals or console behavior are preserved. |
| Callback invitations/push/device arbitration | Unverified; extension may be necessary | No matching native callback/watch/push contract was established. Design only the necessary extension after verifying current host contracts. |
| Raj | Excluded | Operator instructed us to ignore this inaccessible machine. No test or deployment is claimed. |

Baseline Android tests, release lint and minified APK build passed. Plugin baseline: 188 Python tests passed. Lab currently still has the extension installed: native-route tests demonstrate native transport, not operation with every extension component absent.

## Native speech increment

`SpeechInput`/`SpeechOutput` ports now support native implementations. Capture uses permission-checked 16 kHz mono PCM/WAV in memory, activity/silence segmentation and bounded recording. Silence and cancellation do not upload audio. Captured buffers are erased, and no microphone recording file is retained.

Native provider operations bind to the captured origin/profile. The engine binds submission and interruption to the conversation navigation epoch; host/profile/session changes stop old work. Processing has a visible `TRANSCRIBING` state and a separate deadline. Provider failures are terminal for Host speech and never select System automatically.

Synthesis uses bounded sequential whole-file chunks, a memory data source and deterministic player/audio cleanup. The authoritative final reply replaces provisional unplayed text. This increment does not implement PCM streaming, acoustic barge-in, echo cancellation or Telecom calls.

Completed assistant messages offer READ ALOUD/STOP AUDIO. Read-aloud owns a separate audio lease, stops competing capture/playback and fences late callbacks. Main-activity backgrounding cancels operator voice and read-aloud. Backend settings preserve explicit choices; `HOST_SPEECH_ENABLED=false` keeps Host unavailable and System active pending spoken validation.

S22 instrumentation: two `HostSpeechDeviceTest` tests passed in 11.033 seconds. Native synthesis played through the new in-memory player and released audio; microphone cancellation produced zero uploads and no late result. The operator confirmed “Clear from the speaker.” These results do not establish recognition accuracy or a complete spoken conversation. Those checks are deferred at the operator's request.

## Context increment

The existing thread menu now opens Context usage. It shows native used/max tokens, a percentage derived from token counts, the model and labeled category estimates, with a refresh action. Missing values stay unknown, authoritative zero is preserved, and absent categories are explained. The panel scrolls for small screens and large text. The composer is untouched.

Each read is scoped to origin/profile/stored conversation and the current navigation epoch; the adapter resumes the native runtime when needed. Dismissal, host/profile switching and close/reopen reject late results. Only explicit native method-not-found establishes unsupported; authentication/network/busy errors remain retryable.

The compression adapter uses `session.compress`, a 630-second budget, an omitted blank focus topic and no automatic mutation replay or slash fallback. It distinguishes compressed/pending/aborted/unknown and lock-held results; absent messages remain null rather than an empty transcript. It is deliberately not exposed in the UI yet. Durable pending recovery, late terminal acknowledgments and history reconciliation are required before enabling it.

## Validation ledger

Verified debug APK SHA-256: `3525fe44872b1f3c4cb5806cbc75c1000458c1292fbe423cc2f1f2bfbb85c9d5`. The installed APK checksum matches. A verified APK, logs and machine-readable ledger are saved in `/private/tmp/companion-phased-context-20261013/`. Release lint has zero errors, 48 warnings and two informational notices. The minified release build passes; without a configured release keystore it uses the repository's debug signing fallback.

The context device test exercises the native adapter, controller and production panel; thread-menu wiring is separately render-tested. This does not claim a complete on-device navigation/readability review, which remains phase 10. The final launch retained the original origin/profile, and the recent AndroidRuntime error buffer contained no fatal exception. No APK rollback or data wipe was performed.


| Increment | Automated/build evidence | Device evidence |
| --- | --- | --- |
| Baseline | Full Android tests, release lint and minified build passed; 188 plugin tests passed | Previous restored-composer and native chat checks retained; current backup recorded. |
| Native speech | Full Android tests, release lint, minified release and debug/instrumentation APK builds passed | Native speaker playback and capture cancellation passed; speaker clarity confirmed. Spoken tests deferred. |
| Context | 420 unique unit/render tests pass; full release lint (zero errors) and minified release build pass | New APK installed; native context load, refresh and dismissal passed on S22 in 1.909 seconds. The empty synthetic thread was removed; no prompt was sent. |

Temporary native testing uses a loopback SSH forward and ADB reverse rather than changing the saved phone connection. The saved operator connection remains the relay origin `http://100.85.151.99:9120`, profile `knight`. The context device scenario creates an empty synthetic session, submits no prompt, and deletes only that test session afterward. No provider key is copied to the phone.

The current remaining-feature inventory is [the Conduit parity backlog](2026-10-13-current-conduit-parity-backlog.md). Phase gates remain authoritative: a successful compile or adapter test alone does not make a complete feature enabled or verified.

## Rollback and next increments

To restore the known composer baseline while preserving installed data, use `adb -s 100.105.213.54:34143 install -r /private/tmp/companion-phased-baseline-20261013/baseline.apk`. Both builds use the same package/signature and version code. This increment introduces no database schema migration or plugin/server mutation. Keep the verified APK above as the current checkpoint.

Next independent conversation increment: persist compression claims scoped by host/profile/stored session, reconcile only authoritative terminal outcomes (including late compute-host acknowledgments), refresh the post-compression transcript without stale history overwriting it, then enable a dedicated control. Test pending/timeouts/busy/reconnect/process recreation before its APK/device gate. Phase 2 streaming and phase 5 realtime remain distinct implementations; existing host playback is not proof of either.

Cleanup: temporary instrumentation package, native SSH forward and its ADB reverse were removed after validation; the operator app remains installed. The paired device was explicitly disarmed. No mobile tool/skill/guard behavior was changed, so no coordinated plugin deploy was needed for this increment.

The operator subsequently requested bottom-bar work as item 1 and navigation-flow work as item 2. The bottom-bar increment is documented separately in [navigation redesign validation](2026-10-13-navigation-redesign.md); the wider flow changes are explicitly listed in the [current backlog](2026-10-13-current-conduit-parity-backlog.md). The 420-test/APK records above remain the earlier native speech/context checkpoint, not the latest navigation APK.

## October 14 voice checkpoint

The next accepted increment fixes recognition callback ownership within a conversation and erases recordings returned after cancellation. Five regression tests reproduced the failures before the fixes. Full suites now pass 431 unique tests; release lint has zero errors and the minified build passes. A scripted two-turn lifecycle test passed on the S22 without microphone or playback. The installed APK checksum is verified and a rollback APK is retained. See [voice turn reliability](2026-10-14-voice-turn-reliability.md) for the exact evidence and streaming contract preparation. System remains active; Host speech and real spoken tests remain gated. The next voice increment is bounded PCM speech streaming; the separate compression recovery work above remains in the conversation backlog.

The subsequent [streaming speech increment](2026-10-14-streaming-speech.md) implements authenticated synthesis streaming, bounded playback, final reconciliation and guarded fallback. Its software gate passes with 459 automated tests and three S22 checks. Native-host provider acceptance remains pending because lab's configured Edge service returns upstream 403; both speech flags remain false. Independent conversation fixes may proceed while this provider gate is unresolved.

The next independent [attachment ownership increment](2026-10-14-attachment-ownership.md) fixes cross-conversation preparation, bounds input/image memory and prevents send before preparation finishes. Full tests now pass 469 cases. Six focused S22 checks pass; one live-provider scenario remains pending. The latest installed APK and rollback are recorded in that report. Native streaming status now begins at the first hardware write. This does not complete the remaining conversation, call, migration or UX phases.

The [draft recovery increment](2026-10-14-draft-recovery.md) adds encrypted per-conversation recovery, keeps later edits after durable outbox admission, and preserves the original composer during a rejected New request. Full suites pass 485 tests, release lint has zero errors, and the minified build passes. Two S22 storage checks pass across a fresh process after ADB force-stop; they explicitly flush writes and do not validate abrupt termination during pending disk IO or the complete keyboard/navigation journey. Its APK and preceding rollback are recorded in the report. This is the current installed checkpoint.


## October 15 closeout

The operator requested stopping after the current increment, committing all accumulated changes and pushing the repository. The [closeout record](2026-10-15-closeout.md) supersedes earlier installed checkpoints: native creation retry guards, host/proxy credential isolation, approval ownership and event-driven context refresh passed 544 Android tests, 188 plugin tests and 57 final S22 checks. Deferred provider, compression, call, migration and UX gates remain listed there.
