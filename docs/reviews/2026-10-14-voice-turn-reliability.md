# Voice turn reliability and streaming preparation

Voice now rejects recognition callbacks from earlier listening attempts, including retries within the same conversation. This prevents a duplicate transcript from submitting a new question after playback, a delayed error from ending the next turn, and a stale processing event from starting the wrong timeout. Canceled host recordings returned after cancellation are erased before they can be uploaded.

The installed app still uses System speech. Host speech remains disabled, and real spoken recognition and conversation tests remain deferred at the operator's request. The restored rich-text composer and navigation are unchanged.

## Implementation and tests

`VoiceStreamEngine` assigns a generation to each listening attempt and invalidates it before stopping capture, accepting a result, retrying or releasing the conversation. Partial text, activity, processing, errors and results must belong to the current attempt as well as the current conversation. `HostSpeechInput` now wipes returned audio on its stale-operation exit path.

Five new regression tests failed before these fixes and pass afterward. They cover duplicate transcripts after playback, late permission errors, late processing events, results during the retry delay, and recording cleanup after cancellation.

The full Android/JVM suites pass: 431 unique tests with zero failures. Module counts are app 91, data-remote 75, core-model 1, feature-chat 15, feature-threads 8, data-local 21 and domain 220. Release lint reports zero errors, 48 existing warnings and two informational notices. Debug, instrumentation and minified release APK builds pass. Release signing uses the repository's debug-key fallback because no release keystore is configured.

## S22 validation

`VoiceTurnDeviceTest` passed on the connected SM-S901E in 0.018 seconds. It runs the production engine on Android's main thread with scripted speech input/output and audio ownership. Two synthetic questions each receive one synthetic answer; the engine returns to listening, rejects delayed callbacks from the first turn, and releases its owner on stop. This test uses no microphone, playback, network prompt or settings mutation and requires no operator unlock. It validates software lifecycle behavior rather than real recognition or speaker routing.

Installed debug APK SHA-256: `42b9140b3ae201f1c72f8aa00b4c0d976362d05e86f5ce6ccca44185b94dd8bc`. The on-device checksum matches. The instrumentation package was removed and the normal app was relaunched after validation.

Evidence and APKs are saved in `/private/tmp/companion-voice-turn-20261014/`: `verified.apk`, `rollback.apk`, regression/build/device logs, copied test results and `validation.json`. The rollback APK is the previous navigation build, SHA-256 `9230f700193aea5c282bb007b6556a16cd71541139798c59b9156cca8c1aee55`. Restore it with `adb -s <current-S22-serial> install -r /private/tmp/companion-voice-turn-20261014/rollback.apk`; this preserves app data. This increment adds no database migration or plugin deployment.

## Next streaming increment

Lab's installed source at revision `4569bb8d21f` contains `/api/audio/speak-stream`, matching Conduit's incremental text and signed-int16 mono PCM exchange. The host defers its start frame until the provider's actual sample rate is known and can emit fallback if synthesis fails before producing audio. The inspected source does not contain Conduit's `/api/audio/transcribe-stream`. Neither finding is a runtime handshake or provider acceptance test. The sanitized [contract fixture](fixtures/2026-10-14-lab-speech-stream-contract.json) records the distinction.

Next, implement authenticated host/profile-scoped speech-stream transport and bounded PCM playback. Validate malformed frames, rate changes, queue saturation, slow/disconnected providers, cancellation and deterministic teardown. Allow whole-file synthesis only before PCM has been received, so fallback cannot replay a partially spoken reply. Retain whole-file transcription while live transcription support is negotiated. Keep provider changes explicit and streaming disabled until its automated and device gates pass. Actual spoken recognition, route/echo tests and a complete conversation still require operator participation.
