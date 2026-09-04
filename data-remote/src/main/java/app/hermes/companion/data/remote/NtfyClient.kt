package app.hermes.companion.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/** ntfy SSE subscriber. Payload parsing is WakePolicy. */
class NtfyClient(
    http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    private val http: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun events(sseUrl: String): Flow<String> = callbackFlow {
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
                    if (!response.isSuccessful) return@use
                    val source = response.body?.source() ?: return@use
                    while (!call.isCanceled()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotBlank() && data != "{}") trySend(data)
                        }
                    }
                }
            } catch (_: Throwable) {
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
