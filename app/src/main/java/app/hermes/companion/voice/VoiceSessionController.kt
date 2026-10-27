package app.hermes.companion.voice

import app.hermes.companion.*
import app.hermes.companion.data.remote.NativeLiveProtocol
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.local.VoiceCallRecord
import app.hermes.companion.domain.VoiceTextFilter
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Application-owned call. The foreground service holds its lifetime; screens only observe. */
class VoiceSessionController(
    private val app: CompanionApp,
    private val state: MutableStateFlow<CompanionState>,
    private val chat: VoiceConversation,
    private val audioFactory: () -> VoiceAudioSessionControl = { VoiceAudioSession(app) },
    private val captureFactory: (Int) -> CallSpeechCapture = { ContinuousSpeechCapture(it) },
    private val speechOutputFactory: ((CoroutineScope, StreamingSpeechTransport, SpeechCallbacks, Boolean) -> SpeechOutput)? = null,
) {
    private val scope = app.appScope
    private data class Request(val text: String, val turn: String, val context: String, val inputEpoch: Long)
    private var requests: Channel<Request>? = null
    private var generation = 0L
    private var callJob: Job? = null
    private var releaseJob: Job? = null
    private var capture: CallSpeechCapture? = null
    private var output: SpeechOutput? = null
    private var system: VoiceStreamEngine? = null
    private var peer: NativeWebRtcPeer? = null
    private var audio: VoiceAudioSessionControl? = null
    private var inputGeneration = 0L
    private var speechLease: Triple<String, String, String>? = null
    private var waiting = false
    private var suppressed = false
    private var awaitingSteerFinal = false
    private var delegation: String? = null
    private var ownerEpoch = 0L
    private var callScope: CoroutineScope? = null
    private var snapshot: CompanionState? = null
    private var callSettings: HostVoiceSettings? = null
    private val liveText = StreamingSpeechText()
    private val timings = VoiceTimings()
    var callId: String = ""; private set
    val isActive: Boolean get() = callJob != null
    val isMicrophoneMuted: Boolean get() = state.value.voiceMicrophoneMuted
    val conversationEpoch: Long get() = ownerEpoch
    private fun update(change: (CompanionState) -> CompanionState) = state.update(change)

    init {
        chat.onAssistantDelta = ::onAssistantDelta
        chat.onTurnCompleted = {
            if (isActive && ownerEpoch == chat.conversationEpoch) {
                val text = state.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.reasoning }?.text.orEmpty()
                onAssistantFinal(text); onAssistantTurnCompleted()
            }
        }
        chat.onTurnInterrupted = ::onAssistantTurnInterrupted
        chat.onTurnFailed = { if (isActive) fail("Hermes could not complete this voice turn. Check its delivery in chat.") }
    }

    fun start() {
        if (isActive) return
        val epoch = ++generation
        callId = UUID.randomUUID().toString()
        timings.start()
        if (state.value.openSession == null) chat.newThread()
        ownerEpoch = chat.conversationEpoch
        update { it.copy(voiceCallPhase = NativeCallPhase.CONNECTING, voiceStreamState = VoiceStreamState.LISTENING,
            voiceMicrophoneReady = false, voiceMicrophoneMuted = false, voiceTranscript = emptyList(), voicePauseReason = "", error = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    releaseJob?.join()
                    callScope = this
                    val inputs = Channel<Request>(8)
                    requests = inputs
                    launch { for (request in inputs) accept(request, epoch) }
                    val before = state.value
                    require(!before.inRoom && before.origin != null && before.activeProfileId != null) { "Select a connected individual conversation" }
                    val session = chat.ensureVoiceSession()
                    ensureActive()
                    check(ownerEpoch == chat.conversationEpoch && generation == epoch)
                    val selected = state.value
                    snapshot = selected
                    launch {
                        state.collect { current ->
                            if (chat.conversationEpoch != ownerEpoch || current.origin != selected.origin ||
                                current.activeProfileId != selected.activeProfileId || current.openSessionId != session.id) {
                                fail("Voice conversation changed. Start voice again in the selected conversation.")
                            }
                        }
                    }
                    update { it.copy(voiceSessionTitle = session.title.ifBlank { "Voice conversation" }) }
                    val origin = requireNotNull(selected.origin)
                    val profile = requireNotNull(selected.activeProfileId)
                    val api = app.clientFor(origin)
                    val settings = if (selected.speechBackend == SpeechBackend.SYSTEM) null else try { api.audioSettings(origin, profile) }
                    catch (e: DashboardException) {
                        if (e.code !in setOf("http_404", "http_405")) throw e
                        val status = try { api.voiceLiveStatus(origin, profile) }
                        catch (legacy: DashboardException) {
                            if (legacy.code !in setOf("http_404", "http_405")) throw legacy
                            NativeVoiceStatus()
                        }
                        HostVoiceSettings(live = status)
                    }
                    callSettings = settings
                    update { it.copy(hostVoiceSettings = settings ?: it.hostVoiceSettings,
                        voiceModeLabel = if (settings?.realtime == true) "Realtime voice" else if (selected.speechBackend == SpeechBackend.SYSTEM) "Android speech" else "Hermes speech",
                        voiceDescription = settings?.description ?: "Android recognition and text-to-speech") }
                    if (settings?.realtime == true) {
                        check(settings.live.available == true) { settings.live.reason.ifBlank { "Realtime voice is not configured on this profile" } }
                        runRealtime(selected, session, epoch)
                    } else if (selected.speechBackend == SpeechBackend.SYSTEM) runSystem(selected)
                    else {
                        check(settings?.speechAvailable != false) { settings?.speechReason?.ifBlank { "Configure host speech before starting voice" }.orEmpty() }
                        runSpeech(selected, settings ?: HostVoiceSettings(), epoch)
                    }
                    awaitCancellation()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == epoch) fail(if (e is DashboardException)
                    "The host speech request failed (${e.code}). Check the configured provider on the host."
                    else e.message ?: "Voice could not start")
            }
            finally { if (generation == epoch) stopStream() }
        }
        callJob = job
        job.start()
    }

    private fun runSystem(selected: CompanionState) {
        system = VoiceStreamEngine(app, requireNotNull(callScope), { submit(it) }, {},
            { next -> update { it.copy(voiceStreamState = next, voiceCallPhase = if (next == VoiceStreamState.IDLE) NativeCallPhase.ENDED else NativeCallPhase.CONNECTED) }
                if (next == VoiceStreamState.IDLE && isActive) stopStream() }, ::fail,
            onMutedChange = { muted -> update { it.copy(voiceMicrophoneMuted = muted) } },
            onReadinessChange = { ready -> update { it.copy(voiceMicrophoneReady = ready) } },
            onInputPaused = { reason -> update { it.copy(voicePauseReason = reason) } }).also { it.useSpeaker(selected.voiceSpeaker); it.startStream() }
    }

    private suspend fun runSpeech(selected: CompanionState, settings: HostVoiceSettings, epoch: Long) {
        val call = requireNotNull(callScope)
        val origin = requireNotNull(selected.origin); val profile = requireNotNull(selected.activeProfileId)
        val token = if (settings.snapshots) app.clientFor(origin).beginSpeechSnapshot(origin, profile) else ""
        if (token.isNotBlank()) speechLease = Triple(origin, profile, token)
        val transport = NativeSpeechTransport(app.clientFor(origin), origin, profile, token)
        audio = audioFactory().also { session ->
            check(session.acquire { scope.launch { if (generation == epoch) fail("Voice ended because another audio session needs the device") } }) { "Another call owns audio" }
            check(session.useSpeaker(selected.voiceSpeaker)) { "The selected audio route is unavailable" }
        }
        val callbacks = SpeechCallbacks(
            { if (generation == epoch && !suppressed) { timings.audio(); update { it.copy(voiceStreamState = VoiceStreamState.SPEAKING) } } },
            { if (generation == epoch) { waiting = false; suppressed = false; update { it.copy(voiceStreamState = if (isMicrophoneMuted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) } } },
            { error -> if (generation == epoch) fail(error) })
        output = speechOutputFactory?.invoke(call, transport, callbacks, settings.streaming) ?: if (settings.streaming) StreamingHostSpeechOutput(call, transport, AndroidPcmSpeechPlayer(call), AndroidSpeechPlayer(), callbacks)
            else HostSpeechOutput(call, transport, AndroidSpeechPlayer(), callbacks)
        val utterances = Channel<Pair<Long, ByteArray>>(2, onUndeliveredElement = { it.second.fill(0) })
        call.launch {
            try {
                for ((inputEpoch, wav) in utterances) {
                    if (inputEpoch != inputGeneration || isMicrophoneMuted) { wav.fill(0); continue }
                    try {
                        timings.utterance()
                        update { it.copy(voiceStreamState = VoiceStreamState.TRANSCRIBING) }
                        val text = withTimeout(65000) { transport.transcribe(wav) }.trim()
                        timings.transcribed()
                        if (generation == epoch && inputEpoch == inputGeneration && !isMicrophoneMuted && text.isNotBlank()) submit(text)
                    } finally {
                        wav.fill(0)
                        if (generation == epoch && state.value.voiceStreamState == VoiceStreamState.TRANSCRIBING)
                            update { it.copy(voiceStreamState = if (isMicrophoneMuted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) }
                    }
                }
            } finally { utterances.cancel() }
        }
        capture = captureFactory(selected.voicePacingMs).also { mic ->
            mic.muted = isMicrophoneMuted
            mic.start(call,
            { ready -> if (generation == epoch) { if (ready) timings.ready(); update { it.copy(voiceMicrophoneReady = ready, voiceCallPhase = NativeCallPhase.CONNECTED) } } },
            { if (generation == epoch) stopPlayback() },
            { wav -> if (utterances.trySend(inputGeneration to wav).isFailure) { wav.fill(0); fail("Speech is arriving faster than transcription. End voice and check the host connection.") } },
            { error -> if (generation == epoch) fail(error) }) }
    }

    private suspend fun runRealtime(selected: CompanionState, session: SessionRef, epoch: Long) {
        val call = requireNotNull(callScope)
        val origin = requireNotNull(selected.origin); val profile = requireNotNull(selected.activeProfileId)
        val ledger = LiveConversationLedger()
        val token = if (callSettings?.snapshots == true) app.clientFor(origin).beginSpeechSnapshot(origin, profile) else ""
        if (token.isNotEmpty()) speechLease = Triple(origin, profile, token)
        var record = withContext(Dispatchers.IO) { app.voiceJournal.read(origin, profile, session.id) }
        suspend fun persist() {
            val saved = record
            withContext(Dispatchers.IO) { app.voiceJournal.save(origin, profile, session.id, saved) }
        }
        val live = NativeWebRtcPeer(app, NativeCallContext(origin, profile, session.id, epoch), audioFactory())
        peer = live
        live.mute(isMicrophoneMuted)
        var started = false
        call.launch(start = CoroutineStart.UNDISPATCHED) {
            live.phase.collect { phase ->
                if (epoch != generation) return@collect
                update { it.copy(voiceCallPhase = phase, voiceMicrophoneReady = started && phase == NativeCallPhase.CONNECTED && !isMicrophoneMuted) }
                if (phase in setOf(NativeCallPhase.DISCONNECTED, NativeCallPhase.FAILED, NativeCallPhase.ENDED))
                    fail("Realtime voice disconnected. Tap Voice to resume this conversation.")
            }
        }
        val pending = linkedMapOf<String, NativeLiveEvent.Delegation>()
        suspend fun delegate(event: NativeLiveEvent.Delegation) {
            if ("$callId:${event.id}" in record.delegations) { pending.remove(event.id); return }
            val request = ledger.request(event) ?: return
            record = record.copy(delegations = record.delegations + "$callId:${event.id}")
            persist() // Claim before execution; lost acknowledgements are never retried here.
            pending.remove(event.id)
            submit(request.first, event.id, request.second)
        }
        call.launch(start = CoroutineStart.UNDISPATCHED) {
            live.events.collect { event ->
                if (generation != epoch) return@collect
                when (event) {
                    is NativeLiveEvent.Caption -> if (ledger.caption(event)) {
                        record = record.copy(captions = (record.captions + ledger.captions.last()).takeLast(1000))
                        update { it.copy(voiceTranscript = ledger.captions.takeLast(200)) }
                        persist()
                        pending.values.toList().forEach { delegate(it) }
                    }
                    is NativeLiveEvent.Delegation -> {
                        check(pending.size < 32); pending[event.id] = event; delegate(event)
                        if (event.id in pending) call.launch {
                            delay(2000)
                            if (event.id in pending) fail("Hermes requested voice work without a usable transcript. Resume voice and repeat the request.")
                        }
                    }
                    is NativeLiveEvent.Error -> fail(event.message)
                    is NativeLiveEvent.Closed -> fail("Realtime voice ended. Tap Voice to resume this conversation.")
                    is NativeLiveEvent.Started -> { started = true; update { it.copy(voiceMicrophoneReady = live.phase.value == NativeCallPhase.CONNECTED && !isMicrophoneMuted) } }
                }
            }
        }
        val history = selected.messages.filter { !it.reasoning && !it.streaming && it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) }
            .takeLast(20).map { it.role.name.lowercase() to it.text.take(12000) } +
            record.captions.takeLast(30).map { it.role to it.text }
        val input = JsonArray(history.takeLast(24).map { (role, text) -> buildJsonObject {
            put("type", "message"); put("role", role); put("content", JsonArray(listOf(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text.take(1200)) })))
        } })
        val offer = live.offer()
        live.mute(isMicrophoneMuted)
        val answer = app.clientFor(origin).createRealtimeSession(origin, profile, offer, input, token)
        live.acceptAnswer(answer.sdp)
        check(live.speaker(selected.voiceSpeaker)) { "The selected audio route is unavailable" }
    }

    private fun submit(text: String, turn: String = UUID.randomUUID().toString(), context: String = "") {
        val accepted = requests?.trySend(Request(text, turn, context, inputGeneration))?.isSuccess == true
        if (!accepted && isActive) fail("Too many voice requests are waiting. Check the current turn in chat.")
    }
    private suspend fun accept(request: Request, epoch: Long) {
        withTimeout(180000) { while (state.value.submitting) delay(50) }
        if (epoch != generation || request.inputEpoch != inputGeneration || ownerEpoch != chat.conversationEpoch || isMicrophoneMuted) return
        val control = request.text.lowercase().trim().trimEnd('.', '!', '?')
        if (control in setOf("stop talking", "stop speaking")) { stopPlayback(); return }
        if (control in setOf("cancel that task", "cancel the task", "cancel current task", "cancel this task")) {
            stopPlayback(); chat.interrupt(); waiting = false; return
        }
        waiting = true; suppressed = false; liveText.clear()
        update { it.copy(voiceStreamState = VoiceStreamState.THINKING) }
        val metadata = VoiceTurnMetadata(callId, request.turn, context = request.context)
        if (peer != null) delegation = request.turn
        if (state.value.streaming) {
            suppressed = true; awaitingSteerFinal = true
            chat.steerVoice(request.text, metadata)
        } else chat.sendPrompt(request.text, voice = metadata)
    }
    fun onAssistantDelta(text: String) {
        if (!isActive || !waiting || suppressed) return
        timings.text()
        system?.onAssistantDelta(text)
        output?.feedToken(text)
        if (peer != null) { liveText.append(text); flushLive(final = false) }
    }
    fun onAssistantFinal(text: String) {
        if (!isActive || !waiting || (suppressed && !awaitingSteerFinal)) return
        system?.onAssistantFinal(text)
        output?.reconcileFinal(text)
        if (suppressed) { suppressed = false; awaitingSteerFinal = false; system?.onAssistantDelta(text); liveText.clear(); liveText.append(text) }
        else if (peer != null && !liveText.reconcile(text)) liveText.clear()
    }
    fun onAssistantTurnCompleted() {
        if (!isActive || !waiting) return
        system?.onAssistantTurnCompleted()
        if (suppressed) {
            waiting = false; awaitingSteerFinal = false
            update { it.copy(voiceStreamState = if (isMicrophoneMuted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) }
            return
        }
        output?.finishFeed()
        if (peer != null) { flushLive(final = true); waiting = false; update { it.copy(voiceStreamState = if (isMicrophoneMuted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) } }
    }
    fun onAssistantTurnInterrupted() {
        system?.onAssistantTurnInterrupted(); stopPlayback(); waiting = false
    }
    private fun flushLive(final: Boolean) {
        val live = peer ?: return
        val id = delegation ?: return
        val batch = liveText.next(final) ?: return
        liveText.commit(batch)
        if (batch.text.isBlank()) return
        callScope?.launch { live.send(NativeLiveProtocol.append(batch.text, id)) }
    }
    fun stopPlayback() {
        if (!isActive) return
        val stopAt = timings.stopping()
        suppressed = waiting; awaitingSteerFinal = false
        output?.stop()
        timings.stopped(stopAt)
        system?.bargeIn()
        val live = peer
        if (live != null) callScope?.launch { live.send(listOf(buildJsonObject {
            put("type", "session.instructions.append"); put("content", "Stop speaking now and continue listening. Do not cancel backend work.")
        }.toString())) }
        update { it.copy(voiceStreamState = if (isMicrophoneMuted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) }
    }
    fun setMicrophoneMuted(muted: Boolean) {
        if (!isActive) return
        inputGeneration++
        update { it.copy(voiceMicrophoneMuted = muted, voiceStreamState = if (muted) VoiceStreamState.MUTED else VoiceStreamState.LISTENING) }
        capture?.muted = muted
        system?.setMicrophoneMuted(muted)
        peer?.let { live -> callScope?.launch { live.mute(muted) } }
    }
    fun useSpeaker(enabled: Boolean) {
        system?.useSpeaker(enabled)
        audio?.let { if (!it.useSpeaker(enabled)) fail("The selected audio route is unavailable") }
        peer?.let { live -> callScope?.launch { if (!live.speaker(enabled)) fail("The selected audio route is unavailable") } }
    }
    fun stopStream() {
        if (!isActive && capture == null && peer == null && output == null && system == null) return
        generation++
        inputGeneration++
        speechLease?.let { (origin, profile, token) -> scope.launch {
            try { withTimeout(5000) { app.clientFor(origin).endSpeechSnapshot(origin, profile, token) } }
            catch (e: Exception) { android.util.Log.w("VoiceCall", "Speech snapshot release unavailable") }
        } }; speechLease = null
        requests?.cancel(); requests = null
        callJob?.cancel(); callJob = null; callScope = null
        val stoppedInput = capture?.stop(); capture = null
        system?.shutdown(); system = null
        output?.shutdown(); output = null
        val oldPeer = peer; peer = null
        val oldAudio = audio; audio = null
        releaseJob = scope.launch { stoppedInput?.join(); oldPeer?.close(); oldAudio?.release() }
        waiting = false; suppressed = false; awaitingSteerFinal = false; delegation = null; snapshot = null; callSettings = null
        liveText.clear()
        update { it.copy(voiceStreamState = VoiceStreamState.IDLE, voiceCallPhase = NativeCallPhase.ENDED,
            voiceMicrophoneReady = false, voiceMicrophoneMuted = false) }
    }
    fun shutdown() = stopStream()
    private fun fail(message: String) {
        stopStream()
        update { it.copy(error = message, voicePauseReason = message, voiceCallPhase = NativeCallPhase.FAILED) }
    }
}
