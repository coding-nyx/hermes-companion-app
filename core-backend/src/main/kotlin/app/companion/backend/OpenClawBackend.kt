package app.companion.backend

import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OpenClawBackend(override val connection: AgentConnection, credentials: AgentCredentials,
    private val saveDeviceToken: (String) -> Unit = {}) : AgentBackend {
    private val rpc = OpenClawRpc(connection, credentials)
    private val runs = ConcurrentHashMap<String,String>()
    private var roster = emptyList<AgentBot>()
    override suspend fun connect(): AgentHandshake {
        val hello = rpc.connect()
        if (rpc.deviceToken.isNotBlank()) saveDeviceToken(rpc.deviceToken)
        val methods = rpc.methods
        val grants=hello.obj("auth").strings("scopes").toSet()
        val admin="operator.admin" in grants
        return AgentHandshake(connection.name, AgentCapabilities(chat = "chat.send" in methods,
            createBot = admin && "agents.create" in methods, approvals = "exec.approval.resolve" in methods,
            interrupt = "chat.abort" in methods), hello.obj("server").text("version"))
    }
    override suspend fun bots(): List<AgentBot> = rpc.call("agents.list").rows("agents").map { row ->
        AgentBot(row.text("id"), row.text("name").ifBlank { row.obj("identity").text("name") }.ifBlank { row.text("id") },
            row.text("description"), agent = row.text("id"))
    }.also { roster = it }
    override suspend fun threads(bot: AgentBot): List<AgentThread> = rpc.call("sessions.list", buildJsonObject { put("agentId",bot.id); put("limit",100) })
        .rows("sessions").filter { it.text("key").startsWith("agent:${bot.id}:") || it.text("agentId") == bot.id }
        .map { AgentThread(it.text("key"),bot.id,it.text("displayName").ifBlank { it.text("label") }.ifBlank { bot.name }) }
    override suspend fun open(bot: AgentBot): AgentThread = threads(bot).firstOrNull()
        ?: AgentThread("agent:${bot.id}:main",bot.id,bot.name)
    override suspend fun history(thread: AgentThread): List<ChatMessage> = rpc.call("chat.history", buildJsonObject { put("sessionKey",thread.id); put("limit",100) })
        .rows("messages").mapIndexedNotNull { index, row ->
            val role = when(row.text("role")) { "user" -> MessageRole.USER; "assistant" -> MessageRole.ASSISTANT; "toolResult","tool" -> MessageRole.TOOL; else -> return@mapIndexedNotNull null }
            val text = messageText(row)
            if (text.isBlank()) return@mapIndexedNotNull null
            ChatMessage(row.text("id").ifBlank { "${thread.id}:$index" },role,text,
                toolName = row.text("toolName").takeIf(String::isNotBlank),speaker = if(role==MessageRole.ASSISTANT) thread.botId else null)
        }
    override fun send(thread: AgentThread, text: String, operation: String): Flow<AgentEvent> = flow {
        coroutineScope {
            val inbox = Channel<JsonObject>(128)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { rpc.events.collect { inbox.send(it) } }
            val failure=launch { inbox.close(rpc.failure.filterNotNull().first()) }
            try {
                val ack = rpc.call("chat.send", buildJsonObject { put("sessionKey",thread.id); put("message",text); put("idempotencyKey",operation) })
                val run = ack.text("runId").ifBlank { operation }; runs[thread.id] = run
                emit(AgentEvent.Message(ChatMessage(operation, MessageRole.USER,text,delivery=MessageDelivery.SENT)))
                withTimeout(180_000) {
                    var finished = false
                    while(!finished) {
                        val event = inbox.receive(); val payload=event.obj("payload")
                        if(payload.text("sessionKey").ifBlank { payload.obj("request").text("sessionKey") } != thread.id) continue
                        val eventRun=payload.text("runId")
                        if(eventRun.isNotBlank() && eventRun!=run) continue
                        when(event.text("event")) {
                            "chat" -> {
                                val state=payload.text("state")
                                if(state=="error") throw BackendRejected("turn_failed",payload.text("errorMessage").ifBlank { "Agent turn failed." })
                                val body=messageText(payload.obj("message"))
                                if(body.isNotBlank()) emit(AgentEvent.Message(ChatMessage(run,MessageRole.ASSISTANT,body,streaming=state=="delta",speaker=thread.botId)))
                                if(state in setOf("final","aborted")) finished=true
                            }
                            "exec.approval.requested" -> emit(AgentEvent.Approval(ApprovalPrompt(payload.text("id"),command=payload.obj("request").text("command"))))
                        }
                    }
                }
                emit(AgentEvent.Complete)
            } finally { runs.remove(thread.id); collector.cancel(); failure.cancel(); inbox.cancel() }
        }
    }
    override suspend fun interrupt(thread: AgentThread) { rpc.call("chat.abort",buildJsonObject { put("sessionKey",thread.id); runs[thread.id]?.let { put("runId",it) } }) }
    override suspend fun approve(thread: AgentThread, request: String, decision: String) {
        rpc.call("exec.approval.resolve", buildJsonObject { put("id",request); put("decision",if(decision=="deny") "deny" else "allow-once") })
    }
    override suspend fun createBot(name: String, description: String, agent: String): AgentBot {
        require(agent.isNotBlank()) { "Enter the workspace path on your OpenClaw host." }
        val result = rpc.call("agents.create",buildJsonObject { put("name",name.trim()); put("workspace",agent.trim()) })
        return AgentBot(result.text("agentId").ifBlank { result.text("id") },name.trim(),description)
    }
    override fun close() { rpc.close() }
    companion object {
        internal fun messageText(row: JsonObject): String = when(val content=row["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.text("type")=="text" }?.text("text") }.joinToString("\n")
            else -> row.text("text")
        }
    }
}
