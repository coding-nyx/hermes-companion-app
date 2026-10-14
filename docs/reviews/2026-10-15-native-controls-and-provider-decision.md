# Native controls and provider decision — October 15, 2026

The operator declined creating an OpenAI API key or configuring paid API usage. No OpenAI key was created or installed, no OpenAI billing change was made, and no account switch to the work account was completed. Native realtime rollout remains disabled. Existing System voice, chat and independent implementation are unaffected. Subscription voice was identified in the Conduit source; it has not been implemented or accepted in Companion.

## Implemented and validated

- Room conversation ownership captures host, profile, room, chat navigation epoch and generation. Late history, post/approval responses and deep-link lookups cannot update a subsequently selected conversation. Initial history merges newly posted rows. Encrypted room drafts are scoped by host/profile/room. Eight unit scenarios and eight S22 lifecycle scenarios passed.
- Native session model/provider, reasoning and speed controls read authoritative host results. Redirect and native command catalog/dispatch are implemented. Session mutation admission is durable in the separate recovery database; uncertain operations are not automatically replayed. Seven controller/Room device scenarios plus one real native lab workflow passed on the S22. The native fixture used an empty knight session and removed it afterward; no user prompt was submitted.
- Draft promotion and submit checkpoint ordering were corrected to prevent a consumed local draft from being resurrected. A newer destination draft is preserved.
- Firebase project `hermes-companion-nyx` and its Android registration are configured. The dedicated sender service account is restricted to FCM administration on that project. Its private credential is on lab with directory/file permissions 0700/0600; it is not packaged in the app. Client configuration is locally ignored. Firebase auto-initialization and analytics collection are disabled. An explicit debug fixture registered the S22, received a real FCM data message and cleaned its private token/receipt/notification. Both device assertions passed; receipt did not arm Hands. Production invitations, authenticated detail lookup and restart-safe callback watches remain unimplemented.
- The pinned `io.github.webrtc-sdk:android:150.7871.01` transport creates native audio/event SDP, exchanges typed SDP through the captured host/profile route, bounds event ingress and UTF-8 result chunks, and serializes JNI teardown. Three isolated S22 tests passed: repeated offer/release, local peer data-channel delivery/hangup, and permission-denial/malformed-answer cleanup. These checks use neither microphone nor speaker and do not contact a provider. They do not establish live-call, delegated-work or acoustic acceptance.

## Latest automated milestone

All 511 unit tests passed. Release lint passed and the minified release APK built successfully. Focused native realtime contract tests cover reverse-proxy paths, profile and SDP preservation, invalid answers, provider/auth failures without replay, transcript-free delegation events and Unicode-safe chunks. Rich-text composer controls remain intact.

The tested S22 debug APK has versionCode 1 / versionName 0.1.0 and SHA-256 `22d168a5d4dcd4ee023d564a37ed3f63e8ab12583e219dd5f919327099dd04cb`. Artifact: `/private/tmp/companion-remaining-20261014/webrtc-bounded.apk`. The immediately preceding Firebase-verified APK is retained at `/private/tmp/companion-remaining-20261014/fcm-verified.apk`, SHA-256 `d1b065e44176fced91a749b6cf700ed118fdcabdb19bcc4d2e655b9e7a2aa2f4`. The earlier voice-reliability rollback remains available. One streaming sideload failed while the build output changed; installation from a validated immutable APK copy succeeded. Future sideloads must use immutable copies.

An explicit false `NATIVE_REALTIME_ENABLED` build flag was subsequently added to the working tree; the current native transport is also not wired into a user-facing call entry point. Host speech and speech streaming flags remain false. Spoken recognition/full conversation tests remain deferred. Raj remains excluded.

## Navigation work awaiting its UI gate

Chats now selects the thread list when leaving a conversation. Connecting, switching profiles and entering Chats no longer open implicit blank composers. Voice remains an explicit action and can create its intentional local draft. New still creates a real host thread. An explicit “Continue unsent draft” entry restores local text/attachments for the exact host/profile. The new recovery-entry render test and existing draft tests passed; focused S22 testing of these changed navigation journeys is still pending. The previously accepted bottom bar and composer remain preserved.

## Still pending

Uncertain room delivery and unknown thread-creation outcomes; durable compression/late outcomes; live context hydration; richer approval queues and delegated work; upload progress/cancel/retry, image paste and expanded editing; richer transcript rendering; native-first setup, PKCE, refresh and Cloudflare credentials; provider-backed realtime UI/delegation; durable callback invitations and production FCM ingestion; Telecom; native groups/files/Git and remaining host management; plugin reduction; and complete navigation/readability/accessibility journeys. Existing plugin responsibilities are retained. No plugin deployment or route retirement was performed in this increment.

Evidence logs and rollback APKs are in `/private/tmp/companion-remaining-20261014/`; the directory retains the original task start date. Temporary phone fixtures were cleaned. Hardware/provider-dependent acceptance remains explicitly pending, not passed by inference.
