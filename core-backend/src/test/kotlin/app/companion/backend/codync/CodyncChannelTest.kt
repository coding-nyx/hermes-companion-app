package app.companion.backend.codync

import app.companion.backend.*
import app.hermes.companion.model.MessageRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

class CodyncChannelTest {
    @Test fun pairingReconnectsWithPinnedIdentityAndRoutesNativeGroupAndApprovalCalls():Unit=runBlocking {
        val server=MockWebServer();val calls=LinkedBlockingQueue<JsonObject>();val errors=LinkedBlockingQueue<Throwable>()
        val hostSeed=ByteArray(32) {1};val hostKey=RelayCrypto.signingPublic(hostSeed);val hostId=RelayCrypto.computerId(hostKey)
        fun listener()=object:WebSocketListener() {
            var opener:FrameOpener?=null;var sealer:FrameSealer?=null;var subscription=0L
            fun write(ws:WebSocket,value:JsonObject) {for(frame in sealer!!.seal(value.toString().toByteArray())) ws.send(buildJsonObject {put("t","f");put("c",frame.counter);put("d",frame.data.base64Url())}.toString())}
            fun event(ws:WebSocket,value:String) {write(ws,buildJsonObject {put("id",subscription);put("ev",Json.parseToJsonElement(value))})}
            override fun onMessage(ws:WebSocket,text:String) {
                try {
                    val frame=Json.parseToJsonElement(text).jsonObject
                    if(frame.text("t")=="hello") {
                        val dk=frame.text("dk").decodeBase64Url(32);val ek=frame.text("ek").decodeBase64Url(32);val nonce=frame.text("n").decodeBase64Url(32)
                        assertTrue(RelayCrypto.verify(dk,RelayCrypto.hs1Input(hostId.decodeBase64Url(16),dk,ek,nonce),frame.text("sig").decodeBase64Url(64)))
                        val secret=ByteArray(32) {5};val public=RelayCrypto.agreementPublic(secret)
                        val transcript=RelayCrypto.transcriptHash(hostId.decodeBase64Url(16),dk,ek,nonce,public)
                        val keys=RelayCrypto.channelKeys(RelayCrypto.sharedSecret(secret,ek),transcript);opener=FrameOpener(keys.d2h);sealer=FrameSealer(keys.h2d)
                        ws.send(buildJsonObject {put("t","welcome");put("ek",public.base64Url());put("sig",RelayCrypto.sign(hostSeed,transcript).base64Url())}.toString());return
                    }
                    val plain=opener!!.open(Frame(frame.number("c"),frame.text("d").decodeBase64Url())) ?: return
                    val req=Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
                    if(req.flag("cancel")) return
                    calls.add(req);val id=req.number("id");val body=req.obj("b")
                    if(req.text("sub")=="events") {
                        subscription=id
                        event(ws,"""{"type":"hello","hostId":"$hostId","rev":2}""")
                        event(ws,"""{"type":"bot","rev":1,"bot":{"id":"coder","name":"Coder","rev":1}}""")
                        event(ws,"""{"type":"bot","rev":2,"bot":{"id":"reviewer","name":"Reviewer","rev":2}}""");return
                    }
                    val result=when(req.text("m")) {
                        "pair" -> buildJsonObject {put("computerId",hostId)}
                        "hello" -> buildJsonObject {put("computerId",hostId);put("signKey",hostKey.base64Url());put("name","Work")}
                        "history" -> Json.parseToJsonElement("""{"entries":[{"id":"h1","botId":"coder","kind":"agent","seq":1,"rev":2,"data":{"text":"Ready","final":true,"author":"coder"}}]}""")
                        "send" -> Json.parseToJsonElement("""{"entry":{"id":"user-3","rev":3,"turn":1}}""")
                        "createBot" -> buildJsonObject {putJsonObject("bot") {put("id","team");put("name",body.text("name"));put("members",body["members"] ?: JsonArray(emptyList()))}}
                        else -> buildJsonObject {}
                    }
                    write(ws,buildJsonObject {put("id",id);put("ok",result)})
                    if(req.text("m")=="send") {
                        event(ws,"""{"type":"bot","rev":4,"bot":{"id":"coder","name":"Coder","status":"working"}}""")
                        event(ws,"""{"type":"entry","rev":5,"entry":{"id":"p1","botId":"coder","kind":"permission","turn":1,"data":{"command":"test","options":[{"optionId":"native-option-42","name":"Allow once","kind":"allow_once"}]}}}""")
                        event(ws,"""{"type":"entry","rev":6,"entry":{"id":"a1","botId":"coder","kind":"agent","turn":1,"data":{"text":"Done","final":true}}}""")
                        event(ws,"""{"type":"bot","rev":7,"bot":{"id":"coder","name":"Coder","status":"idle"}}""")
                    }
                } catch(e:Throwable) {errors.add(e);ws.cancel()}
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener()));server.enqueue(MockResponse().withWebSocketUpgrade(listener()));server.start()
        val connection=AgentConnection(hostId,AgentRuntime.CODYNC,"Work",server.url("/").toString(),hostId,hostKey.base64Url())
        val backend=CodyncBackend(connection,AgentCredentials(ByteArray(32) {3}),CodyncPairing(connection,ByteArray(16) {8}.base64Url(),listOf(connection.endpoint)))
        try {
            val hello=withTimeout(8000) {backend.connect()};assertTrue(hello.capabilities.nativeGroups)
            val bots=backend.bots();assertEquals(2,bots.size)
            val bot=bots.first {it.id=="coder"};val thread=backend.open(bot)
            assertEquals("Ready",backend.history(thread).single().text)
            val group=backend.createGroup("",bots);assertEquals("Coder, Reviewer",group.name)
            val seen=mutableListOf<AgentEvent>()
            withTimeout(5000) {backend.send(thread,"Hi","nonce-1").collect {event->seen+=event;if(event is AgentEvent.Approval) backend.approve(thread,event.prompt.requestId,event.prompt.choices.single())}}
            assertEquals(listOf("Hi","Done"),seen.filterIsInstance<AgentEvent.Message>().map {it.message.text})
            assertEquals(MessageRole.ASSISTANT,seen.filterIsInstance<AgentEvent.Message>().last().message.role)
            backend.interrupt(thread)
            val recorded=calls.toList()
            assertEquals(1,recorded.count {it.text("m")=="pair"})
            assertEquals("native-option-42",recorded.single {it.text("m")=="respondPermission"}.obj("b").text("optionId"))
            assertEquals("coder",recorded.single {it.text("m")=="stop"}.obj("b").text("botId"))
            assertEquals("nonce-1",recorded.single {it.text("m")=="send"}.obj("b").text("clientNonce"))
            errors.poll()?.let {throw it}
        } finally {backend.close();server.shutdown()}
    }
}
