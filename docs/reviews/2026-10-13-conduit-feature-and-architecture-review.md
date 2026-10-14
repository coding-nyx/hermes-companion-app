# Hermes Companion feature and architecture review

Hermes Companion should become a direct client of the native Hermes dashboard, with voice and session features built on native APIs. The current plugin should become optional during migration, then be retired only after its phone-control, notification-stream, room, and host-tool responsibilities have replacements.

Conduit is a valuable reference for user experience and lifecycle handling. Its full feature set is not plugin-free: basic chat and classic speech use native Hermes APIs, while several live providers, saved-call features, and incoming calls use its notifier plugin. Current native Hermes already provides a WebRTC voice-session endpoint that Companion can adopt without that plugin.

The recommended product keeps Companion's Android phone-control strengths and adds Conduit's stronger conversation experience. A wholesale rewrite or substitution of one plugin for another would create unnecessary work.

## Scope and source versions

Review date: 13 October 2026.

The gap matrix and issue findings describe the reviewed baseline. Subsequent chat/thread and dictation fixes are recorded in [the Android parity implementation notes](2026-10-13-android-parity-implementation.md), including validation and the remaining gaps.

The [current parity backlog](2026-10-13-current-conduit-parity-backlog.md) accounts for completed chat and audio increments and the restoration of the previous rich-text composer. Use it for the remaining work; the baseline matrix below is retained as the original review.

- Conduit main snapshot: [48cfd96](https://github.com/kaishi00/hermes-conduit/tree/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527).
- Native Hermes main snapshot: [63d0b94](https://github.com/NousResearch/hermes-agent/tree/63d0b94120027d487c9a5235b9ec64b696af1b26).
- Companion baseline: local working tree over commit 1b591ed0bd50322423ccd03143cf5038452199fd, including pre-existing uncommitted changes.

This is a source-level architecture and feature review. Availability on lab and raj must be checked against their installed versions and authentication settings. Source presence establishes an implementation path, not successful execution on those hosts. Device audio behavior, push reliability, and release readiness require runtime validation.

## Conduit project review

### Architecture and communication

Conduit authenticates with the native dashboard and communicates over its REST and JSON-RPC WebSocket interfaces. Its central transport is HermesClient; DashboardTicketBridge coordinates authenticated REST and ticket minting. The phone participates in the same sessions and profiles as the desktop. Companion already has equivalent foundations in DashboardClient, GatewaySocket, and its profile-scoped URL builder.

Conduit's core protocol covers more of Hermes than Companion currently exposes: session resume and active status, steering and redirecting, branching, titles, model and reasoning controls, context breakdown, attachment staging, profile management, projects, and hosted groups. The practical lesson is to expand Companion's native protocol coverage before inventing new custom routes. [Conduit RPC implementation](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/HermesClient.swift)

Its transport also distinguishes stored and runtime session identities and handles servers with different resume capabilities. That is especially relevant to Companion's offline cache, reconnects, and future voice sessions. A session's database identity, live execution identity, and call identity should not be treated as interchangeable strings.

### Authentication and security

Conduit has native password authentication and a system-browser OAuth flow using PKCE, state validation, a loopback callback, bearer tokens, refresh-token rotation, and Keychain storage. Its refresh logic consolidates concurrent refreshes and avoids blindly replaying arbitrary mutations after a rejected access token. Companion currently supports password login and loopback-token operation, with credentials encrypted through Android Keystore-backed storage; its connection flow explicitly rejects unsupported OIDC-only hosts. [Native OAuth implementation](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/NativeOAuth.swift), [Companion connection flow](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/CompanionViewModel.kt:703)

Cloudflare Access credentials are scoped to the dashboard origin. Native HTTPS/WSS requests receive the service-token headers, and embedded dashboard requests have a same-origin injection mechanism. The browser authorization navigation has a separate limitation: a service-token-only challenge cannot be solved by simply adding headers to later token requests. This distinction should survive the Android implementation. [Cloudflare implementation](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/CloudflareAccess.swift)

The native Hermes OAuth endpoint currently validates a loopback HTTP redirect URI. An Android implementation cannot assume that the app's existing custom deep-link scheme is accepted by that endpoint. Use the actual callback contract, or make a separate upstream change for mobile redirect support. [Hermes authentication routes](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/hermes_cli/dashboard_auth/routes.py)

### Conversation experience

Conduit goes beyond a basic streaming transcript. It supports richer Markdown, math and Mermaid, reasoning streams, tool inspection, session controls, typed decision cards, context usage, and delegated-agent visibility. Its source also includes bot editing, projects, hosted group rooms, Kanban integration, widgets, Apple Watch, CarPlay, and broad localization.

These features have different dependencies. Bot and group APIs appear in the native RPC implementation. Kanban uses a separate /api/plugins/kanban namespace. Voice history and multiple live providers use /api/plugins/conduit_push. Do not classify everything visible in Conduit as a native Hermes capability. [Hosted groups](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/GroupChatModels.swift), [Kanban service](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/KanbanService.swift)

### Voice engineering

Conduit separates classic voice, read-aloud, dictation, wake-word handling, live providers, audio ownership, history, and background work. Its classic voice gateway uses native transcription and speech endpoints, supports streamed PCM with a whole-file fallback, and keeps provider keys on the Hermes host. It also has a route policy that allows acoustic interruption on proven headset routes and uses half-duplex behavior when speaker output can feed the microphone. [Classic voice gateway](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesVoiceGateway.swift), [Barge-in route policy](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/VoiceBargeInRoutePolicy.swift)

Generation counters fence late callbacks after teardown or replacement. Logical conversation state is distinct from actual microphone capture. Its controller considers foreground transitions, interruptions, route changes, and suspended in-flight work. These are the patterns Companion needs for dependable voice, rather than only adding another microphone button. [Voice controller](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/VoiceConversationController.swift)

Live voice does not itself make the realtime model a Hermes agent. Conduit bridges delegated work into Hermes sessions and routes results back into the call. It deduplicates provider tool/delegation events, distinguishes new jobs from corrections, and preserves approval boundaries. This is a major part of the implementation effort. [GPT delegation bridge](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/GPTLive/GPTLiveDelegationBridge.swift), [Gemini tool bridge](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/GeminiLive/GeminiLiveToolBridge.swift)

### Incoming calls

Conduit implements both a Talk notification and native ringing. Host call watches observe work completion or requests for user input. Push delivery reaches the phone; answering opens voice in the correct conversation; outcomes are reported back. The iOS implementation uses PushKit and CallKit and includes stale-push handling, busy behavior, missed calls, and coordination with Apple Watch. These are incoming agent voice calls, distinct from dialing a telephone number. [Call watches and decisions](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesCalls.swift), [Native call lifecycle](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesNativeCalls.swift)

### Quality and maintainability

Strengths worth adopting:

1. Explicit capability checks and typed explanations for unavailable features.
2. Protocol adapters that accommodate older servers without assuming every new field exists.
3. Tests around host/profile isolation, authentication, voice teardown, route behavior, replay, and long transcripts.
4. Separate audio and provider interfaces that permit scripted test doubles.
5. Durable handling of unsaved voice transcripts.
6. Hosted CI with unit shards, UI smoke, and separate timing/performance suites.

The test inventory and CI design show substantial engineering investment; they do not prove that every behavior is currently correct. [CI configuration](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/.github/workflows/ci.yml)

Risks to avoid carrying over:

1. **Large central state object.** AppState.swift is roughly 26,800 lines in this snapshot. Extensions help organize responsibilities, but state ownership remains heavily centralized. Keep Companion's chat, device, sync, rooms, voice, and calls behind separate owners.
2. **Hidden optional-server dependencies.** The notifier is more than a push adapter: it supplies voice sessions, tags, summaries, memory/personality/search bridges, live providers, takeover, and calls. Conduit's own capability inventory makes this explicit. [Notifier capability inventory](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/NotifierPluginStatus.swift)
3. **Configuration overwrite risk.** Several voice-setting edits perform a full configuration GET followed by PUT. The code acknowledges that concurrent edits can be clobbered. Prefer focused server mutations or revision-checked writes when adding mobile settings. [Voice configuration writes](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesVoiceConfigurationService.swift)
4. **Provider and billing contracts differ.** Conduit's GPT adapter supports explicitly selected subscription/API paths and refuses mismatched responses. Native Hermes's voice-session endpoint has a different request shape. Reuse the abstraction, not the payload blindly. Account entitlement and vendor compatibility must be verified separately.
5. **UI complexity grows quickly.** Bots, groups, voice jobs, Kanban, projects, and multiple dashboards compete for navigation. Prioritize frequent Android tasks before porting every surface.
6. **Platform code is not portable.** SwiftUI, AVAudioSession, PushKit, CallKit, CarPlay, WatchKit, and widgets need Android implementations. Shared behavior and protocol fixtures are the useful assets.

Conduit is MIT licensed. If code or assets are adapted, preserve applicable notices, including notices on third-party assets. Architecture ideas can be implemented directly in Kotlin without translating its entire source tree. [License](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/LICENSE)

## Feature gap list

Present means an implementation exists in the reviewed working tree. Partial means there is a narrower implementation or a meaningful reliability gap. These labels are not device-test results.

Priority P0 removes a blocking dependency or fixes foundational behavior. P1 adds the main conversation/voice experience. P2 follows once those foundations work. P3 is optional platform breadth.

| Feature | Conduit reference | Companion status | Recommended action | Priority |
|---|---|---|---|---|
| Direct native dashboard connection | Core architecture | Native APIs present; setup still prescribes relay on 9120 | Make dashboard origin the default; gate extensions by capabilities | P0 |
| Multiple hosts and profiles | Present with isolated state | Present | Preserve; introduce stable host identity across origin migration | P0 |
| Native password authentication | Present | Present | Keep and test directly against dashboard | P0 |
| Native OAuth and token refresh | Present | Missing; OIDC rejected | Add system-browser PKCE flow matching server callback contract | P1 |
| Cloudflare Access service tokens | Present | Missing | Origin-scoped secrets and headers for REST and all WebSockets | P1 |
| Cached history, drafts, offline send | Present | Present through Room/outbox | Preserve; reconcile ambiguous sends against host history | P0 |
| Streaming chat and tool rows | Present | Present | Improve typed events and recovery before adding more surfaces | P0 |
| Reasoning trace display | Present | No dedicated trace surface found | Separate reasoning from final content; collapsible UI | P1 |
| Delegated-agent progress | Present | Separate console exists; chat projection incomplete | Parent/child progress cards linked to sessions | P2 |
| Steer and redirect active turn | Explicit native methods | Interrupt exists; dedicated steering absent | Add steer and redirect with distinct outcomes | P1 |
| Session title editing | Present | Ordinary-chat rename absent in reviewed manager | Add native title mutation | P1 |
| Pin and archive sessions | Present | Archived-list filter exists; mutations missing | Add server-backed pin/archive and sync | P1 |
| Session branching | Present | Destructive rewind exists, branching absent | Add non-destructive branch action with retained source link | P1 |
| Context usage and breakdown | Present | No context meter/breakdown surface found | Expose token/context usage and compression | P1 |
| Model and provider switching | Present | Present; primarily profile/global REST settings | Add session-scoped behavior and reconcile resulting model | P1 |
| Reasoning effort and speed controls | Present | Catalog carries reasoning capability; control absent | Add supported options only | P1 |
| Inline approvals and clarification | Present | Present, including privileged request types | Preserve; add batch/expiry behavior and request identity tests | P0 |
| Camera, image, and document attachments | Present | Present | Use native staging; make upload failures visible and retryable | P0 |
| Image paste and richer composer | Present | No equivalent image-paste handling found | Add clipboard attachment and draft-safe dictation | P2 |
| Markdown tables and code | Rich support | Existing Markdown renderer | Improve long-content performance and code highlighting | P2 |
| Math and Mermaid rendering | Present | No dedicated renderer found | Add bounded renderers with local assets and safe links | P2 |
| Server command catalog | Present | Small hardcoded slash menu | Fetch native command catalog and dispatch supported commands | P1 |
| Workspace file browser | Present | Plugin file APIs exist | Replace with native file APIs; add navigable browser | P2 |
| Git review and mutations | Native-capable host ecosystem | Plugin routes, limited native fallback | Implement native Git adapter and preserve review UI | P2 |
| Scheduled jobs | Present | Present, including trigger/pause/resume | Preserve; add run history and richer editing if supported | P2 |
| Skills, toolsets, and MCP settings | Capability/settings surfaces | Tool inventory is for terminal-agent launch; not equivalent | Add profile-scoped native capability management | P2 |
| Connector status and gateway logs | Present in product/server surfaces | Host metrics and terminal logs are narrower | Add native connector/log APIs where supported | P2 |
| Bot roster and dedicated Bot Chat | Present; profiles RPC | Profiles exist; dedicated bot management absent | Add bot metadata, dedicated chat lookup, and optional editing | P2 |
| Native hosted group rooms | Present; groups RPC | Custom plugin rooms with additional policy/peer features | Add native adapter and explicit migration | P1 |
| Projects and workspace assignment | Present | No equivalent project tree found | Add native project tree and session assignment | P2 |
| Kanban boards | Plugin integration | Missing | Defer unless native support or optional extension is accepted | P3 |
| Composer dictation | Dedicated dictation lifecycle | Present with draft/partial-result defects | Repair before adding voice providers | P0 |
| Classic voice conversation | Local/host transcription and host TTS | Device recognizer plus device TTS | Add provider selection, native host STT/TTS, robust turn lifecycle | P1 |
| Streaming transcription and speech | Native audio endpoints | Text token-to-device-TTS only | Add PCM capture/playback and server stream adapters | P1 |
| True realtime voice | GPT/Gemini/Grok adapters | Missing | Start with native Hermes WebRTC endpoint | P1 |
| Acoustic interruption | Route-aware; WebRTC for GPT | Callable bargeIn method; no acoustic detection path | Add route policy, VAD, echo control, and remote cancellation | P1 |
| Read an existing message aloud | Dedicated controller | Streaming new replies only | Add message read-aloud with stop and audio ownership | P1 |
| Voice background jobs | Session-backed jobs | No dedicated voice supervisor | Delegate via native sessions and report results once | P2 |
| Live call captions and minimized controls | Present | Basic voice-state UI | Add persistent call surface, captions, mute, routing, end | P1 |
| Voice history and resume | Plugin persistence plus local outbox | Ordinary text turns; no live-call history model | Add structured host persistence with encrypted local retry | P2 |
| Hermes calls after work or for input | Watches, push, native ringing | Missing | Implement durable host call requests and Android call lifecycle | P2 |
| Ordinary telephone dial/place/log | Different feature | Already present in phone toolboxes | Keep separate from agent calls | Existing |
| Dedicated offline wake-word engine | Apple/sherpa lifecycle options | Repeated general speech recognition | Replace with local keyword detector and mic ownership rules | P2 |
| Biometric app lock and secure secrets | Face ID and Keychain | Present via biometric gate/Keystore | Preserve and define locked-call boundaries | Existing |
| Phone gestures and app control | iOS capabilities differ | Major Companion strength | Preserve arm-first broker and device lane | P0 dependency |
| Phone notification reading/reply/stream | iOS differs | Major Companion strength | Preserve denylist, self-echo suppression, explicit injection | P0 dependency |
| Widgets and car/wearable surfaces | Apple widgets, CarPlay, Watch | Not equivalent | Android widgets/tiles, then Auto/Wear OS separately | P3 |
| Localization and tablet layouts | Broad language/platform support | Mostly English and phone-oriented UI | Localize strings and add responsive layouts | P3 |

## What removing the plugin actually requires

### Native replacements already available

The inspected Hermes source implements these capabilities without Companion's plugin:

- Chat, session history, profiles, approvals, models, and many session controls.
- Image and file staging through native RPC and file APIs.
- File browsing, reading, streaming, uploading, and workspace operations.
- Git status, branches, file/review diffs, staging, commits, and other Git operations.
- Hosted groups through groups.capabilities and groups methods.
- Classic voice through /api/audio/transcribe, /api/audio/transcribe-stream, /api/audio/speak, and /api/audio/speak-stream.
- WebRTC live voice through /api/audio/voice-live/status and /api/audio/voice-live/session.

The native live-session request contains sdp and optional history. The route keeps the project API key on the host and returns a session answer. It is not a drop-in equivalent to Conduit's notifier request, which accepts billing, briefing, greeting, voice, and other fields. [Native audio router](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/hermes_cli/web_routers/audio.py), [Request models](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/hermes_cli/web_models.py)

File and Git response shapes need real adapters; changing /companion to /api is insufficient. Terminal-agent sessions, PTY ownership, and the custom cross-agent console also require a separate contract audit. [Native file router](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/hermes_cli/web_routers/files.py), [Native Git router](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/hermes_cli/web_routers/git.py)

### Responsibilities with no verified drop-in native replacement

1. **Phone pairing and device credentials.** Companion persists devices, approves/revokes pairings, issues short-lived tickets, and authenticates a separate device lane.
2. **Mobile tool registration.** The plugin registers mobile tools and the canonical skill/prompt. Deleting it removes the agent's ability to discover and invoke those tools.
3. **Command broker.** Armed-state, protected-package, device-selection, reference freshness, timeout, and audit checks execute on the host as well as the phone.
4. **Phone notification ingestion and agent wake.** This is phone-to-agent streaming, separate from host-to-phone push. Its suppression, deduplication, wake, and explicit-injection policies must survive.
5. **Custom rooms and peers.** Native groups are a promising replacement, but current participant turns, PASS behavior, max_rounds, room sessions, approvals, summaries, and cross-host peers need semantic mapping.
6. **Terminal-agent sessions and console integration.** Tool inventory, process/session ownership, pane events, keys, prompts, transcripts, and approvals have custom routes.
7. **Incoming agent-call watches and delivery.** The inspected core provides live voice transport, but not a verified equivalent of Conduit's call-watch/push lifecycle.
8. **Structured external live-call history.** Creating an ordinary session is not permission to fabricate a completed transcript by sending messages as new prompts.

[Current plugin registration](/Users/nyx/Projects/hermes-companion-app/hermes-plugin/__init__.py), [Broker](/Users/nyx/Projects/hermes-companion-app/hermes-plugin/broker.py), [Relay routes](/Users/nyx/Projects/hermes-companion-app/hermes-plugin/relay.py), [Native group contracts](https://github.com/NousResearch/hermes-agent/blob/63d0b94120027d487c9a5235b9ec64b696af1b26/tui_gateway/contracts/groups_bot_relay.py)

### Three architectural options

| Option | User setup | Feature consequences | Assessment |
|---|---|---|---|
| Native dashboard only | Hermes dashboard plus reachable network origin | Core chat, classic voice, native live voice, supported groups/files/Git; custom phone tools need upstream support | Desired end state |
| Native dashboard plus optional Companion extension | Core works without installation; extension enables phone tools and unmigrated services | Preserves current features while migration progresses | Recommended transition |
| Replace plugin with standalone bridge or MCP server | Another service must be configured | Can preserve phone tools without Hermes plugin registration; still has a host component and tool/prompt integration | Valid if removing the plugin mechanism is sufficient |

For the strict requirement of no additional host component, move the remaining required services into Hermes core and ship a compatible Hermes release. Removing Python plugin registration alone cannot preserve a host command broker. Replacing it with another daemon changes the integration mechanism, not the existence of a dependency.

Until replacements exist, retain the canonical plugin skill at hermes-plugin/skills/hermes-companion/SKILL.md. Do not fork a second installed skill. Changes to mobile behavior must update the skill, registration prompt/hints, and broker guards together. App/protocol changes affecting stream or rooms must be deployed to lab and raj together.

## Target architecture

The Android app should have one operator connection to the native dashboard, an independent device connection when phone integration is available, and a voice/call owner that survives UI navigation.

Suggested boundaries:

| Owner | Responsibilities |
|---|---|
| HostConnectionRepository | Saved dashboards, authentication, origin/header rules, capabilities, reconnect |
| SessionRepository | Session identities, history, controls, models, decisions, outbox |
| VoiceSessionCoordinator | Session binding, modes, state transitions, interruption, captions |
| AudioSessionCoordinator | Microphone ownership, audio focus, route changes, playback/capture teardown |
| ClassicVoiceEngine | Local/host STT, Hermes text turn, local/host TTS |
| RealtimeVoiceEngine | WebRTC/provider stream, transcripts, provider cancellation |
| VoiceJobSupervisor | Delegation, idempotency, job progress and results |
| AgentCallRepository | Watches, invitations, expiry, outcomes, replay |
| CallService | Android Telecom and foreground audio lifecycle |
| DeviceIntegrationRepository | Pairing, device lane, notifications, arm/protect controls |
| GroupRepository | Native groups plus temporary legacy-room adapter |

Use host/profile/session/generation on asynchronous work. When a host, profile, conversation, or call changes, stale callbacks must not append text, submit speech, play audio, or resolve approvals in the replacement context.

The current foreground dataSync connection service should not become a catch-all for voice. Audio and call lifecycles have their own permissions, ownership, and platform rules.

Capability discovery should produce states such as supported, unavailable due to configuration, unsupported on this host version, and temporarily unreachable. Do not infer all behavior from a version string, and do not classify every authentication or network failure as a missing capability.

## Voice implementation plan

### Four separate modes

1. **Dictation:** speech modifies a draft; nothing sends until the user sends.
2. **Read aloud:** an existing message is played; no new agent turn runs.
3. **Classic conversation:** STT creates a Hermes prompt; streamed reply goes to TTS; listening resumes according to preference.
4. **Live call:** a realtime model owns conversational audio and delegates work to Hermes.

All four compete for the same microphone/audio session. Wake-word capture is a fifth claimant, so the coordinator must arbitrate ownership.

### Classic voice

Keep device speech and TTS as options. Add native host transcription and synthesis, selected per profile. Preserve a recording until transcription succeeds or the user discards it; never silently replay an utterance that may already have been submitted.

Implement microphone capture with explicit format negotiation, silence detection, bounded buffers, partial/final transcript distinction, and cancellation. Native streaming transcription accepts an opening sample-rate message, binary PCM16 frames, and end-of-speech; follow the installed host's exact contract. Streaming speech needs PCM playback, idle timeouts, and explicit fallback handling. Partial audio already played must not be replayed from the start during fallback.

Use a durable silence deadline based on actual speech activity, not on recognizer restarts. Provide Listen, Pause mic, Mute output, Interrupt, and End controls with different semantics. Ending local voice may leave delegated host jobs running, while Interrupt explicitly stops the active agent turn.

For barge-in, begin with headset full duplex and speaker half duplex. Enable speaker full duplex only through an echo-controlled audio path that passes device tests. A method named bargeIn is insufficient without an actual speech detector and correct cancellation at the server/provider.

Android documents that general SpeechRecognizer is not intended for continuous recognition and may send audio to remote servers. Use it for bounded dictation where appropriate; a dedicated local keyword detector is the better foundation for always-on wake. [Android SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer)

### Native realtime voice

Start with native Hermes's voice-live endpoints:

1. Discover host/provider readiness for the active profile.
2. Capture the selected chat identity and current call generation.
3. Create a WebRTC offer on Android.
4. Exchange the offer through authenticated Hermes REST.
5. Validate the answer and establish audio/data channels.
6. Render user/assistant captions from provider events.
7. Bridge delegated requests into ordinary Hermes work.
8. Return progress and final outcomes to the same provider request exactly once.
9. Handle route changes, interruptions, reconnects, mute, and teardown.

Keep provider keys on the host. Avoid adding a phone-side provider-key handoff merely because a native voice-config route supports one.

Add Gemini/Grok only after a stable host contract exists for them. Conduit's token minting/proxy routes can inform that contract but currently belong to the notifier plugin. Keep each provider behind an adapter because audio formats, interruption, tool scheduling, transcripts, and context differ.

Do not assume Conduit's subscription-backed GPT path is available through native Hermes or is a supported entitlement for every account. Start with the native host's declared provider and billing configuration; expose it clearly and never silently switch billing paths.

### Voice jobs and history

The realtime voice model should coordinate conversation; Hermes should execute actual tasks. Start/list/cancel/follow-up actions should operate on real sessions, with stable delegation IDs and authoritative server state. Background work can continue after hang-up.

For incoming approval calls, resolve only the exact still-pending request. Do not let a model's tool invocation alone authorize an action. Check the user's current spoken response, request identity, session/profile ownership, and expiry; retain a visible approval fallback for ambiguous speech.

Persist call identity, host/profile, attached session, provider, timestamps, transcript segments, linked jobs, and terminal outcome. Store completed call transcripts through an explicit import/persistence contract. Use an encrypted bounded outbox with visible retry/discard status if that contract is unavailable. Retention should be a product decision rather than an accidental consequence of cache size.

## Incoming call implementation plan

### Host lifecycle

A durable watch ties a job/session/request to a paired device and user preference. While the user is already in the related call, hold the callback; release it at hang-up, with a lease expiry if the phone disappears.

On completion, failure, approval, or clarification, create one call invitation with a unique ID, host/profile/session identity, reason, type, expiry, and allowed action. Record transitions through requested, delivered, ringing, answered/declined/missed/expired/failed, and ended.

Rate limits, quiet hours, a per-profile opt-in, duplicate suppression, and multi-device answer arbitration are required behaviors. An old push must not ring after an invitation has expired or been answered elsewhere.

### Delivery

Use a push abstraction rather than assuming a foreground WebSocket is always available. FCM is one Android transport; a self-hosted or UnifiedPush transport may be offered separately. Existing ntfy/foreground connectivity is useful but is not a guarantee of reliable killed-app ringing.

The host must retain authoritative invitations and outcomes regardless of the delivery transport. Encrypt sensitive payload content end to end and fetch full details after authentication. A push should identify an invitation, not grant permission to resolve an approval.

Keep this host-to-phone channel separate from Companion's phone-notification stream to Hermes. Neither should trigger an injection or reply into Telegram automatically.

### Android lifecycle

Implement incoming agent calls using Jetpack Core-Telecom and a foreground call owner, with CallStyle notifications and correct service permissions/types. Map answer, reject, hang-up, mute, and endpoint changes to the voice coordinator. Add the call to Telecom and honor its lifecycle; do not independently fight its audio routing with separate Bluetooth routing calls. [Core-Telecom guidance](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)

Microphone access and foreground-service startup must follow the target Android version's rules. Full-screen ringing is conditional on platform/user settings; retain a normal notification fallback. Answering may authorize call audio while locked, but must not unlock the full operator UI or arm phone control. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Time-sensitive notifications](https://developer.android.com/develop/ui/views/notifications/time-sensitive)

Start with a notification containing Talk/Open. After host watches, deduplication, and voice startup are reliable, add native ringing. This makes the distributed lifecycle testable before adding platform call behavior.

Ordinary telephone tools already exist in Companion: dial opens a number for user confirmation, place invokes ACTION_CALL with permission, and call-log reading uses a restricted permission. These do not constitute an agent voice call and should remain separate.

## Current Companion issues to address first

These are source-supported findings; the exact device manifestations should be reproduced with focused tests.

1. **P1 Dictation can erase the draft and duplicate recognized text.** MainActivity replaces the draft with each partial result, then onVoiceTranscript appends the final text to that draft. Starting with typed text can lose it; a partial matching the final phrase can yield the phrase twice. Capture the original draft and maintain a separate provisional segment. [Partial callback](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/MainActivity.kt:408), [Final merge](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/CompanionViewModel.kt:422)
2. **P1 Voice is not acoustically full duplex.** VoiceStreamEngine reopens listening after TTS completes. Its bargeIn method has no demonstrated acoustic-monitor integration in this implementation. Add an actual detector and route policy before promising interruption by speaking. [Voice engine](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/voice/VoiceStreamEngine.kt)
3. **P2 Silence timeout can be postponed by recognizer restarts.** No-match/timeout errors call startListeningLoop, which cancels and recreates the inactivity timer. If recognizer windows end before 15 seconds, silence can restart the deadline repeatedly. Store the last actual speech timestamp. [Retry path](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/voice/VoiceStreamEngine.kt:153)
4. **P1 TTS initialization failure lacks a terminal voice transition.** Failed initialization only invokes onError. isReady remains false, and finishFeed does not complete playback while false; the voice coordinator can remain waiting/speaking indefinitely. Propagate a terminal failure or explicit fallback. [TTS completion](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/voice/TextToSpeechEngine.kt:115)
5. **P2 Speech selection does not provide an explicit local-only contract.** VoiceInputManager prefers the Google recognizer when discoverable, before the on-device recognizer. Add explicit local/host/system selection and truthful privacy labels; do not infer that the Google service necessarily processed a particular utterance remotely. [Recognizer selection](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/voice/VoiceInputManager.kt:49)
6. **P1 Voice session ownership is too implicit.** openSession delegates to chat without ending/rebinding the voice stream. The voice engine submits through current UI state rather than a captured session target. Switching conversations while listening can direct the next utterance to a different chat. Bind voice to an explicit host/profile/session or stop on navigation. [Session opening](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/CompanionViewModel.kt:541)
7. **P1 Attachment upload failures are swallowed.** uploadAttachments converts any upload exception into an empty URL and continues. Make failed staging a visible error and keep the message pending; do not imply successful attachment delivery. The native fallback currently uses the image-upload route for arbitrary media, so implement separate native image/file staging. [Attachment staging](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/ChatSessionManager.kt:759), [Transport fallback](/Users/nyx/Projects/hermes-companion-app/data-remote/src/main/java/app/hermes/companion/data/remote/DashboardClient.kt:151)
8. **P2 Long-running wake recognition is a fragile foundation.** WakeWordService repeatedly restarts general speech recognition and launches the activity from a service. Replace it with a local keyword detector, a pending voice intent, and platform-compliant user-visible handoff. Test permission-denied, background, and recognizer-failure paths. [Wake service](/Users/nyx/Projects/hermes-companion-app/app/src/main/java/app/hermes/companion/voice/WakeWordService.kt)

Additional hardening belongs in the migration: event-buffer overflow recovery, stale async callbacks, ambiguous outbox acknowledgments, and consistent profile scoping for settings/files/Git. Companion's transport explicitly uses at-least-once sends; do not assume a native host honors client_id without a verified contract. This can otherwise repeat agent work after a lost acknowledgment.

The existing plan and README also lag the working tree: several features marked planned now have implementations, and the README still describes obsolete built-in protected-package behavior. Reconcile docs after the protocol migration so feature status and deployment requirements remain accurate.

## Delivery sequence and completion criteria

Effort ranges below are rough engineering estimates for implementation, not calendar commitments. They assume familiarity with the codebase and exclude waiting for upstream acceptance. Phases overlap in scope; do not sum the ranges as a reliable delivery date.

### Phase 0 Contract and capability inventory

Estimate: 2–4 engineer-days.

- Probe installed Hermes versions on lab and raj and record advertised capabilities.
- Build fixtures from the exact host responses for auth, session resume, audio, groups, files/Git, and voice readiness.
- Classify every custom route as native replacement, optional extension, or required upstream work.
- Define stable host IDs and old/new origin mappings.

Complete when the app knows what each host supports and the migration does not rely on guessed route shapes.

### Phase 1 Direct connection and foundational fixes

Estimate: 1–2 engineer-weeks.

- Default to the native dashboard origin with a reachable authenticated bind/reverse proxy.
- Remove unconditional plugin-route calls from successful connection.
- Treat pairing as an optional phone-integration step.
- Fix dictation, attachment staging, voice ownership, and terminal failure paths.
- Keep the old relay path as an explicit compatibility option.

Complete when a clean Hermes host without the Companion plugin supports login, profile selection, chat, history, attachments, decisions, offline recovery, and reconnection.

### Phase 2 Conversation parity

Estimate: 1–2 engineer-weeks.

- Add title, pin/archive, branch, steer/redirect, context meter, reasoning controls, and server slash commands.
- Add reasoning and agent-progress transcript projections.
- Add OAuth and Cloudflare support as separate reviewed authentication changes.

Complete when the phone and desktop agree on identities, settings, session mutations, and pending requests.

### Phase 3 Classic voice and audio ownership

Estimate: 2–3 engineer-weeks.

- Add local/host STT and TTS adapters, streaming capture/playback, read-aloud, and voice settings.
- Move ownership into an audio coordinator and foreground voice service.
- Implement route-aware interruption and lifecycle teardown.
- Keep dictation, read-aloud, classic conversation, and wake capture mutually coordinated.

Complete after locked/background, headset, speaker, route-switch, permission, and network-failure tests pass on real phones.

### Phase 4 Native realtime voice

Estimate: 2–4 engineer-weeks.

- Implement native WebRTC status/session exchange and data-channel protocol.
- Add call captions, minimized controls, mute, routing, reconnect, and provider interruption.
- Add delegated Hermes work with idempotent job tracking and explicit approval boundaries.
- Design history persistence separately from sending prompts.

Complete when a live conversation can perform real Hermes work, be interrupted, survive intended UI/background transitions, and end without leaked microphone/playback resources.

### Phase 5 Native groups and host surfaces

Estimate: 1–3 engineer-weeks, depending on required legacy-room compatibility.

- Add native group repository and compare policies/features with existing rooms.
- Migrate files/Git to native adapters.
- Add bots, projects, capabilities, connector status, and logs in priority order.
- Keep old rooms readable during explicit migration.

Complete when migrated rooms retain history, ownership, participants, and approval context without duplicate turns; unmatched features remain clearly accounted for.

### Phase 6 Incoming agent calls

Estimate: 3–5 engineer-weeks across host and app, after voice foundations.

- Implement durable host watches, invitation lifecycle, delivery, and outcomes.
- Ship Talk notifications first.
- Add Telecom ringing, expiry, busy/missed behavior, multi-device arbitration, and quiet hours.
- Add voice approval/question answers with exact request identity.

Complete when the agreed killed-app/locked-device scenarios work on supported phones, and late/duplicate invitations cannot ring or approve an expired request.

### Phase 7 Retire the plugin

Estimate: dependent on upstream scope and acceptance.

- Move required device services, tool registration, prompts/skill, broker guards, notification ingestion, and any remaining console features into Hermes core, or explicitly choose an external bridge.
- Migrate device credentials and tickets through an authenticated process; do not automatically trust an old credential on a new origin.
- Release a versioned app/host compatibility matrix with rollback instructions.
- Update app setup, deployment services, canonical skill location, and operator docs together.

Complete only when uninstalling the plugin on both lab and raj leaves every required feature working. Native chat alone is not the retirement criterion.

### Phase 8 End-to-end design review and redesign

Schedule this as the final roadmap phase. Give UX and readability the highest weight in redesign decisions; fix blocking usability problems encountered during earlier implementation as they arise.

- Review complete user journeys on the running app: setup/login, host and profile selection, finding and creating threads, composing and sending, streaming replies, offline recovery, attachments, approvals, voice/calls, rooms, and phone controls.
- Record friction and ambiguity with screen/state evidence, especially unclear send status, hidden actions, navigation dead ends, lost context, and recovery paths that require technical knowledge.
- Audit text hierarchy, font sizing, line length and spacing, contrast, Markdown/code/table readability, touch targets, keyboard behavior, scrolling, and accessibility labels. Include large system text, TalkBack, light/dark themes, and small screens.
- Plan a consistent navigation and component system. Prioritize an understandable conversation layout, discoverable thread actions, clear delivery and voice/call states, and progressive disclosure of advanced controls.
- Preserve the restored rich-text composer and its visible formatting controls. The compact WhatsApp/Telegram-inspired replacement was rejected and reverted. Prototype and review future composer changes before replacing the existing interaction; text entry, formatting, keyboard behavior and draft safety remain priorities.
- Produce a ranked redesign backlog with before/after proposals and acceptance criteria. Prototype the main journeys before implementing the visual changes, then validate them on the operator phone.

Complete when the critical journeys are discoverable and understandable, long conversations remain readable, failures explain the next action, and device/accessibility checks support the redesign. Visual polish alone does not satisfy this phase.

## Validation and rollout

Use a small contract fixture suite plus focused real-device testing. Required scenarios include:

1. Clean native host without the plugin; legacy relay; partially capable host.
2. Password/OAuth login, expired token, concurrent refresh, reverse-proxy prefix, and Cloudflare-authenticated WebSockets.
3. Host/profile switching during connect, transcript load, speech, and pending decisions.
4. Runtime/stored-session alias changes and interrupted/steered/redirected turns.
5. Offline prompt, lost acknowledgment, reconnect, and attachment-upload failure.
6. Headset/speaker/Bluetooth route changes, cellular-call interruption, mic permission denial, and process/activity recreation.
7. Silent recognition windows, empty transcripts, TTS initialization failure, silent speech streams, and fallback after partial playback.
8. Late/duplicate provider events and background-job outcomes after hang-up.
9. Locked-device incoming call, expired invite, duplicate push, decline, missed call, and answer on another device.
10. Phone integration after migration: arm/disarm, protected apps, emergency stop, notification suppression, explicit injection, and room turns unable to invoke mobile controls.

Measure first-audio latency, interruption latency, reconnect time, dropped frames, duplicate submissions/jobs, battery behavior, and transcript-save failures. Define performance targets after collecting a baseline on the operator phone rather than claiming latency from architecture alone.

Deploy app and compatible host changes to lab and raj together when they affect mobile tools, stream, rooms, or credential protocols. Assemble the debug APK and sideload for device validation when implementation begins. Keep rollback possible until both hosts pass the same checks.

## Recommended first implementation list

1. Capability discovery and a native dashboard default.
2. Dictation, attachment, TTS-failure, and voice-session ownership fixes.
3. Session title/pin/archive/branch/steer and context/reasoning controls.
4. Host STT/TTS and a shared Android audio owner.
5. Native WebRTC live voice with real Hermes delegation.
6. Native groups and files/Git adapters.
7. Durable incoming-call requests, Talk notifications, then Telecom ringing.
8. Upstream device broker/tool registration and notification ingestion.
9. Remove the plugin after the required feature inventory passes without it.
10. Review the design end to end and plan/implement the redesign, giving UX and readability the highest importance.

The immediate goal should be a Companion app whose main experience works on a normal Hermes dashboard. Full plugin removal is a later host-integration milestone, and incoming calls are a distributed host/push/audio feature rather than a chat-screen addition.
