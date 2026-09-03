package app.hermes.companion.data.remote

import app.hermes.companion.domain.DashboardUrls
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.GatewayHello
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SessionRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DashboardException(val code: String, message: String) : RuntimeException(message)

class DashboardClient internal constructor(
    http: OkHttpClient,
    attachToken: Boolean,
) {
    @Volatile
    var sessionToken: String? = null

    private val http: OkHttpClient = if (!attachToken) http else http.newBuilder()
        .addInterceptor { chain ->
            val token = sessionToken
            val req = chain.request().newBuilder()
            if (!token.isNullOrBlank()) {
                req.header("X-Hermes-Session-Token", token)
                req.header("Authorization", "Bearer $token")
            }
            chain.proceed(req.build())
        }
        .build()

    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
        attachToken = true,
    )

    internal constructor(http: OkHttpClient) : this(http, attachToken = false)

    private val rpcLock = Mutex()
    @Volatile private var rpc: GatewaySocket? = null
    @Volatile private var rpcKey: String? = null
    private val liveByStored = ConcurrentHashMap<String, String>()
    private val wsHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(origin: String): DashboardStatus =
        get(DashboardUrls.machine(origin, "/api/status")) { parseStatus(it) }

    /** Loopback dashboards inject an ephemeral token into the SPA. Adopt it. */
    suspend fun adoptLoopbackToken(origin: String): String? {
        val html = runCatching { getHtml(DashboardUrls.machine(origin, "/")) }.getOrNull() ?: return null
        val token = TOKEN_RE.find(html)?.groupValues?.getOrNull(1)
        if (!token.isNullOrBlank()) sessionToken = token
        return token
    }

    /** Machine roster. Not profile-scoped — otherwise the switcher would only see the active agent. */
    suspend fun listProfiles(origin: String): List<ProfileRef> =
        get(DashboardUrls.machine(origin, "/api/profiles")) { parseProfiles(it) }

    /** Catch-up. Always scoped. Client still filters by profileId. */
    suspend fun listSessions(origin: String, profileId: String): List<SessionRef> {
        val socket = rpc
        if (socket != null && socket.isOpen) {
            val rpcRows = runCatching { listSessionsRpc(socket, profileId) }.getOrNull()
            if (rpcRows != null) return rpcRows
        }
        return listSessionsRest(origin, profileId)
    }

    suspend fun listMessages(origin: String, sessionId: String, profileId: String): List<ChatMessage> {
        val rest = runCatching { listMessagesRest(origin, sessionId, profileId) }.getOrNull()
        val socket = rpc
        if (socket != null && socket.isOpen) {
            val resumed = runCatching { resumeSession(sessionId, profileId) }.getOrNull()
            if (resumed != null && resumed.second.isNotEmpty()) return resumed.second
            if (resumed != null) {
                val history = runCatching { listMessagesRpc(socket, resumed.first) }.getOrNull()
                if (!history.isNullOrEmpty()) return history
            }
        }
        return rest ?: listMessagesRest(origin, sessionId, profileId)
    }

    suspend fun pendingApproval(origin: String, sessionId: String, profileId: String): ApprovalPrompt? {
        val url = DashboardUrls.rest(origin, "/api/sessions/$sessionId/approval", profileId)
        return try {
            get(url) { parseApproval(it) }
        } catch (e: DashboardException) {
            if (e.code == "http_404") null else throw e
        }
    }

    suspend fun respondApproval(
        origin: String,
        sessionId: String,
        profileId: String,
        requestId: String,
        decision: String,
        kind: String = "approval",
    ) {
        val prompt = ApprovalPrompt(requestId = requestId, kind = kind, command = "", choices = emptyList())
        respondPrompt(origin, sessionId, profileId, prompt, decision)
    }

    suspend fun respondPrompt(
        origin: String,
        sessionId: String,
        profileId: String,
        prompt: ApprovalPrompt,
        decision: String,
    ) {
        val socket = rpc
        if (socket != null && socket.isOpen) {
            val live = liveSessionId(sessionId)
            val method = when (prompt.kind) {
                "clarify" -> "clarify.respond"
                "sudo" -> "sudo.respond"
                "secret" -> "secret.respond"
                else -> "approval.respond"
            }
            val params = when (prompt.kind) {
                "clarify" ->
                    """{"session_id":${live.json()},"request_id":${prompt.requestId.json()},"answer":${decision.json()},"choice":${decision.json()}}"""
                "sudo" ->
                    """{"session_id":${live.json()},"request_id":${prompt.requestId.json()},"password":${decision.json()}}"""
                "secret" ->
                    """{"session_id":${live.json()},"request_id":${prompt.requestId.json()},"value":${decision.json()}}"""
                else ->
                    """{"session_id":${live.json()},"request_id":${prompt.requestId.json()},"choice":${decision.json()},"decision":${decision.json()}}"""
            }
            val accepted = runCatching { socket.request(method, params) }.isSuccess
            if (accepted) return
        }
        val url = DashboardUrls.rest(origin, "/api/sessions/$sessionId/approval", profileId)
        val payload = """{"request_id":${prompt.requestId.json()},"decision":${decision.json()},"choice":${decision.json()}}"""
        post(url, payload) { it }
    }

    suspend fun ping() {
        val socket = rpc ?: throw DashboardException("ws_closed", "gateway not connected")
        if (!socket.isOpen) throw DashboardException("ws_closed", "gateway not connected")
        socket.request("gateway.ping", "{}", timeoutMs = 8_000)
    }

    suspend fun awaitDisconnect() {
        rpc?.awaitDisconnect()
    }

    fun bus(profileId: String): Flow<BusFrame> = callbackFlow {
        val socket = rpc
        if (socket == null) {
            close()
            return@callbackFlow
        }
        val job = launch {
            socket.events.collect { ev ->
                decodeBus(ev, profileId)?.let { trySend(it) }
            }
        }
        val wait = launch {
            socket.awaitDisconnect()
            close()
        }
        awaitClose {
            job.cancel()
            wait.cancel()
        }
    }.flowOn(Dispatchers.IO)

    fun forgetLiveIds() {
        liveByStored.clear()
    }

    fun matchesSession(storedOrLive: String?, other: String?): Boolean {
        if (storedOrLive.isNullOrBlank() || other.isNullOrBlank()) return false
        if (storedOrLive == other) return true
        return liveSessionId(storedOrLive) == other || liveSessionId(other) == storedOrLive
    }

    internal fun decodeBus(ev: RpcEvent, profileId: String): BusFrame? {
        if (ev.type == "sessions.changed") {
            val payload = ev.payload ?: return BusFrame.SessionRefetch
            val change = parseSessionChange(payload, profileId) ?: return BusFrame.SessionRefetch
            if (change.session.profileId.isBlank()) return BusFrame.SessionRefetch
            return BusFrame.SessionPatch(change)
        }
        val chat = RpcCodec.toChatEvent(ev) ?: return null
        return BusFrame.Chat(chat, ev.sessionId)
    }

    suspend fun wsHello(origin: String, profileId: String, ticket: String? = null): GatewayHello {
        val socket = ensureRpc(origin, profileId, ticket)
        return socket.hello ?: throw DashboardException("ws_hello", "no gateway.ready")
    }

    fun closeRpc() {
        rpc?.close()
        rpc = null
        rpcKey = null
        liveByStored.clear()
    }

    suspend fun createSession(origin: String, profileId: String, title: String = ""): SessionRef {
        val socket = rpc
        if (socket != null && socket.isOpen) {
            val result = socket.request(
                "session.create",
                """{"profile":${profileId.json()},"title":${title.json()}}""",
            )
            val live = result.str("session_id").ifBlank { result.str("id") }
            val created = parseRpcSession(result, profileId)
            rememberLive(created.id, live.ifBlank { created.id })
            return created
        }
        val url = DashboardUrls.rest(origin, "/api/sessions", profileId)
        val payload = """{"profile":${profileId.json()},"title":${title.json()}}"""
        return post(url, payload) { parseCreatedSession(it, profileId) }
    }

    suspend fun interruptTurn(origin: String, sessionId: String, profileId: String) {
        val socket = rpc ?: return
        if (!socket.isOpen) return
        val live = liveSessionId(sessionId)
        socket.request("session.interrupt", """{"session_id":${live.json()},"profile":${profileId.json()}}""")
    }

    fun streamTurn(origin: String, sessionId: String, profileId: String, text: String): Flow<ChatEvent> {
        val socket = rpc
        if (socket != null && socket.isOpen) return streamTurnRpc(socket, sessionId, profileId, text)
        return streamTurnSse(origin, sessionId, profileId, text)
    }

    private fun streamTurnSse(origin: String, sessionId: String, profileId: String, text: String): Flow<ChatEvent> = flow {
        val url = DashboardUrls.rest(origin, "/api/sessions/$sessionId/chat/stream", profileId)
        val payload = """{"input":${text.json()},"profile":${profileId.json()}}"""
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DashboardException("http_${response.code}", "http ${response.code} · chat/stream")
            }
            val source = response.body?.source() ?: return@use
            var event = "message"
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.substringAfter("data:").trim()
                        parseSse(event, data)?.let { emit(it) }
                    }
                    line.isBlank() -> event = "message"
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamTurnRpc(
        socket: GatewaySocket,
        sessionId: String,
        profileId: String,
        text: String,
    ): Flow<ChatEvent> = callbackFlow {
        if (liveByStored[sessionId] == null) {
            runCatching { resumeSession(sessionId, profileId) }
        }
        val bound = liveSessionId(sessionId)
        val finished = CompletableDeferred<Unit>()
        val collectJob = launch {
            socket.events.collect { ev ->
                if (!ev.belongsTo(sessionId, bound)) return@collect
                val chat = RpcCodec.toChatEvent(ev) ?: return@collect
                trySend(chat)
                if (chat is ChatEvent.Completed && !finished.isCompleted) {
                    finished.complete(Unit)
                }
            }
        }
        val submitJob = launch {
            try {
                socket.request(
                    "prompt.submit",
                    """{"session_id":${bound.json()},"text":${text.json()},"profile":${profileId.json()}}""",
                    timeoutMs = 180_000,
                )
                withTimeout(180_000) { finished.await() }
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose {
            collectJob.cancel()
            submitJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureRpc(origin: String, profileId: String, ticket: String? = null): GatewaySocket =
        rpcLock.withLock {
            val key = "$origin|$profileId"
            val current = rpc
            if (current != null && rpcKey == key && current.isOpen) return@withLock current
            current?.close()
            liveByStored.clear()
            val next = GatewaySocket(wsHttp)
            next.connect(DashboardUrls.ws(origin, profileId, ticket = ticket, token = sessionToken))
            rpc = next
            rpcKey = key
            next
        }

    private suspend fun listSessionsRest(origin: String, profileId: String): List<SessionRef> {
        val raw = get(DashboardUrls.rest(origin, "/api/sessions", profileId)) { parseSessions(it) }
        return stampProfile(raw, profileId)
    }

    private suspend fun listSessionsRpc(socket: GatewaySocket, profileId: String): List<SessionRef> {
        val result = socket.request(
            "session.list",
            """{"profile":${profileId.json()},"limit":200}""",
        )
        return stampProfile(parseSessions(result.toString()), profileId)
    }

    private suspend fun listMessagesRest(origin: String, sessionId: String, profileId: String): List<ChatMessage> =
        get(DashboardUrls.rest(origin, "/api/sessions/$sessionId/messages", profileId)) { parseMessages(it) }

    private suspend fun listMessagesRpc(socket: GatewaySocket, liveId: String): List<ChatMessage> {
        val result = socket.request("session.history", """{"session_id":${liveId.json()}}""")
        return parseMessages(result.toString())
    }

    private suspend fun resumeSession(sessionId: String, profileId: String): Pair<String, List<ChatMessage>> {
        val socket = rpc ?: throw DashboardException("ws_closed", "gateway not connected")
        val result = socket.request(
            "session.resume",
            """{"session_id":${sessionId.json()},"profile":${profileId.json()}}""",
        )
        val live = result.str("session_id").ifBlank { sessionId }
        val stored = result.str("stored_session_id").ifBlank { sessionId }
        rememberLive(stored, live)
        rememberLive(sessionId, live)
        return live to parseMessages(result.toString())
    }

    private fun stampProfile(rows: List<SessionRef>, profileId: String): List<SessionRef> =
        rows.map { if (it.profileId.isBlank()) it.copy(profileId = profileId) else it }
            .filter { it.profileId == profileId }

    private fun liveSessionId(stored: String): String = liveByStored[stored] ?: stored

    private fun rememberLive(stored: String, live: String) {
        if (stored.isBlank() || live.isBlank()) return
        liveByStored[stored] = live
        liveByStored[live] = live
    }

    private fun RpcEvent.belongsTo(stored: String, live: String): Boolean {
        val sid = sessionId ?: return true
        return sid == stored || sid == live
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "text/html").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DashboardException("http_${response.code}", "http ${response.code} · /")
            }
            body
        }
    }

    private suspend fun <T> get(url: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DashboardException(
                    "http_${response.code}",
                    "http ${response.code} · ${url.substringAfter(response.request.url.host)}",
                )
            }
            parse(body)
        }
    }

    private suspend fun <T> post(url: String, json: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DashboardException("http_${response.code}", "http ${response.code} · post")
            }
            parse(body)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val sseJson = Json { ignoreUnknownKeys = true }
        private val TOKEN_RE = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")

        private fun String.json(): String = buildString {
            append('"')
            for (ch in this@json) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('"')
        }

        internal fun parseSse(event: String, data: String): ChatEvent? {
            if (data.isBlank() || data == "[DONE]") {
                return if (event.contains("complete") || event == "done") ChatEvent.Completed else null
            }
            val obj = runCatching { sseJson.parseToJsonElement(data).jsonObject }.getOrNull()
            val text = obj.str("text").ifBlank { obj.str("delta") }
            val name = obj.str("name").ifBlank { obj.str("tool") }
            val detail = obj.str("detail").ifBlank { obj.str("command") }.ifBlank { text }
            return when {
                event.contains("tool.start") || event == "tool.started" ->
                    ChatEvent.ToolStarted(name.ifBlank { "tool" }, detail)
                event.contains("tool.complete") || event == "tool.completed" ->
                    ChatEvent.ToolCompleted(
                        name.ifBlank { "tool" },
                        detail,
                        obj?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                event.contains("assistant.delta") || event == "token" || event == "message.delta" ->
                    if (text.isBlank()) null else ChatEvent.AssistantDelta(text)
                event.contains("approval") -> {
                    val prompt = parseApproval(data) ?: return null
                    ChatEvent.Approval(prompt)
                }
                event.contains("complete") || event == "run.completed" || event == "done" ->
                    ChatEvent.Completed
                else -> if (text.isNotBlank()) ChatEvent.AssistantDelta(text) else null
            }
        }

        private fun JsonObject?.str(key: String): String =
            this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
