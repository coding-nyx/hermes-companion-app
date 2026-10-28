package app.companion.backend.codync

import app.companion.backend.*
import app.companion.backend.AgentEvent
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class CodyncBackend(override val connection: AgentConnection, credentials: AgentCredentials,
    private val pairing: CodyncPairing? = null) : AgentBackend {
    private val identity=DeviceIdentity(credentials.seed)
    private var channel: CodyncChannel?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var mirror: Job?=null
    private val roster=ConcurrentHashMap<String,AgentBot>()
    private val updates=MutableSharedFlow<JsonObject>(extraBufferCapacity=128)
    @Volatile private var revision=0L
    private val disconnected=CompletableDeferred<Throwable>()
    private suspend fun dial(pair: String=""): CodyncChannel {
        val urls=pairing?.urls ?: connection.routes.ifEmpty { listOf(connection.endpoint) }.filter { it!=connection.relay }
        var failure: Exception?=null
        for(candidate in urls + listOfNotNull(connection.relay.takeIf(String::isNotBlank))) {
            val client=CodyncChannel(connection.copy(endpoint=candidate),identity,pair,if(candidate==connection.relay) emptyList() else listOf(candidate))
            try { client.connect(); return client }
            catch(e: BackendRejected) { client.close(); throw e }
            catch(e: TimeoutCancellationException) { client.close(); currentCoroutineContext().ensureActive(); failure=IOException("Codync address timed out.") }
            catch(e: IOException) { client.close(); failure=e }
            catch(e: Exception) { client.close(); throw e }
        }
        throw failure ?: IOException("No reachable Codync host address.")
    }
    override suspend fun connect(): AgentHandshake {
        if(pairing!=null) {
            val pair=dial(pairing.code)
            try {
                val result=pair.call("pair",buildJsonObject { put("code",pairing.code); put("name","Companion Android"); put("platform","android") }).jsonObject
                require(result.text("computerId")==connection.hostId) { "Pairing response belongs to another computer." }
            } finally { pair.close() }
        }
        val client=dial(); channel=client
        try {
            val hello=client.call("hello").jsonObject
            require(hello.text("computerId")==connection.hostId && hello.text("signKey")==connection.hostKey) { "Computer identity changed. Pair again." }
            val caughtUp=CompletableDeferred<Unit>(); roster.clear(); revision=0
            mirror=scope.launch {
                var target: Long?=null
                try { client.events().collect { event ->
                    when(event.text("type")) {
                        "hello" -> { require(target==null && event.text("hostId")==connection.hostId); target=event.number("rev").also { require(it>=0) }; if(target==0L) caughtUp.complete(Unit) }
                        "bot","entry" -> {
                            require(target!=null) { "Codync sent changes before its snapshot header." }
                            val rev=event.number("rev"); require(rev>=revision) { "Codync revisions moved backwards." }; revision=rev
                            if(event.text("type")=="bot") {
                                val bot=event.obj("bot"); if(bot.flag("deleted")) roster.remove(bot.text("id")) else parseBot(bot).let { roster[it.id]=it }
                            }
                            if(rev>=requireNotNull(target)) caughtUp.complete(Unit)
                            if(!updates.tryEmit(event)) throw IOException("Codync updates fell behind. Reconnect to refresh history.")
                        }
                        "resync" -> throw IOException("Codync requested a fresh sync. Reconnect to reload history.")
                    }
                } ; throw IOException("Codync event stream ended. Reconnect.") } catch(e: CancellationException) { throw e } catch(e: Exception) { caughtUp.completeExceptionally(e); disconnected.complete(e) }
            }
            withTimeout(30_000) { caughtUp.await() }
            return AgentHandshake(hello.text("name").ifBlank { connection.name },AgentCapabilities(createBot=true,nativeGroups=true,approvals=true,interrupt=true),hello.text("version"))
        } catch(e: Exception) { client.close(); throw e }
    }
    override suspend fun bots() = roster.values.sortedWith(compareBy<AgentBot> { it.group }.thenBy { it.name })
    override suspend fun threads(bot: AgentBot) = listOf(AgentThread(bot.id,bot.id,bot.name,bot.group))
    override suspend fun open(bot: AgentBot) = threads(bot).single()
    override suspend fun history(thread: AgentThread): List<ChatMessage> = requireNotNull(channel).call("history",buildJsonObject { put("botId",thread.botId); put("limit",100) }).jsonObject
        .rows("entries").sortedBy { it.number("seq") }.mapNotNull { entry ->
            require(entry.text("botId")==thread.botId) { "Codync history belongs to another bot." }; message(entry)
        }
    override fun send(thread: AgentThread,text: String,operation: String): Flow<AgentEvent> = flow {
        coroutineScope {
            val inbox=Channel<JsonObject>(128)
            val watch=launch(start=CoroutineStart.UNDISPATCHED) { updates.collect { inbox.send(it) } }
            val failure=launch { inbox.close(disconnected.await()) }
            val startedAt=revision
            try {
                val ack=requireNotNull(channel).call("send",buildJsonObject { put("botId",thread.botId); put("text",text); put("clientNonce",operation) }).jsonObject.obj("entry")
                emit(AgentEvent.Message(ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENT)))
                val turn=ack.number("turn")
                withTimeout(180_000) {
                    var finished=false
                    while(!finished) {
                        val event=inbox.receive()
                        if(event.number("rev")<=maxOf(startedAt,ack.number("rev"))) continue
                        if(event.text("type")=="bot") {
                            val bot=event.obj("bot")
                            if(bot.text("id")==thread.botId && bot.text("status") in setOf("idle","failed")) finished=true
                            continue
                        }
                        val entry=event.obj("entry")
                        if(entry.text("botId")!=thread.botId || (turn>0 && entry.number("turn")<turn)) continue
                        if(entry.text("id")!=ack.text("id")) message(entry)?.let { emit(AgentEvent.Message(it)) }
                        if(entry.text("kind")=="permission" && entry.obj("data").text("status") !in setOf("resolved","cancelled"))
                            permission(entry)?.let { emit(AgentEvent.Approval(it)) }
                    }
                }
                emit(AgentEvent.Complete)
            } finally { watch.cancel(); failure.cancel(); inbox.cancel() }
        }
    }
    override suspend fun interrupt(thread: AgentThread) { requireNotNull(channel).call("stop",buildJsonObject { put("botId",thread.botId) }) }
    override suspend fun approve(thread: AgentThread,request: String,decision: String) {
        requireNotNull(channel).call("respondPermission",buildJsonObject { put("botId",thread.botId); put("entryId",request); put("optionId",decision) })
    }
    override suspend fun createBot(name: String,description: String,agent: String): AgentBot {
        require(name.isNotBlank() && agent.isNotBlank()) { "Enter a bot name and the agent command configured on your computer." }
        return parseBot(requireNotNull(channel).call("createBot",buildJsonObject { put("name",name.trim()); put("description",description); put("command",agent.trim()) }).jsonObject.obj("bot"))
    }
    override suspend fun createGroup(name: String,members: List<AgentBot>): AgentBot {
        require(members.map { it.id }.distinct().size in 2..6 && members.none { it.group }) { "Choose 2–6 bots from this computer." }
        return parseBot(requireNotNull(channel).call("createBot",buildJsonObject { put("kind","group"); put("name",name.trim().ifBlank { members.joinToString { it.name } }); put("members",JsonArray(members.map { JsonPrimitive(it.id) })) }).jsonObject.obj("bot"))
    }
    override fun close() { mirror?.cancel(); channel?.close(); channel=null; scope.cancel() }
    companion object {
        internal fun parseBot(row: JsonObject): AgentBot = AgentBot(row.text("id").also { require(it.isNotBlank()) },row.text("name"),row.text("description"),row.text("lastMessage"),row.strings("members"),row.text("command"),row.text("model"))
        internal fun permission(entry: JsonObject): ApprovalPrompt? {
            val data=entry.obj("data")
            val options=data.rows("options").filter { it.text("optionId").isNotBlank() }
            if(options.isEmpty()) return null
            return ApprovalPrompt(entry.text("id"),command=data.text("command").ifBlank { data.text("title") },
                choices=options.map { it.text("optionId") },choiceLabels=options.associate { it.text("optionId") to it.text("name").ifBlank { it.text("kind") } },speaker=data.text("author").takeIf(String::isNotBlank))
        }
        internal fun message(row: JsonObject): ChatMessage? {
            val data=row.obj("data"); val kind=row.text("kind")
            if(kind=="notice" && (listOf("memory","memoryFile","compaction").any { it in data } || data.obj("handoff").text("status")=="kept")) return null
            if(kind=="tool" && data["bookkeeping"] != JsonPrimitive(false)) return null
            if(kind=="agent" && data.text("text").trim().trim('"','.','*').lowercase() in setOf("pass","(pass)")) return null
            val role=when(kind) { "user"->MessageRole.USER; "agent","thought","notice"->MessageRole.ASSISTANT; "tool"->MessageRole.TOOL; else->return null }
            val text=data.text("text").ifBlank { data.text("output") }
            if(text.isBlank() && role!=MessageRole.TOOL) return null
            return ChatMessage(row.text("id"),role,text,toolName=data.text("name").takeIf(String::isNotBlank),toolDetail=data.text("title"),
                streaming=kind=="agent" && !data.flag("final"),speaker=data.text("author").ifBlank { row.text("botId") }.takeIf { role==MessageRole.ASSISTANT },reasoning=kind=="thought")
        }
    }
}
