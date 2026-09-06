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
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.ChatContent
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.WakePing
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlockKind
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef
import app.hermes.companion.voice.VoiceStreamEngine
import app.hermes.companion.voice.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CompanionViewModel(
    private val clients: HostClientPool,
    private val sticky: StickyStore,
    private val cache: TranscriptCache,
    private val outbox: OutboxStore,
    private val deviceCreds: DeviceCredStore,
    private val runtime: CompanionApp,
    private val operatorCreds: OperatorCredStore = runtime.operatorCreds,
) : ViewModel() {
    private val liveScope: CoroutineScope get() = runtime.appScope
    private val deviceNode: DeviceNodeCoordinator get() = runtime.deviceNode
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)
    // Auto-connect target: the last host that actually connected, never merely the last attempted one.
    private val initialOrigin = sticky.lastGoodOrigin?.takeIf { it.isNotBlank() }
        ?: sticky.origin?.takeIf { it.isNotBlank() }
        ?: deviceCreds.load()?.origin?.takeIf { it.isNotBlank() }
        ?: operatorCreds.load()?.origin?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        CompanionState(
            originInput = initialOrigin.orEmpty(),
            hostName = runtime.hostName(initialOrigin),
            username = initialOrigin?.let { operatorCreds.load(it)?.username }.orEmpty(),
            ntfyTopic = initialOrigin?.let { sticky.ntfyTopicFor(it) }.orEmpty(),
            stayConnected = sticky.stayConnected,
            biometricLock = sticky.biometricLock,
            appLocked = sticky.biometricLock,
            loading = !initialOrigin.isNullOrBlank(),
        ).mirror(runtime.deviceNode.state.value),
    )
    val state: StateFlow<CompanionState> = _state
    private var pendingWake: WakePing? = null
    private var connectJob: Job? = null
    private var probeJob: Job? = null
    private val chat = ChatSessionManager(clients, cache, outbox, _state, viewModelScope)
    private val voiceStream = VoiceStreamEngine(
        context = runtime,
        scope = viewModelScope,
        onSendPrompt = { prompt ->
            chat.sendPrompt(prompt)
        },
        onInterrupt = {
            chat.interrupt()
        },
        onStateChange = { vState ->
            _state.update { it.copy(voiceStreamState = vState) }
        },
        onError = { err ->
            _state.update { it.copy(error = err) }
        },
    )
    private val host = HostToolsController(clients, operatorCreds, _state, viewModelScope) { origin -> connect(origin) }
    private val sync = SyncManager(clients, cache, sticky, operatorCreds, runtime, chat, _state, viewModelScope) { ping ->
        openWake(ping.origin, ping.profile, ping.sessionId)
    }

    init {
        chat.onAssistantDelta = { delta -> voiceStream.onAssistantDelta(delta) }
        chat.onTurnCompleted = { voiceStream.onAssistantTurnCompleted() }
        chat.onTurnInterrupted = { voiceStream.onAssistantTurnInterrupted() }
        viewModelScope.launch {
            deviceNode.state.collect { node -> _state.update { it.mirror(node) } }
        }
        viewModelScope.launch {
            deviceNode.errors.collect { message -> _state.update { it.copy(error = message) } }
        }
        refreshConnectChoices()
        host.loadSavedGateways()
        sync.startFleetHealth()
        if (!initialOrigin.isNullOrBlank()) {
            connect(initialOrigin)
        } else {
            probeConnectChoices()
        }
    }

    fun onNtfyTopicChange(value: String) {
        _state.update { it.copy(ntfyTopic = value, error = null) }
    }

    fun saveNtfy() {
        val topic = _state.value.ntfyTopic.trim().ifBlank { null }
        val origin = _state.value.origin
        if (origin != null) sticky.setNtfyTopic(origin, topic) else sticky.ntfyTopic = topic
        sync.startWake()
    }

    fun toggleStayConnected() {
        val next = !_state.value.stayConnected
        sticky.stayConnected = next
        _state.update { it.copy(stayConnected = next) }
    }

    /**
     * Open [sessionId] on [profileId] at [targetOrigin] (blank = the active host). A ping for another
     * host switches operator host first; the wake is parked until that connect succeeds.
     */
    fun openWake(targetOrigin: String, profileId: String, sessionId: String) {
        val active = _state.value.origin
        val target = targetOrigin.trim().trimEnd('/').ifBlank { active.orEmpty() }
        val ping = WakePing(type = "approval.request", sessionId = sessionId, profile = profileId, origin = target)
        if (active.isNullOrBlank() || (target.isNotBlank() && !HostClientPool.key(target).equals(HostClientPool.key(active)))) {
            pendingWake = ping
            if (target.isNotBlank()) connect(target)
            return
        }
        val origin = active
        viewModelScope.launch {
            if (_state.value.activeProfileId != profileId) {
                sticky.setProfile(origin, profileId)
                val sessions = runCatching {
                    cache.readSessions(origin, profileId) { client(origin).listSessions(origin, profileId) }
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
    fun requestDeepLink(origin: String, profileId: String, sessionId: String) {
        _state.update { it.copy(pendingDeepLink = DeepLinkRequest(profileId, sessionId, origin)) }
    }

    fun confirmDeepLink() {
        val req = _state.value.pendingDeepLink ?: return
        _state.update { it.copy(pendingDeepLink = null) }
        openWake(req.origin, req.profileId, req.sessionId)
    }

    fun dismissDeepLink() {
        _state.update { it.copy(pendingDeepLink = null) }
    }

    fun onOriginChange(value: String) {
        _state.update { it.copy(originInput = value, error = null) }
    }

    fun selectConnectChoice(choice: GatewayChoice) {
        val cred = operatorCreds.load(choice.origin)
        _state.update {
            it.copy(
                originInput = choice.origin,
                username = cred?.username.orEmpty(),
                error = null,
            )
        }
        connect(choice.origin)
    }

    fun forgetConnectChoice(choice: GatewayChoice) {
        if (!choice.forgettable) return
        host.removeSavedGateway(choice.origin)
        refreshConnectChoices()
        probeConnectChoices()
    }

    private fun refreshConnectChoices() {
        val previous = _state.value.connectChoices.associate { GatewayBook.key(it.origin) to it.health }
        val merged = GatewayBook.merge(
            saved = operatorCreds.loadGateways(),
            pairedOrigins = deviceCreds.loadAll().map { it.origin },
            lastGoodOrigin = sticky.lastGoodOrigin,
        ).map { row ->
            row.copy(health = previous[GatewayBook.key(row.origin)].orEmpty())
        }
        _state.update { it.copy(connectChoices = merged) }
    }

    private fun probeConnectChoices() {
        val snapshot = _state.value.connectChoices
        if (snapshot.isEmpty()) return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val results = snapshot.map { choice ->
                async {
                    val up = withTimeoutOrNull(3_000) {
                        runCatching { client(choice.origin).probe(choice.origin) }.getOrNull() != null
                    } == true
                    GatewayBook.key(choice.origin) to up
                }
            }.awaitAll()
            val up = results.filter { it.second }.map { it.first }.toSet()
            val down = results.filter { !it.second }.map { it.first }.toSet()
            _state.update { it.copy(connectChoices = GatewayBook.markHealth(it.connectChoices, up, down)) }
        }
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

    fun noteError(message: String?) {
        _state.update { it.copy(error = message?.trim()?.ifBlank { null }) }
    }

    fun setBiometricLock(enabled: Boolean) {
        sticky.biometricLock = enabled
        _state.update { it.copy(biometricLock = enabled, appLocked = if (enabled) it.appLocked else false) }
    }

    fun lockIfEnabled() {
        if (sticky.biometricLock) _state.update { it.copy(appLocked = true) }
    }

    fun unlock() {
        _state.update { it.copy(appLocked = false, error = null) }
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

    fun approvePair() {
        _state.update { it.copy(error = null) }
        deviceNode.approvePair()
    }

    fun revokePair() {
        _state.update { it.copy(error = null) }
        deviceNode.revokePair()
    }

    fun repair() {
        _state.update { it.copy(error = null) }
        val profile = _state.value.activeProfileId ?: "default"
        deviceNode.repair(profile)
    }

    fun setA11yBound(bound: Boolean) = deviceNode.setA11yBound(bound)

    fun arm() = deviceNode.arm()

    fun disarm() = deviceNode.disarm()

    fun addProtectedPackage(raw: String) = deviceNode.addProtectedPackage(raw)

    fun removeProtectedPackage(pkg: String) = deviceNode.removeProtectedPackage(pkg)

    // ---- voice stream: VoiceStreamEngine ----
    fun toggleVoiceStream() {
        _state.update { it.copy(error = null) }
        if (_state.value.voiceStreamState == VoiceStreamState.IDLE) {
            if (_state.value.openSessionId == null) {
                val first = _state.value.visibleSessions.firstOrNull()
                if (first != null) {
                    chat.openSession(first)
                } else {
                    chat.newThread()
                }
            }
        }
        voiceStream.toggle()
    }

    fun stopVoiceStream() {
        _state.update { it.copy(error = null) }
        voiceStream.stopStream()
    }

    // ---- chat: ChatSessionManager ----
    fun openSession(session: SessionRef) = chat.openSession(session)
    fun closeChat() {
        voiceStream.stopStream()
        chat.closeChat()
    }
    fun loadOlder() = chat.loadOlder()
    fun respondApproval(decision: String) = chat.respondApproval(decision)
    fun newThread() = chat.newThread()
    fun requestDelete(session: SessionRef) = chat.requestDelete(session)
    fun confirmDelete() = chat.confirmDelete()
    fun cancelDelete() = chat.cancelDelete()
    fun beginRewind(message: ChatMessage) = chat.beginRewind(message)
    fun cancelRewind() = chat.cancelRewind()
    fun send() = chat.send()
    fun interrupt() = chat.interrupt()

    fun toggleAttach() {
        _state.update { it.copy(attachOpen = !it.attachOpen) }
    }

    fun removeAttachment(id: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filter { row -> row.id != id }) }
    }

    fun queueAttachment(uri: android.net.Uri, mime: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val kind = ChatContent.kindOf(mime, name, uri.toString())
            val raw = runCatching { runtime.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?: return@launch
            val id = java.util.UUID.randomUUID().toString()
            val dir = java.io.File(runtime.cacheDir, "attach").apply { mkdirs() }
            val bytes = if (kind == ChatBlockKind.IMAGE) ImageCompress.jpeg(raw) else raw
            if (kind == ChatBlockKind.VIDEO && bytes.size > 25 * 1024 * 1024) {
                _state.update { it.copy(error = "video too large · max 25MB", attachOpen = false) }
                return@launch
            }
            if (kind == ChatBlockKind.FILE && bytes.size > 10 * 1024 * 1024) {
                _state.update { it.copy(error = "file too large · max 10MB", attachOpen = false) }
                return@launch
            }
            val file = java.io.File(dir, id)
            file.writeBytes(bytes)
            val stored = ChatAttachment(
                id = id,
                localUri = file.absolutePath,
                name = name.ifBlank { "file" },
                mime = if (kind == ChatBlockKind.IMAGE) "image/jpeg" else mime,
                kind = kind,
                sizeBytes = bytes.size.toLong(),
            )
            _state.update {
                it.copy(pendingAttachments = it.pendingAttachments + stored, attachOpen = false, error = null)
            }
        }
    }

    suspend fun fetchMedia(url: String): ByteArray? {
        val origin = _state.value.origin ?: return null
        val absolute = when {
            url.startsWith("http") -> url
            url.startsWith("/") -> origin.trimEnd('/') + url
            java.io.File(url).isFile -> return java.io.File(url).readBytes()
            else -> return null
        }
        return runCatching { client(origin).fetchBytes(absolute) }.getOrNull()
    }

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
    fun addSavedGateway(name: String, origin: String) {
        host.addSavedGateway(name, origin)
        sync.startFleetHealth()
    }
    fun selectGateway(gw: SavedGateway) = host.selectGateway(gw)
    fun setFleetHealthForeground(on: Boolean) = sync.setFleetHealthForeground(on)

    fun setOverlayGranted(granted: Boolean) {
        _state.update { it.copy(overlayGranted = granted) }
    }

    fun setNotifyGranted(granted: Boolean) {
        _state.update { it.copy(notifyGranted = granted) }
    }

    fun connect(originOverride: String? = null) {
        val origin = (originOverride ?: _state.value.originInput).trim().trimEnd('/')
        if (origin.isBlank()) {
            _state.update { it.copy(loading = false, error = "origin required") }
            return
        }
        if (!OriginPolicy.cleartextAllowed(origin)) {
            _state.update { it.copy(loading = false, originInput = origin, error = OriginPolicy.CLEARTEXT_DENIED) }
            return
        }
        if (connectJob?.isActive == true && _state.value.originInput == origin) return
        if (_state.value.origin == origin && originOverride == null && _state.value.error == null) return
        probeJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, originInput = origin, hostName = runtime.hostName(origin)) }
            var gatedHost = _state.value.authRequired
            val api = client(origin)
            val savedOperator = operatorCreds.load(origin)
            try {
                val status = api.probe(origin)
                val hud = GatewayHudMap.from(status)
                gatedHost = status.authRequired
                _state.update { it.copy(authRequired = status.authRequired, status = status) }
                if (status.authRequired) {
                    val provider = AuthPolicy.passwordProvider(status.authProviders)
                        ?: throw DashboardException("auth_oidc", AuthPolicy.OIDC_MESSAGE)
                    var user = _state.value.username.trim()
                    var pass = _state.value.password
                    if ((user.isBlank() || pass.isBlank()) && savedOperator != null) {
                        user = savedOperator.username
                        pass = savedOperator.password
                    }
                    if (user.isBlank() || pass.isBlank()) {
                        _state.update {
                            it.copy(loading = false, error = "username and password required")
                        }
                        return@launch
                    }
                    api.passwordLogin(origin, user, pass, provider)
                    operatorCreds.save(
                        OperatorCred(
                            origin = origin,
                            username = user,
                            password = pass,
                            sessionToken = api.sessionToken.orEmpty(),
                            authMode = "password",
                        )
                    )
                    _state.update { it.copy(password = "") }
                } else {
                    api.useLoopback()
                    api.adoptLoopbackToken(origin)
                    operatorCreds.save(
                        OperatorCred(
                            origin = origin,
                            sessionToken = api.sessionToken.orEmpty(),
                            authMode = "token",
                        )
                    )
                }
                val profiles = api.listProfiles(origin)
                val active = ProfileScope.resolveActive(profiles, sticky.profileFor(origin))
                    ?: throw DashboardException("no_profiles", "no profiles on host")
                val hello = runCatching { api.wsHello(origin, active.id) }.getOrNull()
                sticky.origin = origin
                sticky.lastGoodOrigin = origin
                sticky.setProfile(origin, active.id)
                val hostName = runtime.hostName(origin)
                val ntfyTopic = sticky.ntfyTopicFor(origin).orEmpty()
                val cached = runCatching { cache.sessions(origin, active.id) }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            sessionsLoading = true,
                            origin = origin,
                            hostName = hostName,
                            ntfyTopic = ntfyTopic,
                            profiles = profiles,
                            activeProfileId = active.id,
                            modelOverride = active.model,
                            sessions = cached,
                            tab = MainTab.THREADS,
                            gatewayHello = hello,
                            status = status,
                            hud = hud,
                        )
                    }
                }
                val sessions = cache.readSessions(origin, active.id) {
                    api.listSessions(origin, active.id)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        sessionsLoading = false,
                        origin = origin,
                        hostName = hostName,
                        ntfyTopic = ntfyTopic,
                        profiles = profiles,
                        activeProfileId = active.id,
                        modelOverride = active.model,
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
                sync.startFleetHealth()
                host.refreshHostMetrics()
                host.loadModelCatalog()
                host.loadCronJobs()
                host.loadSavedGateways()
                StayConnectedService.refresh(runtime)
                pendingWake?.let { wake ->
                    pendingWake = null
                    openWake(wake.origin, wake.profile, wake.sessionId)
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        sessionsLoading = false,
                        origin = null,
                        authRequired = gatedHost,
                        error = t.toMonoError(),
                    )
                }
                refreshConnectChoices()
                probeConnectChoices()
            }
        }
    }

    fun selectProfile(profileId: String) {
        val origin = _state.value.origin ?: return
        if (profileId == _state.value.activeProfileId) return
        sticky.setProfile(origin, profileId)
        val keepChat = _state.value.openSession?.takeIf { it.profileId == profileId }
        chat.cancelTurn()
        voiceStream.stopStream()
        viewModelScope.launch {
            val cached = runCatching { cache.sessions(origin, profileId) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    activeProfileId = profileId,
                    modelOverride = it.profiles.find { p -> p.id == profileId }?.model.orEmpty(),
                    sessions = cached,
                    sessionsLoading = true,
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
                    client(origin).listSessions(origin, profileId)
                }
                _state.update { state ->
                    if (state.activeProfileId != profileId) state
                    else state.copy(sessions = sessions, sessionsLoading = false, error = null)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(sessionsLoading = false, error = t.toMonoError()) }
            }
        }
    }

    override fun onCleared() {
        voiceStream.shutdown()
        chat.cancelAll()
        if (sticky.stayConnected || deviceNode.state.value.arm != DeviceArm.DISARMED) {
            super.onCleared()
            return
        }
        deviceNode.unbind()
        sync.stopAll()
        _state.value.origin?.let { clients.existing(it)?.closeRpc() }
        super.onCleared()
    }

    companion object {
        fun factory(app: CompanionApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CompanionViewModel(
                        app.clients,
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
