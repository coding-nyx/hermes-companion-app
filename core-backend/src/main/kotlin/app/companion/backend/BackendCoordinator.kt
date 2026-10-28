package app.companion.backend

import app.companion.backend.codync.CodyncPairing
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

fun interface BackendFactory { fun create(connection: AgentConnection, credentials: AgentCredentials, pairing: CodyncPairing?): AgentBackend }

data class BackendWorkspaceState(val visible: Boolean=false,val runtime: AgentRuntime=AgentRuntime.HERMES,
    val endpoint: String="",val name: String="",val token: String="",val password: String="",val username: String="",
    val connecting: Boolean=false,val connection: AgentConnection?=null,val connections: List<AgentConnection> = emptyList(),
    val capabilities: AgentCapabilities=AgentCapabilities(chat=false),val bots: List<AgentBot> = emptyList(),
    val thread: AgentThread?=null,val threads: List<AgentThread> = emptyList(),val messages: List<ChatMessage> = emptyList(),val draft: String="",val busy: Boolean=false,
    val approval: ApprovalPrompt?=null,val approvalBusy: Boolean=false,val error: String?=null,val sample: Boolean=false,
    val loadingHistory: Boolean=false,val uncertain: Boolean=false,val creationUncertain: Boolean=false,val section: String="Bots") {
    override fun toString() = "BackendWorkspaceState(runtime=$runtime, connecting=$connecting, visible=$visible)"
}

/** Each operation captures the connection and selection epoch. A retired socket cannot repaint another host. */
class BackendCoordinator(private val scope: CoroutineScope,private val store: BackendStore,private val factory: BackendFactory) {
    private val mutable=MutableStateFlow(BackendWorkspaceState(connections=store.connections()))
    val state=mutable.asStateFlow()
    private var backend: AgentBackend?=null
    private var connectionEpoch=0L
    private var selectionEpoch=0L
    private var work: Job?=null
    private var turn: Job?=null
    fun show() { mutable.update { it.copy(visible=true,connections=store.connections()) } }
    fun restore(): Boolean {
        val saved=store.connections().find { it.owner==store.active() } ?: return false
        openConnection(saved); return true
    }
    fun hide() { store.active(null); mutable.update { it.copy(visible=false) } }
    fun select(runtime: AgentRuntime) { retire(); mutable.value=BackendWorkspaceState(visible=true,runtime=runtime,connections=store.connections()) }
    fun endpoint(value: String) { mutable.update { it.copy(endpoint=value,error=null) } }
    fun name(value: String) { mutable.update { it.copy(name=value) } }
    fun token(value: String) { mutable.update { it.copy(token=value) } }
    fun password(value: String) { mutable.update { it.copy(password=value) } }
    fun username(value: String) { mutable.update { it.copy(username=value) } }
    fun section(value: String) {
        if(state.value.thread!=null) { selectionEpoch++;work?.cancel();turn?.cancel();mutable.update { it.copy(thread=null,busy=false,approval=null) } }
        mutable.update { it.copy(section=value) }
        if(value=="Chats") {
            val api=backend ?: return; val epoch=connectionEpoch; val bots=state.value.bots
            scope.launch { try {
                val threads=bots.flatMap { api.threads(it) }
                if(epoch==connectionEpoch) mutable.update { it.copy(threads=threads.distinctBy { t->t.id }) }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch) mutable.update { it.copy(error=e.message) } } }
        }
    }
    fun draft(value: String) {
        val st=state.value
        st.connection?.let { connection -> st.thread?.let { store.draft(connection.owner,it.id,value) } }
        mutable.update { it.copy(draft=value) }
    }
    fun sample() { connectBackend(SampleBackend(),true) }
    fun connect() {
        val st=state.value
        try {
            val pair=if(st.runtime==AgentRuntime.CODYNC) CodyncPairing.parse(st.endpoint) else null
            val connection=pair?.connection ?: AgentConnection.create(st.runtime,st.endpoint,st.name.ifBlank { st.runtime.label })
            val saved=store.credentials(connection)
            val secret=AgentCredentials(saved.seed,st.token.ifBlank { saved.token },st.password.ifBlank { saved.password },if(st.token.isNotBlank() || st.password.isNotBlank()) "" else saved.deviceToken,st.username.ifBlank { saved.username })
            store.credentials(connection,secret)
            connectBackend(factory.create(connection,secret,pair),false)
        } catch(e: Exception) { mutable.update { it.copy(error=e.message ?: "Check this connection.",connecting=false) } }
    }
    fun openConnection(connection: AgentConnection) {
        retire()
        mutable.update { it.copy(visible=true,runtime=connection.runtime,endpoint=connection.endpoint,name=connection.name,token="",password="",error=null) }
        connectBackend(factory.create(connection,store.credentials(connection),null),false)
    }
    private fun connectBackend(next: AgentBackend,sample: Boolean) {
        retire(); backend=next; val epoch=connectionEpoch
        mutable.update { it.copy(visible=true,connecting=true,error=null,connection=null,bots=emptyList(),thread=null,messages=emptyList(),draft="",sample=sample,busy=false,approval=null) }
        work=scope.launch {
            try {
                val hello=next.connect(); val bots=next.bots()
                if(epoch!=connectionEpoch) return@launch
                val connection=next.connection.copy(name=hello.name)
                if(!sample) { store.save(connection); store.active(connection.owner) }
                mutable.update { it.copy(connecting=false,connection=connection,connections=store.connections(),capabilities=hello.capabilities,
                    bots=bots,token="",password="",endpoint=if(connection.runtime==AgentRuntime.CODYNC) "" else connection.endpoint,section="Bots") }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(epoch==connectionEpoch) mutable.update { it.copy(connecting=false,error=e.message ?: "Could not connect.") } }
        }
    }
    fun open(bot: AgentBot) {
        val api=backend ?: return; val connection=state.value.connection ?: return
        selectionEpoch++; val selection=selectionEpoch; val epoch=connectionEpoch
        turn?.cancel(); work?.cancel()
        mutable.update { it.copy(busy=false,approval=null,error=null,loadingHistory=true) }
        work=scope.launch {
            try {
                val thread=api.open(bot)
                if(epoch!=connectionEpoch || selection!=selectionEpoch) return@launch
                mutable.update { it.copy(thread=thread,messages=store.history(connection.owner,thread.id),draft=store.draft(connection.owner,thread.id),uncertain=store.pending(connection.owner,thread.id)!=null) }
                val fresh=api.history(thread)
                val pending=store.pending(connection.owner,thread.id)
                val history=fresh+store.history(connection.owner,thread.id).filter { it.id==pending && fresh.none { row->row.id==it.id } }
                if(epoch!=connectionEpoch || selection!=selectionEpoch) return@launch
                store.history(connection.owner,thread.id,history)
                mutable.update { it.copy(messages=history) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(error=e.message) } }
            finally { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(loadingHistory=false) } }
        }
    }
    fun openThread(thread: AgentThread) {
        val api=backend ?: return; val connection=state.value.connection ?: return
        selectionEpoch++; val selection=selectionEpoch; val epoch=connectionEpoch
        turn?.cancel(); work?.cancel()
        mutable.update { it.copy(thread=thread,messages=store.history(connection.owner,thread.id),draft=store.draft(connection.owner,thread.id),
            busy=false,approval=null,error=null,loadingHistory=true,uncertain=store.pending(connection.owner,thread.id)!=null) }
        work=scope.launch { try {
            val fresh=api.history(thread)
                val pending=store.pending(connection.owner,thread.id)
                val history=fresh+store.history(connection.owner,thread.id).filter { it.id==pending && fresh.none { row->row.id==it.id } }
            if(epoch==connectionEpoch && selection==selectionEpoch) { store.history(connection.owner,thread.id,history); mutable.update { it.copy(messages=history) } }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(error=e.message) } }
            finally { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(loadingHistory=false) } } }
    }
    fun back() { selectionEpoch++; work?.cancel(); turn?.cancel(); mutable.update { it.copy(thread=null,busy=false,approval=null,error=null,loadingHistory=false,section="Bots") }; refresh() }
    fun refresh() {
        val api=backend ?: return; val epoch=connectionEpoch
        scope.launch { try { val bots=api.bots(); if(epoch==connectionEpoch) mutable.update { it.copy(bots=bots,error=null,creationUncertain=false) } }
            catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch) mutable.update { it.copy(error=e.message) } } }
    }
    fun send() {
        val st=state.value; val thread=st.thread ?: return; val connection=st.connection ?: return; val api=backend ?: return
        val text=st.draft.trim(); if(st.busy || st.uncertain || st.loadingHistory || text.isEmpty()) return
        if(!st.capabilities.chat) { mutable.update { it.copy(error="This host did not grant chat access.") }; return }
        val operation=UUID.randomUUID().toString(); val epoch=connectionEpoch; val selection=selectionEpoch
        store.pending(connection.owner,thread.id,operation)
        val outgoing=ChatMessage(operation,MessageRole.USER,text,delivery=MessageDelivery.SENDING)
        store.history(connection.owner,thread.id,st.messages+outgoing)
        mutable.update { it.copy(busy=true,draft="",error=null,messages=it.messages+outgoing) }; store.draft(connection.owner,thread.id,"")
        turn=scope.launch {
            var admitted=false
            try {
                api.send(thread,text,operation).collect { event ->
                    if(epoch!=connectionEpoch || selection!=selectionEpoch) return@collect
                    when(event) {
                        is AgentEvent.Message -> {
                            if(event.message.id==operation && event.message.delivery==MessageDelivery.SENT) { admitted=true; store.pending(connection.owner,thread.id,null) }
                            mutable.update { current -> current.copy(messages=if(current.messages.any { it.id==event.message.id }) current.messages.map { if(it.id==event.message.id) event.message else it } else current.messages+event.message) }
                        }
                        is AgentEvent.Approval -> mutable.update { it.copy(approval=event.prompt) }
                        AgentEvent.Complete -> mutable.update { it.copy(busy=false,messages=it.messages.map { m -> m.copy(streaming=false) }) }
                    }
                    store.history(connection.owner,thread.id,state.value.messages)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(epoch==connectionEpoch && selection==selectionEpoch) {
                    val rejected=e is BackendRejected && !admitted
                    if(rejected) store.pending(connection.owner,thread.id,null)
                    mutable.update { it.copy(error=e.message,busy=false,uncertain=!admitted && !rejected,
                        messages=it.messages.map { m -> if(m.id==operation && !admitted) m.copy(delivery=if(rejected) MessageDelivery.FAILED else MessageDelivery.UNCERTAIN) else m.copy(streaming=false) }) }
                    store.history(connection.owner,thread.id,state.value.messages)
                }
            } finally { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(busy=false) } }
        }
    }
    fun interrupt() {
        val st=state.value; val thread=st.thread ?: return; val api=backend ?: return; val epoch=connectionEpoch; val selection=selectionEpoch
        scope.launch { try { api.interrupt(thread); if(epoch==connectionEpoch && selection==selectionEpoch) { turn?.cancel(); mutable.update { it.copy(busy=false,approval=null) } } }
            catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(error=e.message) } } }
    }
    fun approve(choice: String) {
        val st=state.value; val thread=st.thread ?: return; val prompt=st.approval ?: return; val api=backend ?: return; val epoch=connectionEpoch; val selection=selectionEpoch
        if(st.approvalBusy) return
        mutable.update { it.copy(approvalBusy=true) }
        scope.launch { try { api.approve(thread,prompt.requestId,choice); if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(approval=null) } }
            catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(error=e.message) } }
            finally { if(epoch==connectionEpoch && selection==selectionEpoch) mutable.update { it.copy(approvalBusy=false) } } }
    }
    fun checkHistory() { state.value.thread?.let(::openThread) }
    fun retryMessage(id: String) {
        val row=state.value.messages.find { it.id==id } ?: return
        if(row.delivery==MessageDelivery.UNCERTAIN || state.value.uncertain) checkHistory() else draft(row.text)
    }
    fun acknowledgeUncertain() {
        val st=state.value; val connection=st.connection ?: return; val thread=st.thread ?: return
        store.pending(connection.owner,thread.id,null); mutable.update { it.copy(uncertain=false) }
    }
    fun create(name: String,description: String,agent: String,members: List<AgentBot>,done: () -> Unit) {
        val api=backend ?: return; val epoch=connectionEpoch
        if(state.value.busy || state.value.creationUncertain) return
        mutable.update { it.copy(busy=true,error=null) }
        scope.launch { try {
            val bot=if(members.isEmpty()) api.createBot(name,description,agent) else api.createGroup(name,members)
            if(epoch==connectionEpoch) { mutable.update { it.copy(bots=it.bots.filterNot { b -> b.id==bot.id }+bot,busy=false) }; done(); open(bot) }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { if(epoch==connectionEpoch) mutable.update { it.copy(error=if(e is java.io.IOException && e !is BackendRejected) "Creation outcome is unconfirmed. Close this form and refresh the roster before trying again." else e.message,
                busy=false,creationUncertain=e is java.io.IOException && e !is BackendRejected) } } }
    }
    fun disconnect() { store.active(null); retire(); mutable.value=BackendWorkspaceState(visible=true,connections=store.connections(),runtime=state.value.runtime) }
    fun forget(connection: AgentConnection) { if(state.value.connection?.owner==connection.owner) disconnect(); store.forget(connection); mutable.update { it.copy(connections=store.connections()) } }
    fun close() { retire() }
    private fun retire() { connectionEpoch++; selectionEpoch++; work?.cancel(); turn?.cancel(); backend?.close(); backend=null }
}
