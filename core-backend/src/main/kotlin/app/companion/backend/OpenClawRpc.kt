package app.companion.backend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BackendRejected(val code: String, message: String) : IOException(message)

/** Operator identity and requests are bound to one physical gateway socket. No mutation retries. */
internal class OpenClawRpc(private val connection: AgentConnection, private val credentials: AgentCredentials) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WireSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val changes = MutableSharedFlow<JsonObject>(extraBufferCapacity = 128)
    val events: SharedFlow<JsonObject> = changes.asSharedFlow()
    val failure=MutableStateFlow<Throwable?>(null)
    var methods: Set<String> = emptySet(); private set
    var deviceToken: String = ""; private set

    suspend fun connect(): JsonObject {
        close()
        failure.value=null
        val wire = WireSocket(Request.Builder().url(connection.endpoint).build())
        socket = wire
        try {
            val challenge = withTimeout(15_000) {
                var next = wire.receive()
                while (next.text("event") != "connect.challenge") next = wire.receive()
                next.obj("payload")
            }
            val nonce = challenge.text("nonce").also { require(it.isNotBlank()) { "Gateway sent no device challenge." } }
            val timestamp = challenge.number("ts").takeIf { it > 0 } ?: System.currentTimeMillis()
            val identity = DeviceIdentity(credentials.seed)
            val scopes = listOf("operator.read", "operator.write", "operator.approvals")
            // OpenClaw's native Android node signs the same v3 payload. Client IDs are protocol enums.
            val signed = listOf("v3", identity.id, "openclaw-android", "ui", "operator", scopes.joinToString(","),
                timestamp.toString(), if(credentials.deviceToken.isBlank()) credentials.token else "", nonce, "android", "phone").joinToString("|")
            val id = UUID.randomUUID().toString()
            wire.send(buildJsonObject {
                put("type", "req"); put("id", id); put("method", "connect")
                putJsonObject("params") {
                    put("minProtocol", 3); put("maxProtocol", 4)
                    putJsonObject("client") { put("id", "openclaw-android"); put("displayName", "Companion"); put("version", "0.4.0-prototype"); put("platform", "android"); put("deviceFamily", "phone"); put("mode", "ui") }
                    put("role", "operator"); put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
                    putJsonObject("auth") { if(credentials.deviceToken.isNotBlank()) put("deviceToken", credentials.deviceToken) else if (credentials.token.isNotEmpty()) put("token", credentials.token); if (credentials.password.isNotEmpty()) put("password", credentials.password) }
                    putJsonObject("device") { put("id", identity.id); put("publicKey", identity.publicKey.b64()); put("signature", identity.sign(signed)); put("signedAt", timestamp); put("nonce", nonce) }
                    put("locale", "en"); put("userAgent", "Companion Android prototype")
                }
            })
            val response = withTimeout(15_000) { var frame = wire.receive(); while (frame.text("id") != id) frame = wire.receive(); frame }
            val hello = result(response)
            methods = hello.obj("features").strings("methods").toSet()
            deviceToken = hello.obj("auth").text("deviceToken")
            scope.launch {
                try {
                    while (isActive && socket === wire) {
                        val frame = wire.receive()
                        if (frame.text("type") == "res") pending.remove(frame.text("id"))?.complete(frame)
                        else if (frame.text("type") == "event" && !changes.tryEmit(frame)) throw IOException("Gateway events fell behind. Reconnect to reload history.")
                    }
                } catch (e: Exception) { if(socket === wire) { failure.value=e; pending.values.forEach { it.completeExceptionally(e) }; pending.clear() } }
            }
            return hello
        } catch (e: Exception) { wire.close(); if (socket === wire) socket = null; throw e }
    }
    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        if (methods.isNotEmpty() && method !in methods) throw BackendRejected("unsupported", "This OpenClaw gateway does not offer $method.")
        val wire = socket ?: error("Connect to OpenClaw first.")
        val id = UUID.randomUUID().toString(); val response = CompletableDeferred<JsonObject>(); pending[id] = response
        try {
            wire.send(buildJsonObject { put("type", "req"); put("id", id); put("method", method); put("params", params) })
            return result(withTimeout(30_000) { response.await() })
        } finally { pending.remove(id) }
    }
    private fun result(frame: JsonObject): JsonObject {
        if (!frame.flag("ok")) {
            val error = frame.obj("error")
            throw BackendRejected(error.text("code"), error.text("message").ifBlank { "OpenClaw refused this request. Check device approval and scopes." })
        }
        return frame.obj("payload")
    }
    override fun close() { socket?.close(); socket = null; pending.values.forEach { it.cancel() }; pending.clear(); methods = emptySet() }
}
