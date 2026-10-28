package app.companion.backend

import app.hermes.companion.model.ChatMessage

interface BackendStore {
    fun active(): String? = null
    fun active(owner: String?) {}
    fun connections(): List<AgentConnection>
    fun save(connection: AgentConnection)
    fun forget(connection: AgentConnection)
    fun credentials(connection: AgentConnection): AgentCredentials
    fun credentials(connection: AgentConnection, credentials: AgentCredentials)
    fun draft(owner: String, thread: String): String
    fun draft(owner: String, thread: String, text: String)
    fun history(owner: String, thread: String): List<ChatMessage>
    fun history(owner: String, thread: String, messages: List<ChatMessage>)
    fun pending(owner: String, thread: String): String?
    fun pending(owner: String, thread: String, operation: String?)
}
