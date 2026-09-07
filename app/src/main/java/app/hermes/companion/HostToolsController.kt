package app.hermes.companion

import app.hermes.companion.console.TerminalLogEntry
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.TerminalExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Host-side tools reached over the dashboard REST surface (A7.4): metrics, cron, git review,
 * terminal, model catalog, Hermes update and the saved-gateway book. Pure request/response;
 * nothing here holds a socket.
 */
class HostToolsController(
    private val clients: HostClientPool,
    private val operatorCreds: OperatorCredStore,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    private val onConnect: (String) -> Unit,
) {
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    private fun applyIfOrigin(origin: String, transform: (CompanionState) -> CompanionState) {
        _state.update { if (it.origin != origin) it else transform(it) }
    }

    fun refreshHostMetrics() {
        val origin = _state.value.origin ?: return
        scope.launch {
            applyIfOrigin(origin) { it.copy(hostLoading = true) }
            val metrics = runCatching { client(origin).getHostMetrics(origin) }.getOrNull()
            applyIfOrigin(origin) { it.copy(hostMetrics = metrics, hostLoading = false) }
        }
    }

    fun loadCronJobs() {
        val origin = _state.value.origin ?: return
        scope.launch {
            applyIfOrigin(origin) { it.copy(cronLoading = true) }
            val jobs = runCatching { client(origin).getCronJobs(origin) }.getOrDefault(emptyList())
            applyIfOrigin(origin) { it.copy(cronJobs = jobs, cronLoading = false) }
        }
    }

    fun triggerCronJob(jobId: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).triggerCronJob(origin, jobId) }
            loadCronJobs()
        }
    }

    fun toggleCronJob(jobId: String, currentEnabled: Boolean) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).toggleCronJob(origin, jobId, pause = currentEnabled) }
            loadCronJobs()
        }
    }

    fun loadModelCatalog() {
        val origin = _state.value.origin ?: return
        scope.launch {
            applyIfOrigin(origin) { it.copy(modelLoading = true) }
            val cat = runCatching { client(origin).getModelCatalog(origin) }.getOrNull()
            applyIfOrigin(origin) {
                val override = it.modelOverride.ifBlank {
                    cat?.currentModel.orEmpty().ifBlank { it.activeProfile?.model.orEmpty() }
                }
                it.copy(
                    modelCatalog = cat,
                    modelLoading = false,
                    modelOverride = override,
                )
            }
        }
    }

    fun switchModel(model: String, provider: String) {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId
        _state.update { it.copy(modelOverride = model) }
        scope.launch {
            val ok = runCatching { client(origin).switchModel(origin, model, provider, profile) }.getOrDefault(false)
            if (ok) {
                loadModelCatalog()
                val status = runCatching { client(origin).probe(origin) }.getOrNull()
                if (status != null) _state.update { it.copy(status = status) }
            }
        }
    }

    fun loadGitStatus() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true) }
            val st = runCatching { client(origin).getGitStatus(origin) }.getOrNull()
            _state.update { it.copy(gitStatus = st, gitLoading = false) }
        }
    }

    fun loadGitDiff(file: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true, gitSelectedFile = file) }
            val diff = runCatching { client(origin).getGitDiff(origin, file) }.getOrNull()
            _state.update { it.copy(gitDiff = diff, gitLoading = false) }
        }
    }

    fun closeGitDiff() {
        _state.update { it.copy(gitSelectedFile = null, gitDiff = null) }
    }

    fun stageGitFile(file: String, stage: Boolean) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).stageGitFile(origin, file, stage) }
            loadGitStatus()
        }
    }

    fun commitGit(message: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true) }
            runCatching { client(origin).commitGit(origin, message) }
            loadGitStatus()
        }
    }

    fun executeTerminal(command: String) {
        val origin = _state.value.origin ?: return
        if (command.isBlank()) return
        scope.launch {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val runningEntry = TerminalLogEntry(command = command, result = null, isRunning = true, timestamp = time)
            _state.update { it.copy(terminalLogs = it.terminalLogs + runningEntry, terminalExecuting = true) }
            val res = runCatching { client(origin).executeTerminalCommand(origin, command) }.getOrElse {
                TerminalExecResult(ok = false, exitCode = 1, stderr = it.message.orEmpty())
            }
            _state.update { st ->
                val updated = st.terminalLogs.map { if (it.command == command && it.isRunning) it.copy(result = res, isRunning = false) else it }
                st.copy(terminalLogs = updated, terminalExecuting = false)
            }
        }
    }

    fun clearTerminalLogs() {
        _state.update { it.copy(terminalLogs = emptyList()) }
    }

    fun checkUpdates() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(updateLoading = true) }
            val status = runCatching { client(origin).checkHermesUpdate(origin) }.getOrNull()
            _state.update { it.copy(updateStatus = status ?: it.updateStatus, updateLoading = false) }
        }
    }

    fun applyUpdate() {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).applyHermesUpdate(origin) }
            checkUpdates()
        }
    }

    fun loadSavedGateways() {
        val currentOrigin = _state.value.origin?.let { OriginPolicy.canonicalize(it) }
        val gateways = operatorCreds.loadGateways().toMutableList()
        if (!currentOrigin.isNullOrBlank()) {
            val key = HostClientPool.key(currentOrigin)
            if (gateways.none { HostClientPool.key(it.origin) == key }) {
                val name = if (gateways.isEmpty()) "Primary Host" else OriginPolicy.host(currentOrigin)
                gateways.add(SavedGateway(id = currentOrigin, name = name, origin = currentOrigin, isActive = true))
                operatorCreds.saveGateways(gateways)
            }
        }
        val withActive = gateways.map {
            it.copy(isActive = currentOrigin != null && HostClientPool.key(it.origin) == HostClientPool.key(currentOrigin))
        }
        _state.update { it.copy(savedGateways = withActive) }
    }

    fun addSavedGateway(name: String, origin: String) {
        val origin = OriginPolicy.canonicalize(origin)
        val current = operatorCreds.loadGateways().toMutableList()
        val existingIdx = current.indexOfFirst { HostClientPool.key(it.origin) == HostClientPool.key(origin) }
        val newGw = SavedGateway(id = origin, name = name.ifBlank { OriginPolicy.host(origin) }, origin = origin, isActive = true)
        if (existingIdx >= 0) {
            current[existingIdx] = newGw
        } else {
            current.add(newGw)
        }
        val updated = current.map { it.copy(isActive = HostClientPool.key(it.origin) == HostClientPool.key(origin)) }
        operatorCreds.saveGateways(updated)
        _state.update { it.copy(savedGateways = updated) }
        onConnect(origin)
    }

    fun selectGateway(gw: SavedGateway) {
        val current = operatorCreds.loadGateways().map { it.copy(isActive = it.id == gw.id) }
        operatorCreds.saveGateways(current)
        _state.update { it.copy(savedGateways = current) }
        onConnect(gw.origin)
    }

    fun removeSavedGateway(origin: String) {
        val key = HostClientPool.key(origin)
        val updated = operatorCreds.loadGateways().filter { HostClientPool.key(it.origin) != key }
        operatorCreds.saveGateways(updated)
        _state.update { it.copy(savedGateways = updated) }
    }
}
