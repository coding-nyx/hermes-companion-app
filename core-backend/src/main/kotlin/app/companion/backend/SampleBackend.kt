package app.companion.backend

import app.hermes.companion.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flow
import java.util.UUID

/** Interactive sample data is a separate backend. It never connects to a real host or runs tools. */
class SampleBackend : AgentBackend {
    override val connection = AgentConnection("sample-v1",AgentRuntime.HERMES,"Sample workspace","https://sample.invalid")
    private val roster=mutableListOf(
        AgentBot("hermes:coder","Coder","Hermes · Lab","Implemented the API change.",agent="Hermes"),
        AgentBot("openclaw:research","Research","OpenClaw · Home","Three sources are ready.",agent="OpenClaw"),
        AgentBot("codync:review","Reviewer","Codync · Work","Two review notes to discuss.",agent="Codync"),
        AgentBot("hermes:design","Designer","Hermes · Lab","Updated the compact layout.",agent="Hermes"),
        AgentBot("openclaw:ops","Ops","OpenClaw · Home","Deployment checks passed.",agent="OpenClaw"),
        AgentBot("sample-team","Launch team","Sample mixed-backend group","Ready for the next step.",members=listOf("hermes:coder","openclaw:research","codync:review")),
    )
    private val approvals=ConcurrentHashMap<String,CompletableDeferred<String>>()
    private val histories=mutableMapOf<String,List<ChatMessage>>()
    override suspend fun connect() = AgentHandshake("Sample workspace",AgentCapabilities(createBot=true,nativeGroups=true,approvals=true,interrupt=true),"prototype")
    override suspend fun bots() = roster.toList()
    override suspend fun threads(bot: AgentBot) = listOf(AgentThread(bot.id,bot.id,bot.name,bot.group))
    override suspend fun open(bot: AgentBot) = threads(bot).single()
    override suspend fun history(thread: AgentThread): List<ChatMessage> = histories.getOrPut(thread.id) {
        if(thread.group) listOf(ChatMessage("sample-user",MessageRole.USER,"Review the launch plan."),
            ChatMessage("sample-research",MessageRole.ASSISTANT,"## Findings\n- Keep setup short\n- Show each bot’s host\n\nThe main flows are ready for review.",speaker="openclaw:research"),
            ChatMessage("sample-review",MessageRole.ASSISTANT,"I’ll check reconnects and approval routing next.",speaker="codync:review"))
        else listOf(ChatMessage("sample-user",MessageRole.USER,"Check the Companion prototype."),
            ChatMessage("sample-reply",MessageRole.ASSISTANT,"## Ready to review\n- Compact bot roster\n- Correct speaker names\n- Explicit connection setup\n\nThis is sample content. Messages stay on this device.",speaker=thread.botId))
    }
    override fun send(thread: AgentThread,text: String,operation: String) = flow {
        emit(AgentEvent.Message(ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENT)))
        if(text.contains("approval",ignoreCase=true)) {
            val id="sample-$operation"; val answer=CompletableDeferred<String>(); approvals[id]=answer
            try {
                emit(AgentEvent.Approval(ApprovalPrompt(id,command="Sample review · no command will run")))
                val choice=withTimeout(120_000) { answer.await() }
                emit(AgentEvent.Message(ChatMessage("sample-decision-$operation",MessageRole.ASSISTANT,
                    if(choice=="deny") "Sample request declined. Nothing was run." else "Sample approval received. Nothing was run.",speaker=thread.botId)))
            } finally { approvals.remove(id) }
        }
        val members=roster.first { it.id==thread.botId }.members.ifEmpty { listOf(thread.botId) }
        val replies=members.map { id -> ChatMessage(UUID.randomUUID().toString(),MessageRole.ASSISTANT,
            "Sample reply from ${roster.first { it.id==id }.name}.\n\nYour message is preserved in this prototype conversation.",speaker=id) }
        for(reply in replies) { delay(220); emit(AgentEvent.Message(reply)) }
        histories[thread.id]=history(thread)+ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENT)+replies
        emit(AgentEvent.Complete)
    }
    override suspend fun interrupt(thread: AgentThread) {}
    override suspend fun approve(thread: AgentThread,request: String,decision: String) { require(decision in setOf("once","deny")); requireNotNull(approvals[request]).complete(decision) }
    override suspend fun createBot(name: String,description: String,agent: String): AgentBot = AgentBot("sample-${UUID.randomUUID()}",name,description,agent=agent).also { roster+=it }
    override suspend fun createGroup(name: String,members: List<AgentBot>): AgentBot {
        require(members.size in 2..6 && members.map { it.id }.distinct().size==members.size && members.none { it.group }) { "Choose 2–6 bots." }
        return AgentBot(UUID.randomUUID().toString(),name.ifBlank { members.joinToString { it.name } },"Sample group · messages stay on this device",members=members.map { it.id }).also { roster+=it }
    }
    override fun close() { approvals.values.forEach {it.cancel()};approvals.clear() }
}
