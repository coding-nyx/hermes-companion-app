# Desk Companion: redesign and implementation

**Date:** October 15, 2026. **Status:** 0.2.0 implements the native Desk perch/robot, secondary sheets, host/device speech selection, native session model/reasoning controls, explicit guarded compaction and hidden camera frame sharing. Debug build/lint and targeted tests pass; see `desk-app/README.md` for device evidence. The full continuous-conversation programme remains incomplete.

**Credential constraint:** The user requested swappable speech with no API-key setup. Use Hermes' existing configured speech services/authentication as the first adapter. Do not provision an OpenAI/Grok key. Direct ChatGPT OAuth and any voice route must be capability-verified separately; the documented ChatGPT plan-usage flow currently excludes audio input/transcription. Existing Hermes model selection can still use the host's configured OAuth providers. The OpenAI Live reference below is an architectural reference, not a requirement to add a paid key.

**Camera refinement:** Camera imagery is for the agent and stays off the main screen. 0.2.0 has no persistent viewfinder; it retains a recent frame in memory and stages it with the next request. One-shot Look at this is separate from ongoing sharing.

This is the execution companion to [continuous-conversation V1](2026-10-15-desk-continuous-conversation-v1.md). The user's latest decisions are authoritative: use compaction for now, make conversation the primary interaction, and use a rich expressive robot in the selected Desk perch layout. Preserve Android 16+, a separate APK, lab/Tailscale connectivity, and Hermes/OpenAI/ChatGPT/Claude/Grok support.

## 1. Decisions for this implementation

| Area | Decision |
| --- | --- |
| Home | Selected Desk perch composition: full-body robot on the left, short speech/caption area on the right, quiet controls below; no permanent chat window |
| Character | Original soft-bodied robot with a ceramic shell, expressive visor, antenna, articulated hands and small grounded feet; layered 2.5D art and motion |
| Personality | Warm and attentive, with restrained motion; the same companion persists when providers change |
| Voice entry | Default foreground voice experience after onboarding consent; persistent mute and clear recording status |
| Speech | Continuous audio transport and one speech owner; system recognizer/TTS stays explicit compatibility mode |
| Model routing | Stable conversation voice plus task-scoped Auto/Fast/Deep reasoning; one owner executes each action |
| Context | Normal supported compaction, recent turns and a concise structured summary; preserve active tasks/decisions independently |
| V1 memory scope | Minimal durable transcript/task journal for recovery and direct adapters; defer semantic retrieval, graph memory and an elaborate no-compaction/dual-session system |
| Vision | Explicit still capture and bounded live frame sharing; camera off until selected |
| Delivery | Small verifiable increments, then an integrated Lenovo build; a UI-only checkpoint is not the full voice release |

## 2. Screen design

### Home: Desk perch (selected direction)

```text
 [Ash · Hermes ▾]            Listening · mic on       [History] [Settings]


       expressive robot             “I'll check their latest email.”
       full body and hands           Checking mail · Details
       generous clear space

               [Mute]      [Camera]      [End]
```

The assistant selector uses the user's chosen name plus provider. Put raw host address/profile/session IDs in connection details, not beside the character. Voice destination is available in the selector and clearly disclosed when different from the task provider. Connection status and microphone status are separate; never show Listening solely because a socket connected.

At expanded widths, use the selected Desk perch composition: the character in the left portion and a short spoken caption/result area on the right. Preserve generous space and natural proportions; this area is not a chat rail. At compact widths, stack character above caption. Captions have a comfortable maximum width and two normal lines; expand on tap or use history for long output. At narrow widths and large font sizes, reduce character space before clipping controls. Use available window size, not a tablet-model/orientation check.

Provider pickers use a single trailing chevron with a reserved icon slot, adequate text padding and consistent vertical alignment. Never combine a native arrow, a custom icon and a Unicode arrow in the label. Preview expression controls are labeled buttons. On Android, use one Material dropdown trailing icon and preserve its native accessibility/expanded state. Add narrow-window and large-font captures covering picker text and arrow alignment.

No old Ambient/Show details switch is needed: the default home already supplies the calm layout. A separate optional Quiet view can hide captions while leaving mute/camera state and controls visible.

### Secondary surfaces

| Surface | Contents and behavior |
| --- | --- |
| Assistant sheet | Hermes/OpenAI/Claude/Grok, account health, profile where relevant, voice destination, Auto/Fast/Deep; setup only when needed |
| History sheet | Recent transcript, results and optional keyboard entry. Opening it keeps the same conversation; it does not silently create another session |
| Camera sheet | Local preview, front/rear choice, Look at this, Share live view. Ongoing sharing becomes a small persistent preview and Stop sharing control |
| Task details | Verified progress/result, relevant sources, explicit Cancel task. A small home task chip opens it; no permanent dashboard |
| Approval card | Concrete pending request and choices; existing presence/expiry behavior retained |
| Settings | Voice/entry preference, language, captions/privacy, reduced motion, character customization, charging display, provider setup and usage |
| First-run setup | Choose assistant, connect account/lab, choose speech, mic disclosure/permission and start-on-open choice; return directly to home afterwards |
| Reconnect | Keep the mascot visible with a neutral waiting pose; show a compact recoverable error, without returning a configured user to the login page |

Home controls use labeled targets of at least 48 dp. Preserve keyboard/TalkBack access, proper contrast and explicit non-color privacy state. Keep controls reachable with captions, keyboard and system insets. End pauses automatic voice entry; Resume deliberately restarts it. Stop speaking and Cancel task remain distinct actions even when surfaced through voice commands.

## 3. Rich mascot specification

### Art direction

Create an original robot with an unmistakable silhouette: rounded ceramic head, inset dark visor with animated eyes/brows/mouth, small antenna, articulated arms/hands, compact torso and little feet. Use soft gradients, layered highlights, contact shadows and a restrained material texture to convey volume. Keep outlines/art consistent across poses. The surrounding surface stays visually quiet so the robot carries the personality.

The selected direction is an expressive robot, replacing the earlier woodland mascot concept. Final name and exact detailing can evolve after visual review. Keep a cream/soft-metal shell, dark visor and one muted mint accent; offer palette/accessory choices without rebuilding the rig. Do not recolor the entire character whenever the provider changes.

### Rig and assets

Author the character as separate editable layers: back antenna/side fins, torso, feet, neck/head shell, visor/highlight, eyes/pupils, lids/brows, mouth, jointed arms/hands and accent panel. Define normalized pivot points and a shared coordinate system; antenna, hands and joints must remain attached during poses. Record palette tokens, layer order, gesture curves and pose thumbnails with the asset source.

Use native Compose paths/layers for the initial rig. Cache static shapes and brushes, animate transforms/draw properties and keep speech-amplitude updates out of whole-screen recomposition. Compose supports cached drawing objects and transform layers; profile the actual rig on Lenovo. This design chooses those tools, without claiming they guarantee a frame rate. [Android graphics guidance](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers).

If the art/rig prototype cannot achieve the approved quality within its measured budget, evaluate an artist-authored animation runtime before scaling the pose set. Do not add a 3D engine or a second runtime merely to display the first character. The animation driver contract stays renderer-independent.

### Behavioral vocabulary

| State | Face and body | Event source |
| --- | --- | --- |
| Available | Soft breathing, occasional blink/weight shift; relaxed arms and antenna | Foreground connected voice session |
| Listening | Small forward lean, open eyes, slight antenna orientation and quiet hands | Actual speech onset/capture readiness |
| Considering | Slight asymmetric head tilt, thoughtful brow, one hand near chin | Accepted request awaiting meaningful output |
| Working | Patient grounded pose; occasional glance toward the real task chip | Verified active task, independently of speech |
| Acknowledging | One small nod and open posture; settle promptly | Relevant acknowledgement playback |
| Speaking | Mouth follows audible amplitude, restrained head/hand gestures at clause boundaries | Playback clock and output audio envelope |
| Supportive | Softer eyes, gentle head angle and gentle posture | Explicit conversational delivery cue, never guessed camera emotion |
| Clarifying | Tilt, raised brow and open paw; wait without anxious looping | Actual clarification request |
| Decision needed | Attentive gaze toward decision card, restrained pause pose | Pending approval identity |
| Looking | Gaze turns toward local preview; curious head/antenna movement | Explicit camera sharing/capture state |
| Muted | Relaxed still pose; mic-off label remains authoritative | Actual capture muted/stopped |
| Reconnecting | Patient pose and compact connection text | Actual transport recovery |
| Result delivered | Single small nod or restrained pleased expression | Relevant verified completion delivered to user |

This is a layered pose system, not one exclusive emotion enum. A task can be running while the character listens. Mic/privacy/transport facts take priority over expressive cues; speaking overlays mouth motion while the correct base state remains visible. New speech interrupts an old flourish immediately.

### Motion rules

- Establish weight and anticipation: a small preparation before a nod/gesture, then follow-through in antenna and hands. Avoid constant bouncing and repeated celebratory reactions.
- Smooth pose changes over roughly 150–350 ms; gesture clips approximately 0.4–1.2 s. These are art starting points, adjusted through review rather than fixed latency promises.
- V1 uses smoothed audio-energy mouth motion with closed/rest/open/rounded forms. This is not phoneme-accurate lip sync. Add viseme timing later only if the speech provider exposes trustworthy alignment.
- Gaze follows explicit interaction targets or the preview, not an invented user location. No face tracking or identity detection is needed for V1.
- Touch on the character can produce one small local acknowledgement; it must not trigger a model request, unmute or submit a task.
- Reduced motion removes breathing/antenna/hand loops and squash/stretch; use clear static expressions with minimal transitions. Hidden/locked views run no animation ticker.
- Use a stable seed/test clock for deterministic pose captures. Performance target is smooth active animation within the device frame budget, with quiet low-cost idle; measure frame pacing, power and heat alongside audio/camera.

## 4. Runtime design and current code changes

Current evidence: `DeskScreen.kt` couples character and chat panes; `DeskViewModel.kt` feeds Hermes text deltas into `VoiceStreamEngine`; that shared engine is half duplex with a 15-second inactivity timeout. `DeskActivity.kt` handles mic, presence and onStop teardown. `DeskStore.kt` stores configuration/drafts/journal in encrypted preferences. Retain working safeguards while replacing the primary interaction.

| File/package proposed | Work |
| --- | --- |
| `feature-desk/.../DeskScreen.kt` | Desk perch composition and secondary sheets; remove permanent ConversationPane and Ambient toggle |
| `feature-desk/.../character/CharacterScene.kt` | Renderer entry point, stage scaling and semantic state label |
| `feature-desk/.../character/RobotRig.kt` | Layer geometry/pivots/palette and drawing cache |
| `feature-desk/.../character/CharacterDirector.kt` | Real-event-to-pose mapping, priority/blending, deterministic clock and reduced motion |
| `feature-desk/.../character/CharacterMotion.kt` | Pose parameters, short clips, audio envelope and transition curves |
| `feature-desk/.../sheets/` | Assistant, history, camera, task and settings surfaces |
| `core-conversation` | Pure Kotlin identities, events, speech arbiter, task ownership and compaction policy; no Android/provider assumptions |
| `desk-app/.../DeskViewModel.kt` | Compose the orchestrator/voice/character driver; avoid a second recorder or independent send path |
| `desk-app/.../DeskActivity.kt` | First-run mic/camera permission, persistent foreground-entry/mute policy, charging and presence/lifecycle boundaries |
| `desk-app/.../DeskStore.kt` | Versioned settings migration preserving existing lab/account/session; keep credentials in secure storage |
| `desk-app/.../conversation/DeskJournal.kt` | Minimal persistent transcript/checkpoint/task/delivery records for recovery; app-private database and field encryption for sensitive content |
| `desk-app/.../vision/DeskCameraController.kt` | Camera lifecycle, preview, selected-frame capture and latest-frame sharing |
| shared realtime module | Audit/extract `NativeWebRtcPeer` and required audio/session interfaces; keep phone behavior/build gates unchanged |
| backend broker adapters | Hermes delegation plus official OpenAI/Claude/Grok execution, capabilities, ephemeral voice authorization and usage |

Paths containing proposed components are future files. Choose precise module/package names during the first build step, and update the plan if existing boundaries make a new module unnecessary. Share existing Room/storage patterns only after checking ownership; do not put Desk data in the phone app's database or claim Room alone encrypts records.

### Neutral events

Every non-audio event carries a logical conversation ID, event ID, source, generation and timestamp. Task events also carry task ID/turn revision. Examples: `VoiceReady`, `SpeechStarted`, `UtteranceAccepted`, `PlaybackStarted/Progress/Stopped`, `TaskAccepted/Progress/Result`, `DecisionRequired`, `CompactionStarted/Completed`, `VisionFrameSelected`, `ConnectionChanged`. Persist accepted semantic events, not every animation/audio frame. Reject stale generations at the boundary.

Keep audio capture, playback, transport, task and compaction as independent states. Do not use `canSend` from the single Hermes turn as the permission to keep listening. Multiple utterances can arrive while a task runs; the orchestrator classifies a correction, cancellation, clarification or separate conversational turn. Only the task owner authorizes/execututes tools.

## 5. Compaction: the V1 policy

Compaction is accepted normal behavior. Keep one logical conversation and use supported backend compaction rather than building a no-compaction memory system. Raw history and structured pending work remain separate from the compressed model prompt.

### Hermes integration already present in source

`DashboardClient.sessionContext()` calls `session.context_breakdown`; `compressSession()` calls `session.compress`. `SessionCompression` already distinguishes `COMPRESSED`, `PENDING`, `ABORTED` and `UNKNOWN`, plus optional returned messages and token counts. These methods are not wired into Desk yet. Their existence is source evidence; verify deployed lab capability and result/lock semantics before enabling automatic mutation.

### Minimal algorithm

1. Read actual context usage after accepted turns and when the host signals a change; use adapter estimates only when needed and label unknown usage as unknown.
2. Request compaction at a safe idle task boundary when usage exceeds a configured provider threshold. Start at 70% as a tunable heuristic, reserving enough space for maximum expected results; never compact every turn or during a pending approval/tool mutation.
3. Before compaction, persist a small versioned checkpoint: current topic, explicit facts/corrections, unresolved questions, latest transcript cursor, tasks/ownership/delivery state, pending decision references and what was actually spoken. This is recovery state, not a semantic-memory project.
4. Invoke the native host operation once and retain the same logical conversation. `PENDING` is not success. Keep its mutation record and reconcile using supported host status/history. On timeout or `UNKNOWN`, never blindly retry; show context maintenance state and reconcile first.
5. On `COMPRESSED`, apply authoritative messages if supplied, refresh host context/history and preserve local transcript/checkpoint. Missing `messages` must not clear history. On `ABORTED`, retain the previous context and report the reason; retry only after a known safe condition changes.
6. Voice can remain available while compaction runs. If the host locks task submission, accept a bounded queued request with a clear waiting acknowledgement; do not say its tool has started. At the high-water limit, pause new task submission and show a recoverable state rather than drop text or bypass limits. Mute/End remain immediate.

For direct adapters, compact older dialogue into a concise summary plus recent turns and structured task state, using provider-native compaction where verified. Treat tool-call/result pairs as indivisible; do not summarize away IDs required to continue a tool loop. Summary failure keeps the old context or pauses submission with a factual error; it does not silently discard history. Background compaction requests stay within the selected provider and budget. Provider-enforced voice-session limits may still require reconnecting; preserve the same logical conversation without replaying speech.

Acceptance: force a small test context budget, trigger compaction, interrupt/reconnect during it, then ask about earlier names/constraints/corrections and outstanding work. Verify real task state and no duplicate send. A compressed summary may lose detail; exact original text remains retrievable from history when needed.

## 6. Voice and providers

Build one useful vertical slice first: mascot home + continuous voice + Hermes task delegation on lab. Compare OpenAI continuous voice and Grok native voice using actual device tests before choosing the default. Claude uses the selected speech layer with direct streaming task responses. Existing official-provider feasibility/authentication references remain in the [product plan](2026-10-15-desk-continuous-conversation-v1.md#4-direct-providers-in-v1); do not assume consumer subscriptions supply every API or voice entitlement.

The speech arbiter orders acknowledgement, result and correction output. Start work concurrently with a relevant acknowledgement; omit filler if the answer is ready. Speech callbacks and played offsets drive the mascot's mouth and expression. An interruption stops audible output and fences unplayed text; it does not cancel an agent unless the user requests cancellation. Deep reasoning never streams as spoken internal thought.

Provider adapters need capability discovery, normalized accepted/final/result/error events, cancellation semantics, usage and history/compaction behavior. Authenticate broker routes explicitly even on Tailscale. Keep long-lived keys on lab and send scoped/short-lived voice credentials to the tablet where supported. A provider/account switch waits for a safe boundary and cannot automatically reexecute in-flight work.

## 7. Camera implementation

Use a lifecycle-bound CameraX preview and selected image analysis. A latest-frame backpressure strategy is available in CameraX; always release analyzed frames and keep image encoding/network work off the UI thread. [CameraX analysis](https://developer.android.com/media/camera/camerax/analyze).

Start with at most one selected analysis frame per second, not one model request per sensor frame. Give each frame a conversation binding, timestamp and destination; drop superseded frames before upload. Still capture has preview/cancel semantics. Stopping sharing cancels queued uploads and closes camera ownership. On provider switch, stop sharing until routing/consent is clear. No continuous video archive and no simulated claim of current sight from stale frames.

## 8. Build order and reviewable checkpoints

| Step | Concrete changes | Required evidence |
| --- | --- | --- |
| R0: Baseline and contracts | Record installed APK/configuration, protect concurrent source work, define neutral identity/state/events; verify lab voice/compaction capabilities read-only | Existing Desk tests identified; compatibility/migration notes and capability matrix |
| R1: Art and static stage | Layered mascot source, rig/pivots, pose board; home/sheets responsive layout | Approved mascot silhouette/material, static captures for home/listening/speaking/decision/mute at Lenovo and narrow widths |
| R2: Motion director | Blended poses, blink/antenna/hands, energy-driven mouth and deterministic gesture captures | Interruptible state transitions, reduced motion, no ticker when hidden; measured tablet frame pacing |
| R3: Orchestration and compaction | Neutral conversation/task state, journal, native compaction adapter, idempotency/reconciliation | Forced small-context tests, PENDING/UNKNOWN/ABORTED cases, no history wipe or duplicate action |
| R4: Continuous voice vertical slice | Shared realtime transport, speech arbiter, first-run/autostart/mute, authenticated lab delegation | Real spoken follow-ups and correction during task; no periodic tones, echo loops or leaked mic owner |
| R5: Direct providers | OpenAI, Claude and Grok task adapters; Grok voice; provider/model/thinking UI; ChatGPT sign-in feasibility isolated | Each enabled provider completes a multi-turn/tool/image-capability test and reconnect/compaction scenario |
| R6: Vision | Camera permission/preview/still/live frame selection and routing | Correct frame-grounded answer, stop/cancel/switch/offline behavior and acceptable heat/frame pacing |
| R7: Integrated release | Error/usage polish, migration, accessibility and sustained Lenovo acceptance | Debug APK installed with exact serial/checksum, no data reset, evidence record and rollback APK |

R1/R2 should be reviewable on the tablet before adding every provider. They are visual checkpoints; do not label them seamless voice delivery. R3 and R4 integrate before the complete speech experience is accepted. All requested providers and camera remain V1 scope, with unavailable credentials/access shown honestly rather than replaced by fake connected states.

### Validation by risk

- Pure Kotlin: task/turn ownership, speech ordering, stale events, compaction transitions and ambiguous delivery.
- UI/render: no persistent chat pane, every semantic pose, large fonts, sheets, decisions, portrait/landscape/narrow windows and reduced motion.
- Transport/provider: capability/auth boundaries, final versus partial transcripts, scoped delegation, cancellation, quota and context-limit behavior. Use synthetic accounts/data for fixtures.
- Physical Lenovo: actual authorized speech, ten minutes of quiet idle, speaker echo, acoustic interruption, mute/relaunch, focus/headset/lock, camera heat, and two-hour conversation with forced compaction/reconnect.

Keep timing goals from the product plan (roughly 0.5–1 s acknowledgement, 200 ms detected-interruption stop) as targets to measure, not promises. Report first useful answer independently of acknowledgement and split cold/warm p50/p95. A faster nod cannot count as lower spoken-answer latency.

Build/check the affected modules and assemble/sideload the debug APK. Run phone regression when shared audio/transport changes; do not repeatedly rerun unaffected suites for documentation/art-only edits. Verify installed APK identity, saved lab/profile/session, mute/camera state and migration. Do not clear app data for testing without preserving the user's setup/history.

## 9. Deployment and completion

No plugin deployment is required for this plan itself. A future broker implementation must follow existing versioned/authenticated routes and AGENTS.md. If it changes mobile tools, notification stream or rooms behavior, ship skill/prompt/guard updates together to lab and raj. Do not modify the phone's global speech configuration or host-wide model/compaction defaults to make Desk tests pass.

The complete redesign is delivered only when the mascot-first APK on Lenovo supports sustained natural voice, verified task handoff, compaction, direct enabled providers and explicit camera sharing with recoverable state. Keep the prior working APK for rollback; the user's backend conversations remain intact.
