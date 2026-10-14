package app.hermes.companion

import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.model.SessionContext
import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class SessionContextPanel(
    val origin: String, val session: SessionRef, val epoch: Long,
    val loading: Boolean = true, val value: SessionContext? = null,
    val error: String? = null, val unsupported: Boolean = false,
    val refreshing: Boolean = false,
)

/** Read-only requests are bound to the selected conversation, including close/reopen. */
class SessionContextController(
    private val state: MutableStateFlow<CompanionState>, private val scope: CoroutineScope,
    private val epoch: () -> Long,
    private val fetch: suspend (String, SessionRef) -> SessionContext,
) {
    private var request: Job? = null
    private var generation = 0L
    private var scheduled: Job? = null
    private data class Signal(val count: Int, val lastId: String?, val lastText: String?,
        val settings: app.hermes.companion.model.NativeSessionSettings?)
    private var lastSignal: Signal? = null

    private fun signal(snapshot: CompanionState): Signal {
        val finished = snapshot.messages.filter { !it.streaming && !it.queued && !it.reasoning }
        val settings = snapshot.sessionControls?.takeIf { it.owner.origin == snapshot.origin &&
            it.owner.profile == snapshot.activeProfileId && it.owner.session == snapshot.openSessionId }?.settings
        return Signal(finished.size, finished.lastOrNull()?.id, finished.lastOrNull()?.text, settings)
    }

    /** Called from state observation; drafts/deltas/panel responses do not trigger another read. */
    fun observe(snapshot: CompanionState) {
        val panel = snapshot.contextPanel ?: return
        if (!owns(panel) || panel.unsupported || snapshot.streaming || snapshot.submitting || snapshot.transcriptLoading) return
        val changed = signal(snapshot)
        if (changed == lastSignal) return
        lastSignal = changed
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(250)
            if (owns(panel) && state.value.contextPanel?.epoch == panel.epoch) load(panel, retainValue = true)
        }
    }

    fun open() {
        val snapshot = state.value
        val origin = snapshot.origin ?: return
        val session = snapshot.openSession ?: return
        if (snapshot.inRoom || DraftThread.isDraft(session)) return
        scheduled?.cancel()
        lastSignal = signal(snapshot)
        val panel = SessionContextPanel(origin, session, epoch())
        state.update { it.copy(contextPanel = panel) }
        load(panel, retainValue = false)
    }

    private fun load(panel: SessionContextPanel, retainValue: Boolean) {
        request?.cancel()
        val turn = ++generation
        if (retainValue) state.update { current ->
            val active = current.contextPanel
            if (!owns(panel) || active?.epoch != panel.epoch) current
            else current.copy(contextPanel = active.copy(refreshing = true, error = null))
        }
        request = scope.launch {
            try {
                val value = fetch(panel.origin, panel.session)
                publish(panel, turn) { it.copy(loading = false, refreshing = false, value = value, error = null, unsupported = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // Only a native method-not-found response establishes unsupported.
                // Network, auth, busy and validation failures remain retryable errors.
                val unsupported = (error as? DashboardException)?.code == "rpc_-32601"
                publish(panel, turn) { it.copy(loading = false, refreshing = false, unsupported = unsupported,
                    error = if (unsupported) "This host does not support context inspection." else
                        error.message?.takeIf(String::isNotBlank) ?: "Could not load context. Try again.") }
            }
        }
    }

    fun dismiss() {
        generation++
        scheduled?.cancel(); scheduled = null; lastSignal = null
        request?.cancel(); request = null
        state.update { it.copy(contextPanel = null) }
    }

    fun selectionChanged() {
        val panel = state.value.contextPanel ?: return
        if (!owns(panel)) dismiss()
    }

    private fun owns(panel: SessionContextPanel): Boolean = state.value.let {
        it.origin == panel.origin && it.activeProfileId == panel.session.profileId &&
            it.openSessionId == panel.session.id && !it.inRoom && epoch() == panel.epoch
    }
    private fun publish(panel: SessionContextPanel, turn: Long, update: (SessionContextPanel) -> SessionContextPanel) {
        if (generation != turn || !owns(panel)) return
        state.update { current ->
            val active = current.contextPanel
            if (active == null || active.epoch != panel.epoch || active.session != panel.session) current
            else current.copy(contextPanel = update(active))
        }
    }
}
