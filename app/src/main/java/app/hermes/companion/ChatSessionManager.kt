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
) {
    private var turnJob: Job? = null
    private var retryJob: Job? = null
    private val outboxMutex = Mutex()
    private val draftMutex = Mutex()
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
        cancelTurn()
        retryJob?.cancel()
        retryJob = null
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
        scope.launch {
            val cached = runCatching { cache.messages(origin, profile, owned.id) }.getOrDefault(emptyList())
            val cachedTail = withQueued(origin, profile, owned.id, HistoryPaging.tail(cached))
            _state.update {
                it.copy(
                    openSessionId = owned.id,
                    openSessionRef = owned,
                    transcriptLoading = true,
                    historySource = "",
                    error = null,
                    draft = "",
                    approval = null,
                    rewindTargetId = null,
                    messages = cachedTail,
                    historyHasMore = cached.size > cachedTail.size,
                    historyLoading = false,
                )
            }
            try {
                val page = client(origin).pageMessages(origin, owned.id, profile, ended = owned.ended)
                runCatching { cache.replaceMessages(origin, profile, owned.id, page.messages) }
                val approval = runCatching { client(origin).pendingApproval(origin, owned.id, profile) }.getOrNull()
                val merged = withQueued(origin, profile, owned.id, page.messages)
                _state.update { state ->
                    if (state.openSessionId != owned.id) state
                    else state.copy(
                        transcriptLoading = false,
                        historySource = page.source,
                        messages = merged,
                        approval = approval,
                        historyHasMore = page.hasMore,
                    )
                }
            } catch (t: Throwable) {
                _state.update { state ->
                    if (state.openSessionId != owned.id) state
                    else state.copy(transcriptLoading = false, error = t.toMonoError())
                }
            }
        }
    }

    fun closeChat() {
        turnJob?.cancel()
        _state.update {
            it.copy(
                openSessionId = null,
                openSessionRef = null,
                messages = emptyList(),
                draft = "",
                streaming = false,
                approval = null,
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
                    if (state.openSessionId != session.id) state
                    else state.copy(
                        historyLoading = false,
                        messages = older + state.messages,
                        historyHasMore = page.hasMore && older.isNotEmpty(),
                    )
                }
                persistOpenMessages()
            } catch (t: Throwable) {
                _state.update { it.copy(historyLoading = false, historyHasMore = false, error = t.toMonoError()) }
            }
        }
    }

    fun respondApproval(decision: String) {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val prompt = _state.value.approval ?: return
        scope.launch {
            try {
                ProfileScope.requireOwnedSession(session, profile)
                client(origin).respondPrompt(origin, session.id, profile, prompt, decision)
                val note = if (decision == "deny") "denied ${prompt.command}" else "allowed ${prompt.command}"
                _state.update {
                    it.copy(
                        approval = null,
                        messages = it.messages + ChatMessage(
                            id = "apr-${prompt.requestId}",
                            role = MessageRole.ASSISTANT,
                            text = note,
                        ),
                    )
                }
                persistOpenMessages()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    /** Open a local dummy composer. Host `session.create` waits until the first send. */
    fun newThread() {
        if (_state.value.origin == null) return
        val profile = _state.value.activeProfileId ?: return
        cancelTurn()
        val draft = DraftThread.placeholder(profile)
        _state.update {
            it.copy(
                openSessionId = draft.id,
                openSessionRef = draft,
                transcriptLoading = false,
                historySource = "",
                error = null,
                draft = "",
                approval = null,
                rewindTargetId = null,
                messages = emptyList(),
                historyHasMore = false,
                historyLoading = false,
                pendingAttachments = emptyList(),
                attachOpen = false,
                streaming = false,
            )
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
        val origin = _state.value.origin ?: return fail("not connected")
        val session = _state.value.openSession ?: return fail("no open thread")
        val profile = _state.value.activeProfileId ?: return fail("no profile")
        val text = _state.value.draft.trim()
        val attachments = _state.value.pendingAttachments
        if (text.isBlank() && attachments.isEmpty()) return
        try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        val rewindId = _state.value.rewindTargetId
        if (rewindId != null) {
            if (DraftThread.isDraft(session)) return fail("no open thread")
            sendRewind(origin, session, profile, rewindId, text)
            return
        }
        val user = ChatMessage(
            id = "u-${UUID.randomUUID()}",
            role = MessageRole.USER,
            text = text,
            queued = true,
            blocks = ChatContent.userBlocks(text, attachments),
        )
        scope.launch {
            val persisted = ensurePersistedSession() ?: return@launch
            outbox.enqueue(
                OutboxItem(
                    id = user.id,
                    origin = origin,
                    profileId = profile,
                    sessionId = persisted.id,
                    text = text,
                    createdAtEpochMs = System.currentTimeMillis(),
                    attachmentsJson = encodeAttachments(attachments),
                ),
            )
            _state.update {
                it.copy(
                    draft = "",
                    error = null,
                    messages = it.messages + user,
                    pendingAttachments = emptyList(),
                    attachOpen = false,
                )
            }
            flushOutbox()
        }
    }

    /** Direct prompt submission for voice stream without overwriting unfinished keyboard drafts. */
    fun sendPrompt(text: String) {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        val clean = text.trim()
        if (clean.isBlank()) return
        scope.launch {
            var session = _state.value.openSession
            var attempts = 0
            while (session == null && attempts < 10) {
                delay(200)
                session = _state.value.openSession
                attempts++
            }
            if (session == null) return@launch
            try {
                ProfileScope.requireOwnedSession(session, profile)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
                return@launch
            }
            val persisted = ensurePersistedSession() ?: return@launch
            val user = ChatMessage(
                id = "u-${UUID.randomUUID()}",
                role = MessageRole.USER,
                text = clean,
                queued = true,
            )
            outbox.enqueue(
                OutboxItem(
                    id = user.id,
                    origin = origin,
                    profileId = profile,
                    sessionId = persisted.id,
                    text = clean,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            _state.update {
                it.copy(error = null, messages = it.messages + user)
            }
            flushOutbox()
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
                        messages = kept + user,
                    )
                }
                coroutineScope {
                    val job = launch {
                        client(origin).streamTurn(origin, session.id, profile, text, spec, turnModel()).coalesceDeltas().collect { event ->
                            if (_state.value.openSessionId == session.id) {
                                _state.update { applyEvent(it, event, assistantId) }
                                if (event is ChatEvent.AssistantDelta) {
                                    onAssistantDelta?.invoke(event.text)
                                }
                            }
                        }
                    }
                    turnJob = job
                    job.join()
                }
                if (_state.value.openSessionId == session.id) {
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
                    if (_state.value.openSessionId == session.id) {
                        _state.update {
                            finishStream(it, assistantId).copy(
                                messages = page.messages,
                                historyHasMore = page.hasMore,
                            )
                        }
                    }
                }
                _state.update { it.copy(streaming = false, error = t.toMonoError()) }
            } finally {
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
            val wasOpen = _state.value.openSessionId == owned.id
            if (wasOpen) cancelTurn()
            _state.update {
                it.copy(
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
            runCatching { cache.deleteSession(origin, profile, owned.id) }
            runCatching { outbox.removeForSession(origin, profile, owned.id) }
            try {
                client(origin).deleteSession(origin, owned.id, profile)
            } catch (t: Throwable) {
                val restored = runCatching { client(origin).listSessions(origin, profile, _state.value.showArchived) }
                    .getOrDefault(previous)
                runCatching { cache.replaceSessions(origin, profile, restored) }
                _state.update { it.copy(sessions = SessionLists.normalize(restored), error = t.toMonoError()) }
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
            runCatching { client(origin).interruptTurn(origin, session.id, profile) }
        }
    }

    internal suspend fun withQueued(
        origin: String,
        profileId: String,
        sessionId: String,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val pending = runCatching { outbox.pending(origin, profileId) }.getOrDefault(emptyList())
            .filter { it.sessionId == sessionId }
        val seen = messages.map { it.id }.toSet()
        return messages + pending.filter { it.id !in seen }.map {
            val attached = decodeAttachments(it.attachmentsJson)
            ChatMessage(
                id = it.id,
                role = MessageRole.USER,
                text = it.text,
                queued = true,
                blocks = ChatContent.userBlocks(it.text, attached),
            )
        }
    }

    internal suspend fun flushOutbox() {
        if (!outboxMutex.tryLock()) return
        try {
            val origin = _state.value.origin ?: return
            val profile = _state.value.activeProfileId ?: return
            while (true) {
                val item = outbox.pending(origin, profile).firstOrNull() ?: return
                if (DraftThread.isDraft(item.sessionId)) {
                    outbox.remove(item.id)
                    continue
                }
                val open = _state.value.openSessionId == item.sessionId
                val assistantId = "a-${UUID.randomUUID()}"
                if (open) {
                    _state.update { st ->
                        st.copy(
                            streaming = true,
                            error = null,
                            messages = st.messages.map { m ->
                                if (m.id == item.id) m.copy(queued = false) else m
                            },
                        )
                    }
                }
                try {
                    var cancelled = false
                    val attachments = decodeAttachments(item.attachmentsJson)
                    val uploaded = uploadAttachments(origin, attachments)
                    val partsJson = ChatContent.toSubmitParts(item.text, uploaded)
                    coroutineScope {
                        val job = launch {
                            client(origin).streamTurn(
                                origin,
                                item.sessionId,
                                profile,
                                item.text,
                                model = turnModel(),
                                partsJson = partsJson,
                            ).coalesceDeltas().collect { event ->
                                if (_state.value.openSessionId == item.sessionId) {
                                    _state.update { applyEvent(it, event, assistantId) }
                                    if (event is ChatEvent.AssistantDelta) {
                                        onAssistantDelta?.invoke(event.text)
                                    }
                                }
                            }
                        }
                        turnJob = job
                        job.join()
                        cancelled = job.isCancelled
                    }
                    if (cancelled) {
                        if (open) _state.update { finishStream(it, assistantId) }
                        return
                    }
                    outbox.remove(item.id)
                    if (_state.value.openSessionId == item.sessionId) {
                        _state.update { finishStream(it, assistantId) }
                        persistOpenMessages()
                        onTurnCompleted?.invoke()
                    }
                } catch (c: CancellationException) {
                    onTurnInterrupted?.invoke()
                    throw c
                } catch (t: Throwable) {
                    onTurnCompleted?.invoke()
                    val code = (t as? DashboardException)?.code.orEmpty()
                    val fate = OutboxPolicy.classify(code, "${t.message.orEmpty()} ${t.javaClass.simpleName}")
                    outbox.markAttempt(item.id, origin, profile, "$code ${t.message}")
                    val queued = _state.value.messages.map { m ->
                        if (m.id == item.id) m.copy(queued = true, streaming = false) else m
                    }
                    when (fate) {
                        SendFate.BUSY -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = "session busy",
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            val attempts = item.attempts + 1
                            if (!OutboxPolicy.giveUp(attempts)) {
                                retryJob?.cancel()
                                retryJob = scope.launch {
                                    delay(OutboxPolicy.backoffMs(attempts))
                                    flushOutbox()
                                }
                            }
                            return
                        }
                        SendFate.OFFLINE -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = "queued",
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            return
                        }
                        SendFate.FAILED -> {
                            if (open) {
                                _state.update {
                                    finishStream(it, assistantId).copy(
                                        error = t.toMonoError(),
                                        messages = queued,
                                        streaming = false,
                                    )
                                }
                            }
                            return
                        }
                    }
                }
            }
        } finally {
            outboxMutex.unlock()
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
    private suspend fun ensurePersistedSession(): SessionRef? = draftMutex.withLock {
        val origin = _state.value.origin ?: return@withLock null
        val profile = _state.value.activeProfileId ?: return@withLock null
        val session = _state.value.openSession ?: return@withLock null
        if (!DraftThread.isDraft(session)) return@withLock session
        try {
            val created = client(origin).createSession(origin, profile, model = turnModel())
            runCatching { cache.upsertSession(origin, created) }
            _state.update {
                it.copy(
                    sessions = SessionLists.prepend(created, it.sessions),
                    openSessionId = created.id,
                    openSessionRef = created,
                    error = null,
                )
            }
            created
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            null
        }
    }

    private suspend fun uploadAttachments(origin: String, items: List<ChatAttachment>): List<ChatAttachment> {
        if (items.isEmpty()) return items
        val api = client(origin)
        return items.map { item ->
            if (item.uploadedUrl.isNotBlank()) item
            else {
                val bytes = withContext(Dispatchers.IO) {
                    val file = java.io.File(item.localUri.removePrefix("file://"))
                    if (file.isFile) file.readBytes() else ByteArray(0)
                }
                if (bytes.isEmpty()) item
                else {
                    val url = runCatching { api.uploadMedia(origin, bytes, item.name, item.mime) }.getOrDefault("")
                    item.copy(uploadedUrl = url)
                }
            }
        }
    }
}

internal fun applyEvent(state: CompanionState, event: ChatEvent, assistantId: String): CompanionState =
    when (event) {
        is ChatEvent.AssistantDelta -> {
            // Text keeps flowing into the open segment only while it is still the last row. A tool
            // row in between closes that segment, so post-tool text starts a new one *below* the
            // tool — otherwise the transcript reorders itself (text, more text, tool) mid-turn.
            val last = state.messages.lastOrNull()
            val next = if (last != null && last.role == MessageRole.ASSISTANT && last.streaming && last.id.startsWith(assistantId)) {
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
            state.copy(messages = next)
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
