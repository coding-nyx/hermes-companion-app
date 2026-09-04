package app.hermes.companion

import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.domain.GatewayHudMap
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.HudState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background sync (A7.4): ntfy wake stream, HUD status poll, operator WS watch with heartbeat,
 * reconnect backoff and `sessions.changed` bus handling. Long-lived jobs live on the app scope
 * (via [CompanionApp]) so stay-connected survives the Activity; per-frame work uses [scope].
 */
class SyncManager(
    private val client: DashboardClient,
    private val cache: TranscriptCache,
    private val sticky: StickyStore,
    private val runtime: CompanionApp,
    private val chat: ChatSessionManager,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    private val onWake: (WakePing) -> Unit,
) {
    private val liveScope: CoroutineScope get() = runtime.appScope

    fun stopAll() {
        runtime.watchJob?.cancel()
        runtime.watchJob = null
        runtime.hudJob?.cancel()
        runtime.hudJob = null
        runtime.wakeJob?.cancel()
        runtime.wakeJob = null
    }

    fun startWake() {
        val sse = WakePolicy.sseUrl(_state.value.ntfyTopic.ifBlank { sticky.ntfyTopic.orEmpty() }) ?: return
        runtime.wakeJob?.cancel()
        runtime.wakeJob = liveScope.launch {
            client.wakeEvents(sse).collect { raw ->
                val ping = WakePolicy.parse(raw) ?: return@collect
                _state.update { it.copy(lastWake = ping) }
                onWake(ping)
            }
        }
    }

    fun startHud(origin: String) {
        runtime.hudJob?.cancel()
        runtime.hudJob = liveScope.launch {
            while (isActive) {
                delay(HUD_MS)
                val snap = runCatching { client.probe(origin) }.getOrNull()
                _state.update { st ->
                    if (st.origin != origin) st
                    else if (snap != null) st.copy(status = snap, hud = GatewayHudMap.from(snap))
                    else st.copy(hud = st.hud.copy(api = HudState.OFF))
                }
            }
        }
    }

    fun startWatch(origin: String, profileId: String) {
        runtime.watchJob?.cancel()
        runtime.watchJob = liveScope.launch {
            var backoff = 1_000L
            var lastInstance = _state.value.gatewayHello?.instanceId.orEmpty()
            while (isActive) {
                try {
                    val hello = client.wsHello(origin, profileId)
                    if (lastInstance.isNotEmpty() && hello.instanceId.isNotEmpty() &&
                        hello.instanceId != lastInstance
                    ) {
                        client.forgetLiveIds()
                        runCatching {
                            cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                        }.onSuccess { rows ->
                            _state.update { st ->
                                if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                            }
                        }
                    }
                    lastInstance = hello.instanceId
                    _state.update { it.copy(gatewayHello = hello) }
                    backoff = 1_000L
                    runCatching { chat.flushOutbox() }
                    val openId = _state.value.openSessionId
                    if (openId != null && !_state.value.streaming) {
                        runCatching { client.pageMessages(origin, openId, profileId) }.onSuccess { page ->
                            runCatching { cache.replaceMessages(origin, profileId, openId, page.messages) }
                            val merged = chat.withQueued(origin, profileId, openId, page.messages)
                            _state.update { st ->
                                if (st.openSessionId != openId) st
                                else st.copy(messages = merged, historyHasMore = page.hasMore)
                            }
                        }
                    }
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
            BusFrame.SessionRefetch -> scope.launch {
                runCatching {
                    cache.readSessions(origin, profileId) { client.listSessions(origin, profileId) }
                }.onSuccess { rows ->
                    _state.update { st ->
                        if (st.activeProfileId == profileId) st.copy(sessions = rows) else st
                    }
                }
            }
            is BusFrame.SessionPatch -> {
                scope.launch { runCatching { cache.applyChange(origin, profileId, frame.change) } }
                _state.update { st ->
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
        private const val HUD_MS = 15_000L
    }
}
