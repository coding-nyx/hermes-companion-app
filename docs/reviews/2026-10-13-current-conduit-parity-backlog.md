# Current Companion gaps against Hermes Conduit

Companion now has the native chat and thread foundation, but substantial voice, session-control, authentication, and host-management parity remains. The previous rich-text composer has been restored following the rejected compact redesign. This list describes the current working tree, including the phased native-speech and context work. See [implementation status](2026-10-13-phased-implementation-status.md) for validation gates and rollout details, and the [October 15 closeout](2026-10-15-closeout.md) for the latest installed APK and remaining acceptance limits.

The comparison uses Conduit commit [48cfd96](https://github.com/kaishi00/hermes-conduit/tree/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527). “Missing” means no equivalent implementation was found in the inspected Companion code. “Partial” means useful code exists but the complete workflow or validation is unfinished. “Improve” means the feature exists and needs additional behavior or runtime validation. These are engineering priorities, not assertions that every Conduit implementation is defect-free.

## Implemented foundations

These should no longer appear as wholly missing: native prompt compatibility; guarded host thread creation; queued, sending, sent, failed and uncertain delivery states; reconnect and history reconciliation; active-session resume; reasoning rows; native rename, pin, archive and branching; steering a running reply; native image/file staging; draft-preserving dictation; native host metrics; and optional plugin capability discovery.

Voice now has explicit foreground audio ownership, Speaker/Auto controls, silence deadlines, stale-callback fencing, and teardown. The native host speech pipeline now connects in-memory recording, transcription, captured-session submission, synthesis and playback, with bounded waits, cancellation and no silent backend fallback. Host speech stays behind a disabled rollout flag until spoken recognition and a complete conversation pass on the phone. System remains active; the operator explicitly deferred spoken tests. The new host playback and microphone-cancellation tests passed, and speaker clarity was confirmed. Completed messages now offer coordinated read-aloud/stop controls.

The restored composer keeps bold, italic, inline-code and list controls visible. It also restores the prior field dimensions and placeholder, separate MIC and SEND/STEER/INTERRUPT actions, slash entry, and visible model controls. The chat rendering suite passes all 16 tests, including the read-aloud controls. Audio and delivery fixes remain intact.

## Requested UX increments

The latest request is interpreted as **1: bottom-bar redesign now; 2: broader navigation-flow redesign added to the backlog**. The bottom-bar increment introduces Chats, Agents, Device and More, readable labels, proper touch targets and a secondary menu. Voice is an explicit action in the chat header. The restored composer and existing route semantics are preserved.

| Item | Priority | Status | Scope and acceptance criteria |
| --- | --- | --- | --- |
| 1 · Bottom bar | P1 | Implemented; final APK installed; focused S22 navigation check passed | Four primary destinations; every previous destination remains reachable in More; selected state and status badges have accessible semantics; labels remain readable at double text size. Installed checksum and final navigation check are verified. The delayed capture confirms an opaque, readable More menu after its window animation. |
| 2 · Navigation flow | P1 | Partial; focused S22 journey passed | Chats now returns to the list, implicit blank composers are removed, and local drafts have an explicit recovery entry. Rich drafts, destination switching, keyboard/back handling and New intent passed an isolated S22 shell/manager journey. Finish deep-link generation/host/profile ownership, agent detail return paths, disconnected recovery and broader accessibility journeys. |

This is a focused UX increment brought forward at the operator's request. The broader final design review remains in phase 10. Item 2 was implemented in a later increment; its tested scope and remaining work are recorded separately from item 1.

## Conversation and session work

| Priority | Feature | Current state | Required work |
| --- | --- | --- | --- |
| P0 | Chat and thread reliability | Improve | Extend recovery to long-running device use, rapid host/profile switching, process recreation and intermittent networks on lab; raj is excluded. Encrypted per-conversation drafts and attachment recovery are implemented with a fresh-process S22 storage gate; real keyboard/navigation journeys remain. Reconcile unknown session-creation outcomes. Room callbacks now have host/profile/room/generation guards. An encrypted room-send journal survives recreation; uncertain sends use CHECK without replay, and duplicate identical admissions are blocked. Finish unknown native session-creation outcomes, live disconnect stress and stronger room server idempotency/correlation. |
| P1 | Context meter and breakdown | Partial | Native inspection and event-driven refresh of an open context panel are implemented and device-tested. Completed messages/settings refresh measurements with coalescing and stale-result guards. Add a continuously visible transcript meter and broader resume hydration. Missing measurements stay unknown; category estimates are labeled separately. |
| P1 | Context compression | Gated adapter | Dedicated native RPC with an LLM-scale deadline and no uncertain-delivery replay is implemented and tested. Add durable pending/late-ack reconciliation, transcript adoption and UI before enabling it. |
| P1 | Session reasoning and speed controls | Implemented; focused S22 gate passed | Session-scoped settings, authoritative readback and durable uncertain-mutation admission are implemented. Extend selective-capability and restart reconciliation coverage. |
| P1 | Session model changes | Implemented for persisted sessions | Native session-scoped model/provider changes, expensive-model confirmation and readback are implemented. Draft/non-chat model selection retains its existing behavior. |
| P1 | Redirect an active turn | Implemented; focused controller gate passed | Native redirect reports queued/redirected/rejected outcomes and records durable claims. Add live busy-turn and restart outcome coverage. |
| P1 | Native command catalog | Implemented; native lab/S22 gate passed | Native catalog and explicit slash/dispatch routing are implemented; skill-generated prompts submit once. Phone-local shortcuts remain separate. Expand alias and selectively capable-host coverage. |
| P1 | Decisions and approvals | Improve | Per-request double-tap admission, submitting feedback, scoped late outcomes and expiration guards are implemented and device-tested. Complete durable pending-queue hydration, restart-safe uncertain outcomes, multiple-request queues and batch behavior where supported. |
| P2 | Delegated-agent progress in chat | Partial | The separate agent console is narrower than integrated parent/child work cards. Link delegated work, status, approvals and final results to the owning conversation. |
| P2 | Composer capabilities | Partial | Formatting is restored. Attachment preparation now has conversation ownership, bounded file/image memory and send admission; upload progress/cancel/retry, image paste and an expanded editor remain. Encrypted chat draft recovery is implemented and storage-tested across process recreation; room drafts and a focused keyboard/navigation journey are implemented and device-tested; image paste, expanded editing and broader UI journeys remain. See [draft recovery validation](2026-10-14-draft-recovery.md). |
| P2 | Quoting and richer message actions | Partial | Add a draft-safe quote/reply flow; review existing copy/rewind actions and their discoverability before expanding the menu. |
| P2 | Transcript rendering | Partial | Markdown, code and tables exist. Add math and Mermaid, syntax highlighting, readable large tables, and bounded rendering for long documents. |

Conduit's native session, context, model and command contracts are concentrated in [HermesClient](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/HermesClient.swift). Its [image-paste composer](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Views/Components/ComposerPasteTextView.swift) and [expanded editor](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Views/Components/ComposerFullEditor.swift) provide separate composer references; restoring Companion's four formatting controls does not implement those workflows.

## Voice and calls

The [October 14 latency measurements](2026-10-14-voice-latency-review.md) found 3.7–9.5 seconds from native prompt submission to first assistant text, while warm System speech started in 17–20 milliseconds. Prioritize per-turn timing/readiness, bounded clause buffering and native speech streaming, then session-model benchmarking and phase-five realtime voice. Lower reasoning alone did not consistently improve the warmed host turn. These synthetic tests used no microphone; spoken-dialogue gates remain deferred.

| Priority | Feature | Current state | Required work |
| --- | --- | --- | --- |
| P1 | Reliable spoken recognition | Unresolved | Diagnose the observed no-match result when the operator is available. Check recognizer readiness, language, microphone route and configured service. Hardware capture success does not prove recognition accuracy. |
| P1 | Host speech in real conversations | Implemented, gated | Host capture/transcription/session/synthesis/playback pipeline and explicit backend settings are implemented. Keep Host disabled until operator recognition and a complete spoken conversation pass. System remains selectable, with no automatic fallback. |
| P1 | Streaming speech and transcription | Synthesis implemented, disabled; live transcription missing | Native synthesis transport, bounded PCM playback and guarded fallback pass automated and isolated S22 checks. Lab Edge synthesis currently fails upstream with 403, leaving live-provider acceptance pending. Add negotiated live transcription, route/echo validation and interruption; whole-file transcription remains. See the streaming speech checkpoint. |
| P1 | Realtime voice | Native transport implemented; rollout disabled | Pinned Android WebRTC engine, native SDP adapter and public Live event codec are implemented. Complete provider-backed delegation, captions/UI and lifecycle acceptance. On October 15 the operator declined new OpenAI credentials and paid API setup; leave that backend disabled. This does not block System voice or independent app work. |
| P1 | Voice-to-Hermes delegation | Missing | Bridge realtime tool/delegation events into the correct native Hermes session, deduplicate requests, preserve approval boundaries and return results once. |
| P1 | Acoustic interruption | Missing | Implement VAD, echo control, route-aware duplex policy and provider interruption. The current explicit interrupt method is not a detector for speaking over playback. |
| P1 | Read existing messages aloud | Partial | Completed-message read/stop actions and audio ownership are implemented and render-tested. Native playback passed the phone test; verify the full message-action journey on device when available. |
| P1 | Full live-call UI | Partial | Classic voice has a state HUD, Speaker/Auto and End. Add captions, mute, connection state, elapsed time and minimized persistent controls for live calls. |
| P2 | Background and headset call behavior | Missing/partial | Foreground cleanup exists. Build an eligible background call owner and test Bluetooth/Auto routes, headset loss, cellular-call interruption, focus loss and process recreation. |
| P2 | Call history and resume | Missing | Persist structured call metadata/transcripts and pending local writes; do not manufacture stored history by resubmitting it as new agent prompts. |
| P2 | Agent callback invitations | Missing | Add durable watches for completed work, clarification and approval, with correct conversation targeting, expiry and deduplication. |
| P2 | Incoming ringing and push | Missing | Start with Talk/Open notifications, then Android Telecom answer/decline/end/missed-call behavior, locked-device boundaries and delivery recovery. |
| P2 | Dedicated wake-word capture | Partial | Replace repeated general recognition with a local keyword detector and explicit microphone/battery ownership. |
| P3 | Additional live providers | Missing | Evaluate Gemini/Grok and other provider adapters only after the native realtime path works; support their actual host dependencies explicitly. |

Conduit's [classic gateway](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesVoiceGateway.swift), [route-aware interruption policy](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/VoiceBargeInRoutePolicy.swift), [read-aloud controller](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/MessageReadAloudController.swift), [delegation bridge](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/GPTLive/GPTLiveDelegationBridge.swift), and [call lifecycle](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesNativeCalls.swift) are the relevant references. Android needs its own platform lifecycle implementation.

## Authentication and host capabilities

| Priority | Feature | Current state | Required work |
| --- | --- | --- | --- |
| P0 | Native-first setup | Partial | Native chat and metrics work, but setup still centers the compatibility relay. Make native dashboard access straightforward and clearly gate optional extensions. |
| P1 | OAuth and refresh lifecycle | Partial | Host/proxy-scoped tokens/cookies, media/notification credential isolation and redirect guards are implemented and device-tested. Implement browser PKCE/state verification, the actual accepted callback contract, secure token storage and consolidated refresh. Avoid blindly replaying failed mutations. |
| P1 | Cloudflare Access | Missing | Add origin-scoped credentials to REST and every relevant WebSocket; handle browser authorization separately. |
| P1 | Native hosted groups | Missing | Current custom rooms are plugin-backed. Implement native group capabilities and map participant/session semantics; preserve custom room policy until migration is verified. |
| P2 | Native file browser | Partial | Existing workspace tree/read APIs use plugin routes. Add native browsing/read/upload adapters and a navigable browser. |
| P2 | Native Git workflows | Partial | Some native status fallback exists; diff, branches, stage and commit remain plugin routes. Add real native adapters and preserve the review workflow. |
| P2 | Bot management | Missing | Profiles exist. Add roster metadata, dedicated bot chats and optional profile editing through supported native APIs. |
| P2 | Projects and workspace assignment | Missing | Add project tree, create/edit operations, session assignment and correct workspace scope. |
| P2 | Skills, toolsets and MCP settings | Partial | Terminal-agent tool inventory is not profile capability management. Add native settings surfaces with supported mutations. |
| P2 | Connectors and gateway diagnostics | Partial | Host metrics and terminal output exist. Add connector health, gateway logs and understandable recovery actions where supported. |
| P2 | Scheduled-job management | Improve | Scheduling/trigger/pause/resume exist. Add richer editing and run history when the native host contract supports them. |
| P3 | Kanban | Missing, optional | Conduit uses an extension for this feature; do not add it to a mandatory plugin-free core. |

See Conduit's [OAuth](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/NativeOAuth.swift), [Cloudflare Access](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/CloudflareAccess.swift), [groups](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/GroupChatModels.swift), and [Kanban service](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Services/KanbanService.swift).

## Plugin retirement and UX

Plugin retirement remains a migration milestone. Native chat, metrics and host audio transport already reduce dependency. Hands pairing/credentials, mobile tool registration and canonical skill, arm/protected-package broker enforcement, notification ingestion/wake, custom rooms and the agent console still need retained or replacement host integration. Conduit also uses plugins for several advanced voice/call/history and Kanban workflows; copying its architecture does not automatically remove all server dependencies.

Keep the final end-to-end design phase, with UX and readability weighted highest. Review setup, navigation, finding threads, composing, delivery/recovery feedback, Markdown, voice/calls, approvals and phone controls. Improve large-text/TalkBack support, touch targets, contrast, tablet layouts and localization. Prototype and review the composer before another replacement; preserve visible formatting throughout.

Android widgets/tiles, Wear OS and Android Auto are optional equivalents to Conduit's Apple widgets/Watch/CarPlay features, rather than direct code ports. They follow reliable phone workflows.

Recommended sequence: finish chat stability checks; connect native host speech and diagnose recognition; add context/session controls and native commands; implement realtime voice and delegation; build callbacks/incoming calls; migrate host capabilities; then retire verified plugin responsibilities. OAuth can be advanced earlier when required by deployment. The broad UX redesign remains last, with blocking usability fixes made as encountered.
