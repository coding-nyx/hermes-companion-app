package app.hermes.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.OperatorCred
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.local.OutboxStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.WakePing
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef
import app.hermes.companion.voice.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CompanionViewModel(
    private val client: DashboardClient,
    private val sticky: StickyStore,
    private val cache: TranscriptCache,
    private val outbox: OutboxStore,
    private val deviceCreds: DeviceCredStore,
    private val runtime: CompanionApp,
    private val operatorCreds: OperatorCredStore = runtime.operatorCreds,
) : ViewModel() {
    private val liveScope: CoroutineScope get() = runtime.appScope
    private val deviceNode: DeviceNodeCoordinator get() = runtime.deviceNode
    private val initialCred = deviceCreds.load()
    private val savedOperator = operatorCreds.load()
    private val initialOrigin = sticky.origin?.takeIf { it.isNotBlank() }
        ?: initialCred?.origin?.takeIf { it.isNotBlank() }
        ?: savedOperator?.origin?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        CompanionState(
            originInput = initialOrigin.orEmpty(),
            username = savedOperator?.username.orEmpty(),
            ntfyTopic = sticky.ntfyTopic.orEmpty(),
            stayConnected = sticky.stayConnected,
        ).mirror(runtime.deviceNode.state.value),
    )
    val state: StateFlow<CompanionState> = _state
    private var pendingWake: WakePing? = null
    private val chat = ChatSessionManager(client, cache, outbox, _state, viewModelScope)
    private val host = HostToolsController(client, operatorCreds, _state, viewModelScope) { origin -> connect(origin) }
    private val sync = SyncManager(client, cache, sticky, runtime, chat, _state, viewModelScope) { ping ->
        openWake(ping.profile, ping.sessionId)
    }

    init {
        viewModelScope.launch {
            deviceNode.state.collect { node -> _state.update { it.mirror(node) } }
        }
        viewModelScope.launch {
            deviceNode.errors.collect { message -> _state.update { it.copy(error = message) } }
        }
        if (!initialOrigin.isNullOrBlank()) {
            connect(initialOrigin)
        }
    }

    fun onNtfyTopicChange(value: String) {
        _state.update { it.copy(ntfyTopic = value, error = null) }
    }

    fun saveNtfy() {
        sticky.ntfyTopic = _state.value.ntfyTopic.trim().ifBlank { null }
        sync.startWake()
    }

    fun toggleStayConnected() {
        val next = !_state.value.stayConnected
        sticky.stayConnected = next
        _state.update { it.copy(stayConnected = next) }
    }

    fun openWake(profileId: String, sessionId: String) {
        val ping = WakePing(type = "approval.request", sessionId = sessionId, profile = profileId)
        val origin = _state.value.origin
        if (origin.isNullOrBlank()) {
            pendingWake = ping
            return
        }
        viewModelScope.launch {
            if (_state.value.activeProfileId != profileId) {
                sticky.profileId = profileId
                val sessions = runCatching {
                    cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        activeProfileId = profileId,
                        sessions = sessions,
                        tab = MainTab.THREADS,
                    )
                }
                sync.startWatch(origin, profileId)
            }
            val session = _state.value.sessions.find { it.id == sessionId }
                ?: SessionRef(
                    id = sessionId,
                    profileId = profileId,
                    title = sessionId,
                    updatedAtEpochMs = 0L,
                    unread = true,
                )
            chat.openSession(session)
        }
    }

    /** Deep link from another app: park it until the user confirms. Own notifications call [openWake]. */
    fun requestDeepLink(profileId: String, sessionId: String) {
        _state.update { it.copy(pendingDeepLink = DeepLinkRequest(profileId, sessionId)) }
    }

    fun confirmDeepLink() {
        val req = _state.value.pendingDeepLink ?: return
        _state.update { it.copy(pendingDeepLink = null) }
        openWake(req.profileId, req.sessionId)
    }

    fun dismissDeepLink() {
        _state.update { it.copy(pendingDeepLink = null) }
    }

    fun onOriginChange(value: String) {
        _state.update { it.copy(originInput = value, error = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            MainTab.CONSOLE -> host.refreshHostMetrics()
            MainTab.REVIEW -> host.loadGitStatus()
            MainTab.REMINDERS -> host.loadCronJobs()
            MainTab.GATEWAY -> {
                host.refreshHostMetrics()
                host.loadModelCatalog()
                host.checkUpdates()
                host.loadSavedGateways()
            }
            else -> {}
        }
    }

    fun toggleAwakeOnVoice() {
        val next = !_state.value.awakeOnVoice
        _state.update { it.copy(awakeOnVoice = next) }
        val intent = android.content.Intent(runtime, WakeWordService::class.java)
        if (next) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(runtime, intent) }
        } else {
            runtime.stopService(intent)
        }
    }

    fun toggleLockedAccess() {
        _state.update { it.copy(lockedAccess = !it.lockedAccess) }
    }

    fun setVoiceListening(listening: Boolean) {
        _state.update { it.copy(isListeningVoice = listening) }
    }

    fun onVoiceTranscript(transcript: String) {
        val current = _state.value.draft
        val combined = if (current.isBlank()) transcript else "$current $transcript"
        _state.update { it.copy(draft = combined, isListeningVoice = false) }
    }


    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    // ---- device node: owned by DeviceNodeCoordinator (app scope); the ViewModel only forwards ----

    fun startPair() {
        val profile = _state.value.activeProfileId ?: return
        _state.update { it.copy(error = null) }
        deviceNode.startPair(profile)
    }

    fun cancelPair() {
        _state.update { it.copy(error = null) }
        deviceNode.cancelPair()
    }

    fun revokePair() {
        _state.update { it.copy(error = null) }
        deviceNode.revokePair()
    }

    fun setA11yBound(bound: Boolean) = deviceNode.setA11yBound(bound)

    fun arm() = deviceNode.arm()

    fun disarm() = deviceNode.disarm()

    fun addProtectedPackage(raw: String) = deviceNode.addProtectedPackage(raw)

    fun removeProtectedPackage(pkg: String) = deviceNode.removeProtectedPackage(pkg)

    // ---- chat: ChatSessionManager ----
    fun openSession(session: SessionRef) = chat.openSession(session)
    fun closeChat() = chat.closeChat()
    fun loadOlder() = chat.loadOlder()
    fun respondApproval(decision: String) = chat.respondApproval(decision)
    fun newThread() = chat.newThread()
    fun beginRewind(message: ChatMessage) = chat.beginRewind(message)
    fun cancelRewind() = chat.cancelRewind()
    fun send() = chat.send()
    fun interrupt() = chat.interrupt()

    // ---- host tools: HostToolsController ----
    fun refreshHostMetrics() = host.refreshHostMetrics()
    fun loadCronJobs() = host.loadCronJobs()
    fun triggerCronJob(jobId: String) = host.triggerCronJob(jobId)
    fun toggleCronJob(jobId: String, currentEnabled: Boolean) = host.toggleCronJob(jobId, currentEnabled)
    fun loadModelCatalog() = host.loadModelCatalog()
    fun switchModel(model: String, provider: String) = host.switchModel(model, provider)
    fun loadGitStatus() = host.loadGitStatus()
    fun loadGitDiff(file: String) = host.loadGitDiff(file)
    fun closeGitDiff() = host.closeGitDiff()
    fun stageGitFile(file: String, stage: Boolean) = host.stageGitFile(file, stage)
    fun commitGit(message: String) = host.commitGit(message)
    fun executeTerminal(command: String) = host.executeTerminal(command)
    fun clearTerminalLogs() = host.clearTerminalLogs()
    fun checkUpdates() = host.checkUpdates()
    fun applyUpdate() = host.applyUpdate()
    fun loadSavedGateways() = host.loadSavedGateways()
    fun addSavedGateway(name: String, origin: String) = host.addSavedGateway(name, origin)
    fun selectGateway(gw: SavedGateway) = host.selectGateway(gw)

    fun setOverlayGranted(granted: Boolean) {
        _state.update { it.copy(overlayGranted = granted) }
    }

    fun setNotifyGranted(granted: Boolean) {
        _state.update { it.copy(notifyGranted = granted) }
    }

    fun connect(originOverride: String? = null) {
        val origin = (originOverride ?: _state.value.originInput).trim().trimEnd('/')
        if (origin.isBlank()) {
            _state.update { it.copy(error = "origin required") }
            return
        }
        if (!OriginPolicy.cleartextAllowed(origin)) {
            _state.update { it.copy(originInput = origin, error = OriginPolicy.CLEARTEXT_DENIED) }
            return
        }
        if (_state.value.loading && _state.value.originInput == origin) return
        if (_state.value.origin == origin && originOverride == null && _state.value.error == null) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, originInput = origin) }
            var gatedHost = _state.value.authRequired
            try {
                val status = client.probe(origin)
                val hud = GatewayHudMap.from(status)
                gatedHost = status.authRequired
                _state.update { it.copy(authRequired = status.authRequired, status = status) }
                if (status.authRequired) {
                    val provider = AuthPolicy.passwordProvider(status.authProviders)
                        ?: throw DashboardException("auth_oidc", AuthPolicy.OIDC_MESSAGE)
                    var user = _state.value.username.trim()
                    var pass = _state.value.password
                    if ((user.isBlank() || pass.isBlank()) && savedOperator != null && savedOperator.origin == origin) {
                        user = savedOperator.username
                        pass = savedOperator.password
                    }
                    if (user.isBlank() || pass.isBlank()) {
                        _state.update {
                            it.copy(loading = false, error = "username and password required")
                        }
                        return@launch
                    }
                    client.passwordLogin(origin, user, pass, provider)
                    operatorCreds.save(
                        OperatorCred(
                            origin = origin,
                            username = user,
                            password = pass,
                            sessionToken = client.sessionToken.orEmpty(),
                            authMode = "password",
                        )
                    )
                    _state.update { it.copy(password = "") }
                } else {
                    client.useLoopback()
                    client.adoptLoopbackToken(origin)
                    operatorCreds.save(
                        OperatorCred(
                            origin = origin,
                            sessionToken = client.sessionToken.orEmpty(),
                            authMode = "token",
                        )
                    )
                }
                val profiles = client.listProfiles(origin)
                val active = ProfileScope.resolveActive(profiles, sticky.profileId)
                    ?: throw DashboardException("no_profiles", "no profiles on host")
                val hello = runCatching { client.wsHello(origin, active.id) }.getOrNull()
                sticky.origin = origin
                sticky.profileId = active.id
                val cached = runCatching { cache.sessions(origin, active.id) }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            loading = true,
                            origin = origin,
                            profiles = profiles,
                            activeProfileId = active.id,
                            sessions = cached,
                            tab = MainTab.THREADS,
                            gatewayHello = hello,
                            status = status,
                            hud = hud,
                        )
                    }
                }
                val sessions = cache.readSessions(origin, active.id) {
                    client.listSessions(origin, active.id)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        origin = origin,
                        profiles = profiles,
                        activeProfileId = active.id,
                        sessions = sessions,
                        tab = MainTab.THREADS,
                        gatewayHello = hello,
                        status = status,
                        hud = hud,
                    )
                }
                sync.startHud(origin)
                sync.startWatch(origin, active.id)
                deviceNode.bind(origin)
                sync.startWake()
                host.refreshHostMetrics()
                host.loadModelCatalog()
                host.loadCronJobs()
                host.loadSavedGateways()
                pendingWake?.let { openWake(it.profile, it.sessionId) }
                pendingWake = null
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        origin = null,
                        authRequired = gatedHost,
                        error = t.toMonoError(),
                    )
                }
            }
        }
    }

    fun selectProfile(profileId: String) {
        val origin = _state.value.origin ?: return
        if (profileId == _state.value.activeProfileId) return
        sticky.profileId = profileId
        val keepChat = _state.value.openSession?.takeIf { it.profileId == profileId }
        chat.cancelTurn()
        viewModelScope.launch {
            val cached = runCatching { cache.sessions(origin, profileId) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    activeProfileId = profileId,
                    sessions = cached,
                    openSessionId = keepChat?.id,
                    messages = if (keepChat == null) emptyList() else it.messages,
                    streaming = false,
                    rewindTargetId = null,
                    // Picked from the profiles tab → land on the new profile's rail.
                    tab = if (it.tab == MainTab.PROFILES) MainTab.THREADS else it.tab,
                )
            }
            sync.startWatch(origin, profileId)
            try {
                val sessions = cache.readSessions(origin, profileId) {
                    client.listSessions(origin, profileId)
                }
                _state.update { state ->
                    if (state.activeProfileId != profileId) state
                    else state.copy(sessions = sessions, error = null)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.toMonoError()) }
            }
        }
    }

    override fun onCleared() {
        chat.cancelAll()
        if (sticky.stayConnected || deviceNode.state.value.arm != DeviceArm.DISARMED) {
            super.onCleared()
            return
        }
        deviceNode.unbind()
        sync.stopAll()
        client.closeRpc()
        super.onCleared()
    }

    companion object {
        fun factory(app: CompanionApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CompanionViewModel(
                        app.dashboard,
                        app.sticky,
                        app.cache,
                        app.outbox,
                        app.deviceCreds,
                        app,
                        app.operatorCreds,
                    ) as T
                }
            }
    }
}
