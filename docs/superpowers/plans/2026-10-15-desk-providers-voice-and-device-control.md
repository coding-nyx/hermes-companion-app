# Desk Companion: providers, natural voice and device control

Updated 15 October 2026. Continues the separate Android 16+ Desk Companion app, approved robot/Desk Perch layout and agent-only camera sharing. Normal compaction remains acceptable. No new provider API keys.

## Delivery boundary for 0.3

| Area | Implemented | Remaining |
| --- | --- | --- |
| Hermes | Existing host profiles, conversations, approvals, model selection and configured speech | Shared control tools in the Desk UI |
| ChatGPT | Native browser OAuth/PKCE, identity verification, encrypted accounts, token renewal, account model catalog, streamed Responses, local threads and attached camera frames | Real-account consent and inference acceptance; native ChatGPT audio is outside the current OAuth grant |
| Grok | Native tablet conversation loop and history; lab broker attaches/renews its existing Grok OAuth credential; streaming Responses and current model catalog | Independent tablet Grok account registration; tools and audio through this broker |
| Speech | Hermes or Android speech independently of the conversation provider; speaker test, start-on-open preference, pacing/sensitivity controls; silence/noise-only input resumes capture | Full duplex, acoustic interruption, local wake phrase, live route/level calibration |
| Presets | Open browser and open tablet settings, by explicit button or an exact spoken/typed phrase | Reading app content, gestures, computer targets and configurable workflows |

The Grok broker is separate from the Companion plugin. It exposes only `/desk-grok/v1/models` and `/desk-grok/v1/responses` on a private Tailscale bind, port 9121. Every call validates the existing dashboard session against loopback port 9119; provider tokens stay on lab. The installed Hermes xAI OAuth adapter owns credential selection/renewal. There is no second Hermes agent loop, tool execution or transfer of provider credentials to Android. Service: `hermes-desk-grok.service`; selected credential home on lab: `~/.hermes/profiles/ash`.

ChatGPT uses the documented open-source Sign in with ChatGPT flow, a stable per-installation host ID and issued client registration per account/workspace. The callback listener binds only to `127.0.0.1` on an ephemeral port. Each attempt has fresh PKCE, state and nonce. ID tokens require RS256 verification using the pinned issuer’s JWKS plus issuer/audience/expiry/nonce checks. Granted plan scope is mandatory. A cancelled sign-in cannot save credentials after cancellation. Refreshes serialize; sign-out attempts renewable-session revocation and clears local tokens.

Native ChatGPT/Grok threads are encrypted on the tablet and isolated by exact provider origin, account identity and session ID. They are independent of Hermes threads and of the consumer apps’ histories. Each HTTP turn includes the saved text context with `store:false` and `stream:true`. No `previous_response_id`, silent history truncation or automatic retry of uncertain sends. Camera images are attached to the current turn only, then cleared from app-owned memory; past photo context is not retained or silently reconstructed. Provider retention is governed by its own service. Native compaction is not implemented; the app explicitly requires a new thread at its local history bound. Hermes retains its normal host compaction path.

## Product behavior to implement next

Home remains the robot, a short caption and voice controls. Connections, setup, history and control details stay on secondary surfaces. Keep a single Material dropdown chevron. Camera/screen previews remain hidden by default, with clear sharing indicators and Stop sharing.

Use three independent selections:

1. **Conversation:** Hermes profile, ChatGPT account or Grok account/broker.
2. **Voice:** configured host speech, supported native realtime voice, or a local/device fallback. An account’s text inference permission does not imply realtime audio access.
3. **Action target:** this tablet, paired phone, or named computer/browser. Provider switching never silently changes the action target or grants control.

Start-up setup becomes a short checklist: connect account → choose and test voice → select language/output route and tune microphone → choose conversation start behavior → optionally add a device target. Show Ready/Needs sign-in/Host unavailable/Permission needed with a concrete recovery button. Do not expose token strings, model protocol names or service internals in the main companion view.

## Voice state machine and wake phrase

Implement a dedicated audio owner instead of running wake recognition alongside the existing `VoiceStreamEngine`. States:

`Off → Wake listening → Conversing → Hearing → Understanding → Replying/Working → Conversing`

`Muted`, `Audio focus lost`, `Locked` and `Error` are explicit states with no hidden restart. **Mute turns off every microphone path, including wake recognition.** A separate “Rest; wake by voice” action leaves only the deliberately enabled local wake detector running. The UI must say when it is listening for a wake phrase. Ending voice means no capture until another explicit start, unless the user selected wake listening as their end behavior.

Begin with foreground/docked wake listening, for example “Hey Hermes.” Evaluate an embedded offline keyword engine with a redistributable model: candidate Vosk constrained grammar vs an Android ONNX keyword implementation. Benchmark on the actual Lenovo before selecting one. Do not loop Android `SpeechRecognizer` as an always-on hotword engine: service availability, network behavior, resource use and tones are outside the app’s control. Do not ship a wake UI backed by a timer or fake trigger. Background/locked-screen support is a separate milestone requiring Android microphone foreground-service/default-assistant-role analysis and visible system indicators; ordinary app launch must not promise privileged always-on hotword access.

Wake acceptance gates: 20 natural owner trials at desk distance, TV/assistant-playback negatives, quiet/noisy rooms, foreground/background/lock transitions, battery/temperature and audio-focus loss. Re-detection while the assistant is speaking cannot wake a second session. A locally retained short pre-roll can preserve “Hey Hermes, open my browser” as one utterance; never upload the dormant wake buffer. Allow a visual wake indication without beeps.

## Seamless conversation and acknowledgement

Replace stop-record-transcribe-play with continuous app-owned PCM capture, local VAD and an interruptible playback queue. Use one microphone owner, bounded buffers and generation fences for provider/account/session/target changes. Probe hardware echo cancellation and Android audio routes. Evaluate speakerphone and headphones independently. Keep a fallback half-duplex mode if acoustic echo cannot be controlled.

There are three distinct events:

- **Heard:** final utterance boundary detected; animate acknowledgement immediately and show the short transcript if captions are enabled.
- **Understood:** a fast model gives a contextually appropriate short response. “That sounds frustrating—let me check” is valid only when justified by the utterance and the action being started.
- **Doing/done:** grounded in real tool-start and tool-result events. Never say mail is open, a message was sent or a browser page was read before evidence exists.

For requests requiring work, the conversation model produces one short acknowledgement while the selected task model executes. Serialize the audio queue so the worker’s answer replaces or follows the acknowledgement without replay. Skip acknowledgement when the final answer is already ready. Do not inject canned emotional responses on a timer or launch a second independent conversation that lacks the current context. Cancellation must stop queued audio and the matching task, and retain an explicit uncertain-action record when a remote operation may already have executed.

Acoustic barge-in first stops playback on credible near-end speech, then cancels/steers the owned response as appropriate. Echo from the robot’s own voice cannot be submitted as a new user turn. Test mid-sentence interruption, pauses, changing one’s mind, long tool execution, muted input during playback and reconnects. Target measured acknowledgement within 0.5–1.0 s after utterance end when a fast path is available; report actual median/p95 STT, first text, first audio and interruption-stop times rather than promising unmeasured latency.

## Device-control architecture

Introduce a provider-neutral `DeviceActionBroker` shared by Hermes, ChatGPT and Grok adapters. The model requests a typed action; the broker checks target, granted capability, current arm/presence state and action policy. The existing Hermes `mobile_*` guards remain authoritative when that route is used. A provider login never grants device access.

Every action record carries: action ID, conversation binding, target ID, permission/arm lease, operation, arguments, timestamp, expiry, cancellation state and verification result. Responses distinguish `completed`, `blocked`, `cancelled`, `expired`, `failed` and `uncertain`. Read/page results include freshness and target identity; changing a tab/device invalidates prior coordinates and snapshots. Require a fresh snapshot after navigation before a gesture. Deduplicate by action ID; do not automatically replay unknown writes after a reconnect.

| Target | Read path | Act path | Setup |
| --- | --- | --- | --- |
| This tablet | Explicit Android single-app MediaProjection; supported accessibility tree | Allowlisted intents first; later accessibility actions/gestures | Separate screen-share consent and optional accessibility + Arm |
| Paired phone | Existing Companion mobile snapshots/trees | Existing `mobile_*` tools | Device selection, pairing, protection rules, `mobile_arm` before control |
| Computer browser | Paired browser extension returns active page URL/title/DOM and optional screenshot | Extension semantic actions; computer agent fallback when needed | Select browser/tab, private authenticated pairing, per-site scope |
| Computer apps | Paired host agent with accessibility/screenshot support | Target-specific app automation | Explicit named machine/app, capabilities, user-presence checks |

For “look at my browser”, first resolve the configured browser target. If two targets match, ask a short spoken clarification. If sharing is unavailable, explain the needed setup; do not substitute the tablet’s camera or pretend to see a page. Android screen capture requires platform consent for each projection session and a mediaProjection foreground service. Never bypass secure windows. Browser DOM, page text and screenshots are untrusted content, not action authorization.

Existing local presets bypass the model only for exact deliberate user utterances and buttons. Model replies, quoted text, substrings and chained commands cannot trigger them. They currently launch an app and end this screen’s microphone/camera session. Maintaining voice while another app is foreground needs the next audio-service milestone.

## Preset catalog

| Preset / example utterance | Target and steps | Approval/result |
| --- | --- | --- |
| Open browser | This tablet; Android default-browser launch intent | Implemented; launch acceptance, no claim of reading a page |
| Open tablet settings | This tablet; Android Settings intent | Implemented; no settings are modified |
| Open my work browser | Saved computer + browser profile, launch/activate | Planned; verify active target and app |
| Look at this page | Selected browser; capture fresh URL/DOM and optional screenshot | Planned; read scope, short acknowledgement then grounded answer |
| Find something on this page | Current tab; page search/highlight | Planned; verify query and match count |
| Open a saved app | User-configured package/application ID | Planned; allowlisted launch, no arbitrary intent extras or shell string |
| Do this in that app | Resolve app + task, arm target, inspect, execute bounded actions, verify | Planned; clarify ambiguity; writes follow existing action/presence rules |
| Take a look / stop looking | Explicit camera or screen-share target | Camera controls implemented; screen-sharing and voice intent integration next |
| Rest / wake up | Local wake-listening state / resume the same conversation | Planned; wake alone cannot approve an action or unlock a device |
| Stop / mute / cancel that | Stop audio, input and/or currently owned task as specified | Existing buttons; universal spoken interruption after continuous audio is ready |

Do not make a preset for sending messages, purchases or deleting content run unattended by default. Preserve existing explicit wording/presence requirements when those actions are requested. “Open browser” needs no confirmation loop; acting on an ambiguous or externally consequential target may require clarification or the existing policy gate.

## Execution order and acceptance

1. **Provider release:** ship ChatGPT OAuth and Grok broker, independent encrypted local history, native model selection, speaker setup and launch presets. Validated a real Grok response to an artificial red image and Hermes restoration on Lenovo (four tablet acceptance tests passed). ChatGPT real-account completion waits for the user’s own sign-in and consent; fixture tests are not claimed as live account acceptance.
2. **Audio service:** one capture/playback owner; route check, level meter, sample recording/playback, language/voice chooser, recovery diagnostics; no periodic service beeps. Preserve consent and disabled states across rotation/reconnect.
3. **Foreground wake:** embed and benchmark the chosen detector, same-session resume and local pre-roll. State indicators and an immediate microphone-off control are mandatory. No hidden camera start on wake.
4. **Tablet/browser read:** MediaProjection onboarding and fresh single-app frames, then a paired browser extension for semantic page context. No control permissions are requested just to view the robot.
5. **Controlled actions:** provider function-call adapters into the typed broker, saved device targets, explicit Arm, common browser/app presets and action verification. Update canonical plugin skill + prompt/armed hint + broker guards together if `mobile_*` behavior changes, and deploy those plugin changes to lab and raj together. This release does not modify `mobile_*` or the plugin.
6. **Natural full duplex:** fast acknowledgement and task-model delegation, acoustic barge-in and measured latency. Advance only after real-room tests, not synthetic TTS fixtures alone.

Regression coverage: cross-account/session fencing, cancelled OAuth callbacks, expired tokens and plan decline, stream failure after text has begun, no auto-resend, no unprompted mic/camera activation, explicit camera/screen off on disconnect/background/lock, unrelated app/phone unaffected, and no model-generated preset execution.

## Primary references checked

- [OpenAI: registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [OpenAI: models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [OpenAI: accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [OpenAI: preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Grok Build: account authentication and enterprise operation](https://docs.x.ai/build/enterprise)
- [Grok Build: headless/ACP integration](https://docs.x.ai/build/cli/headless-scripting)
- Installed official Hermes source on lab: `hermes_cli/proxy/adapters/xai.py`, `hermes_cli/auth_xai.py`, [upstream repository](https://github.com/NousResearch/hermes-agent)
- [Android: MediaProjection consent and service lifecycle](https://developer.android.com/media/grow/media-projection)
- [Android: SpeechRecognizer lifecycle/resource limitations](https://developer.android.com/reference/android/speech/SpeechRecognizer)
