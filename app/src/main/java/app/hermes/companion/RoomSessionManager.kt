package app.hermes.companion

import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.Rooms
import app.hermes.companion.domain.coalesceDeltas
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.PeerLink
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomPolicySpec
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.SavedGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Last-seen transcript seq per room (A22.2). [StickyStore] implements it; tests pass a map. */
interface RoomSeenStore {
    fun roomSeen(origin: String, roomId: String): Int
    fun setRoomSeen(origin: String, roomId: String, seq: Int)

    companion object {
        fun inMemory(): RoomSeenStore = object : RoomSeenStore {
            private val map = mutableMapOf<String, Int>()
            override fun roomSeen(origin: String, roomId: String): Int = map["$origin/$roomId"] ?: 0
            override fun setRoomSeen(origin: String, roomId: String, seq: Int) { map["$origin/$roomId"] = seq }
        }

        fun of(sticky: StickyStore): RoomSeenStore = object : RoomSeenStore {
            override fun roomSeen(origin: String, roomId: String): Int = sticky.roomSeen(origin, roomId)
            override fun setRoomSeen(origin: String, roomId: String, seq: Int) = sticky.setRoomSeen(origin, roomId, seq)
        }
    }
}

/** Everything the create sheet collects (A22.7 / A22.10 / A22.11). */
data class RoomCreateSpec(
    val title: String,
    val participants: List<String>,
    val policy: RoomPolicySpec = RoomPolicySpec(),
    val openingPost: String = "",
)

/**
 * Agent rooms on the phone (P21, v2 in P22). Shares the transcript fields of [CompanionState]
 * (`messages`, `draft`, `streaming`, `approval`) with the single-agent chat so
 * [app.hermes.companion.chat.ChatScreen] renders both; `openRoomId` tells the shell which one is
 * showing. The host owns the room; this class mirrors it: history on open, then the
 * `/companion/rooms/events` socket with reconnect and **incremental** resync (`history?after=`).
 */
class RoomSessionManager(
    private val clients: HostClientPool,
    private val sticky: RoomSeenStore,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    /** Device credential per host, so calls to a *non-active* host (peer grant, peer profiles) are authorised. */
    private val deviceAuth: (origin: String) -> String? = { null },
) {
    private var eventsJob: Job? = null
    /** Streaming segment id for the turn in flight (`a-<turn_id>`), or null between turns. */
    private var currentTurn: String? = null
    /** Highest transcript seq the open room has on the phone (drives `history?after=`). */
    private var localSeq: Int = 0

    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    /** Client for another paired host with its device credential attached (relay routes are gated). */
    private fun peerClient(origin: String): DashboardClient {
        val api = clients.forOrigin(origin)
        deviceAuth(origin)?.let { api.companionAuth = it }
        return api
    }

    // ---- rail ---------------------------------------------------------------------------------

    fun refreshRooms() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(roomsLoading = true) }
            try {
                val rooms = client(origin).listRooms(origin)
                val seen = rooms.associate { it.id to sticky.roomSeen(origin, it.id) }
                _state.update { it.copy(rooms = rooms, roomSeen = seen, roomsLoading = false, roomsError = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A host running an older plugin has no /companion/rooms at all: no section, no noise.
                val unsupported = (t as? DashboardException)?.code == "http_404"
                _state.update { it.copy(rooms = emptyList(), roomsLoading = false, roomsError = if (unsupported) null else roomError(t)) }
            }
        }
    }

    fun createRoom(spec: RoomCreateSpec) {
        val origin = _state.value.origin ?: return
        if (spec.participants.isEmpty()) {
            _state.update { it.copy(error = "pick at least one profile") }
            return
        }
        scope.launch {
            try {
                val room = client(origin).createRoom(origin, spec.title, spec.participants, spec.policy)
                _state.update { it.copy(rooms = listOf(room) + it.rooms.filter { r -> r.id != room.id }, roomCreateOpen = false, error = null) }
                openRoom(room)
                if (spec.openingPost.isNotBlank()) {
                    _state.update { it.copy(draft = spec.openingPost) }
                    post()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = roomError(t)) }
            }
        }
    }

    /** Legacy entry (tests, older callers). */
    fun createRoom(title: String, participants: List<String>, maxRounds: Int) =
        createRoom(RoomCreateSpec(title, participants, RoomPolicySpec(maxRounds = maxRounds)))

    // ---- open / close -------------------------------------------------------------------------

    fun openRoom(room: RoomRef) {
        val origin = _state.value.origin ?: return
        eventsJob?.cancel()
        currentTurn = null
        localSeq = 0
        _state.update {
            it.copy(
                openRoomId = room.id,
                openRoom = room,
                openSessionId = null,
                openSessionRef = null,
                messages = emptyList(),
                draft = "",
                streaming = room.busy,
                roomSpeaking = room.speaking,
                approval = room.approval,
                rewindTargetId = null,
                historyHasMore = false,
                historyLoading = false,
                transcriptLoading = true,
                historySource = "",
                error = null,
            )
        }
        scope.launch {
            try {
                val (fresh, rows) = client(origin).roomHistory(origin, room.id)
                val current = fresh ?: room
                localSeq = current.seq
                markSeen(origin, current.id, current.seq)
                _state.update { st ->
                    if (st.openRoomId != room.id) st
                    else st.copy(
                        messages = rows,
                        transcriptLoading = false,
                        historySource = "room",
                        openRoom = current,
                        streaming = current.busy,
                        roomSpeaking = current.speaking,
                        approval = current.approval,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { st ->
                    if (st.openRoomId != room.id) st else st.copy(transcriptLoading = false, error = roomError(t))
                }
            }
        }
        eventsJob = scope.launch { pumpEvents(origin, room.id) }
    }

    fun openRoomById(roomId: String) {
        val origin = _state.value.origin ?: return
        _state.value.rooms.firstOrNull { it.id == roomId }?.let { openRoom(it); return }
        scope.launch {
            val rooms = runCatching { client(origin).listRooms(origin) }.getOrDefault(emptyList())
            _state.update { it.copy(rooms = rooms) }
            rooms.firstOrNull { it.id == roomId }?.let { openRoom(it) }
                ?: _state.update { it.copy(error = "room $roomId not on this host") }
        }
    }

    fun closeRoom() {
        eventsJob?.cancel()
        eventsJob = null
        currentTurn = null
        _state.update {
            it.copy(
                openRoomId = null,
                openRoom = null,
                roomSpeaking = null,
                messages = emptyList(),
                draft = "",
                streaming = false,
                approval = null,
                transcriptLoading = false,
                historySource = "",
            )
        }
    }

    private fun markSeen(origin: String, roomId: String, seq: Int) {
        if (seq <= 0) return
        if (sticky.roomSeen(origin, roomId) >= seq) return
        sticky.setRoomSeen(origin, roomId, seq)
        _state.update { it.copy(roomSeen = it.roomSeen + (roomId to seq)) }
    }

    // ---- operator actions ---------------------------------------------------------------------

    fun post() {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        // v2: the operator is never locked out; a line mid-plan steers the floor.
        val local = ChatMessage(id = "u-room-${System.nanoTime()}", role = MessageRole.USER, text = text)
        _state.update { it.copy(draft = "", error = null, streaming = true, messages = it.messages + local) }
        scope.launch {
            try {
                val seq = client(origin).postRoom(origin, room.id, text)
                localSeq = maxOf(localSeq, seq)
                markSeen(origin, room.id, seq)
                _state.update { st ->
                    // The room.post echo can land before this reply; never end up with two rm-<seq> rows.
                    val echoed = st.messages.any { m -> m.id == "rm-$seq" }
                    st.copy(
                        messages = if (echoed) st.messages.filter { m -> m.id != local.id }
                        else st.messages.map { m -> if (m.id == local.id) m.copy(id = "rm-$seq") else m },
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { st ->
                    st.copy(streaming = false, error = roomError(t), draft = text, messages = st.messages.filter { m -> m.id != local.id })
                }
            }
        }
    }

    fun interrupt() = roomCall { origin, room -> client(origin).interruptRoom(origin, room.id) }

    fun pause() = roomCall { origin, room -> client(origin).pauseRoom(origin, room.id) }

    fun continueRoom(turns: Int = 6) = roomCall { origin, room ->
        client(origin).continueRoom(origin, room.id, turns)?.let { updated -> mergeRoom(updated) }
    }

    fun summarize(by: String = "") = roomCall { origin, room ->
        client(origin).summarizeRoom(origin, room.id, by)?.let { updated -> mergeRoom(updated) }
    }

    fun rename(title: String) = roomCall { origin, room ->
        if (title.isNotBlank()) mergeRoom(client(origin).patchRoom(origin, room.id, title = title.trim()))
    }

    fun setPolicy(policy: RoomPolicySpec) = roomCall { origin, room ->
        mergeRoom(client(origin).patchRoom(origin, room.id, policy = policy))
    }

    fun setParticipants(add: List<String>, remove: List<String>) = roomCall { origin, room ->
        mergeRoom(client(origin).setRoomParticipants(origin, room.id, add, remove))
    }

    fun respondApproval(decision: String) {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        val prompt = _state.value.approval ?: return
        scope.launch {
            try {
                client(origin).respondRoomApproval(origin, room.id, prompt.requestId, decision)
                _state.update { it.copy(approval = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = roomError(t)) }
            }
        }
    }

    fun deleteRoom(room: RoomRef) {
        val origin = _state.value.origin ?: return
        scope.launch {
            try {
                client(origin).deleteRoom(origin, room.id)
                if (_state.value.openRoomId == room.id) closeRoom()
                _state.update { it.copy(rooms = it.rooms.filter { r -> r.id != room.id }) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = roomError(t)) }
            }
        }
    }

    fun mention(glyph: String) {
        _state.update { it.copy(draft = Rooms.withMention(it.draft, glyph)) }
    }

    fun toggleCreateSheet(open: Boolean) {
        _state.update { it.copy(roomCreateOpen = open) }
        if (open) {
            refreshPeers()
            loadPeerProfiles()
        }
    }

    private fun roomCall(block: suspend (String, RoomRef) -> Unit) {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        scope.launch {
            try {
                block(origin, room)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = roomError(t)) }
            }
        }
    }

    private fun mergeRoom(updated: RoomRef) {
        _state.update { st ->
            st.copy(
                openRoom = if (st.openRoomId == updated.id) updated else st.openRoom,
                rooms = st.rooms.map { if (it.id == updated.id) updated else it }.ifEmpty { listOf(updated) },
            )
        }
    }

    // ---- peers (A22.11) -----------------------------------------------------------------------

    fun refreshPeers() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(peersLoading = true) }
            val peers = runCatching { client(origin).listPeers(origin) }.getOrDefault(emptyList())
            _state.update { st -> if (st.origin != origin) st else st.copy(peers = peers, peersLoading = false) }
        }
    }

    /** Profiles on every linked peer, for the create sheet. Uses the phone's own credentials for that host. */
    fun loadPeerProfiles() {
        val origin = _state.value.origin ?: return
        scope.launch {
            val peers = _state.value.peers.ifEmpty { runCatching { client(origin).listPeers(origin) }.getOrDefault(emptyList()) }
            val out = mutableMapOf<String, List<ProfileRef>>()
            for (peer in peers) {
                val profiles = runCatching { peerClient(peer.origin).listProfiles(peer.origin) }.getOrDefault(emptyList())
                if (profiles.isNotEmpty()) out[peer.name] = profiles
            }
            _state.update { st -> if (st.origin != origin) st else st.copy(peerProfiles = out) }
        }
    }

    /**
     * Phone-brokered link: mint a grant on [gw] (the host that will *run* turns) and store it as a peer on
     * the active host (the one that *owns* rooms). The secret never persists on the phone.
     */
    fun linkHost(gw: SavedGateway) {
        val origin = _state.value.origin ?: return
        if (HostClientPool.key(gw.origin) == HostClientPool.key(origin)) return
        val ownName = _state.value.hostName.ifBlank { origin.substringAfter("://") }
        val peerName = peerName(gw)
        scope.launch {
            _state.update { it.copy(peerLinking = gw.origin, error = null) }
            try {
                val (hostId, secret, _) = peerClient(gw.origin).grantPeer(gw.origin, ownName)
                client(origin).addPeer(origin, peerName, gw.origin, hostId, secret)
                refreshPeers()
                loadPeerProfiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = "link failed · " + roomError(t)) }
            } finally {
                _state.update { it.copy(peerLinking = null) }
            }
        }
    }

    fun unlinkPeer(name: String) {
        val origin = _state.value.origin ?: return
        scope.launch {
            runCatching { client(origin).removePeer(origin, name) }
                .onFailure { t -> _state.update { it.copy(error = roomError(t)) } }
            refreshPeers()
        }
    }

    // ---- live events ----------------------------------------------------------------------------

    /** Reconnecting event pump; a socket drop mid-turn re-syncs incrementally from `history?after=`. */
    private suspend fun pumpEvents(origin: String, roomId: String) {
        var backoff = 1_000L
        while (scope.isActive && _state.value.openRoomId == roomId) {
            try {
                client(origin).roomEvents(origin, roomId).coalesceDeltas().collect { event ->
                    if (_state.value.openRoomId == roomId) {
                        if (event is ChatEvent.RoomReady) {
                            onReady(origin, roomId, event)
                        } else {
                            _state.update { applyRoomEvent(it, event) }
                            noteSeq(origin, roomId, event)
                        }
                    }
                }
                backoff = 1_000L
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ((t as? DashboardException)?.code == "room_unauthorized") {
                    _state.update { it.copy(error = roomError(t), streaming = false) }
                    return
                }
            }
            if (_state.value.openRoomId != roomId) return
            _state.update { st -> if (st.openRoomId == roomId) st.copy(historySource = "resyncing") else st }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private fun noteSeq(origin: String, roomId: String, event: ChatEvent) {
        val seq = when (event) {
            is ChatEvent.RoomPost -> event.seq
            is ChatEvent.TurnEnded -> event.seq
            is ChatEvent.RoomState -> event.seq
            else -> 0
        }
        if (seq > localSeq) {
            localSeq = seq
            markSeen(origin, roomId, seq)
        }
    }

    /** `room.ready` says where the host is; pull only what the phone missed. */
    private suspend fun onReady(origin: String, roomId: String, ready: ChatEvent.RoomReady) {
        currentTurn = null
        val hostSeq = ready.seq
        if (hostSeq >= 0 && hostSeq > localSeq) {
            runCatching { client(origin).roomHistory(origin, roomId, after = localSeq) }.onSuccess { (fresh, rows) ->
                localSeq = fresh?.seq ?: hostSeq
                markSeen(origin, roomId, localSeq)
                _state.update { st ->
                    if (st.openRoomId != roomId) st
                    else {
                        val known = st.messages.map { it.id }.toSet()
                        st.copy(
                            messages = st.messages.filterNot { it.streaming || it.toolRunning } + rows.filter { it.id !in known },
                            openRoom = fresh ?: ready.room ?: st.openRoom,
                            streaming = (fresh ?: ready.room)?.busy ?: false,
                            roomSpeaking = (fresh ?: ready.room)?.speaking,
                            approval = (fresh ?: ready.room)?.approval ?: st.approval,
                            historySource = "room",
                        )
                    }
                }
            }
        } else {
            _state.update { st ->
                if (st.openRoomId != roomId) st
                else st.copy(
                    openRoom = ready.room ?: st.openRoom,
                    streaming = ready.room?.busy ?: st.streaming,
                    roomSpeaking = ready.room?.speaking ?: st.roomSpeaking,
                    approval = ready.room?.approval ?: st.approval,
                    historySource = "room",
                )
            }
        }
    }

    internal fun applyRoomEvent(state: CompanionState, event: ChatEvent): CompanionState = when (event) {
        is ChatEvent.TurnStarted -> {
            currentTurn = "a-${event.turnId}"
            state.copy(
                streaming = true,
                roomSpeaking = event.speaker,
                openRoom = state.openRoom?.copy(state = "running", pauseReason = ""),
            )
        }
        is ChatEvent.AssistantDelta, is ChatEvent.ToolStarted, is ChatEvent.ToolCompleted -> {
            val turn = currentTurn ?: "a-orphan-${state.messages.size}".also { currentTurn = it }
            val speaker = (when (event) {
                is ChatEvent.AssistantDelta -> event.speaker
                is ChatEvent.ToolStarted -> event.speaker
                is ChatEvent.ToolCompleted -> event.speaker
                else -> null
            } ?: state.roomSpeaking)
            val next = applyEvent(state.copy(streaming = true), event, turn)
            // applyEvent does not know about speakers; stamp the rows this turn created.
            next.copy(messages = next.messages.map { m ->
                if ((m.id.startsWith(turn) || (m.role == MessageRole.TOOL && m.toolRunning)) && m.speaker == null && speaker != null) m.copy(speaker = speaker) else m
            })
        }
        is ChatEvent.TurnEnded -> {
            val turn = "a-${event.turnId}"
            val done = finishStream(state, turn)
            val hasSegment = done.messages.any { it.id.startsWith(turn) }
            val speaker = event.speaker.ifBlank { null } ?: state.roomSpeaking
            val messages = when {
                event.passed -> done.messages.filterNot { it.id.startsWith(turn) } + ChatMessage(
                    id = "rm-${event.seq}", role = MessageRole.ASSISTANT, text = "", speaker = speaker, passed = true,
                )
                // No text arrived: keep the turn visible either way (host error, or an empty reply).
                !hasSegment -> done.messages + ChatMessage(
                    id = "rm-${event.seq}", role = MessageRole.ASSISTANT, text = "", speaker = speaker,
                    toolDetail = event.error.ifBlank { "empty_reply" },
                )
                else -> done.messages.map { m ->
                    if (m.id.startsWith(turn)) m.copy(
                        id = if (m.id == turn) "rm-${event.seq}" else m.id,
                        speaker = m.speaker ?: speaker,
                        toolDetail = if (event.error.isNotBlank() && m.role == MessageRole.ASSISTANT) event.error else m.toolDetail,
                    ) else m
                }
            }
            currentTurn = null
            // Still streaming until room.idle / room.state says the floor is closed (another agent may follow).
            done.copy(
                messages = messages,
                streaming = true,
                roomSpeaking = null,
                openRoom = state.openRoom?.copy(turnsUsed = state.openRoom.turnsUsed + 1, seq = maxOf(state.openRoom.seq, event.seq)),
            )
        }
        is ChatEvent.RoomPost -> {
            val id = "rm-${event.seq}"
            val room = state.openRoom?.copy(seq = maxOf(state.openRoom.seq, event.seq), turnsUsed = 0)
            when {
                state.messages.any { it.id == id } -> state.copy(openRoom = room)
                // Our own optimistic row (still carrying its local id) is this post: adopt the seq.
                state.messages.any { it.id.startsWith("u-room-") && it.text == event.text } -> state.copy(
                    messages = state.messages.map { m ->
                        if (m.id.startsWith("u-room-") && m.text == event.text) m.copy(id = id) else m
                    },
                    streaming = true,
                    openRoom = room,
                )
                else -> state.copy(
                    messages = state.messages + ChatMessage(id = id, role = MessageRole.USER, text = event.text),
                    streaming = true,
                    openRoom = room,
                )
            }
        }
        is ChatEvent.Approval -> state.copy(approval = event.prompt)
        is ChatEvent.PromptExpired -> if (state.approval?.requestId == event.requestId) state.copy(approval = null) else state
        is ChatEvent.RoomState -> {
            val running = event.state == "running"
            state.copy(
                streaming = running,
                roomSpeaking = if (running) state.roomSpeaking else null,
                approval = if (running) state.approval else null,
                openRoom = state.openRoom?.copy(
                    state = event.state,
                    pauseReason = event.reason,
                    turnsUsed = event.turnsUsed,
                    budget = if (event.budget > 0) event.budget else state.openRoom.budget,
                    seq = maxOf(state.openRoom.seq, event.seq),
                    busy = running,
                ),
            )
        }
        is ChatEvent.RoomUpdated -> state.copy(
            openRoom = if (state.openRoomId == event.room.id) event.room.copy(state = state.openRoom?.state ?: event.room.state) else state.openRoom,
            rooms = state.rooms.map { if (it.id == event.room.id) event.room else it },
        )
        ChatEvent.Completed -> {
            currentTurn = null
            finishStream(state, "a-").copy(streaming = false, roomSpeaking = null)
        }
        else -> state
    }

    private fun roomError(t: Throwable): String = when ((t as? DashboardException)?.code) {
        "http_404" -> "host plugin has no rooms · update hermes-companion on the host"
        "http_503", "rooms_unavailable" -> "rooms need the dashboard or the hermes CLI on the host"
        "http_401", "room_unauthorized" -> "pair this phone with the host to use rooms"
        "http_409" -> "room busy or peer not linked"
        "peer_unknown" -> "peer not linked · link the hosts on the host tab"
        null -> t.message ?: "room error"
        else -> t.message ?: (t as DashboardException).code
    }

    companion object {
        /** Peer name for a saved gateway: its book name, sanitised for the relay (`[A-Za-z0-9-_.]`). */
        fun peerName(gw: SavedGateway): String =
            gw.name.ifBlank { gw.origin.substringAfter("://").substringBefore(':') }
                .filter { it.isLetterOrDigit() || it in "-_." }
                .take(32)
                .ifBlank { "peer" }
    }
}
