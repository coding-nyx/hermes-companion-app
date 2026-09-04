package app.hermes.companion

import app.hermes.companion.console.TerminalLogEntry
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.remote.DashboardClient
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
    private val client: DashboardClient,
    private val operatorCreds: OperatorCredStore,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    private val onConnect: (String) -> Unit,
) {
    fun refreshHostMetrics() {
        val origin = _state.value.origin ?: return
        scope.launch {
            val metrics = runCatching { client.getHostMetrics(origin) }.getOrNull()
            if (metrics != null) {
                _state.update { it.copy(hostMetrics = metrics) }
            }
        }
    }

    fun loadCronJobs() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(cronLoading = true) }
            val jobs = runCatching { client.getCronJobs(origin) }.getOrDefault(emptyList())
            _state.update { it.copy(cronJobs = jobs, cronLoading = false) }
        }
    }

    fun triggerCronJob(jobId: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client.triggerCronJob(origin, jobId) }
            loadCronJobs()
        }
    }

    fun toggleCronJob(jobId: String, currentEnabled: Boolean) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client.toggleCronJob(origin, jobId, pause = currentEnabled) }
            loadCronJobs()
        }
    }

    fun loadModelCatalog() {
        val origin = _state.value.origin ?: return
        scope.launch {
            val cat = runCatching { client.getModelCatalog(origin) }.getOrNull()
            if (cat != null) {
                _state.update { it.copy(modelCatalog = cat) }
            }
        }
    }

    fun switchModel(model: String, provider: String) {
        val origin = _state.value.origin ?: return
        val profile = _state.value.activeProfileId
        scope.launch {
            val ok = runCatching { client.switchModel(origin, model, provider, profile) }.getOrDefault(false)
            if (ok) {
                loadModelCatalog()
                val status = runCatching { client.probe(origin) }.getOrNull()
                if (status != null) _state.update { it.copy(status = status) }
            }
        }
    }

    fun loadGitStatus() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true) }
            val st = runCatching { client.getGitStatus(origin) }.getOrNull()
            _state.update { it.copy(gitStatus = st, gitLoading = false) }
        }
    }

    fun loadGitDiff(file: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true, gitSelectedFile = file) }
            val diff = runCatching { client.getGitDiff(origin, file) }.getOrNull()
            _state.update { it.copy(gitDiff = diff, gitLoading = false) }
        }
    }

    fun closeGitDiff() {
        _state.update { it.copy(gitSelectedFile = null, gitDiff = null) }
    }

    fun stageGitFile(file: String, stage: Boolean) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client.stageGitFile(origin, file, stage) }
            loadGitStatus()
        }
    }

    fun commitGit(message: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(gitLoading = true) }
            runCatching { client.commitGit(origin, message) }
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
            val res = runCatching { client.executeTerminalCommand(origin, command) }.getOrElse {
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
            val status = runCatching { client.checkHermesUpdate(origin) }.getOrNull()
            _state.update { it.copy(updateStatus = status) }
        }
    }

    fun applyUpdate() {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client.applyHermesUpdate(origin) }
            checkUpdates()
        }
    }

    fun loadSavedGateways() {
        val gateways = operatorCreds.loadGateways()
        val currentOrigin = _state.value.origin
        val withActive = if (gateways.isEmpty() && currentOrigin != null) {
            val initial = listOf(SavedGateway(id = currentOrigin, name = "Primary Host", origin = currentOrigin, isActive = true))
            operatorCreds.saveGateways(initial)
            initial
        } else {
            gateways.map { it.copy(isActive = it.origin == currentOrigin) }
        }
        _state.update { it.copy(savedGateways = withActive) }
    }

    fun addSavedGateway(name: String, origin: String) {
        val current = operatorCreds.loadGateways().toMutableList()
        val existingIdx = current.indexOfFirst { it.origin == origin }
        val newGw = SavedGateway(id = origin, name = name, origin = origin, isActive = true)
        if (existingIdx >= 0) {
            current[existingIdx] = newGw
        } else {
            current.add(newGw)
        }
        val updated = current.map { it.copy(isActive = it.origin == origin) }
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
}
