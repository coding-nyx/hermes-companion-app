package app.hermes.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.GatewayHello
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SessionRef
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MainTab { THREADS, PROFILES, GATEWAY, DEVICE }

data class CompanionState(
    val originInput: String = "",
    val origin: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val profiles: List<ProfileRef> = emptyList(),
    val activeProfileId: String? = null,
    val sessions: List<SessionRef> = emptyList(),
    val tab: MainTab = MainTab.THREADS,
    val openSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val streaming: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val gatewayHello: GatewayHello? = null,
) {
    val visibleSessions: List<SessionRef>
        get() = ProfileScope.visibleSessions(sessions, activeProfileId)
    val activeProfile: ProfileRef?
        get() = profiles.find { it.id == activeProfileId }
    val openSession: SessionRef?
        get() = sessions.find { it.id == openSessionId }
}

class CompanionViewModel(
    private val client: DashboardClient,
    private val sticky: StickyStore,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CompanionState(originInput = sticky.origin.orEmpty()),
    )
    val state: StateFlow<CompanionState> = _state
    private var turnJob: Job? = null
    private var watchJob: Job? = null

    fun onOriginChange(value: String) {
        _state.update { it.copy(originInput = value, error = null) }
    }

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun connect(originOverride: String? = null) {
        val origin = (originOverride ?: _state.value.originInput).trim().trimEnd('/')
        if (origin.isBlank()) {
            _state.update { it.copy(error = "origin required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, originInput = origin) }
            try {
                val status = client.probe(origin)
                if (!status.authRequired) {
                    client.adoptLoopbackToken(origin)
                }
                val profiles = client.listProfiles(origin)
                val active = ProfileScope.resolveActive(profiles, sticky.profileId)
                    ?: throw DashboardException("no_profiles", "no profiles on host")
                val hello = runCatching { client.wsHello(origin, active.id) }.getOrNull()
                val sessions = client.listSessions(origin, active.id)
                sticky.origin = origin
                sticky.profileId = active.id
                _state.update {
                    it.copy(
                        loading = false,
                        origin = origin,
                        profiles = profiles,
                        activeProfileId = active.id,
                        sessions = sessions,
                        tab = MainTab.THREADS,
                        gatewayHello = hello,
                    )
                }
                startWatch(origin, active.id)
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, origin = null, error = t.toMonoError()) }
            }
        }
    }

    fun selectProfile(profileId: String) {
        val origin = _state.value.origin ?: return
        if (profileId == _state.value.activeProfileId) return
        sticky.profileId = profileId
        val keepChat = _state.value.openSession?.takeIf { it.profileId == profileId }
        turnJob?.cancel()
        _state.update {
            it.copy(
                activeProfileId = profileId,
                sessions = emptyList(),
                openSessionId = keepChat?.id,
                messages = if (keepChat == null) emptyList() else it.messages,
                streaming = false,
            )
        }
        startWatch(origin, profileId)
        viewModelScope.launch {
            try {
                val sessions = client.listSessions(origin, profileId)
                _state.update { state ->
                    if (state.activeProfileId != profileId) state
                    else state.copy(sessions = sessions, error = null)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
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
        viewModelScope.launch {
            _state.update {
                it.copy(openSessionId = owned.id, loading = true, error = null, draft = "", approval = null)
            }
            try {
                val messages = client.listMessages(origin, owned.id, profile)
                val approval = runCatching { client.pendingApproval(origin, owned.id, profile) }.getOrNull()
                _state.update { state ->
                    if (state.openSessionId != owned.id) state
                    else state.copy(loading = false, messages = messages, approval = approval)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.toMonoError()) }
            }
        }
    }

    fun closeChat() {
        turnJob?.cancel()
        _state.update {
            it.copy(openSessionId = null, messages = emptyList(), draft = "", streaming = false, approval = null)
        }
    }

    fun respondApproval(decision: String) {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val prompt = _state.value.approval ?: return
        viewModelScope.launch {
            try {
                ProfileScope.requireOwnedSession(session, profile)
                client.respondPrompt(origin, session.id, profile, prompt, decision)
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
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    fun newThread() {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId ?: return
        viewModelScope.launch {
            try {
                val created = client.createSession(origin, profile)
                _state.update { it.copy(sessions = listOf(created) + it.sessions, error = null) }
                openSession(created)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    fun send() {
        val origin = _state.value.origin ?: return
        val session = _state.value.openSession ?: return
        val profile = _state.value.activeProfileId ?: return
        val text = _state.value.draft.trim()
        if (text.isBlank() || _state.value.streaming) return
        try {
            ProfileScope.requireOwnedSession(session, profile)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.toMonoError()) }
            return
        }
        val user = ChatMessage(id = "u-${UUID.randomUUID()}", role = MessageRole.USER, text = text)
        val assistantId = "a-${UUID.randomUUID()}"
        _state.update {
            it.copy(draft = "", streaming = true, error = null, messages = it.messages + user)
        }
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            try {
                client.streamTurn(origin, session.id, profile, text).collect { event ->
                    _state.update { applyEvent(it, event, assistantId) }
                }
                _state.update { finishStream(it, assistantId) }
            } catch (t: Throwable) {
                _state.update { finishStream(it, assistantId).copy(error = t.toMonoError(), streaming = false) }
            }
        }
    }

    fun interrupt() {
        turnJob?.cancel()
        val origin = _state.value.origin
        val session = _state.value.openSession
        val profile = _state.value.activeProfileId
        _state.update { it.copy(streaming = false, messages = it.messages.map { m -> m.copy(streaming = false) }) }
        if (origin == null || session == null || profile == null) return
        viewModelScope.launch {
            runCatching { client.interruptTurn(origin, session.id, profile) }
        }
    }

    override fun onCleared() {
        watchJob?.cancel()
        turnJob?.cancel()
        client.closeRpc()
        super.onCleared()
    }

    private fun startWatch(origin: String, profileId: String) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            var backoff = 1_000L
            var lastInstance = _state.value.gatewayHello?.instanceId.orEmpty()
            while (isActive) {
                try {
                    val hello = client.wsHello(origin, profileId)
                    if (lastInstance.isNotEmpty() && hello.instanceId.isNotEmpty() &&
                        hello.instanceId != lastInstance
                    ) {
                        client.forgetLiveIds()
                        runCatching { client.listSessions(origin, profileId) }.onSuccess { rows ->
                            _state.update { st ->
                                if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                            }
                        }
                    }
                    lastInstance = hello.instanceId
                    _state.update { it.copy(gatewayHello = hello) }
                    backoff = 1_000L
                    coroutineScope {
                        val ping = launch {
                            if (!hello.heartbeat) return@launch
                            while (isActive) {
                                delay(PING_MS)
                                runCatching { client.ping() }.onFailure {
                                    client.closeRpc()
                                }
                            }
                        }
                        try {
                            client.bus(profileId).collect { frame -> onBus(frame, origin, profileId) }
                        } finally {
                            ping.cancel()
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    _state.update { it.copy(gatewayHello = null) }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun onBus(frame: BusFrame, origin: String, profileId: String) {
        when (frame) {
            BusFrame.SessionRefetch -> viewModelScope.launch {
                runCatching { client.listSessions(origin, profileId) }.onSuccess { rows ->
                    _state.update { st ->
                        if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                    }
                }
            }
            is BusFrame.SessionPatch -> _state.update { st ->
                if (st.activeProfileId != profileId) st
                else {
                    val next = ProfileScope.applyChange(st.sessions, frame.change, profileId)
                    val open = st.openSessionId
                    st.copy(
                        sessions = if (open == null) next else next.map { row ->
                            if (row.id == open) row.copy(unread = false) else row
                        },
                    )
                }
            }
            is BusFrame.Chat -> {
                val ev = frame.event
                val open = _state.value.openSessionId
                val forOpen = frame.sessionId == null || client.matchesSession(open, frame.sessionId)
                when (ev) {
                    is ChatEvent.PromptExpired -> _state.update { st ->
                        if (st.approval?.requestId == ev.requestId) st.copy(approval = null) else st
                    }
                    is ChatEvent.Approval -> {
                        if (forOpen && open != null) {
                            _state.update { it.copy(approval = ev.prompt, streaming = false) }
                        } else if (frame.sessionId != null) {
                            _state.update { st ->
                                st.copy(
                                    sessions = st.sessions.map { row ->
                                        if (client.matchesSession(row.id, frame.sessionId)) {
                                            row.copy(unread = true)
                                        } else row
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        if (!forOpen || open == null) return
                        if (_state.value.streaming && ev !is ChatEvent.Approval) return
                        val aid = "live-$open"
                        _state.update { applyEvent(it, ev, aid) }
                    }
                }
            }
        }
    }

    companion object {
        private const val PING_MS = 15_000L

        fun factory(app: CompanionApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CompanionViewModel(app.dashboard, app.sticky) as T
                }
            }
    }
}

private fun applyEvent(state: CompanionState, event: ChatEvent, assistantId: String): CompanionState =
    when (event) {
        is ChatEvent.AssistantDelta -> {
            val existing = state.messages.find { it.id == assistantId }
            val next = if (existing == null) {
                state.messages + ChatMessage(
                    id = assistantId,
                    role = MessageRole.ASSISTANT,
                    text = event.text,
                    streaming = true,
                )
            } else {
                state.messages.map {
                    if (it.id == assistantId) it.copy(text = it.text + event.text, streaming = true) else it
                }
            }
            state.copy(messages = next)
        }
        is ChatEvent.ToolStarted -> state.copy(
            messages = state.messages + ChatMessage(
                id = "t-${UUID.randomUUID()}",
                role = MessageRole.TOOL,
                text = event.detail,
                toolName = event.name,
                toolDetail = event.detail,
            ),
        )
        is ChatEvent.Approval -> state.copy(approval = event.prompt, streaming = false)
        is ChatEvent.PromptExpired ->
            if (state.approval?.requestId == event.requestId) state.copy(approval = null) else state
        is ChatEvent.ToolCompleted -> {
            val updated = state.messages.toMutableList()
            val idx = updated.indexOfLast { it.role == MessageRole.TOOL && it.toolName == event.name }
            if (idx >= 0) {
                val dur = if (event.durationMs > 0) " · ${event.durationMs / 1000.0}s" else ""
                updated[idx] = updated[idx].copy(toolDetail = event.detail + dur, text = event.detail + dur)
            }
            state.copy(messages = updated)
        }
        ChatEvent.Completed -> finishStream(state, assistantId)
    }

private fun finishStream(state: CompanionState, assistantId: String): CompanionState =
    state.copy(
        streaming = false,
        messages = state.messages.map { if (it.id == assistantId) it.copy(streaming = false) else it },
    )

private fun Throwable.toMonoError(): String = when (this) {
    is DashboardException -> "$code · $message"
    else -> message ?: javaClass.simpleName
}
