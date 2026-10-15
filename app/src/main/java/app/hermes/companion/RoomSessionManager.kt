package app.hermes.companion

import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.Rooms
import app.hermes.companion.domain.coalesceDeltas
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.CompanionAvailability
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
import kotlinx.coroutines.flow.collect
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
    private val navigationEpoch: () -> Long = { 0 },
    private val draftStore: app.hermes.companion.data.local.ComposerDraftStore? = null,
    private val roomApi: RoomConversationApi = DashboardRoomConversationApi(clients),
    private val deliveryStore: app.hermes.companion.data.local.RoomDeliveryStore = app.hermes.companion.data.local.RoomDeliveryStore(),
    private val journalDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    /** Device credential per host, so calls to a *non-active* host (peer grant, peer profiles) are authorised. */
    private val deviceAuth: (origin: String) -> String? = { null },
) {
    private var activeSelection: Selection? = null
    private var generation = 0L
    private data class Selection(val origin: String, val profile: String?, val room: String?,
        val session: String?, val generation: Long, val navigation: Long)
    private fun selection(): Selection? = _state.value.let { Selection(it.origin ?: return null,
        it.activeProfileId, it.openRoomId, it.openSessionId, generation, navigationEpoch()) }
    private fun owns(target: Selection, st: CompanionState = _state.value): Boolean =
        st.origin == target.origin && st.activeProfileId == target.profile &&
        st.openRoomId == target.room && st.openSessionId == target.session &&
        generation == target.generation && navigationEpoch() == target.navigation
    private fun saveDraft() {
        val st = _state.value
        val selected = activeSelection ?: return
        if (!owns(selected, st)) return
        val origin = st.origin ?: return
        val room = st.openRoomId ?: return
        val profile = st.activeProfileId ?: return
        runCatching { draftStore?.save(app.hermes.companion.data.local.DraftOwner(origin, profile, "room:$room"),
            app.hermes.companion.data.local.ComposerDraft(st.draft)) }.onFailure {
            _state.update { current -> if (current.origin != origin || current.openRoomId != room || current.activeProfileId != profile) current
                else current.copy(error = "Could not save this room draft. Your text remains in the composer.") }
        }
    }
    init { scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        _state.collect {
            if (it.inRoom) saveDraft()
            if (activeSelection?.let { selected -> !owns(selected) } == true) {
                eventsJob?.cancel(); eventsJob = null; activeSelection = null; currentTurn = null
            }
        }
    } }
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

    private suspend fun extensionsAvailable(origin: String): Boolean {
        val available = client(origin).companionAvailability(origin)
        _state.update { st ->
            if (st.origin != origin) st else if (available == CompanionAvailability.UNAVAILABLE) {
                st.copy(companionAvailability = available, rooms = emptyList(), roomsError = null,
                    roomsLoading = false, peers = emptyList(), peersLoading = false, peerProfiles = emptyMap())
            } else st.copy(companionAvailability = available)
        }
        return available == CompanionAvailability.AVAILABLE && _state.value.origin == origin
    }

    fun refreshRooms() {
        val origin = _state.value.origin ?: return
        scope.launch {
            try {
                if (!extensionsAvailable(origin)) return@launch
                _state.update { if (it.origin != origin) it else it.copy(roomsLoading = true) }
                val rooms = client(origin).listRooms(origin)
                val seen = rooms.associate { it.id to sticky.roomSeen(origin, it.id) }
                _state.update { if (it.origin != origin) it else it.copy(rooms = rooms, roomSeen = seen, roomsLoading = false, roomsError = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A host running an older plugin has no /companion/rooms at all: no section, no noise.
                val unsupported = (t as? DashboardException)?.code == "http_404"
                _state.update { if (it.origin != origin) it else it.copy(rooms = emptyList(), roomsLoading = false, roomsError = if (unsupported) null else roomError(t)) }
            }
        }
    }

    fun createRoom(spec: RoomCreateSpec) {
        val origin = _state.value.origin ?: return
        if (_state.value.companionAvailability != CompanionAvailability.AVAILABLE) {
            _state.update { it.copy(error = "Agent rooms require the optional Companion host integration") }
            return
        }
        if (spec.participants.isEmpty()) {
            _state.update { it.copy(error = "pick at least one profile") }
            return
        }
        val target = selection() ?: return
        scope.launch {
            try {
                val room = client(origin).createRoom(origin, spec.title, spec.participants, spec.policy)
                if (_state.value.origin != origin || _state.value.activeProfileId != target.profile) return@launch
                _state.update { it.copy(rooms = listOf(room) + it.rooms.filter { r -> r.id != room.id }, roomCreateOpen = false, error = null) }
                if (!owns(target)) return@launch
                openRoom(room)
                if (spec.openingPost.isNotBlank()) {
                    _state.update { it.copy(draft = spec.openingPost) }
                    post()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!owns(target, it)) it else it.copy(error = roomError(t)) }
            }
        }
    }

    /** Legacy entry (tests, older callers). */
    fun createRoom(title: String, participants: List<String>, maxRounds: Int) =
        createRoom(RoomCreateSpec(title, participants, RoomPolicySpec(maxRounds = maxRounds)))

    // ---- open / close -------------------------------------------------------------------------

    fun openRoom(room: RoomRef) {
        val origin = _state.value.origin ?: return
        saveDraft()
        generation++
        eventsJob?.cancel()
        currentTurn = null
        localSeq = 0
        val owner = _state.value.activeProfileId?.let { app.hermes.companion.data.local.DraftOwner(origin, it, "room:${room.id}") }
        val journalRecovery = runCatching { owner?.let(deliveryStore::list).orEmpty() }
        val draftRecovery = runCatching { owner?.let { draftStore?.load(it)?.text }.orEmpty() }
        val pending = journalRecovery.getOrDefault(emptyList())
        val savedDraft = draftRecovery.getOrDefault("")
        // Journal durability precedes clearing the draft; recreation cannot expose it as a second send.
        val recovered = if (pending.any { it.text == savedDraft.trim() && it.state != "failed" }) "" else savedDraft
        _state.update {
            it.copy(
                tab = MainTab.THREADS, chatMinimized = false,
                openRoomId = room.id,
                openRoom = room,
                openSessionId = null,
                openSessionRef = null,
                messages = pending.map(::deliveryRow),
                draft = recovered,
                streaming = room.busy,
                roomSpeaking = room.speaking,
                approval = room.approval,
                rewindTargetId = null,
                historyHasMore = false,
                historyLoading = false,
                transcriptLoading = true,
                historySource = "",
                pendingAttachments = emptyList(),
                error = if (journalRecovery.isFailure || draftRecovery.isFailure) "Could not recover room drafts or delivery records." else null,
            )
        }
        val target = selection() ?: return
        activeSelection = target
        val baseline = _state.value.messages
        scope.launch {
            try {
                val (fresh, rows) = roomApi.history(origin, room.id)
                if (!owns(target)) return@launch
                val current = fresh ?: room
                val recoveredRows = owner?.let { reconcileDeliveries(it, rows) }.orEmpty()
                localSeq = maxOf(localSeq, current.seq)
                markSeen(origin, current.id, current.seq)
                _state.update { st ->
                    if (!owns(target, st)) st
                    else st.copy(
                        messages = if (st.messages == baseline) rows + recoveredRows else rows + st.messages.filter { m -> rows.none { it.id == m.id } && recoveredRows.none { it.id == m.id } } + recoveredRows,
                        transcriptLoading = false,
                        historySource = "room",
                        openRoom = current,
                        streaming = if (st.messages == baseline) current.busy else st.streaming,
                        roomSpeaking = if (st.messages == baseline) current.speaking else st.roomSpeaking,
                        approval = st.approval ?: current.approval,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { st ->
                    if (!owns(target, st)) st else st.copy(transcriptLoading = false, error = roomError(t))
                }
            }
        }
        eventsJob = scope.launch { pumpEvents(target) }
    }

    fun openRoomById(roomId: String) {
        val origin = _state.value.origin ?: return
        _state.value.rooms.firstOrNull { it.id == roomId }?.let { openRoom(it); return }
        val target = selection() ?: return
        scope.launch {
            val rooms = try { roomApi.list(origin) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (owns(target)) _state.update { it.copy(error = roomError(t)) }
                return@launch
            }
            if (!owns(target)) return@launch
            _state.update { it.copy(rooms = rooms) }
            rooms.firstOrNull { it.id == roomId }?.let { openRoom(it) }
                ?: _state.update { it.copy(error = "room $roomId not on this host") }
        }
    }

    fun closeRoom() {
        saveDraft()
        generation++
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
                pendingAttachments = emptyList(),
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
        _state.update { if (it.origin != origin) it else it.copy(roomSeen = it.roomSeen + (roomId to seq)) }
    }

    // ---- operator actions ---------------------------------------------------------------------

    private var admittingPost: String? = null
    private val posting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun owner(target: Selection) = app.hermes.companion.data.local.DraftOwner(target.origin,
        requireNotNull(target.profile), "room:${requireNotNull(target.room)}")
    private fun deliveryRow(record: app.hermes.companion.data.local.RoomDeliveryRecord) = ChatMessage(
        id = record.sequence?.let { "rm-$it" } ?: record.id, role = MessageRole.USER, text = record.text,
        delivery = when { record.sequence != null -> app.hermes.companion.model.MessageDelivery.SENT
            record.state == "failed" -> app.hermes.companion.model.MessageDelivery.FAILED
            else -> app.hermes.companion.model.MessageDelivery.UNCERTAIN })
    private suspend fun reconcileDeliveries(owner: app.hermes.companion.data.local.DraftOwner, rows: List<ChatMessage>): List<ChatMessage> =
        kotlinx.coroutines.withContext(journalDispatcher) {
            val records = deliveryStore.list(owner)
            records.mapNotNull { record ->
                val baselineIds = record.baselineUserIds
                val matches = if (baselineIds == null) emptyList() else rows.filter { row ->
                    row.role == MessageRole.USER && row.id !in baselineIds && row.text.trim() == record.text }
                val identicalPending = records.count { it.sequence == null && it.text == record.text && it.state != "failed" }
                val confirmed = record.sequence?.let { seq -> rows.any { it.id == "rm-$seq" } } == true ||
                    (record.state != "failed" && identicalPending == 1 && matches.size == 1)
                if (confirmed) {
                    val sequence = record.sequence ?: matches.singleOrNull()?.id?.removePrefix("rm-")?.toIntOrNull()?.takeIf { it > 0 }
                    if (record.id in posting && sequence != null) deliveryStore.save(record.copy(state = "sent", sequence = sequence))
                    else deliveryStore.remove(owner, record.id)
                    null
                } else deliveryRow(record).let { row ->
                    if (record.state == "pending" && record.id in posting) row.copy(delivery = app.hermes.companion.model.MessageDelivery.SENDING) else row
                }
            }
        }
    private fun definitiveRejection(error: Throwable) = (error as? DashboardException)?.code in setOf(
        "http_400", "http_401", "http_403", "http_404", "http_405", "http_410", "http_413", "http_415", "http_422")

    fun post() {
        val target = selection() ?: return
        val room = _state.value.openRoom ?: return
        if (target.profile == null || admittingPost != null) return
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        val captured = _state.value
        val journalOwner = owner(target)
        val record = app.hermes.companion.data.local.RoomDeliveryRecord("u-room-${java.util.UUID.randomUUID()}", journalOwner,
            text, if (captured.transcriptLoading || captured.historySource != "room") null else captured.messages.filter { it.role == MessageRole.USER && it.id.startsWith("rm-") }.map { it.id }, createdAt = System.currentTimeMillis())
        admittingPost = record.id
        scope.launch {
            var submitted = false
            var receipt: Int? = null
            try {
                kotlinx.coroutines.withContext(journalDispatcher) {
                    check(deliveryStore.claim(record)) {
                        "This text has an unconfirmed send. Use CHECK before sending it again." }
                    draftStore?.consume(journalOwner, app.hermes.companion.data.local.ComposerDraft(captured.draft))
                }
                posting += record.id
                _state.update { st -> if (!owns(target, st)) st else st.copy(
                    draft = if (st.draft.trim() == text) "" else st.draft, error = null, streaming = true,
                    messages = st.messages + ChatMessage(record.id, MessageRole.USER, text,
                        delivery = app.hermes.companion.model.MessageDelivery.SENDING)) }
                if (admittingPost == record.id) admittingPost = null
                submitted = true
                val seq = roomApi.post(target.origin, room.id, text)
                check(seq > 0) { "The room send acknowledgment contained no sequence" }
                receipt = seq
                kotlinx.coroutines.withContext(journalDispatcher) { deliveryStore.save(record.copy(state = "sent", sequence = seq)) }
                if (!owns(target)) {
                    kotlinx.coroutines.withContext(journalDispatcher) { deliveryStore.remove(journalOwner, record.id) }
                    return@launch
                }
                localSeq = maxOf(localSeq, seq)
                markSeen(target.origin, room.id, seq)
                _state.update { st ->
                    if (!owns(target, st)) st else {
                        val echoed = st.messages.any { it.id == "rm-$seq" }
                        st.copy(messages = if (echoed) st.messages.filter { it.id != record.id }
                            else st.messages.map { if (it.id == record.id) it.copy(id = "rm-$seq", delivery = app.hermes.companion.model.MessageDelivery.SENT) else it })
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // The durable pending record is uncertain after recreation.
                val acknowledged = receipt ?: kotlinx.coroutines.withContext(journalDispatcher) {
                    runCatching { deliveryStore.list(journalOwner).firstOrNull { it.id == record.id }?.sequence }.getOrNull()
                }
                if (acknowledged != null) {
                    _state.update { st -> if (!owns(target, st)) st else st.copy(
                        error = if (receipt != null) "Send confirmed; could not save the local receipt. Host history remains authoritative." else null,
                        messages = if (st.messages.any { it.id == "rm-$acknowledged" }) st.messages.filterNot { it.id == record.id }
                            else st.messages.map { if (it.id == record.id) it.copy(id = "rm-$acknowledged", delivery = app.hermes.companion.model.MessageDelivery.SENT) else it }) }
                    return@launch
                }
                val failed = submitted && definitiveRejection(t)
                if (submitted) kotlinx.coroutines.withContext(journalDispatcher) {
                    runCatching { deliveryStore.save(record.copy(state = if (failed) "failed" else "uncertain")) }
                }
                _state.update { st -> if (!owns(target, st)) st else st.copy(streaming = false,
                    error = if (!submitted || failed) roomError(t) else "Delivery is unconfirmed. CHECK reads host history without resending.",
                    messages = st.messages.map { if (it.id == record.id) it.copy(delivery = if (failed)
                        app.hermes.companion.model.MessageDelivery.FAILED else app.hermes.companion.model.MessageDelivery.UNCERTAIN) else it }) }
            } finally {
                posting -= record.id; if (admittingPost == record.id) admittingPost = null
                if (receipt != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + journalDispatcher) {
                    runCatching { deliveryStore.remove(journalOwner, record.id) }
                }
            }
        }
    }

    fun retryMessage(id: String) {
        val target = selection() ?: return
        if (target.room == null || target.profile == null) return
        val journalOwner = owner(target)
        scope.launch {
            try {
                val record = kotlinx.coroutines.withContext(journalDispatcher) { deliveryStore.list(journalOwner).firstOrNull { it.id == id } } ?: return@launch
                if (record.state == "failed") {
                    if (!owns(target)) return@launch
                    if (_state.value.draft.isNotBlank()) {
                        _state.update { if (owns(target, it)) it.copy(error = "Your current draft is preserved. Clear it before retrying this rejected send.") else it }; return@launch
                    }
                    kotlinx.coroutines.withContext(journalDispatcher) { deliveryStore.remove(journalOwner, id) }
                    _state.update { if (!owns(target, it)) it else it.copy(draft = record.text, messages = it.messages.filterNot { row -> row.id == id }) }
                    if (owns(target)) post()
                    return@launch
                }
                val (_, rows) = roomApi.history(target.origin, target.room)
                val pending = reconcileDeliveries(journalOwner, rows)
                _state.update { st -> if (!owns(target, st)) st else {
                    val known = st.messages.filterNot { row -> row.id.startsWith("u-room-") || rows.any { it.id == row.id } }
                    st.copy(messages = rows + known + pending, error = if (pending.any { it.id == id })
                        "Host history refreshed; delivery is still unconfirmed. Nothing was resent." else null)
                } }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!owns(target, it)) it else it.copy(error = roomError(t)) }
            }
        }
    }

    fun interrupt() = roomCall { origin, room -> client(origin).interruptRoom(origin, room.id); null }

    fun pause() = roomCall { origin, room -> client(origin).pauseRoom(origin, room.id); null }

    fun continueRoom(turns: Int = 6) = roomCall { origin, room ->
        client(origin).continueRoom(origin, room.id, turns)
    }

    fun summarize(by: String = "") = roomCall { origin, room ->
        client(origin).summarizeRoom(origin, room.id, by)
    }

    fun rename(title: String) = roomCall { origin, room ->
        if (title.isNotBlank()) client(origin).patchRoom(origin, room.id, title = title.trim()) else null
    }

    fun setPolicy(policy: RoomPolicySpec) = roomCall { origin, room ->
        client(origin).patchRoom(origin, room.id, policy = policy)
    }

    fun setParticipants(add: List<String>, remove: List<String>) = roomCall { origin, room ->
        client(origin).setRoomParticipants(origin, room.id, add, remove)
    }

    fun respondApproval(decision: String) {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        val prompt = _state.value.approval ?: return
        val target = selection() ?: return
        scope.launch {
            try {
                roomApi.approve(origin, room.id, prompt.requestId, decision)
                _state.update { if (!owns(target, it) || it.approval?.requestId != prompt.requestId) it else it.copy(approval = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!owns(target, it)) it else it.copy(error = roomError(t)) }
            }
        }
    }

    fun deleteRoom(room: RoomRef) {
        val origin = _state.value.origin ?: return
        val target = selection() ?: return
        scope.launch {
            try {
                client(origin).deleteRoom(origin, room.id)
                if (_state.value.origin != origin || _state.value.activeProfileId != target.profile) return@launch
                if (owns(target) && _state.value.openRoomId == room.id) closeRoom()
                _state.update { it.copy(rooms = it.rooms.filter { r -> r.id != room.id }) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!owns(target, it)) it else it.copy(error = roomError(t)) }
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

    private fun roomCall(block: suspend (String, RoomRef) -> RoomRef?) {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        val target = selection() ?: return
        scope.launch {
            try {
                block(origin, room)?.let { if (owns(target)) mergeRoom(target, it) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { if (!owns(target, it)) it else it.copy(error = roomError(t)) }
            }
        }
    }

    private fun mergeRoom(target: Selection, updated: RoomRef) {
        _state.update { st ->
            if (!owns(target, st)) st else st.copy(
                openRoom = if (st.openRoomId == updated.id) updated else st.openRoom,
                rooms = st.rooms.map { if (it.id == updated.id) updated else it }.ifEmpty { listOf(updated) },
            )
        }
    }

    // ---- peers (A22.11) -----------------------------------------------------------------------

    fun refreshPeers() {
        val origin = _state.value.origin ?: return
        scope.launch {
            if (!extensionsAvailable(origin)) return@launch
            _state.update { if (it.origin != origin) it else it.copy(peersLoading = true) }
            val peers = try { client(origin).listPeers(origin) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
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
    private suspend fun pumpEvents(target: Selection) {
        val origin = target.origin
        val roomId = target.room ?: return
        var backoff = 1_000L
        while (scope.isActive && owns(target)) {
            try {
                roomApi.events(origin, roomId).coalesceDeltas().collect { event ->
                    if (owns(target)) {
                        if (event is ChatEvent.RoomReady) {
                            onReady(target, event)
                        } else {
                            _state.update { if (owns(target, it)) applyRoomEvent(it, event) else it }
                            noteSeq(origin, roomId, event)
                        }
                    }
                }
                backoff = 1_000L
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ((t as? DashboardException)?.code == "room_unauthorized") {
                    _state.update { if (owns(target, it)) it.copy(error = roomError(t), streaming = false) else it }
                    return
                }
            }
            if (!owns(target)) return
            _state.update { st -> if (owns(target, st)) st.copy(historySource = "resyncing") else st }
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
    private suspend fun onReady(target: Selection, ready: ChatEvent.RoomReady) {
        if (!owns(target)) return
        val origin = target.origin
        val roomId = target.room ?: return
        currentTurn = null
        val hostSeq = ready.seq
        if (hostSeq >= 0 && hostSeq > localSeq) {
            runCatching { roomApi.history(origin, roomId, after = localSeq) }.onSuccess { (fresh, rows) ->
                if (!owns(target)) return@onSuccess
                localSeq = maxOf(localSeq, fresh?.seq ?: hostSeq)
                markSeen(origin, roomId, localSeq)
                _state.update { st ->
                    if (!owns(target, st)) st
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
                if (!owns(target, st)) st
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
                state.messages.any { it.id.startsWith("u-room-") && it.text == event.text && it.delivery != app.hermes.companion.model.MessageDelivery.FAILED } -> state.copy(
                    messages = state.messages.mapIndexed { index, m ->
                        if (index == state.messages.indexOfFirst { it.id.startsWith("u-room-") && it.text == event.text && it.delivery != app.hermes.companion.model.MessageDelivery.FAILED })
                            m.copy(id = id, delivery = app.hermes.companion.model.MessageDelivery.SENT) else m
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
