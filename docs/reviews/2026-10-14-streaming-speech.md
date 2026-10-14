# Native streaming speech implementation and validation

Native streaming synthesis and Android PCM playback are implemented behind `HOST_SPEECH_STREAMING_ENABLED=false`. Host speech also remains disabled. The installed operator app continues to use System speech and the restored composer.

## Behavior

The host-scoped client opens `/api/audio/speak-stream` with the existing native authentication mode: a ticket for gated hosts, or the accepted bootstrap token for ungated loopback hosts. Ticket requests are cancellable and bounded; tickets and credentials do not follow redirects. The upgrade retains the native client's cookies and network policy. Endpoint support is recorded separately for each host/profile as supported, unsupported or unknown; authentication and provider failures do not establish absence.

The adapter validates the negotiated mono PCM16 rate, frame ordering, frame sizes and terminal responses. Incoming frames are backpressured rather than spawning unbounded work. Playback has one writer and a two-second queue, clears discarded PCM, handles partial writes and waits for Android's playback head before completing. The device test identified Android's buffer-fill threshold as a problem for very short replies; playback now sets the API 31 start threshold explicitly.

Only explicit missing-endpoint responses or the native fallback signal allow whole-file synthesis, and only before receiving PCM. Network/authentication failures remain errors. Final text can revise unsent clauses; revising a sent prefix stops speech without replaying it. Capture, navigation, background and focus ownership still use the existing voice-generation guards. Whole-file transcription remains in place; live transcription, route-aware acoustic interruption and echo validation remain pending.

## Completed checks

- 459 unique automated tests pass, including 28 new streaming/PCM tests. Release lint has zero errors, 48 existing warnings and two informational notices; the minified release build passes.
- Three S22 checks pass in a 0.999-second suite: silent hardware drainage, cancellation followed by reuse, and an isolated native WebSocket contract through the production coordinator and real `AudioTrack`.
- The isolated fixture has no extension routes. It validates the native transport contract, not a production Hermes deployment with its extension physically removed.
- Debug APK version 0.1.0/code 1 is installed; SHA-256 `7ae9463a7e138673bddb8727a7b75bbcc11a6f22cef990d7ea1c7f15fc0669f7` matches the on-device APK.

Evidence, failed attempts and the accepted software-gate logs are retained in `/private/tmp/companion-streaming-20261014/`, along with `verified.apk`, `rollback.apk` and `validation.json`. Rollback SHA-256 is `42b9140b3ae201f1c72f8aa00b4c0d976362d05e86f5ce6ccca44185b94dd8bc`; reinstall that APK with `adb install -r` using the current S22 serial. No database migration or plugin deployment was made.

## Live provider gate remains pending

Lab accepts the native upgrade and returns `fallback` before any PCM for profile knight. The configured Edge provider currently fails: native whole-file synthesis returns HTTP 400 and the upstream Edge connection returns 403. The installed edge-tts version is 7.2.7 and matches the host's explicit dependency pin. No provider configuration, dependency pin or host source was changed, and no alternative provider was selected.

The live-provider device scenario failed before this condition was isolated. Those failures remain in the ledger. The final software suite explicitly leaves that scenario pending; its runner's `OK (4 tests)` includes one assumption-skipped live-provider test, so only three checks are claimed as passed. Re-run the live scenario with `validateNativeProvider=true` after provider readiness is restored.

Real host PCM playback, operator spoken recognition/conversation, Speaker/Auto/Bluetooth routes and echo/interruption must pass before the applicable flags are enabled. No microphone, operator speech, chat prompt or global audio-volume change was used in this increment.
