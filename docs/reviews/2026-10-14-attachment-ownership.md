# Attachment preparation ownership and memory bounds

Attachment preparation now belongs to its originating host, profile and conversation generation. A slow file read or failure cannot attach content to another thread or replace the new screen's error. Draft promotion to a stored session retains ownership; close/reopen or host/profile switching retires it.

## Behavior

- Read input incrementally and stop at the limit plus one byte: files 10 MiB, videos and source images 25 MiB. Oversized input is rejected before compression or cache-file creation.
- Serialize preparation to bound aggregate image memory. Decode image dimensions first, sample before allocating pixels, preserve the existing 1568-pixel output edge/aspect ratio, and recycle temporary bitmaps.
- Clear source buffers and delete unpublished staging files on cancellation, failure or stale ownership. Successful files remain attached to the originating draft.
- Block keyboard send while that conversation's files are being prepared, with a visible explanation. Typing remains available and the draft is preserved.
- Explicitly reject room attachments: the current room-post contract sends text only, so files must not appear to be sent successfully.

The restored composer, formatting controls, navigation and backend choices are unchanged. Draft persistence across navigation/process recreation, upload progress/cancellation and native room attachment support remain separate work.

## Validation

469 unique automated tests pass: app 120, data-remote 84, core-model 1, feature-chat 15, feature-threads 8, data-local 21 and domain 220. Release lint has zero errors, 48 existing warnings and two informational notices; the minified release build passes. Ten new tests cover ownership, bounds, serialization, draft promotion, late errors and valid image output.

The final S22 suite completed in 1.793 seconds with six passing checks and one explicitly pending live-provider scenario. Three attachment checks exercise the real ContentResolver/cache path, late-result rejection after switching conversations, and a generated 3840×1920 image staged as valid 1568×784 JPEG. Three streaming checks verify hardware drainage, cancellation/reuse and the isolated WebSocket-to-AudioTrack path. The streaming start callback now runs after the first successful hardware write, so status does not wait for a large frame to finish queuing.

These are backend/runtime checks using synthetic state and files. They do not claim a complete picker/navigation/keyboard review on the operator UI. No microphone, operator speech, chat prompt or settings mutation was used.

Installed APK version 0.1.0/code 1, SHA-256 `fbd09da66c99e4a55d5c53e9eaef9eff0e60731dd53f5bda736d165403c20148`, matches the on-device APK. Saved origin remains `http://100.85.151.99:9120`, profile ash. System is the rollout default; both native speech flags remain false. No database or plugin deployment changed.

## Evidence and remaining gates

Artifacts are `/private/tmp/companion-attachment-scope-20261014/`: `verified.apk`, `rollback.apk`, full build/test results, device log and `validation.json`. Rollback SHA-256 is `7ae9463a7e138673bddb8727a7b75bbcc11a6f22cef990d7ea1c7f15fc0669f7`; reinstall `rollback.apk` with `adb install -r` using the current S22 serial. Existing app data is preserved.

Lab's Edge provider still returns upstream 403; live native PCM acceptance remains pending, not passed. The relay's audio WebSocket upgrade was separately verified as HTTP 101 with a valid accept header, without sending speech text. This verifies proxy admission, not live synthesis. Real spoken tests remain deferred. Full conversation recovery, controls, authentication, realtime/calls, native host migration, plugin reduction and broader navigation/UX phases remain pending.

Temporary native SSH/ADB forwarding and the instrumentation package were removed after validation; the normal operator app was relaunched.
