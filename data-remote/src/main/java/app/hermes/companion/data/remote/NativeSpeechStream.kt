package app.hermes.companion.data.remote

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString

data class SpeechPcmFormat(val sampleRate: Int, val channels: Int = 1) {
    init { require(sampleRate in 8_000..96_000 && channels == 1) { "Unsupported mono PCM16 format" } }
    val bytesPerSecond: Int get() = sampleRate * 2
}

enum class NativeSpeechSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

sealed interface SpeechStreamEvent {
    data object Ready : SpeechStreamEvent
    data class Start(val format: SpeechPcmFormat) : SpeechStreamEvent
    data class Pcm(val bytes: ByteArray) : SpeechStreamEvent
    data object End : SpeechStreamEvent
    data object Fallback : SpeechStreamEvent
    data object Unsupported : SpeechStreamEvent
    data class Failed(val error: DashboardException) : SpeechStreamEvent
}

/** Single-consumer per-reply stream. No reconnection or automatic text replay. */
interface NativeSpeechStream {
    val events: Flow<SpeechStreamEvent>
    fun sendText(text: String): Boolean
    fun finish(): Boolean
    fun stop()
}

internal class SpeechStreamDecoder {
    private var started = false
    private var pcm = false
    private var terminal = false

    fun text(value: String): SpeechStreamEvent {
        checkFrame(!terminal && value.length <= 16_384)
        val obj = try { Json.parseToJsonElement(value).jsonObject } catch (_: Exception) { invalid() }
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "start" -> {
                checkFrame(!started)
                val rate = (obj["sample_rate"] as? JsonPrimitive)?.intOrNull ?: invalid()
                val channels = (obj["channels"] as? JsonPrimitive)?.intOrNull ?: invalid()
                val format = try { SpeechPcmFormat(rate, channels) } catch (_: IllegalArgumentException) { invalid() }
                started = true
                SpeechStreamEvent.Start(format)
            }
            "end" -> { checkFrame(started); terminal = true; SpeechStreamEvent.End }
            "fallback" -> { checkFrame(!pcm); terminal = true; SpeechStreamEvent.Fallback }
            else -> invalid()
        }
    }

    fun binary(value: ByteString): SpeechStreamEvent.Pcm {
        checkFrame(started && !terminal && value.size in 2..MAX_PCM_FRAME && value.size % 2 == 0)
        pcm = true
        return SpeechStreamEvent.Pcm(value.toByteArray())
    }

    private fun checkFrame(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw DashboardException("speech_stream_invalid", "Host returned an invalid speech stream")

    companion object { const val MAX_PCM_FRAME = 2 * 1024 * 1024 }
}

/** Reader-thread backpressure bounds incoming frames; cancellation unblocks the reader. */
internal class OkHttpNativeSpeechStream(http: OkHttpClient, request: Request) : NativeSpeechStream {
    private val inbox = Channel<SpeechStreamEvent>(1, onUndeliveredElement = { event ->
        if (event is SpeechStreamEvent.Pcm) event.bytes.fill(0)
    })
    override val events = inbox.receiveAsFlow()
    private val terminal = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val finishSent = AtomicBoolean()
    private val socket = AtomicReference<WebSocket?>()
    private val decoder = SpeechStreamDecoder()

    init {
        val created = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = accept { SpeechStreamEvent.Ready }
            override fun onMessage(webSocket: WebSocket, text: String) = accept { decoder.text(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = accept { decoder.binary(bytes) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val event = if (response?.code in setOf(404, 405, 501)) SpeechStreamEvent.Unsupported else
                    SpeechStreamEvent.Failed(DashboardException("speech_stream_${response?.code ?: "connection"}",
                        "Host speech stream connection failed"))
                end(event)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                end(SpeechStreamEvent.Failed(DashboardException("speech_stream_closed", "Host speech stream closed before completion")))
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                end(SpeechStreamEvent.Failed(DashboardException("speech_stream_closed", "Host speech stream closed before completion")))
            }
        })
        socket.set(created)
        if (stopped.get() || terminal.get()) created.cancel()
    }

    private fun accept(decode: () -> SpeechStreamEvent) {
        if (stopped.get() || terminal.get()) return
        try {
            val event = decode()
            if (event == SpeechStreamEvent.End || event == SpeechStreamEvent.Fallback) end(event)
            else if (inbox.trySendBlocking(event).isFailure) {
                if (event is SpeechStreamEvent.Pcm) event.bytes.fill(0)
                stop()
            }
        } catch (error: DashboardException) { end(SpeechStreamEvent.Failed(error)); socket.get()?.cancel() }
    }

    private fun end(event: SpeechStreamEvent) {
        if (stopped.get() || !terminal.compareAndSet(false, true)) return
        inbox.trySendBlocking(event)
        inbox.close()
    }

    override fun sendText(text: String): Boolean {
        if (text.isBlank() || finishSent.get()) return false
        return send(buildJsonObject { put("text", text) }.toString())
    }

    override fun finish(): Boolean {
        if (!finishSent.compareAndSet(false, true)) return false
        return send("{\"done\":true}")
    }

    private fun send(value: String): Boolean {
        if (stopped.get() || terminal.get()) return false
        val ws = socket.get() ?: return false
        if (ws.queueSize() + value.toByteArray(Charsets.UTF_8).size > 512 * 1024 || !ws.send(value)) {
            // send() runs on the UI owner. Closing with a cause is nonblocking even if PCM is queued.
            if (terminal.compareAndSet(false, true)) inbox.close(
                DashboardException("speech_stream_send", "Could not send host speech text"))
            return false
        }
        return true
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // Cancel the inbox first: a backpressured listener must be allowed to exit.
        inbox.cancel()
        socket.getAndSet(null)?.let { ws ->
            ws.send("{\"stop\":true}")
            ws.cancel()
        }
    }
}
