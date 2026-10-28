package app.companion.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class WireSocket(request: Request, client: OkHttpClient = http) : AutoCloseable {
    private val opened = CompletableDeferred<Unit>()
    private val incoming = Channel<JsonObject>(128)
    private val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                require(text.toByteArray().size <= 1024 * 1024) { "Host message exceeds the channel limit." }
                if (!incoming.trySend(Json.parseToJsonElement(text).jsonObject).isSuccess) throw IOException("Event queue filled. Reconnect to refresh history.")
            } catch (e: Exception) { incoming.close(e); webSocket.cancel() }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val failure = IOException(if (response == null) "Could not reach this host." else "Host refused the connection (${response.code}).", t)
            opened.completeExceptionally(failure); incoming.close(failure)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val failure = IOException(when (code) { 4001,4003 -> "Device access was refused or revoked. Pair again."; 4410 -> "Pairing code expired. Show a fresh code."; else -> "Host connection closed ($code)." })
            opened.completeExceptionally(failure); incoming.close(failure); webSocket.close(code, null)
        }
    })
    suspend fun send(value: JsonObject) { withTimeout(15_000) { opened.await() }; check(socket.send(value.toString())) { "Host connection closed." } }
    suspend fun receive(): JsonObject = incoming.receive()
    override fun close() { socket.cancel(); incoming.cancel(); opened.cancel() }
    companion object {
        val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: buildJsonObject {}
internal fun JsonObject.rows(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
