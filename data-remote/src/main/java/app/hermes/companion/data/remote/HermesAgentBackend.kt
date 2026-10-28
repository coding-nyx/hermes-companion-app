package app.hermes.companion.data.remote

import app.companion.backend.*
import app.companion.backend.AgentEvent
import app.hermes.companion.model.*
import app.hermes.companion.domain.AuthPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Hermes keeps its native Bot Chat identity and host-owned group scheduling behind the common port. */
class HermesAgentBackend(override val connection: AgentConnection,private val clients: HostClientPool,
    private val credentials: AgentCredentials,private val companionAuth: String?=null) : AgentBackend {
    private val api get()=clients.forOrigin(connection.endpoint)
    private val native=DashboardNativeBotsApi(clients)
    private var owner=BotOwner(connection.endpoint,"default")
    private var botRows=emptyList<NativeBot>()
    private var groups=emptyList<NativeGroup>()
    private var nativeAvailable=false
    private val approvals=ConcurrentHashMap<String,NativeGroupAction>()
    private fun groupBot(group: NativeGroup)=AgentBot(group.id,group.name,"Hermes group",members=group.members.map { "member:${it.id}" },memberLabels=group.members.associate { "member:${it.id}" to it.name.ifBlank { it.profile } })
    private fun groupSpeaker(event: NativeGroupEvent): String = event.actorId.takeIf(String::isNotBlank)?.let { "member:$it" } ?: event.actorProfile.ifBlank { event.actor }
    override suspend fun connect(): AgentHandshake {
        api.companionAuth=companionAuth
        val status=api.probe(connection.endpoint)
        if(status.authRequired && credentials.token.isNotBlank()) { api.gated=true; api.sessionToken=credentials.token }
        val existing=if(status.authRequired && credentials.token.isNotBlank()) runCatching { api.listProfiles(connection.endpoint) }.getOrNull() else null
        if(status.authRequired && existing==null) {
            require(credentials.username.isNotBlank() && credentials.password.isNotBlank()) { "Enter this Hermes host's username and password." }
            api.passwordLogin(connection.endpoint,credentials.username,credentials.password,AuthPolicy.passwordProvider(status.authProviders) ?: error(AuthPolicy.OIDC_MESSAGE))
        } else if(!status.authRequired) { api.useLoopback(); api.adoptLoopbackToken(connection.endpoint) }
        val profiles=existing ?: api.listProfiles(connection.endpoint)
        owner=owner.copy(profile=profiles.firstOrNull { it.id=="default" }?.id ?: profiles.firstOrNull()?.id ?: error("This host has no agents."))
        api.wsHello(connection.endpoint,owner.profile)
        nativeAvailable=runCatching { native.roster(owner).also { botRows=it.bots } }.isSuccess
        val caps=if(nativeAvailable) runCatching { native.capabilities(owner) }.getOrNull() else null
        return AgentHandshake(connection.name,AgentCapabilities(createBot=nativeAvailable,nativeGroups=caps?.canCreate==true,approvals=true,interrupt=true),status.version)
    }
    override suspend fun bots(): List<AgentBot> {
        val bots=if(nativeAvailable) native.roster(owner).bots.also { botRows=it }.map { AgentBot(it.owner.profile,it.title,it.description,it.preview,model=it.model) }
            else api.listProfiles(connection.endpoint).map { AgentBot(it.id,it.displayName,model=it.model) }
        groups=if(nativeAvailable) runCatching { native.groups(owner) }.getOrDefault(emptyList()) else emptyList()
        return bots+groups.map(::groupBot)
    }
    override suspend fun threads(bot: AgentBot): List<AgentThread> = if(bot.group) listOf(AgentThread(bot.id,bot.id,bot.name,true))
        else api.listSessions(connection.endpoint,bot.id).map { AgentThread(it.id,bot.id,if(it.title=="Bot Chat") bot.name else it.title) }
    override suspend fun open(bot: AgentBot): AgentThread {
        if(bot.group) return AgentThread(bot.id,bot.id,bot.name,true)
        val row=botRows.firstOrNull { it.owner.profile==bot.id }
        if(row!=null) return native.canonical(row,UUID.randomUUID().toString()).let { AgentThread(it.id,bot.id,bot.name) }
        val existing=threads(bot).firstOrNull()
        return existing ?: api.createSession(connection.endpoint,bot.id).let { AgentThread(it.id,bot.id,bot.name) }
    }
    override suspend fun history(thread: AgentThread): List<ChatMessage> {
        if(!thread.group) return api.listMessages(connection.endpoint,thread.id,thread.botId)
        val messages=mutableListOf<ChatMessage>(); var cursor=0L; var pages=0
        do {
            val page=native.log(owner,thread.id,cursor)
            messages+=page.events.filter { it.kind in setOf("message.user","message.member") }.map { event ->
                ChatMessage(event.id,if(event.kind=="message.user") MessageRole.USER else MessageRole.ASSISTANT,event.text,
                    speaker=if(event.kind=="message.member") groupSpeaker(event) else null)
            }
            require(!page.hasMore || page.cursor>cursor) { "Group history cursor did not advance." }
            cursor=page.cursor;pages++
        } while(page.hasMore && pages<10)
        return messages.takeLast(200)
    }
    override fun send(thread: AgentThread,text: String,operation: String): Flow<AgentEvent> = flow {
        if(thread.group) {
            val user=native.send(NativeBotOperation(operation,owner,"send",thread.id,text,"topic-$operation"))
            emit(AgentEvent.Message(ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENT)))
            withTimeout(180_000) {
                var cursor=user.sequence
                var snapshot: NativeGroupSnapshot
                val surfaced=mutableSetOf<String>()
                do {
                    delay(700)
                    do {
                        val page=native.log(owner,thread.id,cursor)
                        for(event in page.events) if(event.kind=="message.member") emit(AgentEvent.Message(ChatMessage(event.id,MessageRole.ASSISTANT,event.text,speaker=groupSpeaker(event))))
                        require(!page.hasMore || page.cursor>cursor) { "Group log cursor did not advance." }
                        cursor=page.cursor
                    } while(page.hasMore)
                    snapshot=native.group(owner,thread.id)
                    for(action in snapshot.actions.filter { it.kind=="approval" && it.request.isNotBlank() }) {
                        val key="group:${thread.id}:${action.key}"
                        approvals[key]=action
                        if(surfaced.add(key)) emit(AgentEvent.Approval(ApprovalPrompt(key,command=action.command,
                            choices=listOf("once","deny"),speaker="member:${action.member}")))
                    }
                    if(snapshot.blocked && snapshot.actions.none { it.kind=="approval" }) error("This group needs attention in the full Hermes controls.")
                } while(snapshot.working || snapshot.actions.any { it.kind=="approval" })
            }
            emit(AgentEvent.Complete); return@flow
        }
        val reply=StringBuilder(); val answer="answer-$operation"
        api.streamTurn(connection.endpoint,thread.id,thread.botId,text).collect { event -> when(event) {
            is ChatEvent.Accepted -> emit(AgentEvent.Message(ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENT)))
            is ChatEvent.AssistantDelta -> { reply.append(event.text); emit(AgentEvent.Message(ChatMessage(answer,MessageRole.ASSISTANT,reply.toString(),streaming=true,speaker=thread.botId))) }
            is ChatEvent.Final -> { if(event.error.isNotBlank()) error(event.error); if(event.text.isNotBlank()) { reply.clear(); reply.append(event.text) }; emit(AgentEvent.Message(ChatMessage(answer,MessageRole.ASSISTANT,reply.toString(),speaker=thread.botId))) }
            is ChatEvent.ToolStarted -> emit(AgentEvent.Message(ChatMessage("tool-$operation-${event.name}",MessageRole.TOOL,"",toolName=event.name,toolDetail=event.detail,toolRunning=true)))
            is ChatEvent.ToolCompleted -> emit(AgentEvent.Message(ChatMessage("tool-$operation-${event.name}",MessageRole.TOOL,event.detail,toolName=event.name)))
            is ChatEvent.Approval -> emit(AgentEvent.Approval(event.prompt))
            ChatEvent.Completed -> emit(AgentEvent.Complete)
            else -> Unit
        } }
    }
    override suspend fun interrupt(thread: AgentThread) { if(thread.group) native.stop(owner,thread.id,UUID.randomUUID().toString()) else api.interruptTurn(connection.endpoint,thread.id,thread.botId) }
    override suspend fun approve(thread: AgentThread,request: String,decision: String) {
        if(thread.group) {
            val action=requireNotNull(approvals[request]) { "This approval is no longer available. Refresh the group." }
            require(request=="group:${thread.id}:${action.key}" && native.group(owner,thread.id).actions.contains(action)) { "Group approval changed. Refresh before responding." }
            native.approve(owner,thread.id,action,decision); approvals.remove(request); return
        }
        api.respondApproval(connection.endpoint,thread.id,thread.botId,request,decision)
    }
    override suspend fun createBot(name: String,description: String,agent: String): AgentBot {
        require(agent.matches(Regex("[a-zA-Z0-9_-]+"))) { "Use letters, numbers, dashes and underscores for the profile ID." }
        native.create(owner,BotEdit(agent,name,description)); return bots().first { it.id==agent }
    }
    override suspend fun createGroup(name: String,members: List<AgentBot>): AgentBot {
        val group=native.createGroup(NativeBotOperation(UUID.randomUUID().toString(),owner,"create-group","group-${UUID.randomUUID()}",name.ifBlank { members.joinToString { it.name } },
            members=members.map { NativeGroupMember(UUID.randomUUID().toString(),it.id,it.id,it.name) }))
        groups=groups.filterNot { it.id==group.id }+group
        return groupBot(group)
    }
    override fun close() { clients.existing(connection.endpoint)?.closeBotSockets() }
}
