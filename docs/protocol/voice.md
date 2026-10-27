# Continuous voice

The phone has one application-owned `VoiceSessionController`. A non-exported,
non-sticky foreground `VoiceCallService` holds microphone/media ownership and a
renewed partial wake lock. The visible activity starts it after microphone
permission. Screens observe the controller; recreation and backgrounding do not
end the call. Call state, microphone readiness/mute, playback and agent work are
tracked separately. Notification actions are bound to the process nonce and call
ID; the lock-screen notification contains no conversation text.

A call pins the host, profile, persisted conversation and settings snapshot. With
no selected conversation it creates one through the existing submission path.
Changing the conversation or host ends capture. Calls never arm phone control or
approve pending actions. Process death, force-stop, microphone errors and audio
focus loss end the call. Reopening never starts capture automatically. An ended
call can be started explicitly in the same conversation with a new transport.

## Settings contract

The implementation ships in `hermes-plugin/dashboard`, mounted by Hermes’s supported enabled-plugin API loader. No Hermes core files are patched. All routes inherit native dashboard authentication and accept `profile`. The paths below are legacy relay aliases; their canonical prefix is `/api/plugins/hermes-companion/audio/`:

| Route | Contract |
| --- | --- |
| `GET /api/audio/settings` | Credential-free speech fields, schema choices, resolved providers, readiness and streaming/snapshot capabilities. |
| `PATCH /api/audio/settings` | `{"changes":{"tts.edge.voice":"en-US-AriaNeural"}}`; allowlisted mode, provider, model, voice, language and speed fields only. Returns confirmed settings. |
| `GET /api/audio/settings/voices?provider=edge` | Explicit provider voice catalog discovery; also supports configured ElevenLabs. |
| `POST /api/audio/settings/snapshot` | Returns an opaque, profile-bound call snapshot ID. |
| `DELETE /api/audio/settings/snapshot/{id}` | Releases the call snapshot. |

Fields come from Hermes configuration and dynamic provider schemas, including
declared custom providers. Credentials, commands and endpoints are excluded.
PATCH uses Hermes's existing configuration mutation lock, profile scope and
merge/save helpers. It preserves unrelated settings. `stt.provider=auto` removes
the explicit override to retain Hermes's native detection behavior.

STT, HTTP/streaming TTS and Live session creation accept the optional
`voice_snapshot` query parameter. Plugin-owned context-local config-read adapters span executor/producer
threads and contain only host-side speech configuration. Tokens expire after six
hours, are limited to 64 active snapshots and never cross profile homes. No raw
audio or snapshot is stored on disk. Saving settings affects future calls across
Hermes clients; an active call keeps its original configuration. Very long calls
must resume after snapshot expiration. Readiness checks configuration and installed
dependencies; an upstream outage can still prevent actual synthesis.

Older hosts returning 404/405 retain their configured speech endpoints and show
settings editing as unavailable. They cannot guarantee frozen host settings during
a call. Other errors fail visibly. The phone never requests full config or the
credential-bearing voice-config response.

## Hermes speech

One `AudioRecord` uses `VOICE_COMMUNICATION`, platform AEC/noise suppression when
available, adaptive local energy detection, 300 ms pre-roll and 600/900/1400 ms
endpointing (default 900). Capture continues through silence, transcription, agent
work and playback. Explicit mute stops recording; it fences queued/in-flight STT.
Audio is bounded to two minutes per utterance and cleared after transcription.
Empty transcripts are ignored. Host STT is followed by the ordinary Hermes agent
and full streaming TTS, with the existing explicit same-provider HTTP compatibility
fallback. Failure never switches to Android speech or replays received audio.

Credible near-end speech stops queued/audible output before STT completes. The
utterance retains its onset. Platform echo cancellation still requires physical
speaker/headset acceptance; synthetic tests cannot prove zero echo submissions.
Speaking over work steers through Hermes's existing path. Exact spoken stop-speech
controls stop output; explicit task-cancellation controls call native cancellation.
Conversational voice metadata replaces the preview wrapper. Chat retains the
authoritative answer while spoken text removes code/formatting.

## Realtime delegation

Native WebRTC transports microphone/playback and host GPT-Live owns acoustic
interruption. Host credentials stay on the host. A call becomes ready only after
both transport connection and the Live `session.started` event. Model/voice changes
apply at session creation on the next call.

Captions have event identity and timestamps. Delegations have identity and an
offset; a bounded ledger assembles relevant spoken context. Captions alone never
submit a task. A delegation waits briefly for usable context and is claimed in an
encrypted local journal before the existing submission path runs it. Verified
Hermes answer text returns with the original delegation ID. Approvals/tools/task
ownership remain with Hermes. The local text-only call transcript seeds later calls
alongside permitted visible conversation history and is erased on conversation
deletion. Disconnect stops capture and playback; explicit resume creates a new
transport without resending earlier commands/results.

Outbox rows persist call/turn identity, supported `surface`/`voice_context` metadata
and whether a submission steered existing work. Normal acknowledgement and
uncertain-delivery reconciliation remain in charge. Uncertain steering is never
retried as a new command. Late audio/callbacks are fenced by call/input generation.
The Room migration preserves existing queued messages and pins.

## Diagnostics and acceptance

`VoiceTimings` records microphone readiness, endpointing, STT, first Hermes text,
first audio callback and local stop duration separately, without transcript text.
Callback timing is not an acoustic interruption measurement. Required physical
acceptance includes sustained silence, locked screen, real speech/echo, wired and
Bluetooth routes, interruptions, approvals, long-running work, disconnect and phone
calls. Realtime acceptance additionally requires an operator-configured credential.
No credential provisioning or unattended paid Live sessions are part of rollout.
