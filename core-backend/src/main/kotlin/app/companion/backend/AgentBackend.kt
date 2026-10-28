package app.companion.backend

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.ApprovalPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

@Serializable
enum class AgentRuntime(val label: String, val setup: String) {
    HERMES("Hermes", "Dashboard or Companion relay"),
    OPENCLAW("OpenClaw", "Gateway address and device approval"),
    CODYNC("Codync", "Pairing link from your computer"),
}

@Serializable
data class AgentConnection(val id: String, val runtime: AgentRuntime, val name: String, val endpoint: String,
    val hostId: String = "", val hostKey: String = "", val boxKey: String = "", val relay: String = "", val routes: List<String> = emptyList()) {
    val owner: String get() = "${runtime.name}:$id"
    companion object {
        fun create(runtime: AgentRuntime, endpoint: String, name: String = runtime.label): AgentConnection {
            val uri = URI(endpoint.trim())
            require(uri.scheme in setOf("http", "https", "ws", "wss") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Enter a valid host address without credentials." }
            require(uri.rawQuery == null) { "Put credentials in the sign-in field, not the address." }
            require(app.hermes.companion.domain.OriginPolicy.cleartextAllowed(endpoint)) { "Use HTTPS for a public host. HTTP is limited to LAN or Tailscale addresses." }
            val canonical = URI(if (uri.scheme == "ws") "http" else if (uri.scheme == "wss") "https" else uri.scheme,
                null, uri.host.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
            val digest = MessageDigest.getInstance("SHA-256").digest("${runtime.name}:$canonical".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            return AgentConnection(digest, runtime, name.trim().ifBlank { runtime.label }, canonical)
        }
    }
}

/** Kept separately in the encrypted vault. Never rendered, logged or serialized with public metadata. */
class AgentCredentials(val seed: ByteArray, val token: String = "", val password: String = "", val deviceToken: String = "", val username: String = "") {
    override fun toString() = "AgentCredentials(redacted)"
}

@Serializable
data class AgentCapabilities(val chat: Boolean = true, val createBot: Boolean = false, val nativeGroups: Boolean = false,
    val approvals: Boolean = false, val interrupt: Boolean = false, val voice: Boolean = false,
    val device: Boolean = false, val attachments: Boolean = false, val models: Boolean = false)

@Serializable
data class AgentBot(val id: String, val name: String, val description: String = "", val preview: String = "",
    val members: List<String> = emptyList(), val agent: String = "", val model: String = "", val memberLabels: Map<String,String> = emptyMap()) {
    val group: Boolean get() = members.isNotEmpty()
}

@Serializable
data class AgentThread(val id: String, val botId: String, val title: String, val group: Boolean = false)

data class AgentHandshake(val name: String, val capabilities: AgentCapabilities, val version: String = "")
sealed interface AgentEvent {
    data class Message(val message: ChatMessage) : AgentEvent
    data class Approval(val prompt: ApprovalPrompt) : AgentEvent
    data object Complete : AgentEvent
}

interface AgentBackend : AutoCloseable {
    val connection: AgentConnection
    suspend fun connect(): AgentHandshake
    suspend fun bots(): List<AgentBot>
    suspend fun threads(bot: AgentBot): List<AgentThread>
    suspend fun open(bot: AgentBot): AgentThread
    suspend fun history(thread: AgentThread): List<ChatMessage>
    fun send(thread: AgentThread, text: String, operation: String): Flow<AgentEvent>
    suspend fun interrupt(thread: AgentThread)
    suspend fun approve(thread: AgentThread, request: String, decision: String)
    suspend fun createBot(name: String, description: String, agent: String): AgentBot = error("Bot creation is unavailable on this host.")
    suspend fun createGroup(name: String, members: List<AgentBot>): AgentBot = error("Native group chats are unavailable on this host.")
}
