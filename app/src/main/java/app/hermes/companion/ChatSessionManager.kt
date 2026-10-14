package app.hermes.companion

import androidx.lifecycle.ViewModel
import app.hermes.companion.data.local.OutboxStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.domain.ChatContent
import app.hermes.companion.domain.coalesceDeltas
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.domain.DeliveryPolicy
import app.hermes.companion.domain.HistoryPaging
import app.hermes.companion.domain.OutboxPolicy
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.RewindPolicy
import app.hermes.companion.domain.RewindSubmit
import app.hermes.companion.domain.SendFate
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.MessageDelivery
import app.hermes.companion.model.OutboxItem
import app.hermes.companion.model.SessionRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Operator chat: open/close a session, history paging, approvals, outbox flush with backoff,
 * rewind and interrupt (A7.4). Shares the UI state holder with the ViewModel; runs on the
 * ViewModel scope because every job here is bound to what the user is looking at.
 */
class ChatSessionManager(
    private val clients: HostClientPool,
    private val cache: TranscriptCache,
    private val outbox: OutboxStore,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    draftStore: app.hermes.companion.data.local.ComposerDraftStore? = null,
    private val submitDecision: suspend (String, String, String, app.hermes.companion.model.ApprovalPrompt, String) -> Unit =
        { origin, session, profile, prompt, decision -> clients.forOrigin(origin).respondPrompt(origin, session, profile, prompt, decision) },
) {
    private val drafts = draftStore?.let { DraftRecovery(it, _state, scope) }
    fun checkpointDraft() { drafts?.checkpoint() }
    private var turnJob: Job? = null
    private var retryJob: Job? = null
    private val outboxMutex = Mutex()
    private val flushRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val draftMutex = Mutex()
    private var historyJob: Job? = null
    @Volatile private var navigation = 0L
    /** User navigation identity; draft promotion retains it. Speech must never cross this boundary. */
    val conversationEpoch: Long get() = navigation
    private val instanceId = UUID.randomUUID().toString()
    private val createdSessions = java.util.concurrent.ConcurrentHashMap<String, SessionRef>()
    private val promotedDrafts = mutableMapOf<String, SessionRef>()
    private val assistantIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val deciding = java.util.concurrent.ConcurrentHashMap.newKeySet<List<String>>()
    private val decisionRevision = java.util.concurrent.atomic.AtomicLong()
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    var onAssistantDelta: ((String) -> Unit)? = null
    var onTurnCompleted: (() -> Unit)? = null
    var onTurnInterrupted: (() -> Unit)? = null

    private fun turnModel(): String? = _state.value.modelOverride.trim().takeIf { it.isNotBlank() }

    /** Cancel the streaming turn (profile switch, chat close). */
    fun cancelTurn() {
        turnJob?.cancel()
        turnJob = null
    }

    fun cancelAll() {
        checkpointDraft()
        navigation++
        historyJob?.cancel()
        cancelTurn()
        retryJob?.cancel()
        retryJob = null
        _state.update { it.copy(streamingSessionId = null) }
    }

    fun openSession(session: SessionRef) {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        val owned = try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        checkpointDraft()
        val recovered = drafts?.restore(origin, profile, owned.id)
        val generation = ++navigation
        historyJob?.cancel()
        _state.update {
            it.copy(openSessionId = owned.id, openSessionRef = owned, messages = emptyList(),
                transcriptLoading = true, historySource = "", draft = recovered?.text.orEmpty(), approval = null, approvalSubmittingRequestId = null,
                submitting = false, creatingThread = false, pendingAttachments = recovered?.attachments.orEmpty(),
                rewindTargetId = null, streaming = streamingFor(it, owned.id),
                historyHasMore = false, historyLoading = false, error = drafts?.warning(recovered))
        }
        historyJob = scope.launch {
            val approvalRevision = decisionRevision.get()
            val cached = runCatching { cache.messages(origin, profile, owned.id) }.getOrDefault(emptyList())
            val cachedTail = withQueued(origin, profile, owned.id, HistoryPaging.tail(cached))
            _state.update { st ->
                if (generation != navigation || !st.shows(origin, profile, owned.id) || st.messages.isNotEmpty()) st
                else st.copy(messages = cachedTail, historyHasMore = cached.size > cachedTail.size)
            }
            val baseline = _state.value.messages
            try {
                val page = client(origin).pageMessages(origin, owned.id, profile, ended = owned.ended)
                if (generation == navigation && _state.value.shows(origin, profile, owned.id) && _state.value.messages == baseline) {
                    cache.replaceMessages(origin, profile, owned.id, page.messages)
                }
                val approval = runCatching { client(origin).pendingApproval(origin, owned.id, profile) }.getOrNull()
                val snapshot = client(origin).runtimeSnapshot(owned.id)
                val aid = assistantIds["$origin|$profile|${owned.id}"] ?: "live-${owned.id}"
                val resumed = if (snapshot?.running == true && snapshot.assistant.isNotBlank()) page.messages + ChatMessage(
                    aid, MessageRole.ASSISTANT, snapshot.assistant, streaming = true,
                ) else page.messages
                val merged = withQueued(origin, profile, owned.id, resumed)
                _state.update { state ->
                    if (generation != navigation || !state.shows(origin, profile, owned.id)) state
                    else state.copy(
                        transcriptLoading = false,
                        historySource = page.source,
                        // A history request that started before send must not erase the live turn.
                        messages = if (state.messages != baseline) state.messages else merged,
                        streaming = if (state.messages != baseline) state.streaming
                            else streamingFor(state, owned.id) || snapshot?.running == true,
                        approval = state.approval ?: approval?.takeIf {
                            approvalRevision == decisionRevision.get() && !client(origin).isPromptRetired(owned.id, it.requestId)
                        },
                        historyHasMore = page.hasMore,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { state ->
                    if (generation != navigation || !state.shows(origin, profile, owned.id)) state
                    else state.copy(transcriptLoading = false, error = t.toMonoError())
                }
            }
        }
    }

    /**
     * Leave the chat window.
     *
     * Deliberately does **not** cancel the in-flight turn. It used to, and that produced a
     * nasty loop: cancelling left `flushOutbox` on its `cancelled` path, which returned without
     * `outbox.remove`, so the item stayed pending — and every re-entry rendered it as queued
     * *and* re-submitted it. Exiting a window means "stop looking", not "stop the agent"; the
     * turn keeps running so it reaches `outbox.remove` and the host keeps the reply. Use
     * [interrupt] to actually stop an agent.
     */
    fun closeChat() {
        checkpointDraft()
        navigation++
        historyJob?.cancel()
        _state.update {
            it.copy(
                openSessionId = null,
                openSessionRef = null,
                messages = emptyList(),
                draft = "",
                pendingAttachments = emptyList(),
                submitting = false,
                creatingThread = false,
                streaming = false,
                approval = null,
                approvalSubmittingRequestId = null,
                rewindTargetId = null,
                historyHasMore = false,
                historyLoading = false,
                transcriptLoading = false,
                historySource = "",
            )
        }
    }

    fun loadOlder() {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        if (DraftThread.isDraft(session)) return
        val st = _state.value
        if (st.historyLoading || !st.historyHasMore || st.messages.isEmpty()) return
        val before = st.messages.first().id
        scope.launch {
            _state.update { it.copy(historyLoading = true) }
            try {
                val page = client(origin).pageMessages(
                    origin,
                    session.id,
                    profile,
                    beforeId = before,
                    ended = session.ended,
                )
                val seen = _state.value.messages.map { it.id }.toSet()
                val older = page.messages.filter { it.id !in seen }
                _state.update { state ->
                    if (!state.shows(origin, profile, session.id)) state
                    else state.copy(
                        historyLoading = false,
                        messages = older + state.messages,
                        historyHasMore = page.hasMore && older.isNotEmpty(),
                    )
                }
                persistOpenMessages()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!it.shows(origin, profile, session.id)) it else it.copy(historyLoading = false, error = t.toMonoError()) }
            }
        }
    }

    fun respondApproval(decision: String) {
        val captured = _state.value
        if (captured.inRoom) return
        val origin = captured.origin ?: return
        val session = captured.openSession ?: return
        val profile = captured.activeProfileId ?: return
        val prompt = captured.approval ?: return
        val generation = navigation
        val key = listOf(origin, profile, session.id, prompt.kind, prompt.requestId)
        if (!deciding.add(key)) return
        decisionRevision.incrementAndGet()
        fun owns(st: CompanionState) = generation == navigation && st.shows(origin, profile, session.id) && !st.inRoom
        _state.update { if (!owns(it) || it.approval != prompt) it else it.copy(approvalSubmittingRequestId = prompt.requestId, error = null) }
        scope.launch {
            try {
                ProfileScope.requireOwnedSession(session, profile)
                if (!owns(_state.value) || _state.value.approval != prompt) return@launch
                submitDecision(origin, session.id, profile, prompt, decision)
                decisionRevision.incrementAndGet()
                val note = when (prompt.kind) {
                    "clarify" -> "Response submitted."
                    "sudo", "secret" -> "Secure response submitted."
                    else -> if (decision == "deny") "denied ${prompt.command}" else "allowed ${prompt.command}"
                }
                _state.update {
                    if (!owns(it) || it.approval != prompt) it else it.copy(
                        approval = null,
                        approvalSubmittingRequestId = null,
                        messages = it.messages + ChatMessage(
                            id = "apr-${prompt.requestId}",
                            role = MessageRole.ASSISTANT,
                            text = note,
                        ),
                    )
                }
                if (owns(_state.value)) persistOpenMessages()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ((t as? DashboardException)?.code == "request_expired") decisionRevision.incrementAndGet()
                _state.update {
                    if (!owns(it) || it.approval != prompt) it else it.copy(error = t.toMonoError(),
                        approval = if ((t as? DashboardException)?.code == "request_expired") null else it.approval)
                }
            } finally {
                deciding.remove(key)
                _state.update { if (!owns(it) || it.approvalSubmittingRequestId != prompt.requestId) it
                    else it.copy(approvalSubmittingRequestId = null) }
            }
        }
    }

    /** Open a local dummy composer. Host `session.create` waits until the first send. */
    fun newThread() {
        if (_state.value.origin == null) return
        val profile = _state.value.activeProfileId ?: return
        checkpointDraft()
        val recovered = drafts?.restore(_state.value.origin!!, profile, DraftThread.ID)
        navigation++
        historyJob?.cancel()
        val draft = DraftThread.placeholder(profile)
        _state.update {
            it.copy(
                openSessionId = draft.id,
                openSessionRef = draft,
                transcriptLoading = false,
                historySource = "",
                error = drafts?.warning(recovered),
                draft = recovered?.text.orEmpty(),
                approval = null,
                approvalSubmittingRequestId = null,
                rewindTargetId = null,
                messages = emptyList(),
                historyHasMore = false,
                historyLoading = false,
                pendingAttachments = recovered?.attachments.orEmpty(),
                attachOpen = false,
                streaming = false,
                submitting = false,
                creatingThread = false,
            )
        }
    }

    /** NEW creates a real host session immediately; first-send also promotes a local draft. */
    fun createThread() {
        val st = _state.value
        if (st.creatingThread) return
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        checkpointDraft()
        val generation = ++navigation
        _state.update { it.copy(creatingThread = true) }
        scope.launch {
            try {
                val created = ensurePersistedSession(origin, profile, DraftThread.placeholder(profile),
                    generation, st.modelOverride, promoteDraft = false, activate = false)
                if (created != null && generation == navigation && _state.value.owns(origin, profile)) openSession(created)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // ensurePersistedSession reports the scoped error; leave the original composer intact.
            } finally {
                _state.update { if (generation != navigation || !it.owns(origin, profile)) it else it.copy(creatingThread = false) }
            }
        }
    }

    fun retainCreated(origin: String, profile: String, rows: List<SessionRef>): List<SessionRef> {
        val prefix = "$origin|$profile|"
        rows.forEach { createdSessions.remove(prefix + it.id) }
        return SessionLists.normalize(rows + createdSessions.filterKeys { it.startsWith(prefix) }.values)
    }

    fun updateSession(title: String? = null, pinned: Boolean? = null, archived: Boolean? = null) {
        val st = _state.value
        val origin = st.origin ?: return
        val session = st.openSession ?: return
        if (st.sessionActionsBusy || DraftThread.isDraft(session)) return
        _state.update { it.copy(sessionActionsBusy = true) }
        scope.launch {
            try {
                val updated = client(origin).updateSession(origin, session, title, pinned, archived)
                cache.upsertSession(origin, updated)
                createdSessions.remove("$origin|${session.profileId}|${session.id}")
                _state.update {
                    if (!it.owns(origin, session.profileId)) it else it.copy(
                        sessions = SessionLists.prepend(updated, it.sessions),
                        openSessionRef = if (it.openSessionId == updated.id) updated else it.openSessionRef,
                        sessionActionsBusy = false, error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!it.owns(origin, session.profileId)) it else it.copy(sessionActionsBusy = false, error = t.toMonoError()) }
            }
        }
    }

    fun branchSession() {
        val st = _state.value
        val origin = st.origin ?: return
        val session = st.openSession ?: return
        if (st.sessionActionsBusy || st.streaming || DraftThread.isDraft(session)) return
        _state.update { it.copy(sessionActionsBusy = true) }
        val generation = navigation
        scope.launch {
            try {
                val created = client(origin).branchSession(origin, session, UUID.randomUUID().toString())
                cache.upsertSession(origin, created)
                createdSessions["$origin|${session.profileId}|${created.id}"] = created
                if (_state.value.owns(origin, session.profileId)) {
                    _state.update { it.copy(sessions = SessionLists.prepend(created, it.sessions), sessionActionsBusy = false) }
                    if (generation == navigation) openSession(created)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!it.owns(origin, session.profileId)) it else it.copy(sessionActionsBusy = false, error = t.toMonoError()) }
            }
        }
    }

    fun beginRewind(message: ChatMessage) {
        if (_state.value.streaming) return
        if (!RewindPolicy.canTarget(message)) return
        _state.update {
            it.copy(rewindTargetId = message.id, draft = message.text, error = null)
        }
    }

    fun cancelRewind() {
        _state.update { it.copy(rewindTargetId = null) }
    }

    /** Surface a blocked send instead of swallowing it — the composer keeps the draft. */
    private fun fail(reason: String) {
        _state.update { it.copy(error = "send blocked · $reason") }
    }

    fun send() {
        val st = _state.value
        if (st.submitting || st.creatingThread) return
        val origin = st.origin ?: return fail("not connected")
        val session = st.openSession ?: return fail("no open thread")
        val profile = st.activeProfileId ?: return fail("no profile")
        val text = st.draft.trim()
        if (text.isBlank() && st.pendingAttachments.isEmpty()) return
        if (st.streaming) {
            steer(origin, profile, session, text)
            return
        }
        st.rewindTargetId?.let {
            if (DraftThread.isDraft(session)) return fail("no open thread")
            sendRewind(origin, session, profile, it, text)
            return
        }
        enqueueInput(st, text, st.pendingAttachments, clearDraft = true)
    }

    /** Voice uses the conversation captured at recognition, without clearing keyboard drafts. */
    fun sendPrompt(text: String, displayText: String = text) {
        val st = _state.value
        if (st.submitting || text.isBlank()) return
        if (st.openSession == null) return fail("no open voice conversation")
        enqueueInput(st, text.trim(), emptyList(), clearDraft = false, displayText = displayText)
    }

    private fun enqueueInput(snapshot: CompanionState, text: String, attachments: List<ChatAttachment>, clearDraft: Boolean, displayText: String = text) {
        val origin = snapshot.origin ?: return
        val profile = snapshot.activeProfileId ?: return
        val session = snapshot.openSession ?: return
        ProfileScope.requireOwnedSession(session, profile)
        val generation = navigation
        val user = ChatMessage(id = "u-" + UUID.randomUUID(), role = MessageRole.USER, text = displayText,
            queued = true, delivery = MessageDelivery.QUEUED, blocks = ChatContent.userBlocks(displayText, attachments))
        // Synchronous admission prevents repeated taps from creating duplicate sessions/messages.
        _state.update { it.copy(submitting = true, messages = it.messages + user, error = null) }
        scope.launch {
            try {
                val persisted = ensurePersistedSession(origin, profile, session, generation, snapshot.modelOverride, draftSnapshot = snapshot)
                    ?: throw DashboardException("create_failed", "could not create thread")
                outbox.enqueue(OutboxItem(user.id, origin, profile, persisted.id, text,
                    System.currentTimeMillis(), attachmentsJson = encodeAttachments(attachments)))
                _state.update { st ->
                    if (!st.shows(origin, profile, persisted.id) && !(DraftThread.isDraft(session) && st.shows(origin, profile, session.id))) st
                    else st.copy(submitting = if (generation == navigation) false else st.submitting,
                        draft = if (clearDraft && st.draft == snapshot.draft) "" else st.draft,
                        pendingAttachments = if (clearDraft) st.pendingAttachments.filterNot { it in attachments } else st.pendingAttachments,
                        attachOpen = false)
                }
                if (clearDraft) {
                    checkpointDraft()
                    drafts?.consume(snapshot, persisted.id)
                    if (DraftThread.isDraft(session)) drafts?.consume(snapshot, session.id)
                }
                flushOutbox()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { st ->
                    if (!st.owns(origin, profile) || generation != navigation) st
                    else st.copy(submitting = false, error = t.toMonoError(),
                        messages = st.messages.filterNot { it.id == user.id })
                }
            }
        }
    }

    private fun steer(origin: String, profile: String, session: SessionRef, text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(submitting = true) }
        scope.launch {
            try {
                client(origin).steerSession(origin, session, text)
                _state.update { st ->
                    if (!st.shows(origin, profile, session.id)) st
                    else st.copy(submitting = false, draft = if (st.draft.trim() == text) "" else st.draft,
                        messages = st.messages + ChatMessage("steer-" + UUID.randomUUID(), MessageRole.USER, text, delivery = MessageDelivery.SENT))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!it.shows(origin, profile, session.id)) it else it.copy(submitting = false, error = t.toMonoError()) }
            }
        }
    }

    private fun sendRewind(
        origin: String,
        session: SessionRef,
        profile: String,
        targetId: String,
        text: String,
    ) {
        val rowId = RewindPolicy.durableRowId(targetId)
        if (rowId == null) {
            _state.update { it.copy(error = "rewind needs a durable row", rewindTargetId = null) }
            return
        }
        if (_state.value.streaming) return
        val empty = !_state.value.historyHasMore && RewindPolicy.isFirstUserTurn(_state.value.messages, targetId)
        val spec = RewindSubmit(rowId = rowId, empty = empty)
        val kept = RewindPolicy.dropFrom(_state.value.messages, targetId)
        val user = ChatMessage(
            id = "u-${UUID.randomUUID()}",
            role = MessageRole.USER,
            text = text,
            delivery = MessageDelivery.SENDING,
        )
        scope.launch {
            if (!outboxMutex.tryLock()) {
                _state.update { it.copy(error = "session busy") }
                return@launch
            }
            val assistantId = "a-${UUID.randomUUID()}"
            try {
                _state.update {
                    it.copy(
                        draft = "",
                        error = null,
                        rewindTargetId = null,
                        streaming = true,
                        streamingSessionId = session.id,
                        messages = kept + user,
                    )
                }
                coroutineScope {
                    val job = launch {
                        client(origin).streamTurn(origin, session.id, profile, text, spec, turnModel()).coalesceDeltas().collect { event ->
                            if (_state.value.shows(origin, profile, session.id)) {
                                _state.update { applyEvent(it, event, assistantId) }
                                if (event is ChatEvent.Accepted) {
                                    _state.update { it.copy(messages = it.messages.map { m ->
                                        if (m.id == user.id) m.copy(delivery = MessageDelivery.SENT) else m
                                    }) }
                                }
                                if (event is ChatEvent.AssistantDelta) {
                                    onAssistantDelta?.invoke(event.text)
                                }
                            }
                        }
                    }
                    turnJob = job
                    job.join()
                }
                if (_state.value.shows(origin, profile, session.id)) {
                    _state.update { finishStream(it, assistantId) }
                    persistOpenMessages()
                    onTurnCompleted?.invoke()
                }
            } catch (c: CancellationException) {
                onTurnInterrupted?.invoke()
                throw c
            } catch (t: Throwable) {
                onTurnCompleted?.invoke()
                runCatching { client(origin).pageMessages(origin, session.id, profile) }.onSuccess { page ->
                    runCatching { cache.replaceMessages(origin, profile, session.id, page.messages) }
                    if (_state.value.shows(origin, profile, session.id)) {
                        _state.update {
                            finishStream(it, assistantId).copy(
                                messages = page.messages,
                                historyHasMore = page.hasMore,
                            )
                        }
                    }
                }
                _state.update { if (!it.shows(origin, profile, session.id)) it else it.copy(streaming = false, error = t.toMonoError(), draft = it.draft.ifBlank { text }) }
            } finally {
                if (_state.value.owns(origin, profile)) clearStreamingMarker(session.id)
                outboxMutex.unlock()
            }
        }
    }

    fun requestDelete(session: SessionRef) {
        val profile = _state.value.activeProfileId
        val owned = try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        _state.update { it.copy(pendingDelete = owned) }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        val session = _state.value.pendingDelete ?: return
        val owned = try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(pendingDelete = null, error = t.toMonoError()) }
            return
        }
        scope.launch {
            val previous = _state.value.sessions
            val wasOpen = _state.value.shows(origin, profile, owned.id)
            if (wasOpen) cancelTurn()
            _state.update {
                if (!it.owns(origin, profile)) it else it.copy(
                    pendingDelete = null,
                    sessions = it.sessions.filter { row -> row.id != owned.id },
                    openSessionId = if (wasOpen) null else it.openSessionId,
                    messages = if (wasOpen) emptyList() else it.messages,
                    streaming = if (wasOpen) false else it.streaming,
                    transcriptLoading = if (wasOpen) false else it.transcriptLoading,
                    approval = if (wasOpen) null else it.approval,
                    rewindTargetId = if (wasOpen) null else it.rewindTargetId,
                    draft = if (wasOpen) "" else it.draft,
                    error = null,
                )
            }
            try {
                client(origin).deleteSession(origin, owned.id, profile)
                cache.deleteSession(origin, profile, owned.id)
                outbox.removeForSession(origin, profile, owned.id)
                createdSessions.remove("$origin|$profile|${owned.id}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val restored = runCatching { client(origin).listSessions(origin, profile, _state.value.showArchived) }
                    .getOrDefault(previous)
                runCatching { cache.replaceSessions(origin, profile, restored) }
                _state.update { if (!it.owns(origin, profile)) it else it.copy(sessions = SessionLists.normalize(restored), error = t.toMonoError()) }
            }
        }
    }

    fun interrupt() {
        turnJob?.cancel()
        val origin = _state.value.origin
        val session = _state.value.openSession
        val profile = _state.value.activeProfileId
        _state.update { it.copy(streaming = false, messages = it.messages.map { m -> m.copy(streaming = false, toolRunning = false) }) }
        onTurnInterrupted?.invoke()
        if (origin == null || session == null || profile == null) return
        if (DraftThread.isDraft(session)) return
        scope.launch {
            runCatching { client(origin).interruptTurn(origin, session.id, profile) }.onFailure { error ->
                _state.update { if (!it.shows(origin, profile, session.id)) it else it.copy(error = "Could not interrupt Hermes: " + error.toMonoError()) }
            }
        }
    }

    /**
     * Has this queued item's turn already landed on the host?
     *
     * Called only for retries. Fetches the transcript and asks [OutboxPolicy.landedIds] whether
     * the text shows up as a USER message in the tail. Fetch failures answer "no", so a
     * reconcile that cannot reach the host never discards a message.
     */
    private suspend fun reconcileLanded(
        origin: String,
        profile: String,
        item: OutboxItem,
        requireAttempt: Boolean = true,
    ): Boolean {
        val page = runCatching {
            client(origin).pageMessages(origin, item.sessionId, profile)
        }.getOrNull() ?: return false
        val landed = DeliveryPolicy.landed(item, page.messages)
        if (landed && _state.value.shows(origin, profile, item.sessionId)) {
            _state.update { st ->
                if (!st.shows(origin, profile, item.sessionId)) st
                else st.copy(messages = page.messages, streaming = false, error = null)
            }
        }
        return landed
    }

    internal suspend fun withQueued(
        origin: String,
        profileId: String,
        sessionId: String,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val pending = runCatching { outbox.pending(origin, profileId) }.getOrDefault(emptyList())
            .filter { it.sessionId == sessionId }
        return mergeQueued(messages, pending)
    }

    internal suspend fun flushOutbox() {
        if (!outboxMutex.tryLock()) { flushRequested.set(true); return }
        try {
            val origin = _state.value.origin ?: return
            val profile = _state.value.activeProfileId ?: return
            val skip = mutableSetOf<String>()
            while (_state.value.owns(origin, profile)) {
                val item = outbox.pending(origin, profile).firstOrNull { it.id !in skip } ?: return
                skip.add(item.id)
                if (DraftThread.isDraft(item.sessionId)) continue
                if (item.baselineUserIds != null && reconcileLanded(origin, profile, item)) {
                    outbox.remove(item.id)
                    continue
                }
                if (DeliveryPolicy.uncertain(item) || OutboxPolicy.exhausted(item.attempts) ||
                    item.lastError.startsWith("failed")) {
                    updateDelivery(origin, profile, item, if (DeliveryPolicy.uncertain(item)) MessageDelivery.UNCERTAIN else MessageDelivery.FAILED)
                    continue
                }
                val assistantId = "a-" + UUID.randomUUID()
                assistantIds["$origin|$profile|${item.sessionId}"] = assistantId
                var accepted = false
                var submissionStarted = false
                var spokeDeltas = false
                _state.update { st ->
                    if (!st.owns(origin, profile)) st else st.copy(
                        streamingSessionId = item.sessionId,
                        streaming = st.openSessionId == item.sessionId || st.streaming,
                        messages = if (st.openSessionId != item.sessionId) st.messages else st.messages.map {
                            if (it.id == item.id) it.copy(queued = false, delivery = MessageDelivery.SENDING) else it
                        }, error = null)
                }
                try {
                    val api = client(origin)
                    // Native Hermes has no legacy REST create/chat-stream contract. Recover the
                    // live channel before staging or sending, rather than falling into a 404.
                    api.wsHello(origin, profile)
                    if (!_state.value.owns(origin, profile)) return
                    val attachments = decodeAttachments(item.attachmentsJson)
                    val legacy = api.legacyOperator()
                    val uploaded = mutableListOf<ChatAttachment>()
                    val references = attachments.map { attachment ->
                        val bytes = withContext(Dispatchers.IO) {
                            java.io.File(attachment.localUri.removePrefix("file://")).readBytes()
                        }
                        if (legacy) {
                            uploaded += attachment.copy(uploadedUrl = api.uploadMedia(origin, bytes, attachment.name, attachment.mime))
                            ""
                        } else api.stageAttachment(origin, item.sessionId, profile, bytes, attachment.name, attachment.mime)
                    }
                    val prompt = (listOf(item.text) + references.filter { it.isNotBlank() }).joinToString("\n\n")
                    val baseline = api.pageMessages(origin, item.sessionId, profile).messages
                        .filter { it.role == MessageRole.USER }.map { it.id }
                    if (!_state.value.owns(origin, profile)) return
                    outbox.beginSubmission(item, baseline)
                    submissionStarted = true
                    coroutineScope {
                        val job = launch {
                            api.streamTurn(origin, item.sessionId, profile, prompt,
                                model = if (legacy) turnModel() else null,
                                partsJson = if (legacy) ChatContent.toSubmitParts(item.text, uploaded) else "",
                                clientId = if (legacy) item.id else "",
                            ).coalesceDeltas().collect { event ->
                                if (event is ChatEvent.Accepted || event is ChatEvent.Final || event is ChatEvent.Completed) {
                                    // Once acknowledged, generation failure must not resend the user input.
                                    accepted = true
                                    outbox.remove(item.id)
                                    updateDelivery(origin, profile, item, MessageDelivery.SENT)
                                    if (_state.value.shows(origin, profile, item.sessionId)) persistOpenMessages()
                                }
                                if (_state.value.shows(origin, profile, item.sessionId)) {
                                    _state.update { if (!it.shows(origin, profile, item.sessionId)) it else applyEvent(it, event, assistantId) }
                                    if (event is ChatEvent.AssistantDelta) {
                                        spokeDeltas = true
                                        onAssistantDelta?.invoke(event.text)
                                    } else if (event is ChatEvent.Final && !spokeDeltas && event.text.isNotBlank()) {
                                        onAssistantDelta?.invoke(event.text)
                                    }
                                }
                            }
                        }
                        turnJob = job
                        job.join()
                        if (job.isCancelled && !accepted) {
                            outbox.markAttempt(item.id, origin, profile, if (submissionStarted) "uncertain: stream cancelled" else "queued")
                            updateDelivery(origin, profile, item, if (submissionStarted) MessageDelivery.UNCERTAIN else MessageDelivery.QUEUED)
                        }
                    }
                    if (accepted) {
                        if (_state.value.shows(origin, profile, item.sessionId)) {
                            _state.update { finishStream(it, assistantId) }
                            persistOpenMessages()
                            onTurnCompleted?.invoke()
                        }
                        refreshHistory(origin, profile, item.sessionId, turnFinished = true)
                    }
                } catch (c: CancellationException) {
                    if (!accepted && submissionStarted) {
                        outbox.markAttempt(item.id, origin, profile, "uncertain: connection changed")
                    }
                    throw c
                } catch (t: Throwable) {
                    val code = (t as? DashboardException)?.code.orEmpty()
                    val rejected = code.startsWith("rpc_") || code.startsWith("http_4") || code == "submit_rejected"
                    val uncertain = !accepted && submissionStarted && !rejected && code != "ws_send"
                    val offline = OutboxPolicy.isOffline(code, t.message.orEmpty())
                    val delivery = when {
                        accepted -> MessageDelivery.SENT
                        uncertain -> MessageDelivery.UNCERTAIN
                        offline && !submissionStarted -> MessageDelivery.QUEUED
                        else -> MessageDelivery.FAILED
                    }
                    if (!accepted) outbox.markAttempt(item.id, origin, profile,
                        when (delivery) {
                            MessageDelivery.UNCERTAIN -> "uncertain: " + t.toMonoError()
                            MessageDelivery.FAILED -> "failed: " + t.toMonoError()
                            else -> t.toMonoError()
                        })
                    updateDelivery(origin, profile, item, delivery)
                    _state.update { st ->
                        if (!st.shows(origin, profile, item.sessionId)) st
                        else finishStream(st, assistantId).copy(error = when (delivery) {
                            MessageDelivery.SENT -> "Connection lost; the reply will sync when connected"
                            MessageDelivery.UNCERTAIN -> "Checking delivery; your message will not be sent again automatically"
                            MessageDelivery.QUEUED -> "Offline; message queued"
                            else -> t.toMonoError()
                        })
                    }
                    if (_state.value.shows(origin, profile, item.sessionId)) onTurnCompleted?.invoke()
                    if (delivery == MessageDelivery.QUEUED && !OutboxPolicy.exhausted(item.attempts + 1)) {
                        retryJob?.cancel()
                        retryJob = scope.launch { delay(OutboxPolicy.backoffMs(item.attempts + 1)); flushOutbox() }
                    }
                } finally {
                    assistantIds.remove("$origin|$profile|${item.sessionId}", assistantId)
                    if (_state.value.owns(origin, profile)) clearStreamingMarker(item.sessionId)
                }
            }
        } finally {
            outboxMutex.unlock()
            if (flushRequested.getAndSet(false)) scope.launch { flushOutbox() }
        }
    }

    private fun updateDelivery(origin: String, profile: String, item: OutboxItem, delivery: MessageDelivery) {
        _state.update { st ->
            if (!st.shows(origin, profile, item.sessionId)) st else st.copy(messages = st.messages.map {
                if (it.id == item.id) it.copy(delivery = delivery, queued = delivery == MessageDelivery.QUEUED) else it
            })
        }
    }

    fun retryMessage(id: String) {
        val st = _state.value
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        scope.launch {
            val item = outbox.pending(origin, profile).find { it.id == id } ?: return@launch
            if (reconcileLanded(origin, profile, item)) {
                outbox.remove(item.id)
            } else if (DeliveryPolicy.uncertain(item)) {
                _state.update { if (!it.shows(origin, profile, item.sessionId)) it else it.copy(error = "Delivery is still unknown; check the conversation before resending") }
            } else {
                outbox.retry(item)
                updateDelivery(origin, profile, item, MessageDelivery.QUEUED)
                flushOutbox()
            }
        }
    }

    internal suspend fun refreshHistory(origin: String, profile: String, sessionId: String, turnFinished: Boolean = false) {
        if (!_state.value.shows(origin, profile, sessionId) || _state.value.streaming) return
        val generation = navigation
        val approvalRevision = decisionRevision.get()
        val baseline = _state.value.messages
        try {
            val page = client(origin).pageMessages(origin, sessionId, profile, ended = _state.value.openSession?.ended == true)
            // message.complete can precede the host clearing its running flag. A catch-up for
            // that terminal event must not resurrect the turn from the earlier resume snapshot.
            val snapshot = if (turnFinished) null else client(origin).runtimeSnapshot(sessionId)
            val resumed = if (snapshot?.running == true && snapshot.assistant.isNotBlank()) page.messages + ChatMessage(
                "live-$sessionId", MessageRole.ASSISTANT, snapshot.assistant, streaming = true,
            ) else page.messages
            val merged = withQueued(origin, profile, sessionId, resumed)
            val approval = client(origin).pendingApproval(origin, sessionId, profile)
            if (generation == navigation && _state.value.shows(origin, profile, sessionId) && _state.value.messages == baseline) {
                cache.replaceMessages(origin, profile, sessionId, page.messages)
            }
            _state.update { st ->
                if (generation != navigation || !st.shows(origin, profile, sessionId) || st.messages != baseline) st
                else st.copy(messages = merged, historySource = page.source, historyHasMore = page.hasMore,
                    transcriptLoading = false, approval = st.approval ?: approval?.takeIf {
                        approvalRevision == decisionRevision.get() && !client(origin).isPromptRetired(sessionId, it.requestId)
                    }, streaming = snapshot?.running == true)
            }
        } catch (c: CancellationException) { throw c }
        catch (_: Throwable) { /* Keep the live transcript when catch-up is unavailable. */ }
    }

    /** Drop the in-flight marker for [sessionId], leaving another session's turn alone. */
    private fun clearStreamingMarker(sessionId: String) {
        _state.update {
            if (it.streamingSessionId == sessionId) it.copy(streamingSessionId = null) else it
        }
    }

    private suspend fun persistOpenMessages() {
        val st = _state.value
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        val sessionId = st.openSessionId ?: return
        if (!DraftThread.isPersisted(sessionId)) return
        runCatching { cache.replaceMessages(origin, profile, sessionId, st.messages) }
    }

    /**
     * Promote the local dummy to a host session on the first real turn.
     * No-op when the open thread is already persisted.
     */
    private suspend fun ensurePersistedSession(
        origin: String, profile: String, session: SessionRef, generation: Long, model: String,
        promoteDraft: Boolean = true, draftSnapshot: CompanionState? = null, activate: Boolean = true,
    ): SessionRef? = draftMutex.withLock {
        if (!DraftThread.isDraft(session)) return@withLock session
        val key = "$instanceId|$origin|$profile|$generation"
        promotedDrafts[key]?.let { return@withLock it }
        try {
            val created = client(origin).createSession(origin, profile, model = model.takeIf { it.isNotBlank() },
                idempotencyKey = UUID.nameUUIDFromBytes(key.toByteArray()).toString(), requireRpc = true)
            promotedDrafts[key] = created
            createdSessions["$origin|$profile|" + created.id] = created
            cache.upsertSession(origin, created)
            checkpointDraft()
            val beforePromotion = _state.value
            _state.update { st ->
                if (!st.owns(origin, profile)) st
                else st.copy(sessions = SessionLists.prepend(created, st.sessions)).let {
                    if (!activate || generation != navigation || st.openSessionId != session.id) it
                    else it.copy(openSessionId = created.id, openSessionRef = created, error = null)
                }
            }
            if (promoteDraft) {
                val source = if (generation == navigation && beforePromotion.shows(origin, profile, session.id)) beforePromotion else draftSnapshot
                if (source != null) drafts?.promote(source, created.id)
            }
            created
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { if (!it.owns(origin, profile) || generation != navigation) it else it.copy(error = t.toMonoError()) }
            throw t
        }
    }


}

internal fun applyEvent(state: CompanionState, event: ChatEvent, assistantId: String): CompanionState =
    when (event) {
        is ChatEvent.Accepted -> state
        is ChatEvent.Interim -> {
            val last = state.messages.lastOrNull()
            val message = ChatMessage("$assistantId.interim.${state.messages.size}", MessageRole.ASSISTANT,
                event.text, blocks = ChatContent.fromMarkdown(event.text))
            state.copy(streaming = true, messages =
                if (last?.role == MessageRole.ASSISTANT && !last.reasoning && last.streaming && last.id.startsWith(assistantId))
                    state.messages.dropLast(1) + last.copy(text = event.text, streaming = false, blocks = message.blocks)
                else state.messages + message)
        }
        is ChatEvent.ReasoningDelta -> {
            val id = "$assistantId.reasoning"
            val existing = state.messages.find { it.id == id }
            state.copy(streaming = true, messages = if (existing == null) state.messages + ChatMessage(
                id, MessageRole.ASSISTANT, event.text, reasoning = true, streaming = true,
            ) else state.messages.map { if (it.id == id) it.copy(text = it.text + event.text) else it })
        }
        is ChatEvent.Final -> {
            val messages = state.messages.toMutableList()
            val alreadyVisible = event.reused && messages.any { !it.reasoning && it.role == MessageRole.ASSISTANT &&
                it.id.startsWith(assistantId) && it.text == event.text }
            if (event.text.isNotBlank() && !alreadyVisible) {
                val last = messages.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && !last.reasoning && last.id.startsWith(assistantId)) {
                    messages[messages.lastIndex] = last.copy(text = event.text, blocks = ChatContent.fromMarkdown(event.text), streaming = false)
                } else {
                    messages += ChatMessage("$assistantId.final", MessageRole.ASSISTANT, event.text, blocks = ChatContent.fromMarkdown(event.text))
                }
            }
            finishStream(state.copy(messages = messages), assistantId).copy(
                error = if (event.status == "error" || event.status == "failed") event.error.ifBlank { event.text.ifBlank { "Hermes turn failed" } } else state.error,
            )
        }
        is ChatEvent.AssistantDelta -> {
            // Text keeps flowing into the open segment only while it is still the last row. A tool
            // row in between closes that segment, so post-tool text starts a new one *below* the
            // tool — otherwise the transcript reorders itself (text, more text, tool) mid-turn.
            val last = state.messages.lastOrNull()
            val next = if (last != null && last.role == MessageRole.ASSISTANT && !last.reasoning && last.streaming && last.id.startsWith(assistantId)) {
                state.messages.dropLast(1) + last.copy(text = last.text + event.text)
            } else {
                val segments = state.messages.count { it.id.startsWith(assistantId) }
                state.messages + ChatMessage(
                    id = if (segments == 0) assistantId else "$assistantId.${segments + 1}",
                    role = MessageRole.ASSISTANT,
                    text = event.text,
                    streaming = true,
                )
            }
            state.copy(messages = next, streaming = true)
        }
        is ChatEvent.ToolStarted -> state.copy(
            messages = state.messages.map {
                if (it.streaming) it.copy(streaming = false) else it
            } + ChatMessage(
                id = "t-${UUID.randomUUID()}",
                role = MessageRole.TOOL,
                text = event.detail,
                toolName = event.name,
                toolDetail = event.detail,
                toolRunning = true,
            ),
        )
        is ChatEvent.Approval -> state.copy(approval = event.prompt, streaming = false)
        is ChatEvent.PromptExpired ->
            if (state.approval?.requestId == event.requestId) state.copy(approval = null) else state
        is ChatEvent.ToolCompleted -> {
            val updated = state.messages.toMutableList()
            val idx = updated.indexOfLast { it.role == MessageRole.TOOL && it.toolName == event.name }
            val shot = ChatContent.screenshotBlocks(event.name, event.detail, event.detail)
            if (idx >= 0) {
                val dur = if (event.durationMs > 0) " · ${event.durationMs / 1000.0}s" else ""
                // A blank completion (room summaries, hosts that only send `name`) must not wipe the
                // command line that tool.start carried — that is what the expanded box shows.
                val detail = event.detail.ifBlank { updated[idx].toolDetail?.substringBefore(" · ").orEmpty() }
                updated[idx] = updated[idx].copy(
                    toolDetail = detail + dur,
                    text = detail + dur,
                    blocks = shot.ifEmpty { updated[idx].blocks },
                    toolRunning = false,
                )
            }
            state.copy(messages = updated)
        }
        is ChatEvent.Rewound -> state.copy(
            messages = RewindPolicy.rebindUserRowIds(state.messages, event.userRowIds),
        )
        ChatEvent.Completed -> {
            val done = finishStream(state, assistantId)
            done.copy(
                messages = done.messages.map { msg ->
                    if (msg.id.startsWith(assistantId) && msg.blocks.isEmpty() && msg.text.isNotBlank()) {
                        msg.copy(blocks = ChatContent.fromMarkdown(msg.text))
                    } else msg
                },
            )
        }
        // Room-only events are reduced by RoomSessionManager; a single-agent thread never sees them.
        is ChatEvent.TurnStarted, is ChatEvent.TurnEnded, is ChatEvent.RoomPost,
        is ChatEvent.RoomState, is ChatEvent.RoomReady, is ChatEvent.RoomUpdated -> state
    }

internal fun finishStream(state: CompanionState, assistantId: String): CompanionState =
    state.copy(
        streaming = false,
        messages = state.messages.map {
            when {
                it.streaming || it.toolRunning -> it.copy(streaming = false, toolRunning = false)
                else -> it
            }
        },
    )

internal fun Throwable.toMonoError(): String = when (this) {
    is DashboardException -> "$code · $message"
    else -> message ?: javaClass.simpleName
}

private val attachmentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun encodeAttachments(items: List<ChatAttachment>): String =
    if (items.isEmpty()) "" else runCatching { attachmentJson.encodeToString(items) }.getOrDefault("")

private fun decodeAttachments(raw: String): List<ChatAttachment> =
    if (raw.isBlank()) emptyList()
    else runCatching { attachmentJson.decodeFromString<List<ChatAttachment>>(raw) }.getOrDefault(emptyList())

/**
 * Merge the host transcript with the outbox items still queued for this thread.
 *
 * Id equality can never match: outbox ids are client-side `u-<uuid>` while the host returns
 * `row_id`/`id` (database rows). Suppression is by text against the transcript tail.
 */
internal fun mergeQueued(
    messages: List<ChatMessage>,
    pending: List<OutboxItem>,
): List<ChatMessage> {
    val seen = messages.map { it.id }.toSet()
    val landed = pending.filter { DeliveryPolicy.landed(it, messages) }.map { it.id }.toSet()
    return messages + pending.filter { it.id !in seen && it.id !in landed }.map {
        val attached = decodeAttachments(it.attachmentsJson)
        val delivery = when {
            DeliveryPolicy.uncertain(it) -> MessageDelivery.UNCERTAIN
            it.lastError.startsWith("failed") || OutboxPolicy.exhausted(it.attempts) -> MessageDelivery.FAILED
            else -> MessageDelivery.QUEUED
        }
        ChatMessage(
            id = it.id,
            role = MessageRole.USER,
            text = it.text,
            queued = delivery == MessageDelivery.QUEUED,
            delivery = delivery,
            blocks = ChatContent.userBlocks(it.text, attached),
        )
    }
}

/** Is a turn for [sessionId] streaming right now, so reopening its window shows it live? */
internal fun streamingFor(state: CompanionState, sessionId: String): Boolean =
    state.streamingSessionId != null && state.streamingSessionId == sessionId

/** State write for a turn that failed, cancelled or was rejected. */
internal fun applyTurnFailure(
    state: CompanionState,
    itemId: String,
    sessionId: String,
    assistantId: String,
    error: String?,
): CompanionState {
    if (state.openSessionId != sessionId) return state
    return finishStream(state, assistantId).copy(
        error = error,
        streaming = false,
        messages = state.messages.map {
            if (it.id == itemId) it.copy(queued = true, streaming = false) else it
        },
    )
}
