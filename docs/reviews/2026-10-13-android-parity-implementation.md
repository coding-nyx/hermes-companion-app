# Hermes Companion Android parity implementation

The first implementation focuses on the reported failures: messages not sending, NEW not creating a thread, and missing delivery or streaming updates. It adds native conversation controls while preserving the existing phone integration.

## Implemented behavior

- Native prompt requests use the actual Hermes contract. Unsupported model, client id, and parts fields no longer cause native RPC validation failures.
- NEW calls the host immediately. Draft promotion is guarded against repeated Send taps and uses a stable creation key for retries. Native Hermes may defer its database row until the first message; the app retains the returned host session in its rail.
- Delivery states distinguish queued, sending, acknowledged, failed, and uncertain inputs. Acknowledged inputs leave the outbox even if the provider later fails.
- Lost acknowledgments use a persisted baseline of durable user-row ids. A repeated older message is not evidence of a new send. Uncertain work is checked instead of automatically executed again.
- Failed sends remain in the outbox with an explicit Retry action. Check does not resubmit uncertain work.
- Final-only replies, authoritative replacement text, intermediate responses, errors, and reasoning deltas reach the UI. Reasoning has its own collapsible row.
- History loads carry navigation and host/profile guards. Late pages cannot erase a newly sent message or overwrite another conversation.
- Reopening a running native session restores its partial reply and busy state. The live watcher starts before outbox draining, reconciles on reconnect, and polls the catalog as a fallback.
- Native REST history pages use offsets. The client no longer expects an older page to contain the newer page's anchor.
- Native image/file staging replaces unsupported prompt parts. Staging failures are visible and retain the input for retry.
- Chat title actions provide rename, pin/unpin, archive/unarchive, and a non-destructive native branch. Typed input during generation steers the active turn.
- Pins are preserved in cache and sorted first. Sparse catalog patches merge into cached rows rather than erasing known fields.
- JSON strings correctly encode tabs and other control characters. Socket closing and overflow trigger recovery instead of silently dropping state.
- Dictation replaces one provisional span after the original draft. Final recognition no longer appends the partial transcript twice. Late recognizer callbacks are fenced after cancellation.
- Voice retries no longer restart the silence deadline indefinitely. TTS failures stop voice instead of leaving it waiting for playback.
- Room cache migration 6→7 preserves existing sessions and queued inputs.
- Native host telemetry is queried first. A legacy metrics fallback is allowed only after an unsupported native route and verified optional extension availability; auth/network failures do not switch services.
- Optional Companion integration is discovered separately from native chat. Missing routes, including an HTTP-200 SPA fallback, suppress room/peer fetching and unsupported room creation. Transient discovery failures remain unknown and can recover.
- Older native hosts that explicitly reject a branch idempotency key can retry without it. Lost responses and unrelated validation errors never trigger that retry.
- Terminal chat events prevent a stale running flag from reviving a completed turn during history catch-up, including a late page started before the final reply.

## Validation

All 369 tests in the targeted Gradle suites pass. These cover app orchestration, transport, local storage, domain policies, chat rendering, and thread rendering. A strict mock native server exercises the real Room outbox, WebSocket client, event codec, and chat reducer together, including first send, double taps, lost acknowledgments, disconnected replies, late history, and reopening a running session.

Additional checks cover standalone-relay attachment compatibility, JSON control characters, native offset pagination, final-only/error responses, separation of reasoning, delivery UI, dictation text, baseline persistence, and migration of a seeded version-six database without losing its queued message.

The debug APK is assembled and passed an Android 14 emulator installation/launch smoke check. It was subsequently installed on the connected Samsung S22 running Android 16 and tested through ADB against lab’s Hermes 0.21.5, including direct native access through a temporary SSH/ADB tunnel. Detailed device results and remaining checks are recorded in the [device validation report](2026-10-13-adb-device-validation.md). The raj SSH alias was not resolvable during the earlier review; no host plugin was changed or redeployed.

## Remaining parity

This is the chat/thread foundation and a set of native conversation features. It does not implement native OAuth, Cloudflare service tokens, the context meter, session reasoning controls, host STT/TTS, WebRTC live voice, incoming agent calls, native group migration, or the full bot/project/capability surfaces.

The plugin remains required for Hands, phone notification ingestion, custom rooms, and the agent console. Those services have not been removed or redeployed. Chat and threads can use a reachable native dashboard directly; the compatibility relay remains supported.

The final roadmap item is an end-to-end design review and redesign plan, prioritizing UX and readability across the complete app. It includes navigation, conversation/composer layout, delivery and recovery feedback, voice/call states, typography, accessibility, and real-device validation. Blocking usability issues found in earlier phases should be fixed when encountered.

The larger feature inventory and host migration sequence remain in [the architecture review](2026-10-13-conduit-feature-and-architecture-review.md).
