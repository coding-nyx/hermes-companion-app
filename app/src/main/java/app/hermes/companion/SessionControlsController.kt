package app.hermes.companion

import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.*
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface NativeSessionApi {
    suspend fun settings(origin: String, session: SessionRef): NativeSessionSettings
    suspend fun set(origin: String, session: SessionRef, key: String, value: String, confirmed: Boolean): NativeConfigResult
    suspend fun catalog(origin: String, session: SessionRef): List<NativeCommand>
    suspend fun dispatch(origin: String, session: SessionRef, name: String, argument: String): NativeCommandResult
    suspend fun redirect(origin: String, session: SessionRef, text: String): NativeRedirectResult
}
internal class DashboardNativeSessionApi(private val clients: HostClientPool) : NativeSessionApi {
    override suspend fun settings(origin: String, session: SessionRef) = clients.forOrigin(origin).sessionSettings(origin, session)
    override suspend fun set(origin: String, session: SessionRef, key: String, value: String, confirmed: Boolean) = clients.forOrigin(origin).setSessionSetting(origin, session, key, value, confirmed)
    override suspend fun catalog(origin: String, session: SessionRef) = clients.forOrigin(origin).commandCatalog(origin, session)
    override suspend fun dispatch(origin: String, session: SessionRef, name: String, argument: String) = clients.forOrigin(origin).dispatchCommand(origin, session, name, argument)
    override suspend fun redirect(origin: String, session: SessionRef, text: String) = clients.forOrigin(origin).redirectSession(origin, session, text)
}
data class SessionControlsPanel(val owner: DraftOwner, val session: SessionRef, val epoch: Long,
    val loading: Boolean = true, val busy: Boolean = false,
    val settings: NativeSessionSettings? = null, val commands: List<NativeCommand> = emptyList(),
    val pending: List<SessionMutationEntity> = emptyList(), val error: String? = null, val notice: String? = null,
    val unsupported: Set<String> = emptySet(), val confirmationKey: String? = null, val confirmationValue: String? = null)

/** Mutations have durable claims; reads reconcile settings, never resend timed-out writes. */
class SessionControlsController(
    private val state: MutableStateFlow<CompanionState>, private val scope: CoroutineScope,
    private val epoch: () -> Long, private val api: NativeSessionApi, private val operations: SessionMutationStore,
    private val submitCommand: (String, String) -> Unit = { _, _ -> },
) {
    private var hydrated: Pair<DraftOwner, Long>? = null
    fun hydrate() {
        val panel = captured() ?: return
        val key = panel.owner to panel.epoch
        if (hydrated == key) return
        hydrated = key
        scope.launch {
            try {
                val commands = api.catalog(panel.owner.origin, panel.session)
                if (owns(panel)) state.update { it.copy(nativeCommands = commands, nativeCommandsOwner = panel.owner) }
            } catch (t: Throwable) { if (t is CancellationException) throw t }
        }
    }
    private var request = 0L
    private var busy = false
    private fun captured(): SessionControlsPanel? {
        val st = state.value
        val origin = st.origin ?: return null; val session = st.openSession ?: return null
        if (st.inRoom || DraftThread.isDraft(session)) return null
        return SessionControlsPanel(DraftOwner(origin, session.profileId, session.id), session, epoch())
    }
    private fun owns(panel: SessionControlsPanel) = state.value.let { it.origin == panel.owner.origin &&
        it.activeProfileId == panel.owner.profile && it.openSessionId == panel.owner.session && !it.inRoom && epoch() == panel.epoch }
    private fun publish(panel: SessionControlsPanel, block: (SessionControlsPanel) -> SessionControlsPanel) {
        state.update { st -> val current = st.sessionControls
            if (!owns(panel) || current?.owner != panel.owner || current.epoch != panel.epoch) st
            else st.copy(sessionControls = block(current)) }
    }
    fun open() {
        val panel = captured() ?: return
        val generation = ++request
        state.update { it.copy(sessionControls = panel) }
        scope.launch {
            val pending = operations.pending(panel.owner)
            if (generation != request) return@launch
            publish(panel) { it.copy(pending = pending) }
            try {
                val settings = api.settings(panel.owner.origin, panel.session)
                reconcile(panel, settings)
                if (generation != request) return@launch
                publish(panel) { it.copy(settings = settings, loading = false, pending = emptyList()) }
                val remaining = operations.pending(panel.owner)
                publish(panel) { it.copy(pending = remaining) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publish(panel) { it.copy(loading = false, error = message(t), unsupported = unsupported(t, it.unsupported, "settings")) }
            }
            try {
                val commands = api.catalog(panel.owner.origin, panel.session)
                if (generation == request) publish(panel) { it.copy(commands = commands) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publish(panel) { it.copy(error = message(t), unsupported = unsupported(t, it.unsupported, "commands")) }
            }
        }
    }
    fun dismiss() { request++; state.update { it.copy(sessionControls = null) } }
    fun selectionChanged() { state.value.sessionControls?.let { if (!owns(it)) dismiss() } }
    fun set(key: String, value: String, confirmed: Boolean = false) {
        require(key in setOf("model", "reasoning", "fast"))
        mutate("config.$key", value) { panel, operation ->
            val result = api.set(panel.owner.origin, panel.session, key, value, confirmed)
            if (result.confirmationRequired) {
                operations.finish(operation, "confirmation", result.confirmation)
                publish(panel) { it.copy(confirmationKey = key, confirmationValue = value, notice = result.confirmation, busy = false) }
            } else {
                operations.finish(operation, if (result.deferred) "unknown" else "applied", result.warning)
                publish(panel) { it.copy(confirmationKey = null, confirmationValue = null,
                    notice = if (result.deferred) "Queued by Hermes. Refresh to check the applied setting." else result.warning.takeIf(String::isNotBlank)) }
                val settings = api.settings(panel.owner.origin, panel.session)
                reconcile(panel, settings)
                val pending = operations.pending(panel.owner)
                publish(panel) { it.copy(settings = settings, pending = pending, busy = false) }
            }
        }
    }
    fun redirect(text: String) {
        if (text.isBlank()) return
        mutate("redirect", text) { panel, operation ->
            val result = api.redirect(panel.owner.origin, panel.session, text)
            require(result.status in setOf("redirected", "queued", "rejected")) { "Hermes returned an unknown redirect outcome" }
            operations.finish(operation, if (result.status == "rejected") "rejected" else "applied", result.status)
            publish(panel) { it.copy(notice = "Redirect ${result.status}", busy = false) }
        }
    }
    fun dispatch(command: String) {
        val words = command.trim().removePrefix("/").split(Regex("\\s+"), limit = 2)
        if (words[0].isBlank()) return
        mutate("command.${words[0]}", command) { panel, operation ->
            val result = api.dispatch(panel.owner.origin, panel.session, words[0], words.getOrElse(1) { "" })
            operations.finish(operation, "applied", result.type)
            if (!owns(panel)) return@mutate
            if (state.value.draft.trim() == command.trim() && result.type in setOf("exec", "plugin", "send", "skill")) {
                state.update { it.copy(draft = "") }
            }
            when (result.type) {
                "exec", "plugin" -> publish(panel) { it.copy(notice = result.output.ifBlank { result.notice }, busy = false) }
                "alias" -> state.update { if (it.draft.isNotBlank() && it.draft.trim() != command.trim()) it else it.copy(draft = "/${result.target.removePrefix("/")}" + words.getOrElse(1) { "" }.let { arg -> if (arg.isBlank()) "" else " $arg" }) }
                "prefill" -> state.update { if (it.draft.isNotBlank() && it.draft.trim() != command.trim()) it else it.copy(draft = result.message) }
                "send", "skill" -> { if (result.message.isNotBlank()) submitCommand(result.message, result.display.ifBlank { command }) }
                else -> publish(panel) { it.copy(error = "Hermes returned an unsupported command result.") }
            }
        }
    }
    private fun mutate(kind: String, value: String, run: suspend (SessionControlsPanel, SessionMutationEntity) -> Unit) {
        val panel = captured() ?: run { state.update { it.copy(error = "Create a host thread before using session commands.") }; return }
        if (busy) return
        busy = true
        request++
        if (state.value.sessionControls == null) state.update { it.copy(sessionControls = panel.copy(loading = false)) }
        publish(panel) { it.copy(busy = true, error = null) }
        scope.launch {
            var operation: SessionMutationEntity? = null
            try {
                operation = operations.claim(panel.owner, kind, value)
                if (operation == null) { publish(panel) { it.copy(error = "An earlier operation is still awaiting its host outcome. Refresh; it will not be resent.") }; return@launch }
                if (!owns(panel)) { operations.finish(operation, "canceled"); return@launch }
                run(panel, operation)
            } catch (t: Throwable) {
                if (operation != null) withContext(NonCancellable) {
                    val rejected = (t as? DashboardException)?.code in setOf("rpc_-32601", "rpc_-32602", "rpc_4000", "rpc_4001", "rpc_4002", "rpc_4009")
                    operations.finish(operation, if (rejected) "rejected" else "unknown", "Host outcome requires reconciliation")
                }
                if (t is CancellationException) throw t
                publish(panel) { it.copy(error = message(t), unsupported = unsupported(t, it.unsupported, kind)) }
            } finally {
                busy = false
                val pending = withContext(NonCancellable) { operations.pending(panel.owner) }
                publish(panel) { it.copy(busy = false, pending = pending) }
            }
        }
    }
    private suspend fun reconcile(panel: SessionControlsPanel, settings: NativeSessionSettings) {
        for (operation in operations.pending(panel.owner)) {
            val expected = operation.requested.substringBefore(" --").trim()
            val actual = when (operation.kind) { "config.model" -> settings.model; "config.reasoning" -> settings.reasoning; "config.fast" -> settings.fast; else -> null }
            if (actual != null && actual == expected && operation.kind != "config.model") operations.finish(operation, "applied", "Confirmed by host settings")
            // Model confirmation must include provider identity when an explicit provider was requested.
            if (operation.kind == "config.model" && actual == expected) {
                val requestedProvider = Regex("--provider\\s+(\\S+)").find(operation.requested)?.groupValues?.get(1)
                if (requestedProvider == null || settings.provider == requestedProvider) operations.finish(operation, "applied", "Confirmed by host model/provider")
            }
        }
    }
    private fun unsupported(t: Throwable, previous: Set<String>, key: String) =
        if ((t as? DashboardException)?.code == "rpc_-32601") previous + if (key.startsWith("command.")) "command" else key else previous
    private fun message(t: Throwable) = t.message ?: "Could not contact Hermes. The operation will not be replayed."
}
