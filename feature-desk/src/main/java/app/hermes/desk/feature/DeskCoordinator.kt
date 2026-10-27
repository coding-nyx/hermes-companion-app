package app.hermes.desk.feature

import app.hermes.companion.VoiceStreamState
import app.hermes.companion.domain.DeliveryPolicy
import app.hermes.companion.domain.PrivilegePolicy
import app.hermes.companion.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One immutable binding per operation; navigation fences callbacks, never replays a submission. */
class DeskCoordinator(
    private val backend: DeskBackend,
    private val persistence: DeskPersistence,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(DeskState(backend = backend.label, origin = persistence.connection()?.origin.orEmpty()))
    val state: StateFlow<DeskState> = mutable.asStateFlow()
    private val signalsMutable = MutableSharedFlow<DeskSignal>(extraBufferCapacity = 128)
    val signals: SharedFlow<DeskSignal> = signalsMutable.asSharedFlow()
    private var generation = 0L
    private var navigation: Job? = null
    private var bus: Job? = null
    private var turn: Job? = null
    private var decision: Job? = null
    private var refresh: Job? = null
    private var compaction: Job? = null
    private var settings: Job? = null
    private val retired = mutableSetOf<Pair<DeskBinding, String>>()

    fun connect(connection: DeskConnection) {
        invalidate()
        backend.close()
        val epoch = generation
        mutable.value = DeskState(backend = backend.label, origin = connection.origin, connecting = true,
            privateCaptions = state.value.privateCaptions, keepAwake = state.value.keepAwake, reducedMotion = state.value.reducedMotion, speechMode = state.value.speechMode)
        navigation = scope.launch {
            try {
                val profiles = backend.connect(connection)
                if (epoch != generation) return@launch
                require(profiles.isNotEmpty()) { "This host returned no profiles" }
                persistence.saveConnection(connection)
                mutable.update { it.copy(connected = true, connecting = false, profiles = profiles, backend = backend.label, capabilities = backend.capabilities) }
                val profile = persistence.profile(connection.origin).takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
                openProfile(profile)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) { backend.close(); mutable.update { it.copy(connecting = false, connected = false, error = message(e)) } } }
        }
    }

    fun reconnect() { persistence.connection()?.let(::connect) }
    fun disconnect() {
        invalidate(); backend.close()
        mutable.value = state.value.copy(connected = false, connecting = false, loading = false, working = false,
            messages = emptyList(), session = null, sessions = emptyList(), profiles = emptyList(), pending = null, draft = "", tool = "", error = null, turnActive = false)
    }

    fun openProfile(profile: String) {
        if (!state.value.connected || state.value.profiles.none { it.id == profile }) return
        invalidate()
        val epoch = generation
        val origin = state.value.origin
        mutable.update { it.copy(profile = profile, loading = true, session = null, sessions = emptyList(), messages = emptyList(), draft = "",
            working = false, turnActive = false, pending = null, uncertain = false, decisionSending = false, tool = "", error = null,
            context = null, compactionUncertain = false) }
        navigation = scope.launch {
            try {
                val sessions = backend.selectProfile(profile).filter { it.profileId == profile && !it.archived }
                if (epoch != generation) return@launch
                persistence.saveProfile(origin, profile)
                mutable.update { it.copy(sessions = sessions, loading = false) }
                startBus(epoch, profile)
                val previous = persistence.session(origin, profile)
                sessions.firstOrNull { it.id == previous }?.let(::openSession)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.update { it.copy(loading = false, connected = false, error = message(e)) } }
        }
    }

    fun openSession(session: SessionRef) {
        if (!state.value.connected || state.value.loading || session.profileId != state.value.profile) return
        invalidate(keepBus = true)
        val epoch = generation
        val binding = DeskBinding(state.value.origin, session.profileId, session.id)
        mutable.update { it.copy(session = session, loading = true, messages = emptyList(), draft = persistence.draft(binding), working = false,
            pending = null, uncertain = persistence.pending(binding) != null, decisionSending = false, tool = "", error = null, turnActive = false,
            compacting = false, context = null, compactionUncertain = persistence.compactionPending(binding)) }
        navigation = scope.launch {
            try {
                val messages = backend.history(binding)
                val approval = backend.pending(binding)
                if (!owns(epoch, binding)) return@launch
                reconcile(binding, messages)
                persistence.saveSession(binding)
                mutable.update { it.copy(messages = messages, loading = false, pending = activePrompt(binding, approval), uncertain = persistence.pending(binding) != null,
                    working = backend.runtime(binding)?.running == true) }
                refreshContext(epoch, binding)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) mutable.update { it.copy(loading = false, error = message(e)) } }
        }
        // Session changes advance generation; replace the old subscription with the new owner.
        bus?.cancel(); startBus(epoch, binding.profile)
    }

    fun newThread() {
        if (!state.value.connected || state.value.loading || state.value.profile.isBlank()) return
        invalidate(keepBus = true)
        val epoch = generation
        val profile = state.value.profile
        mutable.update { it.copy(loading = true, session = null, messages = emptyList(), draft = "", uncertain = false,
            working = false, turnActive = false, pending = null, decisionSending = false, tool = "") }
        navigation = scope.launch {
            try {
                val session = backend.createThread(profile)
                if (epoch != generation) return@launch
                mutable.update { it.copy(loading = false, sessions = listOf(session) + it.sessions.filterNot { s -> s.id == session.id }) }
                openSession(session)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.update { it.copy(loading = false, error = "Could not confirm creation. Refresh conversations before creating again. ${message(e)}") } }
        }
    }

    fun draft(text: String) {
        mutable.update { it.copy(draft = text.take(32_000)) }
        state.value.binding?.let { persistence.saveDraft(it, state.value.draft) }
    }

    fun send(text: String = state.value.draft, image: ByteArray? = null) {
        val snapshot = state.value
        val binding = snapshot.binding ?: run { image?.fill(0); return }
        if (!snapshot.canSend || text.isBlank()) { image?.fill(0); stopVoice(); return }
        val epoch = generation
        val item = OutboxItem(UUID.randomUUID().toString(), binding.origin, binding.profile, binding.session, text.trim(), System.currentTimeMillis(),
            lastError = "awaiting_ack", baselineUserIds = snapshot.messages.filter { it.role == MessageRole.USER }.map { it.id })
        if (!persistence.savePending(binding, item)) { image?.fill(0); error("Cannot save delivery state. Message was not sent."); return }
        persistence.saveDraft(binding, "")
        mutable.update { it.copy(working = true, turnActive = true, uncertain = false, draft = "", error = null, tool = "",
            messages = it.messages + ChatMessage("desk-${item.id}", MessageRole.USER, item.text, delivery = MessageDelivery.SENDING)) }
        turn = scope.launch {
            try {
                image?.let { try { backend.stageImage(binding, it) } finally { it.fill(0) } }
                if (!owns(epoch, binding)) return@launch
                backend.turn(binding, item.text).collect { event ->
                    if (!owns(epoch, binding)) return@collect
                    if (event is ChatEvent.Accepted || event is ChatEvent.Final || event is ChatEvent.Completed) persistence.savePending(binding, null)
                    applyEvent(binding, event)
                    signalsMutable.emit(DeskSignal.Event(event))
                }
                if (owns(epoch, binding) && state.value.working) {
                    stopVoice()
                    mutable.update { it.copy(working = false, uncertain = persistence.pending(binding) != null,
                        error = "The stream ended. Refresh history to check the result.") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owns(epoch, binding)) {
                    stopVoice()
                    mutable.update { it.copy(working = false, uncertain = persistence.pending(binding) != null,
                        error = "Connection ended. Check history before sending again. ${message(e)}") }
                }
            } finally {
                image?.fill(0)
                if (owns(epoch, binding)) mutable.update { it.copy(turnActive = false) }
                if (owns(epoch, binding)) refreshContext(epoch, binding)
            }
        }
    }

    fun refreshHistory() {
        val binding = state.value.binding ?: run {
            if (state.value.connected && !state.value.loading) openProfile(state.value.profile)
            return
        }
        if (!state.value.connected || state.value.loading || refresh?.isActive == true || turn?.isActive == true && state.value.pending == null) return
        val epoch = generation
        refresh = scope.launch {
            try {
                val messages = backend.history(binding)
                val approval = backend.pending(binding)
                if (!owns(epoch, binding)) return@launch
                reconcile(binding, messages)
                mutable.update { it.copy(messages = messages, uncertain = persistence.pending(binding) != null, pending = activePrompt(binding, approval),
                    working = backend.runtime(binding)?.running == true, decisionSending = false, error = null) }
                refreshContext(epoch, binding)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) error(message(e)) }
        }
    }

    /** Explicit acknowledgement after the operator has checked host history. Never resubmit. */
    fun dismissUncertain() {
        val binding = state.value.binding ?: return
        if (persistence.savePending(binding, null)) mutable.update { it.copy(uncertain = false, error = null) }
    }

    fun respond(binding: DeskBinding, requestId: String, choice: String, presenceVerified: Boolean = false) {
        val s = state.value
        val prompt = s.pending?.takeIf { it.requestId == requestId } ?: return
        if (s.binding != binding || s.decisionSending || !s.connected || prompt.kind !in setOf("approval", "clarify")) return
        if (PrivilegePolicy.requiresPresence(prompt.kind, prompt.command, choice) && !presenceVerified) { error("Confirm this action with your device lock first"); return }
        if (prompt.kind != "clarify" && choice !in prompt.choices) return
        if (choice.isBlank()) return
        val epoch = generation
        mutable.update { it.copy(decisionSending = true) }
        decision = scope.launch {
            try {
                backend.respond(binding, prompt, choice)
                if (!owns(epoch, binding)) return@launch
                retired += binding to requestId
                mutable.update { it.copy(pending = it.pending?.takeUnless { p -> p.requestId == requestId }, decisionSending = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owns(epoch, binding)) {
                    // Keep the request disabled until an authoritative refresh after a lost response.
                    error("Decision was not confirmed. Refresh before trying again. ${message(e)}")
                }
            }
        }
    }

    fun interrupt() {
        val binding = state.value.binding ?: return
        if (!state.value.connected) return
        val epoch = generation
        stopVoice()
        scope.launch {
            try {
                backend.interrupt(binding)
                if (owns(epoch, binding) && !backend.capabilities.decisions) {
                    turn?.cancelAndJoin()
                    mutable.update { it.copy(working = false, turnActive = false) }
                    refreshHistory()
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) error("Could not confirm interruption. ${message(e)}") }
        }
    }

    fun voice(value: VoiceStreamState) { mutable.update { it.copy(voice = value) } }
    fun microphoneReady(value: Boolean) { mutable.update { it.copy(microphoneReady = value) } }
    fun speechMode(value: String) { mutable.update { it.copy(speechMode = value) } }
    fun vision(value: Boolean) { mutable.update { it.copy(visionSharing = value) } }
    fun acknowledgeCompaction() {
        val binding = state.value.binding ?: return
        if (state.value.compacting) return
        if (persistence.saveCompactionPending(binding, false)) mutable.update { it.copy(compactionUncertain = false, error = null) }
    }
    fun loadSettings() {
        val binding = state.value.binding ?: return
        if (settings?.isActive == true) return
        val epoch = generation
        mutable.update { it.copy(settingsBusy = true) }
        settings = scope.launch {
            try {
                val loaded = backend.settings(binding)
                if (owns(epoch, binding)) mutable.update { it.copy(modelSettings = loaded, settingsBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) mutable.update { it.copy(settingsBusy = false, settingNotice = message(e)) } }
        }
    }
    fun setSetting(key: String, value: String, confirmed: Boolean = false) {
        val s = state.value
        val binding = s.binding ?: return
        if (!s.canSend || s.settingsBusy || key !in setOf("model", "reasoning", "fast")) return
        val confirmation = s.settingConfirmation
        if (confirmed && (confirmation?.key != key || confirmation.value != value)) return
        val epoch = generation
        stopVoice()
        mutable.update { it.copy(settingsBusy = true, settingConfirmation = null, settingNotice = null) }
        settings = scope.launch {
            try {
                val result = backend.setting(binding, key, value, confirmed)
                if (!owns(epoch, binding)) return@launch
                if (result.confirmationRequired) {
                    mutable.update { it.copy(settingsBusy = false, settingConfirmation = DeskSettingConfirmation(key, value, result.confirmation)) }
                } else {
                    val loaded = backend.settings(binding)
                    if (owns(epoch, binding)) mutable.update { it.copy(settingsBusy = false, modelSettings = loaded,
                        settingNotice = if (result.deferred) "Queued on Hermes; refresh to confirm the applied model" else result.warning.takeIf(String::isNotBlank)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) mutable.update { it.copy(settingsBusy = false, settingNotice = "Setting was not confirmed. Refresh to check. ${message(e)}") } }
        }
    }

    /** One durable mutation. PENDING/unknown never become an automatic retry after restart. */
    fun compact() {
        val s = state.value
        val binding = s.binding ?: return
        if (!s.canSend || s.voice != VoiceStreamState.IDLE || compaction?.isActive == true) return
        if (!persistence.saveCompactionPending(binding, true)) { error("Cannot save context maintenance state"); return }
        val epoch = generation
        mutable.update { it.copy(compacting = true, error = null) }
        compaction = scope.launch {
            try {
                val result = backend.compact(binding)
                if (!owns(epoch, binding)) return@launch
                val resolved = result.status in setOf(CompressionStatus.COMPRESSED, CompressionStatus.ABORTED)
                val saved = resolved && persistence.saveCompactionPending(binding, false)
                mutable.update { it.copy(compacting = false, compactionUncertain = !saved,
                    messages = if (result.status == CompressionStatus.COMPRESSED) result.messages ?: it.messages else it.messages,
                    error = when { !saved -> "Compaction is not confirmed. Check host history; it will not be retried automatically."
                        result.status == CompressionStatus.ABORTED -> result.message.ifBlank { "Compaction was aborted; history is unchanged" }
                        else -> null }) }
                refreshContext(epoch, binding)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(epoch, binding)) mutable.update { it.copy(compacting = false, compactionUncertain = true,
                error = "Compaction was not confirmed. Check the host before trying again. ${message(e)}") } }
        }
    }

    private suspend fun refreshContext(epoch: Long, binding: DeskBinding) {
        try {
            val context = backend.context(binding)
            if (owns(epoch, binding)) mutable.update { it.copy(context = context) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Unsupported/unknown context must not invalidate a working conversation. */ }
    }
    fun ambient(value: Boolean) { mutable.update { it.copy(ambient = value) } }
    fun preferences(privacy: Boolean, awake: Boolean, reduced: Boolean) {
        mutable.update { it.copy(privateCaptions = privacy, keepAwake = awake, reducedMotion = reduced) }
    }
    fun error(text: String?) { mutable.update { it.copy(error = text) } }
    fun stopVoice() { mutable.update { it.copy(voice = VoiceStreamState.IDLE) }; signalsMutable.tryEmit(DeskSignal.StopVoice) }
    fun close() { invalidate(); backend.close() }

    private fun invalidate(keepBus: Boolean = false) {
        generation++; navigation?.cancel(); turn?.cancel(); decision?.cancel(); refresh?.cancel(); compaction?.cancel(); settings?.cancel()
        mutable.update { it.copy(compacting = false, visionSharing = false, microphoneReady = false, modelSettings = null, settingsBusy = false, settingConfirmation = null, settingNotice = null) }
        if (!keepBus) bus?.cancel()
        stopVoice()
    }
    private fun owns(epoch: Long, binding: DeskBinding) = epoch == generation && state.value.binding == binding
    private fun activePrompt(binding: DeskBinding, prompt: ApprovalPrompt?) = prompt?.takeUnless { binding to it.requestId in retired }
    private fun reconcile(binding: DeskBinding, messages: List<ChatMessage>) {
        persistence.pending(binding)?.let { if (DeliveryPolicy.landed(it, messages)) persistence.savePending(binding, null) }
    }
    private fun startBus(epoch: Long, profile: String) {
        if (!backend.capabilities.liveEvents) return
        bus = scope.launch {
            try {
                backend.events(profile).collect { frame ->
                    val binding = state.value.binding
                    if (epoch != generation || binding == null || turn?.isActive == true) return@collect
                    if (frame is BusFrame.Chat && backend.belongsTo(binding, frame.sessionId)) applyEvent(binding, frame.event)
                }
                if (epoch == generation) { stopVoice(); mutable.update { it.copy(connected = false, working = false, error = "Connection closed. Reconnect to check history.") } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) { stopVoice(); mutable.update { it.copy(connected = false, working = false, error = message(e)) } } }
        }
    }
    private fun applyEvent(binding: DeskBinding, event: ChatEvent) {
        when (event) {
            is ChatEvent.Accepted -> mutable.update { s -> s.copy(messages = s.messages.map { if (it.delivery == MessageDelivery.SENDING) it.copy(delivery = MessageDelivery.SENT) else it }) }
            is ChatEvent.AssistantDelta -> mutable.update { s ->
                val last = s.messages.lastOrNull()
                val rows = if (last?.role == MessageRole.ASSISTANT && last.streaming) s.messages.dropLast(1) + last.copy(text = last.text + event.text)
                    else s.messages + ChatMessage("desk-${UUID.randomUUID()}", MessageRole.ASSISTANT, event.text, streaming = true)
                s.copy(messages = rows, working = true)
            }
            is ChatEvent.Final -> mutable.update { s ->
                val last = s.messages.lastOrNull()
                val rows = if (last?.role == MessageRole.ASSISTANT && last.streaming) s.messages.dropLast(1) + last.copy(text = event.text.ifBlank { last.text }, streaming = false)
                    else if (event.text.isNotBlank()) s.messages + ChatMessage("desk-${UUID.randomUUID()}", MessageRole.ASSISTANT, event.text) else s.messages
                s.copy(messages = rows, working = false, tool = "", error = event.error.takeIf { it.isNotBlank() })
            }
            ChatEvent.Completed -> mutable.update { it.copy(working = false, tool = "", messages = it.messages.map { m -> m.copy(streaming = false) }) }
            is ChatEvent.ToolStarted -> mutable.update { it.copy(tool = event.name, working = true) }
            is ChatEvent.ToolCompleted -> mutable.update { it.copy(tool = "") }
            is ChatEvent.Approval -> { stopVoice(); mutable.update { it.copy(pending = activePrompt(binding, event.prompt)) } }
            is ChatEvent.PromptExpired -> { retired += binding to event.requestId; mutable.update { it.copy(pending = it.pending?.takeUnless { p -> p.requestId == event.requestId }) } }
            else -> Unit
        }
    }
    private fun message(e: Exception) = e.message?.take(240) ?: "Could not complete this operation"
}
