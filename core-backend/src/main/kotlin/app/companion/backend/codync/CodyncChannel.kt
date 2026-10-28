package app.companion.backend.codync

import app.companion.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Codync's signed ephemeral channel, including direct and relay routing. No bearer token export. */
internal class CodyncChannel(private val connection: AgentConnection, private val identity: DeviceIdentity,
    private val pairingCode: String = "", private val directUrls: List<String> = listOf(connection.endpoint)) : AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var socket: WireSocket?=null
    private var sealer: FrameSealer?=null
    private var opener: FrameOpener?=null
    private val writer=Mutex()
    private val ids=AtomicLong()
    private val calls=ConcurrentHashMap<Long,CompletableDeferred<JsonElement>>()
    private val streams=ConcurrentHashMap<Long,Channel<JsonObject>>()
    private val ready=CompletableDeferred<Unit>()

    suspend fun connect() {
        val relay = directUrls.isEmpty() || connection.endpoint == connection.relay
        val url = if(relay) connection.relay.toHttpUrl().newBuilder().addPathSegments("v1/relay/device/${connection.hostId}")
            .addQueryParameter("v","1").apply { if(pairingCode.isNotBlank()) addQueryParameter("pair",RelayCrypto.offerId(pairingCode.decodeBase64Url(16))) }.build()
        else directUrls.first().toHttpUrl().newBuilder().encodedPath("/channel").query("v=1").build()
        val request=Request.Builder().url(url)
        if(relay) {
            val ts=System.currentTimeMillis(); val nonce=RelayCrypto.randomBytes(16).base64Url()
            val canonical=listOf("codync-sig-v1","GET",url.toUri().rawAuthority.lowercase(),url.encodedPath+"?"+url.encodedQuery,ts.toString(),nonce,RelayCrypto.sha256(byteArrayOf()).base64Url()).joinToString("\n")
            request.header("Codync-Sig","v=1,kid=${identity.publicKey.b64()},ts=$ts,nonce=$nonce,sig=${identity.sign(canonical)}")
        }
        val wire=WireSocket(request.build()); socket=wire
        var ephemeral=RelayCrypto.randomBytes(32); var nonce=RelayCrypto.randomBytes(32)
        val cid=connection.hostId.decodeBase64Url(16); val hostKey=connection.hostKey.decodeBase64Url(32)
        var begun=false
        suspend fun begin() {
            check(!begun && !ready.isCompleted) { "Codync restarted the device handshake. Reconnect." }; begun=true
            ephemeral=RelayCrypto.randomBytes(32); nonce=RelayCrypto.randomBytes(32)
            val ek=RelayCrypto.agreementPublic(ephemeral)
            wire.send(buildJsonObject { put("t","hello"); put("v",1); put("dk",identity.publicKey.b64()); put("ek",ek.base64Url()); put("n",nonce.base64Url()); put("sig",identity.sign(RelayCrypto.hs1Input(cid,identity.publicKey,ek,nonce)).b64()); if(pairingCode.isNotBlank()) put("pair",true) })
        }
        scope.launch {
            try {
                if(!relay) begin()
                while(isActive) {
                    val frame=wire.receive()
                    when(frame.text("t")) {
                        "presence" -> if(frame.flag("online")) begin() else throw IOException("This Codync computer is offline.")
                        "welcome" -> {
                            check(begun && !ready.isCompleted) { "Unexpected Codync welcome." }
                            val hostEphemeral=frame.text("ek").decodeBase64Url(32)
                            val transcript=RelayCrypto.transcriptHash(cid,identity.publicKey,RelayCrypto.agreementPublic(ephemeral),nonce,hostEphemeral)
                            if(!RelayCrypto.verify(hostKey,transcript,frame.text("sig").decodeBase64Url(64))) throw SecurityException("Host identity changed. Pair again.")
                            val keys=RelayCrypto.channelKeys(RelayCrypto.sharedSecret(ephemeral,hostEphemeral),transcript)
                            sealer=FrameSealer(keys.d2h); opener=FrameOpener(keys.h2d); ephemeral.fill(0); ready.complete(Unit)
                        }
                        "reject" -> throw BackendRejected(frame.text("code"),frame.text("message").ifBlank { "This computer refused device access. Pair again." })
                        "f" -> {
                            val decoded=requireNotNull(opener) { "Encrypted data arrived before host verification." }.open(Frame(frame.number("c"),frame.text("d").decodeBase64Url()))
                            if(decoded!=null) receive(Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject)
                        }
                        "pong" -> Unit
                        else -> Unit
                    }
                }
            } catch(e: Exception) {
                ready.completeExceptionally(e); calls.values.forEach { it.completeExceptionally(e) }; streams.values.forEach { it.close(e) }
                wire.close()
            }
        }
        if(relay) scope.launch { while(isActive) { delay(30_000); wire.send(buildJsonObject { put("t","ping") }) } }
        try { withTimeout(15_000) { ready.await() } } catch(e: Exception) { close(); throw e }
    }
    private suspend fun receive(message: JsonObject) {
        val id=message.number("id")
        when {
            "ev" in message -> streams[id]?.send(message.obj("ev"))
            "err" in message -> {
                val err=message.obj("err"); val failure=BackendRejected(err.number("status").toString(),err.text("message"))
                calls.remove(id)?.completeExceptionally(failure); streams.remove(id)?.close(failure)
            }
            "ok" in message -> calls.remove(id)?.complete(message.getValue("ok"))
            message.flag("end") -> streams.remove(id)?.close()
        }
    }
    suspend fun call(method: String, body: JsonElement=JsonNull): JsonElement {
        require(pairingCode.isBlank() || method=="pair")
        val id=ids.incrementAndGet(); val answer=CompletableDeferred<JsonElement>(); calls[id]=answer
        try {
            send(buildJsonObject { put("id",id); put("m",method); put("b",body) })
            return withTimeout(30_000) { answer.await() }
        } finally { calls.remove(id) }
    }
    fun events(since: Long=0): Flow<JsonObject> = flow {
        val id=ids.incrementAndGet(); val inbox=Channel<JsonObject>(256); streams[id]=inbox
        try {
            send(buildJsonObject { put("id",id); put("sub","events"); putJsonObject("b") { put("since",since); put("client","android") } })
            for(event in inbox) emit(event)
        } finally { streams.remove(id); inbox.cancel(); runCatching { send(buildJsonObject { put("id",id); put("cancel",true) }) } }
    }
    private suspend fun send(message: JsonObject) = writer.withLock {
        try { for(frame in requireNotNull(sealer) { "Computer is not connected." }.seal(message.toString().toByteArray()))
            requireNotNull(socket).send(buildJsonObject { put("t","f"); put("c",frame.counter); put("d",frame.data.base64Url()) })
        } catch(e: Exception) { close(); throw e }
    }
    override fun close() {
        socket?.close(); socket=null; scope.cancel(); calls.values.forEach { it.cancel() }; streams.values.forEach { it.cancel() }
        calls.clear(); streams.clear(); sealer=null; opener=null
    }
}
