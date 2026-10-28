package app.companion.backend

import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackendCoordinatorTest {
    @Test fun unknownDeliveryIsDurableAndNeverReplayedOnReconnect() = runTest {
        val store=MemoryStore();var sends=0
        val fake=object: FakeBackend() { override fun send(thread:AgentThread,text:String,operation:String)=flow<AgentEvent> { sends++;throw IOException("Connection lost") } }
        val c=BackendCoordinator(backgroundScope,store,BackendFactory {_,_,_->fake})
        c.openConnection(fake.connection);runCurrent();c.open(fake.bots().single());runCurrent()
        c.draft("preserve this outgoing message");c.send();runCurrent()
        assertTrue(c.state.value.uncertain);assertEquals(1,sends)
        assertEquals(MessageDelivery.UNCERTAIN,store.history(fake.connection.owner,"native-session").single().delivery)
        c.send();runCurrent();assertEquals(1,sends)
        c.disconnect();c.openConnection(fake.connection);runCurrent();c.open(fake.bots().single());runCurrent()
        assertTrue(c.state.value.uncertain);assertEquals(1,sends)
    }
    @Test fun retiredConnectionCannotPublishLateRosterAndDraftsAreHostScoped() = runTest {
        val store=MemoryStore();val late=CompletableDeferred<List<AgentBot>>()
        val a=object:FakeBackend("a") { override suspend fun bots()=withContext(NonCancellable) {late.await()} }
        val b=FakeBackend("b")
        val c=BackendCoordinator(backgroundScope,store,BackendFactory {conn,_,_->if(conn.id=="a") a else b})
        c.openConnection(a.connection);runCurrent();c.openConnection(b.connection);runCurrent();late.complete(listOf(AgentBot("wrong","Wrong host")));runCurrent()
        assertEquals("b",c.state.value.connection?.id);assertEquals("Bot b",c.state.value.bots.single().name)
        c.open(b.bots().single());runCurrent();c.draft("only B")
        assertEquals("only B",store.draft(b.connection.owner,"native-session"));assertEquals("",store.draft(a.connection.owner,"native-session"))
    }
    @Test fun explicitRejectionAllowsReviewWithoutDeclaringAnUncertainSend() = runTest {
        val store=MemoryStore()
        val fake=object:FakeBackend() { override fun send(thread:AgentThread,text:String,operation:String)=flow<AgentEvent> {throw BackendRejected("denied","Not allowed")} }
        val c=BackendCoordinator(backgroundScope,store,BackendFactory {_,_,_->fake})
        c.openConnection(fake.connection);runCurrent();c.open(fake.bots().single());runCurrent();c.draft("hello");c.send();runCurrent()
        assertFalse(c.state.value.uncertain);assertNull(store.pending(fake.connection.owner,"native-session"))
        assertEquals(MessageDelivery.FAILED,c.state.value.messages.single().delivery)
    }
    @Test fun historyLoadingCannotRaceAndOverwriteAnOutgoingTurn() = runTest {
        val history=CompletableDeferred<List<ChatMessage>>();var sends=0
        val fake=object:FakeBackend() {
            override suspend fun history(thread:AgentThread)=history.await()
            override fun send(thread:AgentThread,text:String,operation:String)=flow<AgentEvent> {sends++}
        }
        val c=BackendCoordinator(backgroundScope,MemoryStore(),BackendFactory {_,_,_->fake})
        c.openConnection(fake.connection);runCurrent();c.open(fake.bots().single());runCurrent()
        assertTrue(c.state.value.loadingHistory);c.draft("keep this draft");c.send();runCurrent();assertEquals(0,sends)
        history.complete(emptyList());runCurrent();assertFalse(c.state.value.loadingHistory);assertEquals("keep this draft",c.state.value.draft)
    }
    @Test fun sampleApprovalUsesTheSameExplicitDecisionFlowWithoutARealCommand() = runTest {
        val sample=SampleBackend();val thread=sample.open(sample.bots().first());var requested=false
        sample.send(thread,"Review an approval","sample-operation").collect { event ->
            if(event is AgentEvent.Approval) {requested=true;sample.approve(thread,event.prompt.requestId,"deny")}
        }
        assertTrue(requested);sample.close()
    }
    @Test fun blankGroupNameIsDerivedAndMixedRuntimeSampleStaysLocal() = runTest {
        val sample=SampleBackend();val selected=sample.bots().filterNot {it.group}.take(3)
        val group=sample.createGroup("",selected)
        assertEquals("Coder, Research, Reviewer",group.name);assertEquals(3,group.members.size)
        assertTrue(sample.connection.endpoint.endsWith("sample.invalid"))
    }
}

open class FakeBackend(id:String="test"):AgentBackend {
    override val connection=AgentConnection(id,AgentRuntime.OPENCLAW,id,"http://127.0.0.1")
    override suspend fun connect()=AgentHandshake(connection.name,AgentCapabilities())
    override suspend fun bots()=listOf(AgentBot(connection.id,"Bot ${connection.id}"))
    override suspend fun threads(bot:AgentBot)=listOf(AgentThread("native-session",bot.id,bot.name))
    override suspend fun open(bot:AgentBot)=threads(bot).single()
    override suspend fun history(thread:AgentThread)=emptyList<ChatMessage>()
    override fun send(thread:AgentThread,text:String,operation:String)=flow<AgentEvent> {emit(AgentEvent.Complete)}
    override suspend fun interrupt(thread:AgentThread) {}
    override suspend fun approve(thread:AgentThread,request:String,decision:String) {}
    override fun close() {}
}

class MemoryStore:BackendStore {
    private val hosts=linkedMapOf<String,AgentConnection>();private val values=mutableMapOf<String,String>();private val cache=mutableMapOf<String,List<ChatMessage>>()
    private var selected:String?=null
    override fun active()=selected
    override fun active(owner:String?) {selected=owner}
    override fun connections()=hosts.values.toList()
    override fun save(connection:AgentConnection) {hosts[connection.owner]=connection}
    override fun forget(connection:AgentConnection) {hosts.remove(connection.owner)}
    override fun credentials(connection:AgentConnection)=AgentCredentials(ByteArray(32) {1})
    override fun credentials(connection:AgentConnection,credentials:AgentCredentials) {}
    override fun draft(owner:String,thread:String)=values["$owner|$thread|draft"].orEmpty()
    override fun draft(owner:String,thread:String,text:String) {values["$owner|$thread|draft"]=text}
    override fun history(owner:String,thread:String)=cache["$owner|$thread"].orEmpty()
    override fun history(owner:String,thread:String,messages:List<ChatMessage>) {cache["$owner|$thread"]=messages}
    override fun pending(owner:String,thread:String)=values["$owner|$thread|pending"]
    override fun pending(owner:String,thread:String,operation:String?) {if(operation==null) values.remove("$owner|$thread|pending") else values["$owner|$thread|pending"]=operation}
}
