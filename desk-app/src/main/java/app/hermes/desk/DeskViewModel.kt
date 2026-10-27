package app.hermes.desk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.companion.VoiceStreamState
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.voice.VoiceStreamEngine
import app.hermes.companion.voice.VoiceInputManager
import app.hermes.companion.voice.TextToSpeechEngine
import app.hermes.companion.voice.VoiceAudioSession
import app.hermes.desk.voice.*
import app.hermes.desk.feature.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import app.hermes.desk.providers.*

class DeskViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        /** In-process instrumentation gate; never persisted or exposed as an external command. */
        @Volatile internal var suppressAutomaticEntryForTests = false
    }
    private val store = DeskStore(application)
    private val auth = ChatGptAuth(store)
    private val backend = DeskProviderRouter(HermesBackend(), ChatGptBackend(store, auth)) { origin -> GrokBackend.create(store, auth, origin) }
    private val speechBackend = HermesBackend()
    private var speechSetup: Job? = null
    private var signIn: Job? = null
    private var authAttempt = 0L
    private var sample: Job? = null
    private val samplePlayer = AndroidSpeechPlayer()
    val connectionNotice = MutableStateFlow("")
    val connectionBusy = MutableStateFlow(false)
    val signInPending = MutableStateFlow(false)
    val localActions = MutableSharedFlow<DeskLocalAction>(extraBufferCapacity = 4)
    val accountLabels = MutableStateFlow(accountLabels())
    val savedHermes get() = store.hermesConnection()
    val automaticVoiceEnabled get() = store.preference("autoVoice", false)
    fun setAutomaticVoice(enabled: Boolean) = store.setPreference("autoVoice", enabled)
    val voicePause get() = store.voicePause()
    val voiceThreshold get() = store.voiceThreshold()
    fun voicePacing(value: Int) { stopVoice(); store.saveVoicePause(value) }
    fun voiceSensitivity(value: Int) { stopVoice(); store.saveVoiceThreshold(value) }
    fun preset(action: DeskPresets.Action) { localActions.tryEmit(DeskLocalAction(action, state.value.binding)) }
    private fun accountLabels() = store.accounts().mapIndexed { index, account ->
        account.key to (account.email.ifBlank { "ChatGPT account ${index + 1}" } + " · " + account.clientId.takeLast(6))
    }
    val coordinator = DeskCoordinator(backend, store, viewModelScope)
    val state = coordinator.state
    private var voice: VoiceStreamEngine? = null
    private var voiceBinding: DeskBinding? = null
    var automaticEntryHandled = false
    val voiceConsentKnown get() = store.preference("voiceConsent", false)
    val shouldStartVoiceOnEntry get() = !suppressAutomaticEntryForTests && voiceConsentKnown && store.preference("autoVoice", false) && !store.preference("voicePaused", false)
    private val frames = DeskVisionFrames { android.os.SystemClock.elapsedRealtime() }

    init {
        coordinator.preferences(store.preference("privacy", false), store.preference("awake", true), store.preference("motion", false))
        coordinator.speechMode(store.speechMode())
        viewModelScope.launch {
            coordinator.signals.collect { signal ->
                when (signal) {
                    DeskSignal.StopVoice -> stopVoice()
                    is DeskSignal.Event -> if (voiceBinding == state.value.binding) when (val event = signal.value) {
                        is ChatEvent.AssistantDelta -> voice?.onAssistantDelta(event.text)
                        is ChatEvent.Final -> {
                            voice?.onAssistantFinal(event.text)
                            if (event.status == "interrupted" || event.error.isNotBlank()) stopVoice() else voice?.onAssistantTurnCompleted()
                        }
                        ChatEvent.Completed -> voice?.onAssistantTurnCompleted()
                        else -> Unit
                    }
                }
            }
        }
        // Reopening the desk restores its host/session, but never microphone capture or speech.
        if (store.preference("autoConnect", true)) store.connection()?.let(coordinator::connect)
    }
    fun connect(origin: String, user: String, password: String, token: String) {
        stopVoice()
        val canonical = OriginPolicy.canonicalize(origin)
        val saved = store.connection()?.takeIf { it.origin == canonical }
        coordinator.connect(DeskConnection(canonical, user.ifBlank { saved?.username.orEmpty() },
            password.ifBlank { saved?.password.orEmpty() }, token.ifBlank { saved?.token.orEmpty() }))
    }
    fun startVoice() {
        if (!state.value.canSend) { coordinator.error("Select a ready conversation before starting voice"); return }
        stopVoice()
        val binding = state.value.binding ?: return
        speechSetup = viewModelScope.launch {
            try {
                val transport = if (state.value.speechMode == "hermes") prepareSpeech(binding) else null
                if (state.value.binding == binding && state.value.canSend) beginVoice(binding, transport)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { coordinator.error("Could not connect speech. Open Voice setup to check the saved Hermes host or select Device speech.") }
        }
    }
    private suspend fun prepareSpeech(binding: DeskBinding): HermesSpeech {
        if (state.value.backend == "Hermes") return backend.hermes.speech(binding)
        val connection = store.hermesConnection() ?: error("Set up a Hermes speech host or select Device speech")
        val profiles = speechBackend.connect(connection)
        val profile = profiles.firstOrNull { it.id == store.profile(connection.origin) } ?: profiles.first()
        return speechBackend.speech(DeskBinding(connection.origin, profile.id, "speech-only"))
    }
    private fun beginVoice(binding: DeskBinding, transport: HermesSpeech?) {
        voiceBinding = binding
        store.setPreference("voicePaused", false)
        val hostSpeech = state.value.speechMode == "hermes"
        voice = VoiceStreamEngine(getApplication(), viewModelScope,
            onSendPrompt = { text -> if (voiceBinding == state.value.binding && voiceBinding == binding) {
                val matched = DeskPresets.match(text)
                if (matched != null) preset(matched)
                else coordinator.send(text, if (state.value.visionSharing) frames.take(binding, spoken = true) else null)
            } else stopVoice() },
            onInterrupt = { if (voiceBinding == state.value.binding) coordinator.interrupt() },
            onStateChange = { value -> if (voiceBinding == state.value.binding && voiceBinding == binding) {
                if (value == VoiceStreamState.TRANSCRIBING && state.value.visionSharing) frames.freeze(binding)
                if (value == VoiceStreamState.LISTENING) frames.clearUtterance()
                coordinator.voice(value)
            } },
            onError = { coordinator.error(it); stopVoice() },
            voiceInput = if (hostSpeech) DeskHostInput(viewModelScope, AndroidSpeechCapture(getApplication(), store.voicePause(), store.voiceThreshold())) { transport!!.transcribe(it) } else VoiceInputManager(getApplication()),
            outputFactory = { callbacks -> if (hostSpeech) DeskHostOutput(viewModelScope, { transport!!.synthesize(it) }, AndroidSpeechPlayer(), callbacks)
                else TextToSpeechEngine(getApplication(), callbacks.onStart, callbacks.onCompleted, callbacks.onError, VoiceAudioSession.attributes) },
            inactivityTimeoutMs = if (hostSpeech) Long.MAX_VALUE / 4 else 15_000,
            onReadinessChange = { if (voiceBinding == state.value.binding && voiceBinding == binding) coordinator.microphoneReady(it) },
            onMutedChange = { if (voiceBinding == binding && voiceBinding == state.value.binding) store.setPreference("voicePaused", it) },
            onInputPaused = { coordinator.error(it) })
        voice?.startStream()
    }
    fun stopVoice() {
        speechSetup?.cancel(); speechSetup = null
        sample?.cancel(); sample = null; samplePlayer.stop()
        frames.clearUtterance()
        voiceBinding = null
        voice?.shutdown(); voice = null
        coordinator.voice(VoiceStreamState.IDLE)
        coordinator.microphoneReady(false)
    }
    fun endVoice() { stopVoice(); store.setPreference("voicePaused", true) }
    fun toggleMute() { voice?.let { it.setMicrophoneMuted(!it.isMicrophoneMuted) } }
    fun rememberVoiceConsent(automatic: Boolean) {
        store.setPreference("voiceConsent", true); store.setPreference("autoVoice", automatic)
    }
    fun speechMode(mode: String) { stopVoice(); store.saveSpeechMode(mode); coordinator.speechMode(mode) }
    fun continueChatGpt(openBrowser: (String) -> Unit, key: String? = null) {
        if (connectionBusy.value || state.value.working) return
        stopVoice(); connectionBusy.value = true; signInPending.value = true; connectionNotice.value = "Complete sign-in in your browser, then return here."
        val attempt = ++authAttempt
        signIn = viewModelScope.launch {
            try {
                val existing = key?.let { selected -> store.accounts().singleOrNull { it.key == selected } }
                val account = auth.signIn(existing, openBrowser)
                accountLabels.value = accountLabels()
                store.saveProfile(ChatGptBackend.ORIGIN, account.key)
                coordinator.connect(DeskConnection(ChatGptBackend.ORIGIN))
                connectionNotice.value = "ChatGPT connected. Create or choose a conversation in History. Speech stays on your selected service."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == authAttempt) connectionNotice.value = e.message?.takeIf { !it.contains("http") && !it.contains("token=") } ?: "ChatGPT sign-in did not complete. Try again." }
            finally { if (attempt == authAttempt) { connectionBusy.value = false; signInPending.value = false } }
        }
    }
    fun cancelSignIn() {
        if (!signInPending.value) return
        authAttempt++; signIn?.cancel(); auth.cancel(); signIn = null
        connectionBusy.value = false; signInPending.value = false; connectionNotice.value = "Sign-in cancelled."
    }
    fun useChatGpt(key: String) {
        if (state.value.working || connectionBusy.value) return
        val account = store.accounts().singleOrNull { it.key == key } ?: return
        if (account.refresh.isBlank()) { connectionNotice.value = "This account is signed out. Use Reconnect to sign in again."; return }
        stopVoice(); store.saveProfile(ChatGptBackend.ORIGIN, key); coordinator.connect(DeskConnection(ChatGptBackend.ORIGIN))
    }
    fun useHermes() {
        if (state.value.working || connectionBusy.value) return
        val saved = store.hermesConnection() ?: run { connectionNotice.value = "Enter your Hermes host on the connection screen first."; return }
        stopVoice(); coordinator.connect(saved)
    }
    fun useGrok() {
        if (state.value.working || connectionBusy.value) return
        val saved = store.hermesConnection() ?: run { connectionNotice.value = "Connect your Hermes host first so lab can broker its Grok OAuth account."; return }
        stopVoice(); coordinator.connect(DeskConnection(GrokBackend.originFor(saved.origin)))
        connectionNotice.value = "Connecting native Grok through lab’s OAuth broker. Choose or create a local conversation in History."
    }
    fun signOutChatGpt(key: String) {
        if (state.value.working || connectionBusy.value) return
        stopVoice()
        if (state.value.origin == ChatGptBackend.ORIGIN && state.value.profile == key) coordinator.disconnect()
        connectionBusy.value = true
        viewModelScope.launch {
            try { val revoked = auth.signOut(key); connectionNotice.value = if (revoked) "ChatGPT signed out. Local conversations are retained."
                else "Signed out locally. Remote revocation was not confirmed; disconnect Desk Companion in ChatGPT Settings." }
            finally { accountLabels.value = accountLabels(); connectionBusy.value = false }
        }
    }
    fun testSpeaker() {
        val binding = state.value.binding ?: run { connectionNotice.value = "Connect a conversation before testing speech."; return }
        stopVoice(); connectionNotice.value = "Testing the selected speaker…"
        sample = viewModelScope.launch {
            try {
                if (state.value.speechMode == "hermes") {
                    val speech = prepareSpeech(binding)
                    val audio = speech.synthesize("Your desk companion is ready. You can speak naturally.")
                    try {
                        if (state.value.binding == binding) samplePlayer.play(audio, {}, { connectionNotice.value = "Speaker test finished." }, { connectionNotice.value = "Speaker playback failed. Check the tablet volume and audio route." })
                    } finally { audio.bytes.fill(0) }
                } else connectionNotice.value = "Device speech is tested when you start a conversation. Select Hermes to test host speech here."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connectionNotice.value = "Speech test failed. Check lab connectivity and the host’s selected voice." }
        }
    }
    fun send(text: String = state.value.draft) {
        DeskPresets.match(text)?.let { coordinator.draft(""); preset(it); return }
        val image = state.value.binding?.takeIf { state.value.visionSharing }?.let { frames.take(it) }
        coordinator.send(text, image)
    }
    fun visionFrame(jpeg: ByteArray) { state.value.binding?.let { frames.update(it, jpeg) } ?: jpeg.fill(0) }
    fun visionSharing(active: Boolean) { if (!active) frames.clear(); coordinator.vision(active) }
    fun disconnect() {
        stopVoice()
        store.setPreference("autoConnect", false)
        coordinator.disconnect()
    }
    fun preferences(privacy: Boolean, awake: Boolean, reduced: Boolean) {
        store.setPreference("privacy", privacy); store.setPreference("awake", awake); store.setPreference("motion", reduced)
        coordinator.preferences(privacy, awake, reduced)
    }
    override fun onCleared() { cancelSignIn(); stopVoice(); speechBackend.close(); visionSharing(false); coordinator.close() }
}
