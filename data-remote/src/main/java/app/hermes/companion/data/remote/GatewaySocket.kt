package app.hermes.companion.data.remote

import app.hermes.companion.model.GatewayHello
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class GatewaySocket(private val http: OkHttpClient) {
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val opened = AtomicBoolean(false)
    private val helloDone = CompletableDeferred<GatewayHello>()
    private val gone = CompletableDeferred<Unit>()
    private val _events = MutableSharedFlow<RpcEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RpcEvent> = _events.asSharedFlow()

    @Volatile
    var hello: GatewayHello? = null
        private set

    @Volatile
    private var ws: WebSocket? = null

    val isOpen: Boolean get() = opened.get() && ws != null

    suspend fun awaitDisconnect() {
        gone.await()
    }

    suspend fun connect(url: String): GatewayHello {
        val request = Request.Builder().url(url).build()
        val socket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    text.split('\n').forEach { line -> handleLine(line) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failAll(DashboardException("ws_failed", t.message ?: "websocket failed"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.set(false)
                    failAll(
                        DashboardException("ws_$code", reason.ifBlank { "websocket closed" }),
                    )
                }
            },
        )
        ws = socket
        return try {
            withTimeout(8_000) { helloDone.await() }
        } catch (t: Throwable) {
            close()
            throw if (t is DashboardException) t else DashboardException("ws_hello", t.message ?: "no gateway.ready")
        }
    }

    suspend fun request(method: String, paramsJson: String, timeoutMs: Long = 15_000): JsonObject {
        val socket = ws ?: throw DashboardException("ws_closed", "gateway not connected")
        val id = "w${nextId.incrementAndGet()}"
        val done = CompletableDeferred<JsonObject>()
        pending[id] = done
        val payload = RpcCodec.request(id, method, paramsJson)
        if (!socket.send(payload)) {
            pending.remove(id)
            throw DashboardException("ws_send", "failed to send $method")
        }
        return try {
            withTimeout(timeoutMs) { done.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        opened.set(false)
        val socket = ws
        ws = null
        failAll(DashboardException("ws_closed", "websocket closed"))
        socket?.cancel()
    }

    private fun handleLine(raw: String) {
        when (val inbound = RpcCodec.parseLine(raw)) {
            is RpcInbound.Result -> pending.remove(inbound.id)?.complete(inbound.result)
            is RpcInbound.Error -> pending.remove(inbound.id)?.completeExceptionally(
                DashboardException("rpc_${inbound.code}", inbound.message),
            )
            is RpcInbound.Event -> {
                if (inbound.event.type == "gateway.ready") {
                    val ready = RpcCodec.parseHello(raw) ?: return
                    hello = ready
                    opened.set(true)
                    if (!helloDone.isCompleted) helloDone.complete(ready)
                }
                _events.tryEmit(inbound.event)
            }
            null -> Unit
        }
    }

    private fun failAll(error: Throwable) {
        opened.set(false)
        if (!helloDone.isCompleted) helloDone.completeExceptionally(error)
        if (!gone.isCompleted) gone.complete(Unit)
        pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(error) }
        pending.clear()
    }

    companion object {
        internal fun parseReady(raw: String): GatewayHello? = RpcCodec.parseHello(raw)
    }
}
