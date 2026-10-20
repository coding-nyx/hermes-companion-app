# Gateway and notification integration audit

Date: 2026-10-20
Base inspected: `0e43d97ee63a44e74f63aaafbfd4f4bd759a695d`
Scope: local source and tests. No connected Hermes host, account, phone, or live notification topic was contacted.

## Executive result

The app already has a substantial native Hermes chat client. Preserve it rather than replacing it with a speculative gateway API. The redesign should make its three independent connections understandable:

1. **Native Hermes**: profiles, session catalog/history, streamed chat, decisions, per-session model controls, and native telemetry.
2. **Optional Companion relay**: paired phone tools, phone-to-agent notification streaming, custom agent rooms, and coding-agent console.
3. **Incoming notification topic**: ntfy messages from a configured publisher. This is not automatically equivalent to native chat push, and it is unrelated to Android Notification Access.

The current implementation adds reliable topic reconnection and local alerts from the connected native gateway without enabling new permissions, installing server services, or changing voice rollout gates.

## What the checked-in client actually supports

| Area | Implemented contract | Important boundary |
| --- | --- | --- |
| Native origin | Usually dashboard port `9119`, or its configured HTTPS reverse-proxy prefix | Do not require API-server port `8642`. A phone cannot reach a host's loopback address directly. |
| Relay origin | Bundled `hermes-plugin/relay.py`, usually `9120` | It can proxy the dashboard; standalone mode is a separate compatibility dialect. The archived prototype and separate mailbox projects are not protocol substitutes. |
| Capability discovery | `GET /api/status`; independent `GET /companion/health` | Only a valid `relay: "ok"` establishes extension availability. Missing route/HTML means unavailable on this connection; auth/transport/malformed responses remain unknown. |
| Native authentication | Password login, host/prefix-scoped cookies or loopback bootstrap token; gated `POST /api/auth/ws-ticket` | Ticket is single-use. Never reuse it, share credentials across hosts/prefixes, or switch gated sessions to loopback-token authentication. Native OAuth remains unfinished. |
| Live connection | `/api/ws?profile=…` with ticket or permitted token; newline-delimited JSON-RPC | `gateway.ready` precedes use. Heartbeats only when advertised. `SyncManager.startWatch` reconnects and reconciles. |
| Thread rail | Union of `session.list` and paginated `GET /api/sessions?profile=…` | Neither source alone is exhaustive. Preserve `(host, profile, session)` ownership and locally retained lazy-created sessions. |
| New chat | Native `session.create`; optional idempotency key | Retry without the optional key only after explicit unsupported-parameter rejection, never after timeout/lost acknowledgment. |
| History | `session.resume` plus `session.history`, REST fallback | Stored and live session IDs can differ. Native history is locally paged; do not add unsupported `limit`/`before` fields to its RPC. |
| Send | Native `prompt.submit` and event stream; explicit standalone/legacy compatibility path | Native submission does not accept arbitrary `model`, `client_id`, or `parts`. Stage native attachments separately. Do not automatically replay uncertain sends. |
| Turn state | Deltas, authoritative final-only messages, reasoning, tools, approval/clarify/sudo/secret requests and expiry | Final output can arrive without deltas. Decision expiry must match request identity. |
| Session changes | `PATCH /api/sessions/{id}` for title/pin/archive; native branch, steer, interrupt | Preserve captured conversation ownership and durable uncertainty tracking. |
| Session model | `model.options`, `config.get`, session-scoped `config.set` allowlisted to model/reasoning/fast | Show host-confirmed model/provider. Expensive-model confirmation and deferred/unknown outcomes already exist. Do not substitute a global model change. |
| Device lane | Paired Companion device registration and ticketed socket | Permission grants and arming are separate. New navigation must not automatically arm, grant, or repoint a stream. |
| Voice | System recognition/playback and existing explicit voice controls | Host speech, host streaming speech, and native realtime flags are all false. Provider-backed calls, incoming calls, and delegation are not ready for rollout. |

Primary implementation:

- [DashboardClient](../../data-remote/src/main/java/app/hermes/companion/data/remote/DashboardClient.kt), [GatewaySocket](../../data-remote/src/main/java/app/hermes/companion/data/remote/GatewaySocket.kt), [RpcCodec](../../data-remote/src/main/java/app/hermes/companion/data/remote/RpcCodec.kt).
- [ChatSessionManager](../../app/src/main/java/app/hermes/companion/ChatSessionManager.kt), [SyncManager](../../app/src/main/java/app/hermes/companion/SyncManager.kt), [SessionControlsController](../../app/src/main/java/app/hermes/companion/SessionControlsController.kt).
- [Operator protocol](../protocol/operator.md), [relay implementation](../../hermes-plugin/relay.py), [device coordinator](../../app/src/main/java/app/hermes/companion/DeviceNodeCoordinator.kt).

The older operator document's blanket prohibition on `config.set` predates the implemented, allowlisted session model/reasoning/fast controls. The current code and `NativeSessionControlsTest` are the narrower contract; this is not permission for unrestricted host configuration writes.

## Notification directions and actual producers

### Hermes to phone

`SyncManager.startWake` subscribes to saved ntfy topic URLs. `WakePolicy` recognizes:

- `approval.request`, `clarify`, `run.completed`, `error`.
- `room.quiet`, `room.paused`, `room.approval`, `room.mention`.

The in-repository ntfy producer is `RelayState._publish_wake`, invoked by the room controller when no phone watches a room. It publishes routing metadata, not transcript bodies, to registered devices' topics. The existence of other recognized event names is **not evidence that a native chat producer is installed**.

`WakeNotifier` maps events to user-tunable Android channels and builds a nonce-bound `hermes-companion://open` PendingIntent containing host, profile, session, and optional room. A user tap opens the intended conversation. External deep links continue through the existing confirmation path.

The native gateway bus now generates local approval/question/completion/error alerts for known sessions in the currently connected host/profile. The visible foreground conversation is suppressed; background or non-visible conversations can alert. Local routing uses the stored session ID and the authenticated watch's host/profile, never an event-supplied destination. Matching expired requests dismiss their native approval notice.

`NativeNotificationPolicy` deduplicates approvals by actual request ID and coalesces `Final`/`Completed` into one observed completion. Progress, a new observed local submission, or distinct final content establishes a new turn. An identical final-only reply with no identifiable new activity is indistinguishable from replay and remains suppressed. This is deliberately a bounded local reducer, not an exactly-once durable event system.

`CompanionMessagingService` still accepts only a nonce-bound debug FCM fixture; it is not a production push or incoming-call implementation.

### Phone to Hermes

Android Notification Access supplies `NotifStreamBus`. `DeviceNodeCoordinator` forwards explicitly enabled, filtered events over the paired device lane to the configured sink host/profile. The relay stores a bounded notification ring. Agent wake and explicit notification injection are separate server behaviors.

Changing the selected chat host/profile must not silently change this sink. The built-in stream suppressions, custom protection rules, pairing, and arm-first controls stay intact. Android notification-posting permission is not Android Notification Access.

## Reliability changes in this redesign

- EOF and retryable transport/HTTP failures reconnect with bounded 1–30 second backoff.
- Following delivery of an ntfy message ID, reconnect requests use its documented `since` cursor; a bounded per-subscription ID set suppresses replay duplicates.
- Open/keepalive envelopes do not become notifications. Multiline SSE data is assembled; incomplete EOF frames are not invented as complete events.
- Authentication/configuration HTTP failures, TLS failures, and cleartext-policy denials stop and surface a generic setup error rather than repeatedly probing or falling back to another service.
- Saving a different topic for a host replaces its previous subscription.
- Receipt no longer opens or changes a chat. Navigation is owned by notification taps/deep-link handling.
- Notification IDs include profile for sessions and use a distinct room namespace.
- Topic URL construction appends `/sse` to the path, preserving existing encoding and query parameters.
- Topic publishers cannot override the subscription's host to manufacture a trusted cross-host notification link.
- Topics now use versioned exact-origin ownership, including reverse-proxy paths. Active and inactive subscriptions consume only persisted exact-owner topics, never unsaved UI text. Legacy authority/global values are preserved but cannot identify their original path owner, so existing users must explicitly re-save each topic once; they are never automatically subscribed or registered. Separate dashboards on the same authority retain independent topics.
- Native connection status clears at the start of an owned watch and after both clean EOF and failures, without clearing another host/profile's handshake.
- A never-configured host no longer inherits the phone-global latest topic. Legacy unowned topic values remain stored but must be explicitly saved for a host before subscription or device registration.
- Native bus events now feed the tested, bounded local notification reducer before transcript-specific early returns. Alerts never contain transcript, approval command, or secret-request payloads.

ntfy's [subscription API](https://docs.ntfy.sh/subscribe/api/) documents message IDs, SSE framing, and `since` replay. Recovery still depends on server caching. The cursor is in-memory for the current subscription, not a durable delivery receipt or killed-process recovery guarantee.

## Remaining work, in safe implementation order

1. **Visible lane state**: model native connection, optional relay, topic configuration/connection, and outgoing stream separately. Do not label a configured topic as connected or an unavailable route as proof of installation absence. Keep unknown, connecting, connected, degraded, and blocked distinguishable in words as well as color.
2. **Connected native alerts acceptance**: the mapper is implemented in this change. Verify real-device permission denial, foreground suppression, ordinary completion/question/error events, notification taps, expiry, and reconnect. It covers known sessions on the active connected host/profile, not unknown runtime-only sessions or general background push. Replace heuristic final-only discrimination with stable host turn IDs only when an installed-host contract provides them.
3. **Native notification producer contract**: confirm supported installed-host event identity, cursors, expiry, and routing. If no server producer exists, implement and test a deliberately versioned extension instead of inventing a `/api/notifications` endpoint. Keep notification content minimal and fetch authoritative conversation/decision state after opening.
4. **Durable delivery**: define retention, replay, last-read state, and duplicate acknowledgment semantics. Restore subscriptions from an actual background owner after process recreation. Validate Android background/Doze behavior before promising reliability with the app closed.
5. **Production push/incoming calls**: separate design and authorization gate. Requires authenticated server enrollment, device routing, expiry, token rotation, notification permission, and real-device tests. FCM transport success alone is not a completed feature.
6. **Permissions and decisions**: retain quick access to local permission state and the active approval strip, but do not conflate them. A multi-session decision inbox needs server-supported pending-request enumeration and request ownership/expiry, not a locally fabricated global queue.
7. **Voice**: improve access to System voice now. Keep host/realtime controls disabled with an accurate reason until the existing spoken recognition, complete conversation, interruption, and lifecycle acceptance gates pass.

## Verification matrix

| Requirement | Automated coverage or next acceptance gate |
| --- | --- |
| Reconnect/replay/no duplicate topic messages | New `NtfyClientTest`: EOF, `since`, replayed IDs, keepalive, 503/429, denied/invalid topics, TLS/cleartext-policy errors, cancellation, consumer failure, multiline/incomplete frames, topic-local cursors. |
| Correct identity and URL behavior | `NotificationPolicyTest` adds distinct profile/session/room identity; `WakePolicyTest` adds encoded path/query, invalid URLs, publisher-origin overrides, and exact proxy-prefix routing. `HostScopedStoresTest` covers independent same-authority paths and fail-closed legacy topics; `GatewayWatchStatusTest` covers scoped handshake clearing. |
| Native local alerts | New `NativeNotificationPolicyTest`: both final-event orders, repeat suppression, new progress/submission, distinct final-only content, foreground suppression, request IDs, expiry, host/profile/session ownership, error upgrades, interruption, and unroutable owners. |
| Native session correctness | Existing `NativeChatIntegrationTest`, `NativeCreationRetryTest`, `NativeHistoryPagingTest`, `NativeDecisionContractTest`, `NativeSessionControlsTest`, and host-credential isolation suites. |
| Receipt does not navigate | Obsolete receipt callback is removed from `SyncManager` and its ViewModel construction. Device acceptance: while composing on host A, deliver a host B room ping; selection and draft must remain unchanged, then tapping it must route once. |
| Topic replacement | Device/integration acceptance: save topic A, replace with B, confirm A stops delivering and B delivers without restarting the app. |
| Permissions and background | Real Android acceptance still required for denied notification permission, permission revocation, Doze, background process death, notification tap after recreation, and accessibility/large-text behavior. |

The final task report must distinguish tests actually executed from these prescribed acceptance gates. No APK installation, host deployment, provider setup, or live delivery claim follows from this source audit.

### Executed notification checkpoint

At the 2026-10-20 audit checkpoint, `NativeNotificationPolicyTest` (12), `NotificationPolicyTest` (16), `WakePolicyTest` (9), and `NtfyClientTest` (8) all passed: 45 focused checks. The ntfy suite was rerun through Gradle with one worker after correcting exception-identity assertions for coroutine stack-trace recovery; the tests retain exact exception type/message, original-cause, and no-retry-count checks. Broader module, APK, and device validation results belong to the final implementation report.

The subsequent exact-origin topic migration and watch-status patch added three storage, three routing, and three watch-status regressions. Those new tests require the final coordinated validation run; the checkpoint above is not a claim that these later additions have passed.
