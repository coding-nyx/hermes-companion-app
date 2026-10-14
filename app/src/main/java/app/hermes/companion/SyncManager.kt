package app.hermes.companion

import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.HostHealthMap
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.HudState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background sync (A7.4): ntfy wake stream, HUD status poll, operator WS watch with heartbeat,
 * reconnect backoff and `sessions.changed` bus handling. Long-lived jobs live on the app scope
 * (via [CompanionApp]) so stay-connected survives the Activity; per-frame work uses [scope].
 */
class SyncManager(
    private val clients: HostClientPool,
    private val cache: TranscriptCache,
    private val sticky: StickyStore,
    private val operatorCreds: OperatorCredStore,
    private val runtime: CompanionApp,
    private val chat: ChatSessionManager,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    private val onWake: (WakePing) -> Unit,
) {
    private val liveScope: CoroutineScope get() = runtime.appScope
    private var rosterJob: kotlinx.coroutines.Job? = null
    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    fun stopAll() {
        rosterJob?.cancel()
        runtime.watchJob?.cancel()
        runtime.watchJob = null
        runtime.hudJob?.cancel()
        runtime.hudJob = null
        runtime.fleetHealthJob?.cancel()
        runtime.fleetHealthJob = null
        runtime.wakeJobs.values.forEach { it.cancel() }
        runtime.wakeJobs.clear()
    }

    /**
     * One ntfy subscription per host that has a topic (A8.5). A ping is attributed to the host whose
     * topic it arrived on unless the payload names an `origin`, so hub pings open hub sessions even
     * while lab is the active operator host.
     */
    fun startWake() {
        val topics = sticky.ntfyHosts().toMutableMap()
        _state.value.origin?.let { active ->
            val own = _state.value.ntfyTopic.ifBlank { sticky.ntfyTopicFor(active).orEmpty() }
            if (own.isNotBlank()) topics[HostClientPool.key(active)] = own
        }
        val wanted = topics.mapNotNull { (origin, topic) -> WakePolicy.sseUrl(topic)?.let { origin to it } }.toMap()
        // Drop subscriptions for hosts that lost their topic.
        runtime.wakeJobs.keys.filter { it !in wanted }.forEach { runtime.wakeJobs.remove(it)?.cancel() }
        for ((origin, sse) in wanted) {
            if (runtime.wakeJobs[origin]?.isActive == true) continue
            runtime.wakeJobs[origin] = liveScope.launch {
                client(origin).wakeEvents(sse).collect { raw ->
                    val ping = WakePolicy.parse(raw, origin) ?: return@collect
                    _state.update { it.copy(lastWake = ping) }
                    // Post the shade notification here, in appScope, not from a Compose effect
                    // in MainActivity: this collector outlives the UI (StayConnectedService keeps
                    // the process up), so a thread reply that arrives while the app is closed —
                    // which is when a notification is actually the point — used to update state
                    // and notify nobody.
                    runCatching { WakeNotifier.show(runtime, ping) }
                    // UI navigation; a no-op when no Activity is alive.
                    onWake(ping)
                }
            }
        }
    }

    fun setFleetHealthForeground(on: Boolean) {
        runtime.fleetHealthForeground.value = on
        if (on && runtime.fleetHealthJob?.isActive != true) startFleetHealth()
    }

    /** Probe every saved host every 60s while the UI is foregrounded (A8.4). */
    fun startFleetHealth() {
        runtime.fleetHealthJob?.cancel()
        runtime.fleetHealthJob = liveScope.launch {
            while (isActive) {
                runtime.fleetHealthForeground.first { it }
                if (!isActive) break
                probeFleet()
                delay(FLEET_MS)
            }
        }
    }

    private suspend fun probeFleet() {
        val origins = buildList {
            operatorCreds.loadGateways().forEach { add(it.origin) }
            _state.value.origin?.let { add(it) }
        }.map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinctBy { GatewayBook.key(it) }
        if (origins.isEmpty()) {
            _state.update { it.copy(hostHealth = emptyMap()) }
            return
        }
        val results = coroutineScope {
            origins.map { origin ->
                async {
                    val key = GatewayBook.key(origin)
                    val status = withTimeoutOrNull(PROBE_MS) {
                        runCatching { client(origin).probe(origin) }.getOrNull()
                    }
                    key to HostHealthMap.classify(status)
                }
            }.awaitAll()
        }
        // Replace (do not forever-merge) so forgotten hosts leave hostHealth.
        _state.update { it.copy(hostHealth = results.toMap()) }
    }

    fun startHud(origin: String) {
        runtime.hudJob?.cancel()
        runtime.hudJob = liveScope.launch {
            while (isActive) {
                delay(HUD_MS)
                val snap = runCatching { client(origin).probe(origin) }.getOrNull()
                _state.update { st ->
                    if (st.origin != origin) st
                    else if (snap != null) st.copy(status = snap, hud = GatewayHudMap.from(snap))
                    else st.copy(hud = st.hud.copy(api = HudState.OFF))
                }
            }
        }
    }

    fun startWatch(origin: String, profileId: String) {
        rosterJob?.cancel()
        runtime.watchJob?.cancel()
        runtime.watchJob = liveScope.launch {
            var backoff = 1_000L
            while (isActive && _state.value.owns(origin, profileId)) {
                try {
                    val hello = client(origin).wsHello(origin, profileId)
                    _state.update { if (!it.owns(origin, profileId)) it else it.copy(gatewayHello = hello) }
                    backoff = 1_000L
                    coroutineScope {
                        // Listen before draining outbox: draining may run for an entire agent turn.
                        val events = launch(start = CoroutineStart.UNDISPATCHED) {
                            client(origin).bus(profileId).collect { onBus(it, origin, profileId) }
                        }
                        val catchUp = launch {
                            refreshSessions(origin, profileId)
                            val open = _state.value.openSessionId
                            if (DraftThread.isPersisted(open)) chat.refreshHistory(origin, profileId, open!!)
                            chat.flushOutbox()
                        }
                        val poll = launch {
                            while (isActive) {
                                delay(30_000)
                                refreshSessions(origin, profileId)
                            }
                        }
                        val ping = launch {
                            if (hello.heartbeat) while (isActive) {
                                delay(PING_MS)
                                try { client(origin).ping() }
                                catch (c: CancellationException) { throw c }
                                catch (_: Throwable) { client(origin).closeRpc(); break }
                            }
                        }
                        try { events.join() } finally { catchUp.cancel(); poll.cancel(); ping.cancel() }
                    }
                } catch (c: CancellationException) { throw c }
                catch (_: Throwable) {
                    _state.update { if (!it.owns(origin, profileId)) it else it.copy(gatewayHello = null) }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun refreshSessions(origin: String, profile: String) {
        val archived = _state.value.showArchived
        try {
            val rows = chat.retainCreated(origin, profile, client(origin).listSessions(origin, profile, archived))
            cache.replaceSessions(origin, profile, rows)
            _state.update { if (!it.owns(origin, profile) || it.showArchived != archived) it else it.copy(sessions = rows, sessionsLoading = false) }
        } catch (c: CancellationException) { throw c }
        catch (_: Throwable) { /* Keep the last successful catalog during outages. */ }
    }

    private fun onBus(frame: BusFrame, origin: String, profileId: String) {
        if (!_state.value.owns(origin, profileId)) return
        when (frame) {
            BusFrame.SessionRefetch -> {
                if (rosterJob?.isActive != true) rosterJob = scope.launch {
                    delay(150)
                    refreshSessions(origin, profileId)
                }
            }
            is BusFrame.SessionPatch -> {
                scope.launch { runCatching { cache.applyChange(origin, profileId, frame.change) } }
                _state.update { st ->
                    if (!st.owns(origin, profileId)) st
                    else {
                        val next = SessionLists.normalize(
                            ProfileScope.applyChange(st.sessions, frame.change, profileId),
                        )
                        val open = st.openSessionId
                        st.copy(
                            sessions = if (open == null) next else next.map { row ->
                                if (row.id == open) row.copy(unread = false) else row
                            },
                        )
                    }
                }
            }
            is BusFrame.Chat -> {
                val ev = frame.event
                val open = _state.value.openSessionId
                val forOpen = frame.sessionId != null && client(origin).matchesSession(open, frame.sessionId)
                when (ev) {
                    is ChatEvent.PromptExpired -> _state.update { st ->
                        if (st.owns(origin, profileId) && !st.inRoom && frame.sessionId != null &&
                            client(origin).matchesSession(st.openSessionId, frame.sessionId) && st.approval?.requestId == ev.requestId)
                            st.copy(approval = null) else st
                    }
                    is ChatEvent.Approval -> {
                        if (forOpen && open != null) {
                            _state.update { if (!it.shows(origin, profileId, open) || it.inRoom) it
                                else it.copy(approval = ev.prompt, streaming = false) }
                        } else if (frame.sessionId != null) {
                            _state.update { st ->
                                if (!st.owns(origin, profileId)) st else st.copy(
                                    sessions = st.sessions.map { row ->
                                        if (client(origin).matchesSession(row.id, frame.sessionId)) {
                                            row.copy(unread = true)
                                        } else row
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        if (!forOpen || open == null) return
                        if (streamingFor(_state.value, open)) return
                        val aid = "live-$open"
                        _state.update { if (!it.shows(origin, profileId, open)) it else applyEvent(it, ev, aid) }
                        if (ev is ChatEvent.Final || ev is ChatEvent.Completed) {
                            scope.launch { chat.refreshHistory(origin, profileId, open, turnFinished = true) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PING_MS = 15_000L
        private const val HUD_MS = 15_000L
        private const val FLEET_MS = 60_000L
        private const val PROBE_MS = 3_000L
    }
}
