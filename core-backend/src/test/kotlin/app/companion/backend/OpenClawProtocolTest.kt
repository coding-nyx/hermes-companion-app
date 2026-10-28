package app.companion.backend

import app.hermes.companion.model.MessageRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

class OpenClawProtocolTest {
    @Test fun signedChallengeAndScopedStreamingMatchTheNativeGateway(): Unit = runBlocking {
        val server=MockWebServer(); val errors=LinkedBlockingQueue<Throwable>()
        val methods=listOf("agents.list","sessions.list","chat.history","chat.send","chat.abort","exec.approval.resolve")
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) { ws.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"native-challenge","ts":1800000000000}}""") }
            override fun onMessage(ws: WebSocket,text: String) {
                try {
                    val req=Json.parseToJsonElement(text).jsonObject; val p=req.obj("params")
                    val payload=when(req.text("method")) {
                        "connect" -> {
                            assertEquals(4,p.number("maxProtocol").toInt()); assertEquals("openclaw-android",p.obj("client").text("id"))
                            assertEquals("secret-token",p.obj("auth").text("token"))
                            val d=p.obj("device"); val key=d.text("publicKey").unb64(32)
                            assertEquals(sha256(key).joinToString("") { "%02x".format(it) },d.text("id"))
                            val canonical=listOf("v3",d.text("id"),"openclaw-android","ui","operator",p.strings("scopes").joinToString(","),d.number("signedAt").toString(),"secret-token","native-challenge","android","phone").joinToString("|").toByteArray()
                            val verifier=Ed25519Signer(); verifier.init(false,Ed25519PublicKeyParameters(key,0)); verifier.update(canonical,0,canonical.size)
                            assertTrue(verifier.verifySignature(d.text("signature").unb64(64)))
                            buildJsonObject { putJsonObject("features") { put("methods",JsonArray(methods.map(::JsonPrimitive))) }; putJsonObject("auth") { put("deviceToken","issued-device-token") } }
                        }
                        "agents.list" -> Json.parseToJsonElement("""{"agents":[{"id":"research","name":"Research"}]}""").jsonObject
                        "sessions.list" -> Json.parseToJsonElement("""{"sessions":[{"key":"agent:research:main"},{"key":"agent:other:main"}]}""").jsonObject
                        "chat.history" -> Json.parseToJsonElement("""{"messages":[{"id":"h1","role":"assistant","content":[{"type":"text","text":"Previous reply"}]}]}""").jsonObject
                        "chat.send" -> buildJsonObject { put("runId","run-1") }
                        else -> buildJsonObject { put("ok",true) }
                    }
                    ws.send(buildJsonObject { put("type","res"); put("id",req.text("id")); put("ok",true); put("payload",payload) }.toString())
                    if(req.text("method")=="chat.send") {
                        assertEquals("op-1",p.text("idempotencyKey"))
                        for((session,run,body,state) in listOf(listOf("agent:other:main","run-1","WRONG HOST","final"),listOf("agent:research:main","other-run","WRONG RUN","final"),listOf("agent:research:main","run-1","Hello","delta"),listOf("agent:research:main","run-1","Hello, Companion","final"))) {
                            ws.send(buildJsonObject { put("type","event");put("event","chat");putJsonObject("payload") {put("sessionKey",session);put("runId",run);put("state",state);putJsonObject("message") {put("content",body)} } }.toString())
                        }
                    }
                } catch(e: Throwable) { errors.add(e); ws.cancel() }
            }
        }))
        server.start(); var saved=""
        val backend=OpenClawBackend(AgentConnection.create(AgentRuntime.OPENCLAW,server.url("/").toString()),AgentCredentials(ByteArray(32) {1},"secret-token")) { saved=it }
        try {
            assertTrue(backend.connect().capabilities.chat); assertEquals("issued-device-token",saved)
            val bot=backend.bots().single(); assertEquals("Research",bot.name)
            val thread=backend.open(bot); assertEquals("agent:research:main",thread.id)
            assertEquals("research",backend.history(thread).single().speaker)
            val result=withTimeout(5000) { backend.send(thread,"Hi","op-1").toList() }
            assertEquals(listOf("Hi","Hello","Hello, Companion"),result.filterIsInstance<AgentEvent.Message>().map {it.message.text})
            assertEquals(MessageRole.USER,(result.first() as AgentEvent.Message).message.role)
            assertEquals(AgentEvent.Complete,result.last())
            errors.poll()?.let {throw it}
        } finally { backend.close(); server.shutdown() }
    }

    @Test fun publicCleartextAndCredentialUrlsAreRejectedAndRuntimeNamespacesAreDistinct() {
        for(url in listOf("http://example.com", "https://user:password@example.com", "https://example.com?token=bad")) {
            assertThrows(IllegalArgumentException::class.java) { AgentConnection.create(AgentRuntime.OPENCLAW,url) }
        }
        val a=AgentConnection.create(AgentRuntime.HERMES,"https://EXAMPLE.com/proxy/")
        val b=AgentConnection.create(AgentRuntime.OPENCLAW,"wss://example.com/proxy")
        assertEquals("https://example.com/proxy",a.endpoint); assertNotEquals(a.owner,b.owner)
        assertEquals("AgentCredentials(redacted)",AgentCredentials(ByteArray(32),"never-log-me").toString())
    }
}
