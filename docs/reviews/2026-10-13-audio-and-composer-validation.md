# Companion audio and composer validation

The compact composer described below was subsequently rejected and reverted. The previous visible formatting toolbar, field settings and separate actions are restored; audio fixes remain. The [current parity backlog](2026-10-13-current-conduit-parity-backlog.md) describes the latest implementation state. The composer checks below remain a historical record of the rejected build.

The installed Android increment makes thread composition text-first and gives foreground voice explicit audio ownership and cleanup. Native host speech adapters are implemented and tested independently of the Companion plugin. Classic conversation still uses Android speech recognition and text-to-speech; native realtime voice and incoming agent calls remain later milestones.

## Text first composition

The default composer now presents an attachment entry, a broad multiline message field, and one primary action. An empty field offers dictation; text or an attachment offers Send. During a running reply, content offers a follow-up, and an empty field offers Interrupt. Creating and sending states disable repeat submission and show progress. Button descriptions identify the action to accessibility services.

Formatting, slash commands, dictation, and model controls remain available through secondary controls. Formatting starts collapsed and resets on thread change. The field grows to six lines, preserves multiline entry, and retains keyboard send shortcuts. Existing draft-preserving dictation and durable delivery states remain in place.

This follows the requested WhatsApp/Telegram interaction emphasis without copying either product's visual styling. The broader end-to-end design review remains the final roadmap phase, with UX and readability weighted highest. Composer rendering and semantics pass automated checks, including a message field wider than 60% of the test viewport. On the S22, typing changes the action from Dictate to Send, the field remains visible above the keyboard, and the attachment menu exposes formatting, commands, dictation, and models. The synthetic draft was cleared without sending a message.

## Foreground voice changes

- A single classic voice owner requests transient audio focus, selects communication audio, and provides Speaker or Auto routing. It refuses startup while another call owns the audio mode. Ending voice clears its route, mode request, and focus.
- Microphone input stops before a recognized turn is submitted. Only that voice turn's reply enters playback; an ordinary typed response while listening cannot unexpectedly become speech.
- Playback completion returns to listening, including an empty reply arriving before TTS initialization. Playback failure, lost audio focus, and disconnected output stop the session and release its resources.
- Session generations reject callbacks from stopped sessions. Silence uses an absolute deadline across recognizer retries, and teardown cleans up even after the ViewModel scope is canceled.
- Leaving the foreground stops voice and dictation. Navigation and profile changes also stop voice. Failed or uncertain delivery stops conversational voice instead of silently continuing.
- Dictation errors now explain no recognized speech, denied microphone permission, unavailable recording, network failure, or a busy recognizer instead of presenting only a numeric speech error.

This remains foreground, half-duplex conversation. Speaking over the assistant is not an implemented acoustic interruption detector. The system recognizer may use its configured remote service; this increment does not establish a local-only speech guarantee.

Android requires applications to clear communication routing when finished, and applications targeting Android 15 or later can obtain audio focus only while the top app or an eligible foreground service. These constraints support the explicit foreground owner used here. [AudioManager routing contract](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)), [Audio focus guidance](https://developer.android.com/media/optimize/audio-focus). Continuous listening requires a different capture strategy from repeatedly reopening the system recognizer. [SpeechRecognizer contract](https://developer.android.com/reference/android/speech/SpeechRecognizer)

## Native host audio and call readiness

The transport now supports native `/api/audio/speak`, `/api/audio/transcribe`, and `/api/voice-live/status`, scoped to the active profile. Speech responses must contain a valid audio data URL; upload and decoded response sizes are bounded. A successful empty transcript represents silence. Authorization and provider errors remain errors; these routes never silently fall back to the plugin.

Provider configuration and keys stay on the host. The app does not consume `voice-config`, which can contain secrets. The Gateway view reports host realtime readiness and explicitly says incoming agent calls are not available.

Lab, profile `knight`, reported Hermes 0.21.5. Native realtime readiness was unavailable because no OpenAI API key was configured. Setting a key alone would not finish this feature: Android WebRTC transport, call lifecycle, interruption handling, and incoming-call delivery still require implementation. No provider keys were changed, no realtime session was requested, and no telephone call was placed.

## Verified results

| Check | Result |
| --- | --- |
| Automated app, transport, storage, domain, chat and thread suites | 384 tests pass, zero failures or errors. |
| Debug app and instrumentation APKs | Build successfully. Updated main app and test APK installed on the S22, preserving app data. |
| Voice engine lifecycle regressions | Nine tests cover start/stop races, stale callbacks, typed-reply isolation, retries, silence, audio ownership failure, empty playback, and canceled-scope teardown. |
| Native audio contracts | Five tests cover request shape, silent transcript, malformed audio, non-mutating readiness, and authorization failure without plugin fallback. |
| Native provider roundtrip from the phone | Passes through the temporary native dashboard connection, using the app's adapters. Host speech provider is Edge; transcription provider is local. |
| Host speech roundtrip | “Hermes audio test. One two three.” produces 25,920 bytes of MPEG audio; local transcription returns “Hermie's audio test 1, 2, 3”. Transport works; recognition is imperfect. |
| Real Android TTS initialization with an empty feed | Completes successfully on the phone. |
| Physical microphone capture | Passes: Android initializes mono 16 kHz PCM recording, returns samples, reports the client unsilenced, and stops cleanly. Samples are discarded; this does not establish spoken recognition accuracy. |
| Speaker route and classic voice lifecycle | Passes: Android reports the built-in speaker, transitions through thinking and speaking, returns to listening, and restores normal audio mode after teardown. The operator confirms the phrase was clear from the phone speaker. |
| Spoken recognition | Initial attempt returns Android error 7, no speech match, despite the operator reporting speech after the instruction. A follow-up with a ready beep was prepared but skipped at the operator's request. Accuracy and the reason for the missed utterance remain unresolved. |
| Composer on the S22 | Passes: keyboard layout, primary-action switching, collapsed secondary controls, and draft cleanup checked through ADB and a device screenshot. |

Four automated device tests pass: three local audio checks and one native provider roundtrip. Initial startup checks encountered keyguard before audio capture. The test activity now waits for normal user authentication and keeps its foreground screen awake; it does not dismiss a secure lock. A test-runner signature issue in the native test was corrected before the passing native run. No spoken-recognition pass is claimed.

## Installation and retained scope

The final APK includes readable recognition errors and was sideloaded after the passing local audio run; the app's 54 unit tests pass again after that small change. The other passing suite results remain valid, totaling 384. The installed package checksum matches the APK: `103556d68013b41ac897853daf42f6428e2edb6b7ed61980883254fdf5ee29a1`. The connected phone is a Samsung S22, SM-S901E, Android 16. Audio test samples are discarded, and the spoken recognition test never submits a Hermes prompt.

Phone control was disarmed after UI checks. The temporary SSH forward, ADB reverse, and instrumentation package were removed. The updated main app remains installed, with normal audio mode and no new AndroidRuntime crash after the final install.

No plugin behavior was changed or deployed in this audio/composer increment. Existing Hands, notification streaming, rooms, and other plugin-backed features remain installed. Native audio adapters are the next dependency reduction step; switching runtime voice to host providers should follow capture/playback and failure-path device validation.

## Next implementation sequence

1. Resume spoken recognition when the operator is available, diagnose the no-match result, and check headset/Auto routing plus foreground interruption. Speaker playback, microphone capture, and teardown have passed.
2. Add explicit System and Host speech selection, then connect native host recording/transcription and playback to the existing voice turn lifecycle. Preserve drafts and expose provider failures and cancel states.
3. Implement Android realtime voice using short-lived host-issued session credentials, actual audio capture/playback, and an explicit call state machine. Validate reconnect, mute, interruption, and route changes before background calls.
4. Add durable host call invitations and Talk notifications, then Android Telecom ringing and answer/decline lifecycle.
5. Continue native capability migration gradually; retire the plugin only after required phone and host features work on both hosts.
