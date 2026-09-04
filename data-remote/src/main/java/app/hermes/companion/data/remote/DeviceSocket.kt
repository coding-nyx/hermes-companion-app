package app.hermes.companion.data.remote

import app.hermes.companion.model.DeviceCommand
import app.hermes.companion.model.DeviceResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class DeviceSocket(private val http: OkHttpClient) {
    private val opened = AtomicBoolean(false)
    private val helloDone = CompletableDeferred<String>()
    private val gone = CompletableDeferred<Unit>()
    private val json = Json { ignoreUnknownKeys = true }
    private val _commands = MutableSharedFlow<DeviceCommand>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<DeviceCommand> = _commands.asSharedFlow()

    @Volatile
    var deviceId: String = ""
        private set

    @Volatile
    private var ws: WebSocket? = null

    val isOpen: Boolean get() = opened.get() && ws != null

    suspend fun awaitDisconnect() {
        gone.await()
    }

    suspend fun connect(url: String, protocolHeader: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", protocolHeader)
            .build()
        http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handle(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(DashboardException("device_ws_failed", t.message ?: "device websocket failed"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail(DashboardException("device_ws_$code", reason.ifBlank { "device websocket closed" }))
                }
            },
        )
        return try {
            withTimeout(8_000) { helloDone.await() }
        } catch (t: Throwable) {
            close()
            throw if (t is DashboardException) t else DashboardException("device_hello", t.message ?: "no device hello")
        }
    }

    fun send(result: DeviceResult): Boolean {
        val socket = ws ?: return false
        val error = if (result.ok) "" else
            ""","error":{"code":${q(result.errorCode)},"message":${q(result.errorMessage.ifBlank { result.errorCode })}}"""
        val body = if (result.ok) result.resultJson.ifBlank { "{}" } else "{}"
        val payload =
            """{"type":"mobile.controller.result","command_id":${q(result.commandId)},"ok":${result.ok},"result":$body$error}"""
        return socket.send(payload)
    }

    fun close() {
        opened.set(false)
        val socket = ws
        ws = null
        fail(DashboardException("device_ws_closed", "device websocket closed"))
        socket?.cancel()
    }

    private fun handle(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = obj.str("type")
        when (type) {
            "mobile.controller.hello" -> {
                deviceId = obj.str("device_id")
                opened.set(true)
                if (!helloDone.isCompleted) helloDone.complete(deviceId)
            }
            "mobile.controller.command" -> {
                if (!opened.get()) {
                    deviceId = obj.str("device_id")
                    opened.set(true)
                    if (!helloDone.isCompleted) helloDone.complete(deviceId)
                }
                val id = obj.str("command_id")
                val action = obj.str("action")
                if (id.isBlank() || action.isBlank()) return
                val args = obj["arguments"] as? JsonObject
                _commands.tryEmit(
                    DeviceCommand(
                        commandId = id,
                        action = action,
                        argumentsJson = args?.toString() ?: "{}",
                        toolCallId = obj.str("tool_call_id"),
                        profile = obj.str("profile"),
                        deviceId = obj.str("device_id").ifBlank { deviceId },
                    ),
                )
            }
            "mobile.controller.cancel", "mobile.controller.detach" -> Unit
        }
    }

    private fun fail(error: DashboardException) {
        opened.set(false)
        if (!helloDone.isCompleted) helloDone.completeExceptionally(error)
        if (!gone.isCompleted) gone.complete(Unit)
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun q(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}
