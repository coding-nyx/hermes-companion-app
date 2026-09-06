package app.hermes.companion

import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.domain.Rooms
import app.hermes.companion.domain.coalesceDeltas
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.RoomRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Agent rooms on the phone (P21). Shares the transcript fields of [CompanionState]
 * (`messages`, `draft`, `streaming`) with the single-agent chat so [app.hermes.companion.chat.ChatScreen]
 * renders both; `openRoomId` tells the shell which one is showing. The host owns the room; this
 * class mirrors it: history on open, then the `/companion/rooms/events` socket with reconnect.
 */
class RoomSessionManager(
    private val clients: HostClientPool,
    private val _state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
) {
    private var eventsJob: Job? = null
    /** Streaming segment id for the turn in flight (`a-<turn_id>`), or null between turns. */
    private var currentTurn: String? = null

    private fun client(origin: String): DashboardClient = clients.forOrigin(origin)

    fun refreshRooms() {
        val origin = _state.value.origin ?: return
        scope.launch {
            _state.update { it.copy(roomsLoading = true) }
            try {
                val rooms = client(origin).listRooms(origin)
                _state.update { it.copy(rooms = rooms, roomsLoading = false, roomsError = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A host running an older plugin has no /companion/rooms at all: no section, no noise.
                val unsupported = (t as? DashboardException)?.code == "http_404"
                _state.update { it.copy(rooms = emptyList(), roomsLoading = false, roomsError = if (unsupported) null else roomError(t)) }
            }
        }
    }

    fun createRoom(title: String, participants: List<String>, maxRounds: Int) {
        val origin = _state.value.origin ?: return
        if (participants.isEmpty()) {
            _state.update { it.copy(error = "pick at least one profile") }
            return
        }
        scope.launch {
            try {
                val room = client(origin).createRoom(origin, title, participants, maxRounds)
                _state.update { it.copy(rooms = listOf(room) + it.rooms.filter { r -> r.id != room.id }, roomCreateOpen = false, error = null) }
                openRoom(room)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = roomError(t)) }
            }
        }
    }

    fun openRoom(room: RoomRef) {
        val origin = _state.value.origin ?: return
        eventsJob?.cancel()
        currentTurn = null
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
                approval = null,
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
                _state.update { st ->
                    if (st.openRoomId != room.id) st
                    else st.copy(
                        messages = rows,
                        transcriptLoading = false,
                        historySource = "room",
                        openRoom = fresh ?: room,
                        streaming = fresh?.busy ?: room.busy,
                        roomSpeaking = fresh?.speaking,
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
                transcriptLoading = false,
                historySource = "",
            )
        }
    }

    fun post() {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        if (_state.value.streaming) {
            _state.update { it.copy(error = "room busy · interrupt first") }
            return
        }
        val local = ChatMessage(id = "u-room-${System.nanoTime()}", role = MessageRole.USER, text = text)
        _state.update { it.copy(draft = "", error = null, streaming = true, messages = it.messages + local) }
        scope.launch {
            try {
                val seq = client(origin).postRoom(origin, room.id, text)
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

    fun interrupt() {
        val origin = _state.value.origin ?: return
        val room = _state.value.openRoom ?: return
        scope.launch {
            runCatching { client(origin).interruptRoom(origin, room.id) }
                .onFailure { t -> _state.update { it.copy(error = roomError(t)) } }
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
    }

    /** Reconnecting event pump; a socket drop mid-turn re-syncs from history. */
    private suspend fun pumpEvents(origin: String, roomId: String) {
        var backoff = 1_000L
        while (scope.isActive && _state.value.openRoomId == roomId) {
            try {
                client(origin).roomEvents(origin, roomId).coalesceDeltas().collect { event ->
                    if (_state.value.openRoomId == roomId) {
                        _state.update { applyRoomEvent(it, event) }
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
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
            // Re-sync the transcript after a gap; live segments may have completed unseen.
            runCatching { client(origin).roomHistory(origin, roomId) }.onSuccess { (fresh, rows) ->
                currentTurn = null
                _state.update { st ->
                    if (st.openRoomId != roomId) st
                    else st.copy(messages = rows, openRoom = fresh ?: st.openRoom, streaming = fresh?.busy ?: false, roomSpeaking = fresh?.speaking)
                }
            }
        }
    }

    internal fun applyRoomEvent(state: CompanionState, event: ChatEvent): CompanionState = when (event) {
        is ChatEvent.TurnStarted -> {
            currentTurn = "a-${event.turnId}"
            state.copy(streaming = true, roomSpeaking = event.speaker)
        }
        is ChatEvent.AssistantDelta, is ChatEvent.ToolStarted, is ChatEvent.ToolCompleted -> {
            val turn = currentTurn ?: "a-orphan-${state.messages.size}".also { currentTurn = it }
            val speaker = when (event) {
                is ChatEvent.AssistantDelta -> event.speaker
                is ChatEvent.ToolStarted -> event.speaker
                is ChatEvent.ToolCompleted -> event.speaker
                else -> null
            }
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
            val messages = when {
                event.passed -> done.messages.filterNot { it.id.startsWith(turn) } + ChatMessage(
                    id = "rm-${event.seq}", role = MessageRole.ASSISTANT, text = "", speaker = event.speaker, passed = true,
                )
                !hasSegment && event.error.isNotBlank() -> done.messages + ChatMessage(
                    id = "rm-${event.seq}", role = MessageRole.ASSISTANT, text = "", speaker = event.speaker,
                    toolDetail = event.error,
                )
                else -> done.messages.map { m ->
                    if (m.id.startsWith(turn)) m.copy(
                        id = if (m.id == turn) "rm-${event.seq}" else m.id,
                        toolDetail = if (event.error.isNotBlank() && m.role == MessageRole.ASSISTANT) event.error else m.toolDetail,
                    ) else m
                }
            }
            currentTurn = null
            // Still streaming until room.idle says the plan is over (another agent may follow).
            done.copy(messages = messages, streaming = true, roomSpeaking = null)
        }
        is ChatEvent.RoomPost -> {
            val id = "rm-${event.seq}"
            when {
                state.messages.any { it.id == id } -> state
                // Our own optimistic row (still carrying its local id) is this post: adopt the seq.
                state.messages.any { it.id.startsWith("u-room-") && it.text == event.text } -> state.copy(
                    messages = state.messages.map { m ->
                        if (m.id.startsWith("u-room-") && m.text == event.text) m.copy(id = id) else m
                    },
                    streaming = true,
                )
                else -> state.copy(messages = state.messages + ChatMessage(id = id, role = MessageRole.USER, text = event.text), streaming = true)
            }
        }
        ChatEvent.Completed -> {
            currentTurn = null
            finishStream(state, "a-").copy(streaming = false, roomSpeaking = null)
        }
        else -> state
    }

    private fun roomError(t: Throwable): String = when ((t as? DashboardException)?.code) {
        "http_404" -> "host plugin has no rooms · update hermes-companion on the host"
        "http_401", "room_unauthorized" -> "rooms need a paired phone · Device → PAIR"
        "http_503" -> "rooms unavailable · host relay is in standalone mode"
        "http_409" -> "room busy · interrupt first"
        else -> t.toMonoError()
    }
}
