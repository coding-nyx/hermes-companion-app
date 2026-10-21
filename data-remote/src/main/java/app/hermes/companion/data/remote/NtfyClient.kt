package app.hermes.companion.data.remote

import app.hermes.companion.domain.OriginPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ntfy SSE subscriber. A closed stream is not the end of a subscription.
 * Reconnect using ntfy's documented `since=<message id>` cache cursor, when available.
 * The cursor and bounded duplicate window belong to one topic collection, never another host.
 * Payload routing remains WakePolicy's responsibility; this transport never opens a chat.
 */
class NtfyClient(
    http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val retryDelayMs: Long = 1_000,
    private val maxRetryDelayMs: Long = 30_000,
) {
    init {
        require(retryDelayMs > 0 && maxRetryDelayMs >= retryDelayMs)
    }

    private val http: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun events(sseUrl: String): Flow<String> = flow {
        val base = sseUrl.toHttpUrl()
        var cursor: String? = null
        var backoff = retryDelayMs
        val seen = linkedSetOf<String>()
        while (currentCoroutineContext().isActive) {
            val url = cursor?.let { base.newBuilder().setQueryParameter("since", it).build() } ?: base
            var failure: IOException? = null
            connection(url.toString()).catch { error ->
                if (error is IOException) failure = error else throw error
            }.collect { raw ->
                val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                val event = (envelope?.get("event") as? JsonPrimitive)?.contentOrNull
                if (event != null && event != "message") return@collect
                val id = if (event == "message")
                    (envelope?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 256 }
                else null
                if (id != null && id in seen) return@collect
                emit(raw)
                // Advance only after the consumer accepted it, not when the reader queued it.
                if (id != null) {
                    cursor = id
                    seen += id
                    while (seen.size > 256) seen.remove(seen.first())
                }
                backoff = retryDelayMs
            }
            // Authentication and invalid-topic responses require configuration, not a
            // retry loop or a fallback to a different endpoint. Downstream collector errors
            // are deliberately outside catch, so cancellation and failures remain transparent.
            (failure as? NtfyHttpException)?.let { if (!it.retryable) throw it }
            failure?.let { if (it is SSLException || it.message == OriginPolicy.CLEARTEXT_DENIED) throw it }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxRetryDelayMs)
        }
    }

    private fun connection(sseUrl: String): Flow<String> = callbackFlow {
        val call = http.newCall(
            Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build(),
        )
        val worker = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw NtfyHttpException(response.code)
                    val source = response.body?.source() ?: return@use
                    val data = StringBuilder()
                    fun deliver() {
                        if (data.isEmpty()) return
                        val raw = data.toString()
                        data.setLength(0)
                        if (raw.isNotBlank() && raw != "{}" && !trySend(raw).isSuccess) {
                            throw IOException("Notification event buffer is full")
                        }
                    }
                    while (!call.isCanceled()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> deliver()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                        }
                    }
                    // An unfinished event at EOF was not delivered by the server. The next
                    // connection's cache cursor can recover it without inventing a partial ping.
                }
            } catch (failure: Exception) {
                close(failure)
            } finally {
                close()
            }
        }.apply { name = "ntfy-sse"; isDaemon = true; start() }
        awaitClose {
            call.cancel()
            worker.interrupt()
        }
    }.flowOn(Dispatchers.IO)
}

internal class NtfyHttpException(val status: Int) : IOException("Notification topic returned HTTP $status") {
    val retryable: Boolean get() = status == 408 || status == 429 || status >= 500
}
