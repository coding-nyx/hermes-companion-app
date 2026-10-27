# Desk Companion V1 implementation plan

> **Superseded product scope:** The [continuous-conversation V1 plan](2026-10-15-desk-continuous-conversation-v1.md) replaces the chat-led layout, manual Talk flow, short listening timeout and deferred camera/providers below. This document records the delivered 0.1.0 prototype foundation, not completion of the revised V1.

**Status:** Implemented as a separate Android application; see `desk-app/README.md` for the delivered scope and validation.\
**Date:** October 15, 2026\
**Product requirement:** Desk Mode requires Android 16 or newer.\
**Related work:** Raspberry Pi 4B with 4 GB RAM purchasing and hardware prototyping are handled in the separate shopping chat.

Desk Mode gives an existing Hermes conversation a permanent place on a tablet: an expressive character, readable captions, useful task state and deliberate voice controls. Hermes remains the source of truth for sessions, tools and conversation history. Ship this as the separate `desk-app` application, sharing the `feature-desk`, `core-design`, `core-voice` and Hermes transport modules with this repository.

## 1. Product decisions

| Decision | Plan |
| --- | --- |
| Platform | The separate desk application requires Android 16+ (API 36+). Preserve the existing phone application's Android 12+ support. |
| SDK migration | Compile and target SDK 36 in `desk-app`. Root AGP is 8.9.1 with Gradle 8.11.1. The original phone application remains on compile/target SDK 35 and minSdk 31. |
| Application | Separate APK and private credential storage. Hermes is the first backend adapter; other providers are later integrations. Shared code avoids a second voice and transport implementation. |
| Entry | A separate Desk Companion launcher icon. Opening it does not start recording. |
| Conversation | User selects an existing individual thread, or explicitly creates a new one. The chosen host, profile and thread stay visible. |
| Visual identity | Extend the existing native robot mark with a few expressive states, using the current colour and typography system. |
| Initial voice | User starts a foreground half-duplex voice conversation using system speech. Text remains fully usable when speech is unavailable. |
| Listening | Opt-in and visible; entering Desk Mode, connecting a charger or restarting the app never grants listening permission. |
| Camera | A later explicit still-capture feature. Continuous camera processing is outside the first release. |
| Agent work | Reuse native session submission, streaming, approvals and interruption. No second model or hidden agent is required for the first release. |
| Pi | An independent endpoint of the same backend in a later increment. Tablet Desk Mode does not depend on Pi availability. |

The desk application has minSdk 36. Shared UI and voice libraries retain minSdk 31 so that the original phone application can consume them. Backend credentials and local state belong to each application's Android sandbox. The original in-app packaging proposal was replaced by the user's decision to build a standalone companion supporting additional backends later.

## 2. Current foundations and gaps

These are source findings from the current checkout, not tablet acceptance results. Existing uncommitted composer, model and navigation work must be reconciled when implementation begins.

| Foundation | Reference | Consequence |
| --- | --- | --- |
| Application currently compiles/targets API 35 and has minSdk 31 | `app/build.gradle.kts` | Budget an SDK 36 compatibility increment; do not silently drop older phone support. |
| Foreground half-duplex voice engine | `app/src/main/java/app/hermes/companion/voice/VoiceStreamEngine.kt` | Reuse its turn lifecycle; do not advertise realtime interruption. |
| Host speech, streaming host speech and native realtime flags are false | `app/build.gradle.kts` | Leave these gates unchanged until their existing device/provider acceptance is completed. |
| Speech input/output and audio ownership interfaces | `app/src/main/java/app/hermes/companion/voice/VoicePorts.kt` | Add a Desk adapter over the existing owner, rather than opening another recorder. |
| Audio focus and route handling | `app/src/main/java/app/hermes/companion/voice/VoiceAudioSession.kt` | Respect phone/VoIP calls, headset changes and audio focus loss. |
| Host/profile/conversation fencing in voice integration | `app/src/main/java/app/hermes/companion/CompanionViewModel.kt` | Carry the same identity guards into Desk actions and captions. |
| Voice is stopped on Activity ON_STOP | `app/src/main/java/app/hermes/companion/MainActivity.kt` | Keep the first release foreground-only. |
| General speech recognizer used for wake matching | `app/src/main/java/app/hermes/companion/voice/WakeWordService.kt` | Dedicated local wake detection and wake-to-turn handoff are separate later work. |
| Manual TakePicture attachment flow | `app/src/main/java/app/hermes/companion/MainActivity.kt` | Reuse staging for the later explicit camera feature. |
| Existing Canvas character | `core-design/src/main/java/app/hermes/companion/design/Controls.kt` | Start from RobotMark; no new raster asset pipeline is required. |

## 3. First release experience

### Entry and continuity

The separate launcher appears after installing on Android 16+. Android rejects installation on older versions. A supported phone can enter a compact version for development and accessibility testing; layout decisions use available window dimensions, rather than a model-name tablet check.

On entry, show the selected host/profile and a thread chooser. Offer Continue current thread and New desk conversation without silently changing profiles or creating an empty session every time the view opens. Store the selected identity per host/profile. If its thread has disappeared or access has expired, ask the user to select a thread again.

Open in chat returns to that same conversation. Leaving Desk Mode stops its recording and playback, while an already submitted host task can continue and remain visible in ordinary chat. An explicit Stop task action retains the existing interruption semantics.

### Ambient and engaged layouts

The ambient surface contains the character, a clock, the selected identity, a short connection label, and persistent Talk, End voice, Settings and Exit controls as applicable. Microphone status must remain understandable without animation or colour.

The engaged surface adds readable user/assistant captions and a detail pane for recent conversation, task progress and actual approval requests. Ambient mode can hide transcript details for a desk in a shared room. Returning from a biometric lock must not expose the transcript or resume recording before unlock.

Proposed expanded-window arrangement:

```text
 Host / profile / thread                     Connection / privacy / Exit
 ----------------------------------------------------------------------
                                     | Recent conversation
             Character               | Current task or approval
                                     | Result details / Open in chat
         Caption and state           |
 ----------------------------------------------------------------------
 Talk / End voice       Stop task when running       Settings
```

At medium widths, put the detail pane in a drawer/sheet. At compact widths or short heights, use a single column and preserve the controls and captions before decorative character space. Support portrait, landscape, split-screen and desktop window resizing. Respect cutouts, system bars, keyboard insets, predictive back and large font sizes.

On API 36 targets, Android ignores orientation/resizability restrictions on many large displays. Landscape is the preferred composition, not a forced orientation contract.

### Character behaviour

Derive expression from actual events: relaxed when idle, attentive when recording, processing when input is being recognized, working while the host turn is active, speaking only during playback, and a distinct appearance for muted/offline/error states. Paused for a user decision shows a decision state rather than an endless working loop.

Use local bounded animations with reduced-motion support. Idle animation should use little work and stop when hidden. Avoid continuous random movement, invented emotion recognition and visual claims of progress that the host has not reported.

## 4. Voice and lifecycle contract

Keep presentation, capture, playback and task state separate. The screen can be ambient while a host task runs; that does not mean the microphone is open. An ended voice conversation can still have a running host task.

| Event | Required behaviour |
| --- | --- |
| Enter Desk Mode | No recording or playback starts automatically. |
| Tap Talk | Check unlock, permission, selected conversation, connection and audio ownership; then start the existing voice loop. |
| Recognition unavailable or permission denied | Keep text chat usable and explain the unavailable capability. Do not loop permission prompts. |
| Listening | Display an explicit microphone-on indicator and partial caption. Only final accepted text may enter the existing submission path. |
| Host processing | Show real task state; half-duplex input remains stopped. |
| Speaking | Show the actual assistant text; resume follow-up listening only under the active voice loop's existing policy. |
| Voice inactivity timeout | End the voice loop and show idle; keep Desk Mode open. Initial policy retains the existing 15-second listening inactivity timeout. |
| End voice / mute | Stop capture and playback immediately, fence callbacks and release audio focus. An already submitted task is not implicitly interrupted. Talk requires a new explicit user action. |
| Stop task | Invoke the normal host interrupt path and report its actual outcome; do not claim cancellation merely because playback stopped. |
| App hidden, screen locked, audio focus lost or required permission revoked | Stop recording/playback; fence late callbacks. No automatic background restart. |
| Host, profile or session changes | End voice before changing identity. No late caption, audio or submission may appear in the new conversation. |
| Network loss after submission | Retain the existing uncertain-delivery behaviour. Do not automatically resubmit recognized text. |
| Process death or Activity recreation | Restore eligible UI/session selection; recording starts off. Reconcile host history without replaying spoken output. |

Use a single audio owner for dictation, read-aloud, voice conversations and future wake detection. The current `bargeIn()` also invokes host interruption; it is unsuitable as a playback-only stop. Reuse `stopStream()` for End voice and add distinct operations only when a separate behaviour is actually implemented.

The first release adds no microphone foreground service or boot-time recorder. A later hands-free release must separately address Android service restrictions, wake engine licensing, acoustic testing, echo suppression and permission transitions. Android 16 alone does not guarantee offline recognition, reliable echo cancellation or far-field audio.

## 5. Application structure

| Component | Responsibility |
| --- | --- |
| `feature-desk` | Desk screen, responsive layout, character, captions, detail pane and UI tests; depends on shared models/design and narrow interfaces. |
| `DeskCoordinator` in `feature-desk` | Selects the bound conversation; maps existing chat/voice/connection/approval state; handles entry and teardown. Avoid adding another large block of state ownership to CompanionViewModel. |
| Desk runtime interface | Typed actions and state passed from the app into the feature. The feature must not depend on `app` or directly own DashboardClient. |
| Existing voice owner | One recognizer/player and audio focus owner. Desk and normal chat use the same lifecycle. |
| Shared desk preferences | Host/profile-scoped selected session; caption/privacy, reduced motion and display preferences. Never persist live microphone-on state. |
| Existing session repository and transport | Sole submission, history, approval and reconnect paths. Desk creates no independent transcript or outbox. |

Implementation files will include the new module/build registration, DeskActivity and DeskViewModel in the new app, app-side storage/authentication and local preferences. Inspect the pinned Compose/Gradle versions before choosing an adaptive library API; keep an SDK/toolchain upgrade distinct from visual feature work.

The first release works with the native dashboard and requires no new Companion relay routes. Rooms remain accessible through normal navigation, but Desk voice binds only to individual threads. A multi-agent voice room and its speaking-floor policy are separate features.

## 6. Display, privacy and attention

Keep-screen-on is an explicit preference, defaulting to while charging when Desk Mode is visible. Clear its Activity flag on exit/background. Use an app-local dim presentation and optional pixel movement; never change the user's system brightness globally. Stop decorative animation when the view is hidden. This feature does not claim OS kiosk control or bypass lock/biometric policy.

Initial task and approval cards are projections of the selected conversation. Task completion is visual by default. Speak replies produced by an explicitly started voice conversation; an opted-in read-aloud action also remains available. Arbitrary phone notifications do not become automatic speech.

A later attention increment may add selected event sources, quiet hours, privacy-sensitive summaries and deduplication. Events need host/profile/session/source identity, freshness and stable event IDs before they can interrupt. Unknown or stale events cannot generate spoken completion claims. Do not invent backend progress fields when the host only exposes a running turn.

Entering Desk Mode never arms Hands, enables notification access, injects notifications into Telegram/chat, or grants camera access. Any future change to `mobile_*` behaviour must update the canonical plugin skill, prompt/armed hint and broker guards together, with the coordinated lab/raj deployment required by AGENTS.md. The UI-only first release does not require that deployment.

## 7. Implementation increments and acceptance

| Increment | Deliverable | Acceptance |
| --- | --- | --- |
| D0: Android 16 compatibility | SDK 36/toolchain migration, runtime feature eligibility and device/window matrix | Existing phone tests and relevant API 36 behaviour checks pass; API 31 phone support is preserved. |
| D1: Desk shell | New feature module, entry/exit, selected identity, character, text captions and Open in chat | Rotation/resizing preserves the selected conversation and draft; entry causes no mic use or extra session creation. |
| D2: Foreground voice | Adapter to existing speech owner; explicit Talk/End voice; real states and errors | Real spoken turn reaches the intended session once; reply plays once; focus loss, mute and identity changes stop capture and stale callbacks. Existing host/realtime flags stay false. |
| D3: Task and decision details | Actual turn state, approval/clarification cards and explicit Stop task | Existing request identity, expiry, biometric and double-tap protections work in Desk Mode; ending voice does not cancel a task. |
| D4: Ambient operation | Charging-aware display policy, transcript privacy and reduced motion | No screen flag or audio owner leaks after exit; hidden/locked view stops recording; sustained desk session remains usable. |
| D5: Explicit camera | Look at this capture with permission, preview, existing staging and retry/cancel | Cancel produces no upload; failed/uncertain uploads are visible; images enter only the bound thread. |
| D6: Hands-free voice | Local keyword detector, wake-to-turn handoff, bounded follow-up window | Real room testing measures misses/false activations; own playback does not trigger conversation; no general recognizer loop is used as the wake detector. |
| D7: Pi coordination | Capability discovery and explicit endpoint selection through a separately designed bridge | One chosen endpoint records/speaks; reconnect and duplicate events do not cause repeated announcements. Tablet still works without Pi. |

The first useful release is D0-D4. D5-D7 can ship independently afterwards. Provider-backed realtime and acoustic barge-in remain a separate voice programme, not implicit requirements of Desk Mode.

## 8. Verification and rollout

Meaningful automated coverage should exercise identity fencing, ambiguous delivery, permission/audio transitions, layout resizing and exactly-once presentation of decisions. Reuse the existing voice test doubles and session controls; add Desk-specific coordinator tests and a small set of render tests rather than copying every chat test.

Device acceptance requires an Android 16 tablet or an API 36 tablet emulator for layout, plus a physical tablet for speech, audio routes, charging and sustained use. Cover portrait/landscape, a narrow split-screen window, large fonts, keyboard/TalkBack navigation, lock/unlock, recreation and switching between Desk and normal chat. Confirm unsupported devices retain ordinary phone features.

On hardware, run repeated conversational turns and explicitly test End voice during recognition and playback, Stop task during work, disconnect after submission, switching profiles mid-turn, headset unplug and incoming call/audio focus loss. Record recognition errors and timing rather than claiming a latency target from transport-only tests. A one-hour charging session should show no duplicate microphone owner, stuck animation or accumulated stale callbacks.

Build/test commands will follow the added module and SDK migration. At minimum use targeted Desk/coordinator/voice suites, the existing app regression suite, lint and a debug APK. A physical speech acceptance run is required before advertising voice readiness. Rollback disables Desk entry and restores the preceding APK; ordinary session history remains on Hermes. No database schema or plugin protocol change is required for the proposed first release unless implementation uncovers a concrete need.

## 9. Remaining inputs

- Exact tablet model and installed Android 16 build, for microphone, charging/display and physical acceptance testing.
- Preferred spoken language and system voice, selected during device acceptance rather than assumed from locale.
- Whether the first home screen should prioritise the character or task detail; default is character in ambient mode with detail visible during engagement.
- A later choice of Pi audio hardware and endpoint protocol, owned by the hardware track.

These inputs do not block the SDK compatibility and Desk shell implementation. Provider account setup, paid voice service selection, background recording and OS kiosk enrolment are outside this plan's first release.

## 10. Platform references

- [Android 16 target behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16): large-screen resizing/orientation, edge-to-edge and predictive back.
- [Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk): supported SDK/toolchain setup.
- [Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes): adapt to available window dimensions.
- [Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on): Activity-owned display flag.
- [Foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): later microphone/camera service design constraints.

Repository reference paths above identify the current architecture. Re-read edited integration files when implementation starts; the plan is not a promise that those files will retain the same line numbers.
