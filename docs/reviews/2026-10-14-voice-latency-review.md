# Companion voice latency measurements and Conduit comparison

Companion's largest measured conversational delay is the Hermes host producing its first reply text: 3.7–9.5 seconds for a tiny tool-free response. Once initialized, the S22's existing System speech engine starts playback in 17–20 milliseconds. Moving speech transport to a WebSocket alone cannot eliminate the model's initial wait.

These measurements were collected on 14 October 2026 using lab, the phone's selected `ash` profile and synthetic content. Human recognition and complete spoken-dialogue tests remain deferred. No microphone was used. The comparison uses Conduit snapshot `48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527`; Conduit itself was not run against the same host in this benchmark.

## Measured stages

| Stage | Result | What it establishes |
| --- | --- | --- |
| S22 to saved host ping | 11–23 ms; average 17 ms; no loss in four packets | Basic network reachability is much faster than reply generation. This is ICMP, not a measurement of the entire app transport. |
| Native WebSocket upgrade on lab loopback | 3.8–4.5 ms | Connection establishment on the host is fast. |
| Synthetic session create, including upgrade | 17–21 ms | Empty-session creation is fast in these probes. |
| Native prompt acknowledgment | 7–228 ms across six accepted turns | The host accepts the synthetic prompts before the multi-second generation wait. |
| Prompt send to first assistant text | 3.7–9.5 s | Host/model work before its first text is the dominant measured stage. Includes agent setup, context preparation, provider scheduling and reasoning; this benchmark does not split those internals. |
| Prompt send to terminal reply | 3.8–10.5 s | Tiny replies still spend seconds before completion. No tool events occurred. |
| Native whole-file synthesis | 1.05–1.35 s in the final two samples | The configured xAI provider adds another request delay if whole-file host output is used. |
| Native synthetic-file transcription | About 1.26 s in both final samples | The local provider recognized generated speech. This is not a microphone or recognition-accuracy test. |
| Native speech stream first PCM | 1.60–1.83 s | This provider's stream was not faster than whole-file synthesis for the short fixed phrase. Stream completion took 8.54–9.10 s; playback could start before completion. |
| Fresh System speech output with a complete clause | 1.37–3.91 s across two device runs | Includes new-engine startup and first synthesis. It must not be attributed to every subsequent conversational turn. |
| Reusing the same System output | 17 ms and 20 ms on turns two and three | Existing local output is fast when warm. The conversation engine already retains it across turns and resets queued speech when listening resumes. |
| Platform readiness probe | 1.13 s; Google TTS; `en-IN-language`; network voice false | The selected output voice is local. Network TTS is not the explanation for the measured warm output delay. |

Results are small diagnostic samples, not percentile estimates or an end-to-end speech latency total. Do not add independent cold/warm measurements together and present them as one observed conversation. Speech-engine initialization begins when voice mode starts and can overlap listening and host processing.

## Host reasoning comparison

The selected profile defaults to `grok-4.7` through `xai-oauth`, with high reasoning. Synthetic sessions were tested with session-only reasoning overrides; profile defaults were preserved.

| Setting | First turn first text | Next turn first text |
| --- | --- | --- |
| Existing default | 9.53 s | 4.42 s |
| Session low reasoning | 6.29 s | 4.67 s |
| Session high reasoning control | 8.47 s | 3.70 s |

Low reasoning did not consistently improve the warmed turn. The slower first turn in each fresh session suggests initialization, context/prompt caching or provider scheduling may matter, but these runs do not isolate which one. A faster voice model remains a testable option, not a demonstrated fix. Do not change the operator's global model or reasoning based on these samples.

## Companion buffering costs

**System input waits for a final recognition result.** Partial text updates activity but does not submit a speculative prompt. The input implementation recreates its recognizer each listening window and prefers its explicit Google service when available before checking Android on-device recognition. Readiness, utterance endpointing and final recognition time still need measurement with an operator or an authorized prerecorded-input harness. The 65-second transcription watchdog is a failure bound, not an intentional wait.

**System output waits for a clause boundary.** `VoiceTextFilter.extractNextClause` cuts on question/exclamation marks, newlines, colons and periods followed by whitespace. It has no elapsed-time or length threshold. A final period without following whitespace, or a long punctuation-free opening, remains buffered until a later boundary or `finishFeed`. On the S22, a controlled punctuation-free input stayed silent during a 1.5-second simulated model pause and began after finalization. That 1.5 seconds is an injected test delay, not measured host delay. For the tiny host benchmark, the first speakable boundary followed the first delta almost immediately, so buffering did not explain its multi-second wait.

**Host output currently waits for the whole response.** Its phase-one implementation accumulates every delta until `finishFeed`, then requests complete audio files. It also waits for each file to finish playing before requesting the next chunk. Long replies therefore lose synthesis/generation overlap and may contain additional gaps. Host speech remains disabled; this behavior must not be blamed for the currently enabled System backend.

**Classic conversation is half duplex.** Listening resumes after speech and turn completion. It does not provide continuous acoustic interruption or a native realtime audio session.

## Why Conduit can feel faster

Conduit's classic controller forwards assistant deltas into one speech stream for the reply and enqueues PCM as it arrives. Speech can overlap remaining text generation. It also starts a native transcription stream while capturing when the host/provider supports it, with a whole-recording fallback. These remove serial waits rather than accelerating the text model itself. [Classic controller](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/VoiceConversationController.swift), [speech transport](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesVoiceGateway.swift), [live transcription](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/HermesLiveTranscription.swift).

Conduit also has a separate GPT Live WebRTC conversation path with Hermes delegation. A realtime model can handle conversational audio while Hermes performs agent work separately. That is different from waiting for the full Hermes text-agent pipeline for every spoken response. Its live implementation is a reference for phase five, not proof that Companion already has realtime calls. [Peer connection](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/GPTLive/GPTLivePeer.swift), [delegation bridge](https://github.com/kaishi00/hermes-conduit/blob/48cfd96bd7aff7a569d0d8ccf5ab731fb63e0527/Conduit/Voice/GPTLive/GPTLiveDelegationBridge.swift).

For ChatGPT-like realtime voice, the relevant documented OpenAI architecture accepts and emits audio directly without separate speech-to-text and text-to-speech stages, and provides WebRTC audio transport. This explains an architectural route to lower latency; it is not a measured comparison against ChatGPT's product. [Official OpenAI documentation](https://developers.openai.com/api/docs/guides/realtime-conversations), [WebRTC transport](https://developers.openai.com/api/docs/guides/voice-webrtc).

The same Hermes model/profile behind Conduit's classic mode would still have to produce its first answer text. Which Conduit mode and provider produced the operator's faster experience is not established. Lab currently reports chained mode and no configured native realtime key.

## Next implementation increments and gates

1. **Add per-turn timing diagnostics and listening readiness.** Capture monotonic readiness, last speech, final transcript, send/accept, first assistant delta, first queued speech and first playback callbacks. Store durations and scoped identifiers, not prompts, transcripts or credentials. Bind callbacks to conversation/recognizer generations. Keep cold startup distinct from warm turns and show Thinking only for actual host work. Validate delayed finals, stale callbacks, cancellation and device output without enabling the microphone automatically.
2. **Improve the existing classic path.** Preserve the warm output engine. Introduce safe bounded text flushing for punctuation-free openings, avoiding decimal/code/Markdown fragments and duplicate playback. Add native per-reply PCM speech streaming, bounded buffering and synthesis prefetch. Discover transcription streaming per profile; unsupported local providers keep explicit whole-file behavior. Cancellation and fallback must not replay already spoken audio. Compare first audio and gaps on the same host/provider before enabling it.
3. **Benchmark a suitable voice model/session configuration.** Use only isolated synthetic sessions and identical prompts; separate fresh from warmed samples and collect enough observations before changing defaults. Faster text generation can help classic voice, whereas a speech WebSocket alone cannot remove the measured host wait.
4. **Implement phase-five realtime voice for natural conversation.** Native Android WebRTC and host SDP exchange, captions, mute/routes, cancellation, exact-once Hermes delegation and deterministic teardown. Configure the host provider separately when available. Verify ordinary conversation and delegated agent work as separate latency measures; acknowledge work without falsely claiming it has finished.

Human spoken recognition, multi-turn conversation, echo and acoustic interruption gates remain pending. These diagnostics do not enable Host speech, incoming calls or full duplex, and do not alter the restored composer.

## Validation and reproducibility

The focused automated run passed 32 voice tests and the existing speech-text filter tests. The final S22 instrumentation run passed three synthetic-output tests in 25.048 seconds. The debug instrumentation APK built successfully. There were no production behavior edits in this increment, so no full release milestone is claimed.

Installed application and local debug APK checksum matched: `42b9140b3ae201f1c72f8aa00b4c0d976362d05e86f5ce6ccca44185b94dd8bc`. The temporary test package was removed and the normal operator app relaunched. Saved host/profile remain `http://100.85.151.99:9120` / `ash`. Every completed native benchmark reports its temporary session deleted; the final host check found zero remaining benchmark sessions and high reasoning still configured. Raj remains excluded. No plugin deploy or retirement was performed.

The first exploratory host run omitted the native `message.complete` terminal and inspected `text` rather than `transcript` in the transcription result. It was interrupted and excluded from the accepted host table; the corrected runs above handle both contracts. The first device attempt preceded successful registration of the temporary test package and did not run; the final three-test result is authoritative.

Sanitized results: [voice latency fixture](fixtures/2026-10-14-voice-latency.json). Reusable synthetic host probe: `scripts/measure_voice_latency.py`; invoke it on lab's loopback dashboard with `--profile ash`, optionally `--chat-only --reasoning low` for a session-scoped comparison. Credentials stay in process memory and are never printed. Raw diagnostic logs and the tested APK are in `/private/tmp/companion-voice-latency-20261014/`.
