package app.hermes.companion

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
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
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.ChatContent
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.domain.PairDeepLink
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.ThreadSort
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlockKind
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.RoomPolicySpec
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.SessionRef
import app.hermes.companion.model.SpeechBackend
import app.hermes.companion.voice.*
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
    private val _state = runtime.conversationState ?: MutableStateFlow(
        CompanionState(
            originInput = initialOrigin.orEmpty(),
            hostName = runtime.hostName(initialOrigin),
            username = initialOrigin?.let { operatorCreds.load(it)?.username }.orEmpty(),
            ntfyTopic = initialOrigin?.let { sticky.ntfyTopicFor(it) }.orEmpty(),
            stayConnected = sticky.stayConnected,
            keepA11yOn = sticky.keepA11yOn,
            keepA11yAvailable = CompanionAccessibilityService.canWriteSecureSettings(runtime),
            threadSort = ThreadSort.parse(sticky.threadSort),
            showArchived = sticky.showArchived,
            speechBackend = runCatching { SpeechBackend.valueOf(sticky.speechBackend.orEmpty()) }.getOrDefault(SpeechBackend.HOST),
            voicePacingMs = sticky.voicePacingMs,
            biometricLock = sticky.biometricLock,
            appLocked = sticky.biometricLock,
            loading = !initialOrigin.isNullOrBlank(),
        ).mirror(runtime.deviceNode.state.value),
    )
    .also { runtime.conversationState = it }
    val state: StateFlow<CompanionState> = _state
    private val pendingWakeNavigation = PendingWakeNavigation()
    private var connectJob: Job? = null
    private var probeJob: Job? = null
    private var profileJob: Job? = null
    private var sessionsJob: Job? = null
    private var dictationBase: String? = null
    private var dictationTarget: Triple<String?, String?, String?>? = null
    private val chat = runtime.conversationChat ?: ChatSessionManager(clients, cache, outbox, _state, runtime.appScope, runtime.drafts).also { runtime.conversationChat = it }
    private val attachmentStager = AttachmentStager(runtime, _state, viewModelScope, { chat.conversationEpoch })
    private val sessionContext = SessionContextController(_state, viewModelScope, { chat.conversationEpoch }) { origin, session ->
        client(origin).sessionContext(origin, session)
    }
    private val sessionControls = SessionControlsController(_state, viewModelScope, { chat.conversationEpoch },
        DashboardNativeSessionApi(clients), runtime.sessionMutations) { text, display -> chat.sendPrompt(text, display) }
    private val agents = AgentSessionManager(clients, _state, viewModelScope)
    private val rooms = RoomSessionManager(clients, RoomSeenStore.of(sticky), _state, viewModelScope,
        navigationEpoch = { chat.conversationEpoch }, draftStore = runtime.drafts, deliveryStore = runtime.roomDeliveries) { origin ->
        deviceCreds.load(origin)?.let { "${it.deviceId}:${it.credential}" }
    }
    private var dictationInput: SpeechInput? = null
    private val readAloud = MessageReadAloud(VoiceAudioSession(runtime),
        { id -> _state.update { it.copy(readAloudMessageId = id) } },
        { error -> noteError(error) }, { action -> runtime.mainExecutor.execute(action) })
    private var readAloudConversationEpoch = 0L
    private val voiceStream = runtime.voiceController ?: VoiceSessionController(runtime, _state, chat).also { runtime.voiceController = it }

    private fun transport(st: CompanionState): StreamingSpeechTransport {
        val origin = st.origin ?: error("Connect to use host speech")
        val profile = st.activeProfileId ?: error("Select a profile to use host speech")
        return NativeSpeechTransport(client(origin), origin, profile)
    }
    private fun speechInput(st: CompanionState): SpeechInput = when (st.speechBackend) {
        SpeechBackend.SYSTEM -> VoiceInputManager(runtime)
        SpeechBackend.HOST -> {
            HostSpeechInput(viewModelScope, AndroidSpeechCapture(runtime), transport(st))
        }
    }
    private fun speechOutput(st: CompanionState, cb: SpeechCallbacks): SpeechOutput = when (st.speechBackend) {
        SpeechBackend.SYSTEM -> TextToSpeechEngine(runtime, cb.onStart, cb.onCompleted, cb.onError, VoiceAudioSession.attributes)
        SpeechBackend.HOST -> {
            if (_state.value.hostVoiceSettings?.streaming == true) {
                StreamingHostSpeechOutput(viewModelScope, transport(st), AndroidPcmSpeechPlayer(viewModelScope), AndroidSpeechPlayer(), cb)
            } else HostSpeechOutput(viewModelScope, transport(st), AndroidSpeechPlayer(), cb)
        }
    }
    fun setSpeechBackend(backend: SpeechBackend) {
        sticky.speechBackend = backend.name
        _state.update { it.copy(speechBackend = backend) }
    }
    fun stopVoicePlayback() = voiceStream.stopPlayback()
    fun setVoicePacing(ms: Int) {
        sticky.voicePacingMs = ms
        _state.update { it.copy(voicePacingMs = ms) }
    }
    fun loadVoiceSettings() {
        val st = _state.value
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        if (st.voiceSettingsLoading) return
        _state.update { it.copy(voiceSettingsLoading = true, voiceSettingsError = "") }
        viewModelScope.launch {
            try {
                val settings = client(origin).audioSettings(origin, profile)
                _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(hostVoiceSettings = settings) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(hostVoiceSettings = null,
                    voiceSettingsError = "Speech settings are unavailable on this host. Update its native audio API or use its configured speech defaults.") }
            } finally { _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(voiceSettingsLoading = false) } }
        }
    }
    fun loadVoiceOptions(key: String) {
        val st = _state.value; val origin = st.origin ?: return; val profile = st.activeProfileId ?: return
        val parts = key.split('.')
        if (parts.size != 3 || parts.first() != "tts" || parts.last() !in setOf("voice", "voice_id")) return
        viewModelScope.launch {
            try {
                val voices = client(origin).audioVoiceCatalog(origin, profile, parts[1])
                if (voices.isNotEmpty()) _state.update { current ->
                    if (current.origin != origin || current.activeProfileId != profile) current else current.copy(
                        hostVoiceSettings = current.hostVoiceSettings?.copy(fields = current.hostVoiceSettings.fields.map { if (it.key == key) it.copy(options = voices) else it }))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(voiceSettingsError = "Voice list is unavailable; you can still enter a configured voice ID.") } }
        }
    }
    fun saveVoiceSettings(changes: Map<String, String>) {
        val st = _state.value
        val origin = st.origin ?: return
        val profile = st.activeProfileId ?: return
        val settings = st.hostVoiceSettings ?: return
        if (changes.isEmpty() || st.voiceSettingsLoading) return
        _state.update { it.copy(voiceSettingsLoading = true, voiceSettingsError = "") }
        viewModelScope.launch {
            try {
                val numeric = settings.fields.filter { it.numeric }.map { it.key }.toSet()
                val confirmed = client(origin).saveAudioSettings(origin, profile, changes, numeric)
                _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(hostVoiceSettings = confirmed) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(voiceSettingsError = "Could not confirm saved speech settings. Refresh before trying again.") }
            } finally { _state.update { if (it.origin != origin || it.activeProfileId != profile) it else it.copy(voiceSettingsLoading = false) } }
        }
    }
    fun readMessageAloud(message: ChatMessage) {
        if (_state.value.messages.none { it.id == message.id && it.text == message.text }) return
        if (_state.value.readAloudMessageId == message.id) { readAloud.stop(); return }
        if (message.streaming || message.reasoning || message.text.isBlank()) return
        cancelDictation(); voiceStream.stopStream()
        val snapshot = _state.value
        readAloudConversationEpoch = chat.conversationEpoch
        readAloud.play(message.id, message.text) { cb -> speechOutput(snapshot, cb) }
    }

    private val host = HostToolsController(clients, operatorCreds, _state, viewModelScope) { origin -> connect(origin) }
    private val sync = SyncManager(clients, cache, sticky, operatorCreds, runtime, chat, _state, viewModelScope)
    private val bots = BotModeController(_state, viewModelScope,
        app.hermes.companion.data.remote.DashboardNativeBotsApi(clients), runtime.nativeBotOperations, runtime.drafts)
    val botActions = BotModeActions(
        refresh = bots::refresh, openBot = { bot ->
            bots.openBot(bot) { owner, session ->
                if (_state.value.origin?.let(app.hermes.companion.domain.GatewayBook::key) == owner.origin) {
                    if (_state.value.activeProfileId != owner.profile) selectProfile(owner.profile)
                    openSession(session)
                }
            }
        }, saveBot = bots::saveBot, createGroup = bots::createGroup, openGroup = bots::openGroup,
        closeGroup = bots::closeGroup, draft = bots::draft, send = bots::send, reply = bots::reply,
        mention = bots::mention, rename = bots::rename, stop = bots::stop, disband = bots::disband,
        approve = bots::approve, retry = bots::retry, recover = bots::recover)

    private val backendVault = app.hermes.companion.backend.BackendVault(runtime)
    val backendWorkspace = app.companion.backend.BackendCoordinator(viewModelScope, backendVault,
        app.companion.backend.BackendFactory { connection, secret, pairing ->
            when (connection.runtime) {
                app.companion.backend.AgentRuntime.HERMES -> {
                    val old = operatorCreds.load(connection.endpoint)
                    val credentials = app.companion.backend.AgentCredentials(secret.seed, secret.token.ifBlank { old?.sessionToken.orEmpty() },
                        secret.password.ifBlank { old?.password.orEmpty() }, secret.deviceToken, secret.username.ifBlank { old?.username.orEmpty() })
                    app.hermes.companion.data.remote.HermesAgentBackend(connection, clients, credentials,
                        deviceCreds.load(connection.endpoint)?.let { "${it.deviceId}:${it.credential}" })
                }
                app.companion.backend.AgentRuntime.OPENCLAW -> app.companion.backend.OpenClawBackend(connection, secret) { token ->
                    val current = backendVault.credentials(connection)
                    backendVault.credentials(connection, app.companion.backend.AgentCredentials(current.seed,current.token,current.password,token,current.username))
                }
                app.companion.backend.AgentRuntime.CODYNC -> app.companion.backend.codync.CodyncBackend(connection, secret, pairing)
            }
        })
    fun showBackendConnections() {
        cancelDictation(); readAloud.stop(); voiceStream.stopStream()
        backendWorkspace.show()
    }
    fun openHermesControls(origin: String) { backendWorkspace.hide(); connect(origin) }

    init {
        if (!backendWorkspace.restore() && initialOrigin.isNullOrBlank()) backendWorkspace.show()
        for (gateway in operatorCreds.loadGateways()) if (gateway.kind == "hermes") {
            runCatching {
                val connection=app.companion.backend.AgentConnection.create(app.companion.backend.AgentRuntime.HERMES,gateway.origin,gateway.name)
                if(backendVault.connections().none { it.owner==connection.owner }) backendVault.save(connection)
            }
        }
        val appOwner = runtime
        chat.onSessionDeleted = { origin, profile, session ->
            kotlinx.coroutines.withContext(Dispatchers.IO) { appOwner.voiceJournal.remove(origin, profile, session) }
        }
        viewModelScope.launch {
            state.collect { snapshot ->
                refreshLocalDraftEntry(snapshot)
                sessionContext.selectionChanged()
                sessionContext.observe(snapshot)
                sessionControls.selectionChanged()
                sessionControls.hydrate()
                if (voiceStream.isActive && voiceStream.conversationEpoch != chat.conversationEpoch) stopVoiceStream(clearError = false)
                if (snapshot.readAloudMessageId != null && readAloudConversationEpoch != chat.conversationEpoch) readAloud.stop()
            }
        }
        viewModelScope.launch {
            deviceNode.state.collect { node -> _state.update { it.mirror(node) } }
        }
        viewModelScope.launch {
            deviceNode.errors.collect { message -> _state.update { it.copy(error = message) } }
        }
        refreshConnectChoices()
        host.loadSavedGateways()
        sync.startFleetHealth()
        if (!voiceStream.isActive) {
            if (!initialOrigin.isNullOrBlank()) connect(initialOrigin) else probeConnectChoices()
        }
    }

    private fun refreshLocalDraftEntry(snapshot: CompanionState) {
        if (snapshot.openSessionId != null || snapshot.inRoom) return
        val origin = snapshot.origin
        val profile = snapshot.activeProfileId
        val available = if (origin == null || profile == null) false else runCatching {
            !runtime.drafts.load(app.hermes.companion.data.local.DraftOwner(origin, profile,
                app.hermes.companion.domain.DraftThread.ID)).empty
        }.getOrDefault(false)
        _state.update { current ->
            if (current.origin != origin || current.activeProfileId != profile || current.openSessionId != null || current.inRoom || current.hasLocalDraft == available) current
            else current.copy(hasLocalDraft = available)
        }
    }

    fun resumeLocalDraft() {
        pendingWakeNavigation.clear()
        cancelDictation(); readAloud.stop(); voiceStream.stopStream()
        if (_state.value.inRoom) rooms.closeRoom()
        chat.newThread()
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

    /** WRITE_SECURE_SETTINGS can be granted over adb while the app runs; re-read on resume. */
    fun refreshKeepA11yAvailable() {
        val available = CompanionAccessibilityService.canWriteSecureSettings(runtime)
        if (available != _state.value.keepA11yAvailable) _state.update { it.copy(keepA11yAvailable = available) }
    }

    fun toggleKeepA11yOn() {
        val next = !_state.value.keepA11yOn
        sticky.keepA11yOn = next
        _state.update { it.copy(keepA11yOn = next) }
        if (next) deviceNode.recoverAccessibility()
    }

    fun toggleStayConnected() {
        val next = !_state.value.stayConnected
        if (next) StayConnectedService.backgroundTimedOut = false
        sticky.stayConnected = next
        _state.update { it.copy(stayConnected = next) }
    }

    /**
     * Open [sessionId] on [profileId] at [targetOrigin] (blank = the active host). A ping for another
     * host switches operator host first; the wake is parked until that connect succeeds.
     */
    fun openWake(targetOrigin: String, profileId: String, sessionId: String) {
        pendingWakeNavigation.clear()
        val snapshot = _state.value
        val active = snapshot.origin
        val target = targetOrigin.trim().trimEnd('/').ifBlank { active.orEmpty() }
        val request = DeepLinkRequest(profileId, sessionId, target)
        if (WakeNavigationPolicy.requiresVoiceConfirmation(snapshot, request)) {
            requestDeepLink(target, profileId, sessionId)
            return
        }
        if (WakeNavigationPolicy.isCurrentConversation(snapshot, request)) {
            _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
            return
        }
        if (!WakeNavigationPolicy.sameHost(active, target)) {
            pendingWakeNavigation.enqueue(request)
            if (target.isNotBlank()) connect(target)
            return
        }
        // Select the owner synchronously. The old suspended roster lookup could complete
        // after a newer host/profile selection and write its sessions into that context.
        // Existing managers checkpoint drafts and guard all subsequent history requests.
        if (_state.value.activeProfileId != profileId) selectProfile(profileId)
        val session = _state.value.sessions.find { it.id == sessionId && it.profileId == profileId }
            ?: SessionRef(sessionId, profileId, sessionId, updatedAtEpochMs = 0L, unread = true)
        openSession(session)
    }

    /** Trusted notification taps still respect an active voice conversation's ownership. */
    fun openNotificationLink(origin: String, profileId: String, sessionId: String, roomId: String = "") {
        if (roomId.isNotBlank()) openRoomWake(origin, roomId) else openWake(origin, profileId, sessionId)
    }

    /** Deep link from another app, or a voice-changing notification tap: wait for confirmation. */
    fun requestDeepLink(origin: String, profileId: String, sessionId: String, roomId: String = "") {
        _state.update { it.copy(pendingDeepLink = DeepLinkRequest(profileId, sessionId, origin, roomId)) }
    }

    fun confirmDeepLink() {
        val req = _state.value.pendingDeepLink ?: return
        _state.update { it.copy(pendingDeepLink = null) }
        if (req.roomId.isNotBlank()) openRoomWake(req.origin, req.roomId) else openWake(req.origin, req.profileId, req.sessionId)
    }

    /** Room wake / deep link (A22.5): land on the room, switching host first when needed. */
    fun openRoomWake(targetOrigin: String, roomId: String) {
        pendingWakeNavigation.clear()
        val snapshot = _state.value
        val active = snapshot.origin
        val target = targetOrigin.trim().trimEnd('/').ifBlank { active.orEmpty() }
        val request = DeepLinkRequest(snapshot.activeProfileId.orEmpty(), roomId, target, roomId)
        if (WakeNavigationPolicy.requiresVoiceConfirmation(snapshot, request)) {
            requestDeepLink(target, request.profileId, request.sessionId, roomId)
            return
        }
        if (WakeNavigationPolicy.isCurrentConversation(snapshot, request)) {
            _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
            return
        }
        if (!WakeNavigationPolicy.sameHost(active, target)) {
            pendingWakeNavigation.enqueue(request)
            if (target.isNotBlank()) connect(target)
            return
        }
        cancelDictation(); readAloud.stop(); voiceStream.stopStream()
        chat.cancelAll()
        _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
        rooms.openRoomById(roomId)
    }

    private fun openPendingWake(origin: String) {
        val request = pendingWakeNavigation.takeForHost(origin) ?: return
        openNotificationLink(request.origin, request.profileId, request.sessionId, request.roomId)
    }

    fun dismissDeepLink() {
        _state.update { it.copy(pendingDeepLink = null) }
    }

    fun onOriginChange(value: String) {
        _state.update { it.copy(originInput = value, error = null) }
    }

    fun selectConnectChoice(choice: GatewayChoice) {
        val cred = operatorCreds.load(choice.origin)
        // Clear leftover password so connect() loads this host's saved creds (A8 leftover).
        _state.update {
            it.copy(
                originInput = choice.origin,
                username = cred?.username.orEmpty(),
                password = "",
                error = null,
            )
        }
        connect(choice.origin)
    }

    /** Leave the active operator host: cancel sync/watch/fleet, clear session UI, stop stay-connected. */
    fun disconnect() {
        pendingWakeNavigation.clear()
        connectJob?.cancel()
        connectJob = null
        chat.cancelAll()
        voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
        sticky.stayConnected = false
        sticky.origin = null
        val origin = _state.value.origin
        sync.stopAll()
        origin?.let { clients.existing(it)?.let { api -> api.closeRpc(); api.closeBotSockets() } }
        // Keep device lanes while ARMED so Hands survives an operator disconnect (A8.5).
        if (deviceNode.state.value.arm == DeviceArm.DISARMED) {
            deviceNode.unbind()
        }
        _state.update {
            it.copy(
                origin = null,
                hostName = "",
                loading = false,
                sessionsLoading = false,
                transcriptLoading = false,
                historySource = "",
                error = null,
                password = "",
                profiles = emptyList(),
                activeProfileId = null,
                sessions = emptyList(),
                openSessionId = null,
                messages = emptyList(),
                draft = "",
                streaming = false,
                streamingSessionId = null,
                approval = null,
                historyHasMore = false,
                historyLoading = false,
                rewindTargetId = null,
                gatewayHello = null,
                status = null,
                hud = app.hermes.companion.model.GatewayHud(),
                ntfyTopic = "",
                stayConnected = false,
                hostMetrics = null,
                modelCatalog = null,
                cronJobs = emptyList(),
                gitStatus = null,
                gitDiff = null,
                gitSelectedFile = null,
                terminalLogs = emptyList(),
                pendingAttachments = emptyList(),
                attachOpen = false,
                tab = MainTab.BOTS,
            ).mirror(deviceNode.state.value)
        }
        StayConnectedService.refresh(runtime)
        refreshConnectChoices()
        probeConnectChoices()
        sync.startWake()
        sync.startFleetHealth()
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
        pendingWakeNavigation.clear()
        cancelDictation()
        readAloud.stop()
        _state.update { it.copy(tab = tab, chatMinimized = true) }
        if (tab == MainTab.CONSOLE) {
            agents.refreshSessions()
            if (_state.value.agentTools.isEmpty()) agents.refreshTools()
        }
        when (tab) {
            MainTab.DEVICE -> host.loadConnectionHealth()
            MainTab.BOTS -> bots.refresh()
            // Pairing may have happened since the last fetch; the room list needs the credential.
            MainTab.THREADS -> {
                rooms.refreshRooms()
            }
            MainTab.CONSOLE -> host.refreshHostMetrics()
            MainTab.REVIEW -> host.loadGitStatus()
            MainTab.REMINDERS -> host.loadCronJobs()
            MainTab.GATEWAY -> {
                host.loadConnectionHealth()
                host.loadVoiceStatus()
                host.refreshHostMetrics()
                host.loadModelCatalog()
                host.checkUpdates()
                host.loadSavedGateways()
            }
            else -> {}
        }
    }

    fun toggleAwakeOnVoice() {
        if (voiceStream.isActive) { noteError("End the voice conversation before enabling the wake-word microphone"); return }
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

    fun cancelDictation(error: String? = null) {
        dictationInput?.stopListening(); dictationInput = null
        dictationBase = null
        dictationTarget = null
        _state.update { it.copy(isListeningVoice = false, error = error ?: it.error) }
    }

    fun beginDictation() {
        cancelDictation()
        readAloud.stop()
        voiceStream.stopStream()
        val st = _state.value
        dictationBase = st.draft
        dictationTarget = Triple(st.origin, st.activeProfileId, st.openSessionId)
        setVoiceListening(true)
        try {
            dictationInput = speechInput(st).also { input ->
                input.startListening(onPartial = ::onDictationPartial, onResult = ::onVoiceTranscript,
                    onError = { cancelDictation(it) })
            }
        } catch (e: Exception) { cancelDictation(e.message ?: "Speech could not start") }
    }

    fun onDictationPartial(transcript: String) {
        val base = dictationBase ?: return
        _state.update { st ->
            if (dictationTarget != Triple(st.origin, st.activeProfileId, st.openSessionId)) st
            else st.copy(draft = app.hermes.companion.domain.DictationText.combine(base, transcript))
        }
    }

    fun onVoiceTranscript(transcript: String) {
        onDictationPartial(transcript)
        cancelDictation()
    }


    fun onDraftChange(value: String) {
        cancelDictation()
        // Manual edits retire the provisional speech span; late results cannot erase them.
        dictationBase = null
        dictationTarget = null
        _state.update { it.copy(draft = value) }
        chat.checkpointDraft()
    }

    // ---- device node: owned by DeviceNodeCoordinator (app scope); the ViewModel only forwards ----

    fun startPair() {
        val profile = _state.value.activeProfileId ?: return
        _state.update { it.copy(error = null) }
        deviceNode.startPair(profile)
    }

    /** Pair link from another app or a scanned QR: park it until the user confirms the host. */
    fun requestPairLink(origin: String, code: String, profile: String = "default") {
        val canonical = OriginPolicy.canonicalize(origin)
        if (canonical.isBlank() || code.isBlank()) return
        // Refuse up front: the strip must never offer a pair the credential exchange can't do safely.
        if (!OriginPolicy.cleartextAllowed(canonical)) {
            _state.update { it.copy(pendingPairLink = null, error = OriginPolicy.CLEARTEXT_DENIED) }
            return
        }
        _state.update { it.copy(pendingPairLink = PairDeepLink(canonical, code.trim().uppercase(), profile.ifBlank { "default" })) }
    }

    fun confirmPairLink() {
        val req = _state.value.pendingPairLink ?: return
        _state.update { it.copy(pendingPairLink = null) }
        pairWithLink(req.origin, req.code, req.profile)
    }

    fun dismissPairLink() {
        _state.update { it.copy(pendingPairLink = null) }
    }

    fun pairWithLink(origin: String, code: String, profile: String = "default") {
        val canonical = OriginPolicy.canonicalize(origin)
        if (canonical.isBlank() || code.isBlank()) return
        // The pair poll carries the device credential, so it gets the same cleartext rule as connect().
        if (!OriginPolicy.cleartextAllowed(canonical)) {
            _state.update { it.copy(originInput = canonical, error = OriginPolicy.CLEARTEXT_DENIED) }
            return
        }
        _state.update { it.copy(error = null, originInput = canonical) }
        deviceNode.pairWithCode(canonical, code, profile)
        if (_state.value.origin == null) {
            connect(canonical)
        }
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

    fun turnAccessibilityOn(): Boolean = deviceNode.turnAccessibilityOn()

    fun turnAccessibilityOff(): Boolean = deviceNode.turnAccessibilityOff()

    fun renameDeviceLabel(name: String) = deviceNode.renameDeviceLabel(name)

    fun addProtectedPackage(raw: String) = deviceNode.addProtectedPackage(raw)

    fun toggleNotifStream() {
        deviceNode.setNotifStreamEnabled(!_state.value.notifStreamEnabled)
    }

    fun setNotifStreamOrigin(origin: String) {
        deviceNode.setNotifStreamTarget(origin, null)
    }

    fun setNotifStreamApp(pkg: String, selected: Boolean) = deviceNode.setNotifStreamApp(pkg, selected)
    fun setNotifStreamContent(mode: String) = deviceNode.setNotifStreamContent(mode)
    fun setNotifStreamRetention(minutes: Int) = deviceNode.setNotifStreamRetention(minutes)
    fun toggleNotifStreamCalls() = deviceNode.toggleNotifStreamCalls()

    fun setNotifStreamProfile(profileId: String) {
        deviceNode.setNotifStreamTarget(_state.value.notifStreamOrigin, profileId)
    }


    fun removeProtectedPackage(pkg: String) = deviceNode.removeProtectedPackage(pkg)

    // ---- voice stream: VoiceStreamEngine ----
    fun toggleVoiceStream() {
        if (_state.value.tab == MainTab.BOTS && _state.value.botMode.selected != null) {
            noteError("Open an individual Bot Chat to start a voice conversation.")
            return
        }
        val st = _state.value
        if (st.voiceStreamState == VoiceStreamState.IDLE && st.inRoom) {
            _state.update { it.copy(error = "Voice chat is available in individual threads") }
            return
        }
        if (st.voiceStreamState == VoiceStreamState.IDLE && (st.origin == null || st.activeProfileId == null || st.creatingThread)) {
            _state.update { it.copy(error = "Connect and select a conversation before starting voice") }
            return
        }
        _state.update { it.copy(error = null) }
        if (voiceStream.isActive) { voiceStream.stopStream(); return }
        cancelDictation(); readAloud.stop()
        try { VoiceCallService.start(runtime) }
        catch (e: Exception) { noteError(e.message ?: "Voice could not start") }
    }

    fun stopVoiceStream(clearError: Boolean = true) {
        if (clearError) _state.update { it.copy(error = null) }
        voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
    }

    fun toggleVoiceMicrophone() = voiceStream.setMicrophoneMuted(!voiceStream.isMicrophoneMuted)

    fun returnToVoiceConversation() {
        if (_state.value.openSessionId != null) _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
    }

    fun toggleVoiceSpeaker() {
        val enabled = !_state.value.voiceSpeaker
        _state.update { it.copy(voiceSpeaker = enabled) }
        voiceStream.useSpeaker(enabled)
    }

    // ---- chat: ChatSessionManager ----
    fun openSession(session: SessionRef) {
        pendingWakeNavigation.clear()
        if (voiceStream.isActive && _state.value.openSessionId == session.id) { returnToVoiceConversation(); return }
        cancelDictation(); readAloud.stop(); voiceStream.stopStream()
        if (_state.value.inRoom) rooms.closeRoom()
        _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
        chat.openSession(session)
    }
    fun closeChat() {
        pendingWakeNavigation.clear()
        val returnToBots = _state.value.openSession?.title == "Bot Chat"
        if (voiceStream.isActive) {
            cancelDictation()
            _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = true) }
            return
        }
        voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
        if (_state.value.inRoom) rooms.closeRoom() else chat.closeChat()
        if (returnToBots) selectTab(MainTab.BOTS)
    }

    // ---- Agent rooms (P21) ----
    fun refreshRooms() = rooms.refreshRooms()
    fun openRoom(room: RoomRef) {
        pendingWakeNavigation.clear()
        _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }
        voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
        if (_state.value.openSessionId != null) chat.closeChat()
        rooms.openRoom(room)
    }
    fun toggleRoomCreate(open: Boolean) = rooms.toggleCreateSheet(open)
    fun createRoom(spec: RoomCreateSpec) = rooms.createRoom(spec)
    fun deleteRoom(room: RoomRef) = rooms.deleteRoom(room)
    fun mentionRoom(glyph: String) = rooms.mention(glyph)
    fun pauseRoom() = rooms.pause()
    fun continueRoom(turns: Int = 6) = rooms.continueRoom(turns)
    fun summarizeRoom(by: String = "") = rooms.summarize(by)
    fun renameRoom(title: String) = rooms.rename(title)
    fun setRoomPolicy(policy: RoomPolicySpec) = rooms.setPolicy(policy)
    fun addRoomParticipant(id: String) = rooms.setParticipants(listOf(id), emptyList())
    fun removeRoomParticipant(id: String) = rooms.setParticipants(emptyList(), listOf(id))
    fun refreshPeers() = rooms.refreshPeers()
    fun refreshAgentTools(force: Boolean = false) = agents.refreshTools(force)
    fun refreshAgentSessions() = agents.refreshSessions()
    fun startAgent(tool: String, cwd: String, prompt: String, mode: String = "pty", title: String = "") = agents.start(tool, cwd, prompt, title, mode)
    fun agentSubmit(text: String) = agents.submit(text)
    fun browseAgentDirs(path: String, hidden: Boolean = false) = agents.browseDirs(path, hidden)
    fun agentApprove(decision: String) = agents.approve(decision)
    fun openAgent(session: AgentSession) = agents.open(session)
    fun closeAgent() = agents.close()
    fun agentSendText(text: String, enter: Boolean = true) = agents.sendText(text, enter)
    fun agentSendKey(key: String) = agents.sendKey(key)
    fun killAgent(session: AgentSession? = null) = agents.kill(session)
    fun forgetAgent(session: AgentSession) = agents.forget(session)
    fun setAgentInput(value: String) = agents.setInput(value)
    fun setAgentCols(cols: Int) = agents.setCols(cols)
    fun toggleAgentNew(open: Boolean) = agents.toggleNew(open)
    fun linkHost(gw: SavedGateway) = rooms.linkHost(gw)
    fun unlinkPeer(name: String) = rooms.unlinkPeer(name)
    fun loadOlder() = chat.loadOlder()
    fun respondApproval(decision: String) = if (_state.value.inRoom) rooms.respondApproval(decision) else chat.respondApproval(decision)
    fun newThread() { pendingWakeNavigation.clear(); _state.update { it.copy(tab = MainTab.THREADS, chatMinimized = false) }; cancelDictation(); readAloud.stop(); voiceStream.stopStream(); if (_state.value.inRoom) rooms.closeRoom(); chat.createThread() }
    fun requestDelete(session: SessionRef) = chat.requestDelete(session)
    fun retryMessage(id: String) { if (_state.value.inRoom) rooms.retryMessage(id) else chat.retryMessage(id) }
    fun renameSession(title: String) {
        if (_state.value.openSession?.title == "Bot Chat") noteError("Bot Chat keeps its canonical title. Edit the bot's name in Bots.")
        else chat.updateSession(title = title)
    }
    fun pinSession() = chat.updateSession(pinned = !(_state.value.openSession?.pinned ?: false))
    fun archiveSession() = chat.updateSession(archived = !(_state.value.openSession?.archived ?: false))
    fun branchSession() = chat.branchSession()
    fun showSessionContext() = sessionContext.open()
    fun dismissSessionContext() = sessionContext.dismiss()
    fun showSessionControls() = sessionControls.open()
    fun dismissSessionControls() = sessionControls.dismiss()
    fun setSessionSetting(key: String, value: String, confirmed: Boolean) = sessionControls.set(key, value, confirmed)
    fun redirectSession(text: String) = sessionControls.redirect(text)
    fun dispatchNativeCommand(text: String) {
        val command = app.hermes.companion.domain.BotChatPolicy.command(_state.value.openSession?.title, text)
        if (command != text && _state.value.draft.trim() == text.trim()) onDraftChange(command)
        sessionControls.dispatch(command)
    }
    fun confirmDelete() = chat.confirmDelete()
    fun cancelDelete() = chat.cancelDelete()
    fun beginRewind(message: ChatMessage) = chat.beginRewind(message)
    fun cancelRewind() = chat.cancelRewind()
    fun send() {
        if (attachmentStager.isPreparingCurrent()) { noteError("Wait for attachment preparation to finish."); return }
        if (_state.value.inRoom) rooms.post()
        else if (_state.value.draft.trim().startsWith("/")) dispatchNativeCommand(_state.value.draft.trim())
        else chat.send()
    }
    fun interrupt() = if (_state.value.inRoom) rooms.interrupt() else chat.interrupt()

    fun toggleAttach() {
        _state.update { it.copy(attachOpen = !it.attachOpen) }
    }

    fun removeAttachment(id: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filter { row -> row.id != id }) }
    }

    fun queueAttachment(uri: android.net.Uri, mime: String, name: String) {
        attachmentStager.stage(uri, mime, name)
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
    fun setConnectionMode(mode: String) { chat.checkpointDraft(); host.setConnectionMode(mode) }
    fun loadCronJobs() = host.loadCronJobs()
    fun triggerCronJob(jobId: String) = host.triggerCronJob(jobId)
    fun toggleCronJob(jobId: String, currentEnabled: Boolean) = host.toggleCronJob(jobId, currentEnabled)
    fun loadModelCatalog() = host.loadModelCatalog()
    fun switchModel(model: String, provider: String) {
        if (DraftThread.isPersisted(_state.value.openSessionId) && !_state.value.inRoom) {
            sessionControls.set("model", "$model --provider $provider --session")
        } else host.switchModel(model, provider)
    }
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
    fun setFleetHealthForeground(on: Boolean) {
        if (!on) chat.checkpointDraft()
        sync.setFleetHealthForeground(on)
    }

    fun setOverlayGranted(granted: Boolean) {
        _state.update { it.copy(overlayGranted = granted) }
    }

    fun setNotifyGranted(granted: Boolean) {
        _state.update { it.copy(notifyGranted = granted) }
    }

    fun connect(originOverride: String? = null) {
        val origin = OriginPolicy.canonicalize(originOverride ?: _state.value.originInput)
        pendingWakeNavigation.connectingTo(origin)
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
        connectJob?.cancel()
        profileJob?.cancel()
        sessionsJob?.cancel()
        if (_state.value.origin != origin) {
            sync.stopAll()
            chat.cancelAll()
            voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
            _state.update { it.dropHostScoped().copy(origin = null) }
        }
        connectJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, originInput = origin, hostName = runtime.hostName(origin)) }
            var gatedHost = _state.value.authRequired
            val api = client(origin)
            api.invalidateCompanionAvailability(origin)
            // Paired phones authenticate plugin routes (rooms, host tools) with the device credential.
            deviceCreds.load(origin)?.let { api.companionAuth = "${it.deviceId}:${it.credential}" }
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
                        val switched = it.origin != origin
                        it.copy(
                            loading = false,
                            sessionsLoading = true,
                            origin = origin,
                            hostName = hostName,
                            ntfyTopic = ntfyTopic,
                            profiles = profiles,
                            activeProfileId = active.id,
                            modelOverride = active.model,
                            sessions = SessionLists.normalize(cached),
                            tab = MainTab.BOTS,
                            gatewayHello = hello,
                            status = status,
                            hud = hud,
                        ).let { next -> if (switched) next.dropHostScoped() else next }
                    }
                    // The catalog can be used while catch-up completes; keep any opened draft.
                }
                val sessions = SessionLists.normalize(
                    cache.readSessions(origin, active.id) {
                        chat.retainCreated(origin, active.id, api.listSessions(origin, active.id, _state.value.showArchived))
                    },
                )
                _state.update {
                    if (cached.isNotEmpty()) return@update it.finishConnectionRoster(origin, active.id, sessions)
                    val switched = it.origin != origin
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
                        tab = MainTab.BOTS,
                        gatewayHello = hello,
                        status = status,
                        hud = hud,
                    ).let { next -> if (switched) next.dropHostScoped() else next }
                }
                if (_state.value.origin != origin) return@launch
                sync.startHud(origin)
                sync.startWatch(origin, _state.value.activeProfileId ?: active.id)
                deviceNode.bind(origin)
                host.loadConnectionHealth()
                rooms.refreshRooms()
                rooms.refreshPeers()
                sync.startWake()
                sync.startFleetHealth()
                host.refreshHostMetrics()
                host.loadModelCatalog()
                host.loadCronJobs()
                host.loadVoiceStatus()
                host.loadSavedGateways()
                StayConnectedService.refresh(runtime)
                openPendingWake(origin)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
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
        pendingWakeNavigation.clear()
        val origin = _state.value.origin ?: return
        if (profileId == _state.value.activeProfileId) return
        profileJob?.cancel()
        sessionsJob?.cancel()
        chat.cancelAll()
        voiceStream.stopStream()
        readAloud.stop()
        cancelDictation()
        if (_state.value.inRoom) rooms.closeRoom()
        sticky.setProfile(origin, profileId)
        _state.update { it.copy(activeProfileId = profileId,
            nativeVoiceStatus = null,
            modelOverride = it.profiles.find { p -> p.id == profileId }?.model.orEmpty(),
            sessions = emptyList(), sessionsLoading = true,
            openSessionId = null, openSessionRef = null, messages = emptyList(), draft = "",
            approval = null, pendingAttachments = emptyList(), streaming = false,
            streamingSessionId = null, submitting = false, creatingThread = false,
            historyLoading = false, transcriptLoading = false, rewindTargetId = null, error = null,
            tab = if (it.tab == MainTab.PROFILES) MainTab.THREADS else it.tab) }
        sync.startWatch(origin, profileId)
        host.loadVoiceStatus()
        profileJob = viewModelScope.launch {
            val cached = cache.sessions(origin, profileId)
            _state.update { if (!it.owns(origin, profileId)) it else it.copy(sessions = SessionLists.normalize(cached)) }
            reloadSessions()
        }
    }

    /** Rail order (A18.9). Persisted phone-wide; the list re-sorts in place. */
    fun setThreadSort(sort: ThreadSort) {
        sticky.threadSort = sort.name
        _state.update { it.copy(threadSort = sort) }
    }

    /** ARCHIVED toggle (A18.12): archived rows need a different host query, so flip then refetch. */
    fun setShowArchived(on: Boolean) {
        sticky.showArchived = on
        _state.update { it.copy(showArchived = on) }
        reloadSessions()
    }

    /** Re-fetch the sticky profile's session rail (threads RETRY / refresh). */
    fun reloadSessions() {
        val origin = _state.value.origin ?: return
        val profileId = _state.value.activeProfileId ?: return
        rooms.refreshRooms()
        sessionsJob?.cancel()
        val archived = _state.value.showArchived
        sessionsJob = viewModelScope.launch {
            _state.update { it.copy(sessionsLoading = true, error = null) }
            try {
                val sessions = SessionLists.normalize(
                    cache.readSessions(origin, profileId) {
                        chat.retainCreated(origin, profileId, client(origin).listSessions(origin, profileId, _state.value.showArchived))
                    },
                )
                _state.update { state ->
                    if (!state.owns(origin, profileId) || state.showArchived != archived) state
                    else state.copy(sessions = sessions, sessionsLoading = false, error = null)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!it.owns(origin, profileId)) it else it.copy(sessionsLoading = false, error = t.toMonoError()) }
            }
        }
    }

    override fun onCleared() {
        backendWorkspace.close()
        clients.all().values.forEach { it.closeBotSockets() }
        cancelDictation(); readAloud.stop()
        if (voiceStream.isActive) { super.onCleared(); return }
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
