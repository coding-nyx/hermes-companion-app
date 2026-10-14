package app.hermes.companion

import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.RoomRef
import kotlinx.coroutines.flow.Flow

interface RoomConversationApi {
    suspend fun list(origin: String): List<RoomRef>
    suspend fun history(origin: String, room: String, after: Int = 0): Pair<RoomRef?, List<ChatMessage>>
    suspend fun post(origin: String, room: String, text: String): Int
    suspend fun approve(origin: String, room: String, request: String, decision: String)
    fun events(origin: String, room: String): Flow<ChatEvent>
}
internal class DashboardRoomConversationApi(private val clients: HostClientPool) : RoomConversationApi {
    override suspend fun list(origin: String) = clients.forOrigin(origin).listRooms(origin)
    override suspend fun history(origin: String, room: String, after: Int) = clients.forOrigin(origin).roomHistory(origin, room, after)
    override suspend fun post(origin: String, room: String, text: String) = clients.forOrigin(origin).postRoom(origin, room, text)
    override suspend fun approve(origin: String, room: String, request: String, decision: String) =
        clients.forOrigin(origin).respondRoomApproval(origin, room, request, decision)
    override fun events(origin: String, room: String) = clients.forOrigin(origin).roomEvents(origin, room)
}
