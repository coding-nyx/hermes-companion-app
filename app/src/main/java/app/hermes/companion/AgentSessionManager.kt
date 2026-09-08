package app.hermes.companion

import app.hermes.companion.data.remote.AgentJson
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.AgentTranscript
import app.hermes.companion.domain.AgentTranscriptState
import app.hermes.companion.model.AgentEvent
import app.hermes.companion.model.AgentPane
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.AgentTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Preset terminal widths the pane view offers; the host resizes the tmux window to match. */
val AgentPaneWidths = listOf(60, 80, 100, 120)

/**
 * Coding-agent sessions on the phone (P23 / A23.2). The host owns the tmux sessions; this mirrors
 * the list, streams one session's screen while it is open, and sends keys back.
 */
class AgentSessionManager(
    private val clients: HostClientPool,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
) {
    private var eventsJob: Job? = null

    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    fun refreshTools(force: Boolean = false) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(agentToolsLoading = true) }
            try {
                val (tools, tmux, cwd) = client(origin).agentTools(origin, refresh = force)
                _state.update { st ->
                    if (st.origin != origin) st
                    else st.copy(agentTools = tools, agentTmux = tmux, agentDefaultCwd = cwd, agentToolsLoading = false, agentError = null)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(agentToolsLoading = false, agentError = agentError(t)) }
            }
        }
    }

    fun refreshSessions() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(agentSessionsLoading = true) }
            try {
                val rows = client(origin).agentSessions(origin)
                _state.update { st -> if (st.origin != origin) st else st.copy(agentSessions = rows, agentSessionsLoading = false, agentError = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val unsupported = (t as? DashboardException)?.code == "http_404"
                _state.update { it.copy(agentSessionsLoading = false, agentSessions = emptyList(), agentError = if (unsupported) "host plugin has no agent sessions · update hermes-companion" else agentError(t)) }
            }
        }
    }

    fun start(tool: String, cwd: String, prompt: String, title: String = "", mode: String = "pty") {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(agentStarting = true, agentError = null) }
            try {
                val cols = _state.value.agentCols
                val s = client(origin).startAgentSession(origin, tool, cwd, prompt, title, cols = cols, rows = 40, mode = mode)
                _state.update { it.copy(agentSessions = listOf(s) + it.agentSessions.filter { r -> r.id != s.id }, agentStarting = false, agentNewOpen = false) }
                open(s)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(agentStarting = false, agentError = agentError(t)) }
            }
        }
    }

    fun open(session: AgentSession) {
        val origin = _state.value.origin ?: return
        eventsJob?.cancel()
        _state.update { it.copy(openAgentId = session.id, openAgent = session, agentPane = AgentPane(), agentTranscript = AgentTranscriptState(), agentPaneLoading = true, agentError = null) }
        eventsJob = scope.launch { if (session.mode == "structured") pumpStructured(origin, session.id) else pump(origin, session.id) }
    }

    fun close() {
        eventsJob?.cancel()
        eventsJob = null
        _state.update { it.copy(openAgentId = null, openAgent = null, agentPane = AgentPane(), agentTranscript = AgentTranscriptState(), agentPaneLoading = false) }
        refreshSessions()
    }

    /** Structured sessions: one prompt starts one turn. Echoed locally so the row shows before the host confirms. */
    fun prompt(text: String) = sessionCall { origin, id ->
        val t = text.trim()
        if (t.isEmpty()) return@sessionCall
        _state.update { it.copy(agentInput = "") }
        client(origin).agentPrompt(origin, id, t)
    }

    fun approve(decision: String) = sessionCall { origin, id ->
        val prompt = _state.value.agentTranscript.approval ?: return@sessionCall
        client(origin).agentApproval(origin, id, prompt.requestId, decision)
        _state.update { it.copy(agentTranscript = it.agentTranscript.copy(approval = null)) }
    }

    /** Sends text to whichever kind of session is open: a turn for structured, keys+Enter for pty. */
    fun submit(text: String) {
        if (_state.value.openAgent?.mode == "structured") prompt(text) else sendText(text, enter = true)
    }

    fun setCols(cols: Int) {
        _state.update { it.copy(agentCols = cols) }
        val id = _state.value.openAgentId ?: return
        val origin = _state.value.origin ?: return
        if (_state.value.openAgent?.mode == "structured") return
        // Reconnect the stream at the new width; the host resizes the tmux window.
        eventsJob?.cancel()
        eventsJob = scope.launch { pump(origin, id) }
    }

    fun sendText(text: String, enter: Boolean = true) = sessionCall { origin, id ->
        if (text.isEmpty() && !enter) return@sessionCall
        client(origin).agentKeys(origin, id, text, if (enter) listOf("enter") else emptyList())
        _state.update { it.copy(agentInput = "") }
    }

    fun sendKey(key: String) = sessionCall { origin, id -> client(origin).agentKeys(origin, id, "", listOf(key)) }

    fun interrupt() = sendKey("c-c")

    fun kill(session: AgentSession? = null) {
        val origin = _state.value.origin ?: return
        val target = session ?: _state.value.openAgent ?: return
        scope.launch {
            try {
                client(origin).killAgentSession(origin, target.id)
                if (_state.value.openAgentId == target.id) {
                    _state.update { it.copy(openAgent = target.copy(status = "exited")) }
                }
                refreshSessions()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(agentError = agentError(t)) }
            }
        }
    }

    fun forget(session: AgentSession) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).killAgentSession(origin, session.id, forget = true) }
                .onFailure { t -> _state.update { it.copy(agentError = agentError(t)) } }
            if (_state.value.openAgentId == session.id) close() else refreshSessions()
        }
    }

    fun setInput(value: String) = _state.update { it.copy(agentInput = value) }

    /** Directory picker: list [path] (empty = host default). Stale replies for another origin are dropped. */
    fun browseDirs(path: String, hidden: Boolean = false) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(agentDirsLoading = true, agentDirsError = null) }
            try {
                val listing = client(origin).agentDirs(origin, path, hidden)
                _state.update { st -> if (st.origin != origin) st else st.copy(agentDirs = listing, agentDirsLoading = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val msg = when ((t as? DashboardException)?.code) {
                    "http_403" -> "not readable or outside the allowed roots"
                    "http_400" -> "not a directory"
                    "http_404" -> "host plugin has no directory picker · update hermes-companion"
                    else -> agentError(t)
                }
                _state.update { it.copy(agentDirsLoading = false, agentDirsError = msg) }
            }
        }
    }

    fun toggleNew(open: Boolean) {
        _state.update { it.copy(agentNewOpen = open, agentError = null, agentDirs = if (open) it.agentDirs else null, agentDirsError = null) }
        if (open) refreshTools(force = true)
    }

    private fun sessionCall(block: suspend (String, String) -> Unit) {
        val origin = _state.value.origin ?: return
        val id = _state.value.openAgentId ?: return
        scope.launch {
            try {
                block(origin, id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(agentError = agentError(t)) }
            }
        }
    }

    /** Reconnecting pane stream. Falls back to one REST snapshot when the socket cannot be opened. */
    private suspend fun pump(origin: String, id: String) {
        var backoff = 1_000L
        while (scope.isActive && _state.value.openAgentId == id) {
            val cols = _state.value.agentCols
            try {
                client(origin).agentEvents(origin, id, cols, 40).collect { ev ->
                    if (_state.value.openAgentId != id) return@collect
                    when (ev) {
                        is AgentJson.Event.Pane -> _state.update { it.copy(agentPane = ev.pane, agentPaneLoading = false) }
                        is AgentJson.Event.Status -> _state.update { st ->
                            st.copy(
                                openAgent = ev.session,
                                agentSessions = st.agentSessions.map { if (it.id == ev.session.id) ev.session else it },
                            )
                        }
                        is AgentJson.Event.Error -> _state.update { it.copy(agentError = ev.code.replace('_', ' '), agentPaneLoading = false) }
                        is AgentJson.Event.Replay, is AgentJson.Event.Structured -> Unit
                    }
                }
                backoff = 1_000L
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ((t as? DashboardException)?.code == "agent_unauthorized") {
                    _state.update { it.copy(agentError = agentError(t), agentPaneLoading = false) }
                    return
                }
            }
            if (_state.value.openAgentId != id) return
            if (_state.value.openAgent?.status == "exited") {
                // Final screen of an exited session, then stop streaming.
                runCatching { client(origin).agentPane(origin, id, cols, 40) }.onSuccess { (pane, s) ->
                    _state.update { it.copy(agentPane = pane, openAgent = s ?: it.openAgent, agentPaneLoading = false) }
                }
                return
            }
            runCatching { client(origin).agentPane(origin, id, cols, 40) }.onSuccess { (pane, s) ->
                _state.update { it.copy(agentPane = pane, openAgent = s ?: it.openAgent, agentPaneLoading = false) }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private fun applyStructured(id: String, ev: AgentEvent) {
        _state.update { st ->
            if (st.openAgentId != id) return@update st
            val tr = AgentTranscript.reduce(st.agentTranscript, ev)
            val status = when (ev) {
                AgentEvent.TurnStart -> "running"
                is AgentEvent.Approval -> "waiting_approval"
                is AgentEvent.ApprovalResolved -> "running"
                is AgentEvent.TurnEnd -> "waiting_input"
                is AgentEvent.Exit -> "exited"
                else -> null
            }
            val open = st.openAgent?.let { s ->
                if (status == null) s else s.copy(status = status, exitCode = (ev as? AgentEvent.Exit)?.exitCode ?: s.exitCode)
            }
            st.copy(
                agentTranscript = tr, agentPaneLoading = false, openAgent = open,
                agentError = tr.lastError.takeIf { it.isNotBlank() && ev is AgentEvent.Error } ?: st.agentError,
                agentSessions = if (open == null) st.agentSessions else st.agentSessions.map { if (it.id == open.id) open else it },
            )
        }
    }

    /** Structured stream: replay frame first, then live events; REST transcript paging when the socket is unavailable. */
    private suspend fun pumpStructured(origin: String, id: String) {
        var backoff = 1_000L
        while (scope.isActive && _state.value.openAgentId == id) {
            try {
                client(origin).agentEvents(origin, id, 0, 0).collect { ev ->
                    if (_state.value.openAgentId != id) return@collect
                    when (ev) {
                        is AgentJson.Event.Replay -> _state.update { st ->
                            val tr = AgentTranscript.replay(ev.events).let { t -> if (ev.approval != null && t.approval == null && !t.exited) t.copy(approval = ev.approval) else t }
                            st.copy(agentTranscript = tr, agentPaneLoading = false, openAgent = ev.session ?: st.openAgent)
                        }
                        is AgentJson.Event.Structured -> applyStructured(id, ev.event)
                        is AgentJson.Event.Status -> _state.update { it.copy(openAgent = ev.session) }
                        is AgentJson.Event.Error -> _state.update { it.copy(agentError = ev.code.replace('_', ' '), agentPaneLoading = false) }
                        is AgentJson.Event.Pane -> Unit
                    }
                }
                backoff = 1_000L
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ((t as? DashboardException)?.code == "agent_unauthorized") {
                    _state.update { it.copy(agentError = agentError(t), agentPaneLoading = false) }
                    return
                }
            }
            if (_state.value.openAgentId != id) return
            if (_state.value.agentTranscript.exited || _state.value.openAgent?.status == "exited") {
                _state.update { it.copy(agentPaneLoading = false) }
                return
            }
            // Socket unavailable: page the transcript over REST from where we left off.
            runCatching { client(origin).agentTranscript(origin, id, _state.value.agentTranscript.seq) }.onSuccess { (events, approval, s) ->
                events.forEach { applyStructured(id, it) }
                _state.update { st ->
                    st.copy(
                        agentPaneLoading = false, openAgent = s ?: st.openAgent,
                        agentTranscript = if (approval != null && st.agentTranscript.approval == null) st.agentTranscript.copy(approval = approval) else st.agentTranscript,
                    )
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5_000L)
        }
    }

    private fun agentError(t: Throwable): String = when ((t as? DashboardException)?.code) {
        "http_404" -> "host plugin has no agent sessions · update hermes-companion on the host"
        "http_503" -> "tmux is not installed on the host"
        "http_409" -> t.message ?: "refused by host"
        "http_403" -> "directory not allowed on the host"
        "http_401", "agent_unauthorized" -> "pair this phone with the host to use agent sessions"
        null -> t.message ?: "agent error"
        else -> t.message ?: (t as DashboardException).code
    }
}
