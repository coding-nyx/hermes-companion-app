package app.hermes.companion.data.remote

import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.DashboardUrls
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.domain.HistoryPaging
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.RewindPolicy
import app.hermes.companion.domain.RewindSubmit
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.model.AgentDirListing
import app.hermes.companion.model.AgentEvent
import app.hermes.companion.model.AgentPane
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.AgentTool
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.BusFrame
import app.hermes.companion.model.ChatEvent
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.CompanionHealth
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.DeviceCommand
import app.hermes.companion.model.DeviceCred
import app.hermes.companion.model.DeviceResult
import app.hermes.companion.model.DeviceTicket
import app.hermes.companion.model.HistoryPage
import app.hermes.companion.model.GatewayHello
import app.hermes.companion.model.PairingStatus
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomPolicySpec
import app.hermes.companion.model.PeerLink
import app.hermes.companion.model.RoomRef
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
import app.hermes.companion.model.GitBranches
import app.hermes.companion.model.GitDiffSummary
import app.hermes.companion.model.GitStatus
import app.hermes.companion.model.HostMetrics
import app.hermes.companion.model.HostCpuMetrics
import app.hermes.companion.model.HostMemoryMetrics
import app.hermes.companion.model.HostDiskMetrics
import app.hermes.companion.model.HostSystemMetrics
import app.hermes.companion.model.HostHermesProcess
import app.hermes.companion.model.RemoteFsItem
import app.hermes.companion.model.TerminalExecResult
import app.hermes.companion.model.CronJob
import app.hermes.companion.model.ModelCatalog
import app.hermes.companion.model.ModelOption
import app.hermes.companion.model.HermesUpdateStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

class DashboardException(val code: String, message: String) : RuntimeException(message)

class DashboardClient internal constructor(
    http: OkHttpClient,
    attachToken: Boolean,
) {
    @Volatile
    var sessionToken: String? = null

    @Volatile
    var gated: Boolean = false

    private val cookies = MemoryCookieJar()

    private val http: OkHttpClient = buildHttp(http, attachToken)

    /**
     * `device_id:credential` of this phone's pairing with the host. Sent as
     * `Authorization: Companion …` on `/companion/` calls, which the relay gates for anything
     * beyond the pairing handshake (rooms, device command, host tools). Set by the device node
     * once a credential is known; null until then.
     */
    @Volatile
    var companionAuth: String? = null

    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
        attachToken = true,
    )

    internal constructor(http: OkHttpClient) : this(http, attachToken = false)

    private val rpcLock = Mutex()
    private val deviceLock = Mutex()
    @Volatile private var rpc: GatewaySocket? = null
    @Volatile private var rpcKey: String? = null
    @Volatile private var deviceWs: DeviceSocket? = null
    private val liveByStored = ConcurrentHashMap<String, String>()
    /** SSE turn stream: a tool call can sit silent for minutes, so no read timeout. */
    private val sseHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val wsHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(origin: String): DashboardStatus =
        get(DashboardUrls.machine(origin, "/api/status")) { parseStatus(it) }

    suspend fun companionHealth(origin: String): CompanionHealth =
        get(DashboardUrls.machine(origin, "/companion/health")) { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            CompanionHealth(
                relay = obj["relay"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                mode = obj["mode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                upstream = obj["upstream"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hermesVersion = obj["hermes_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                profilesDir = obj["profiles_dir"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                drift = obj["drift"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    suspend fun uploadMedia(
        origin: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val payload = """{"filename":${filename.json()},"mime":${mime.json()},"data":${b64.json()}}"""
        return try {
            post("$origin/companion/media", payload) { body ->
                val obj = DashboardJson.parseToJsonElement(body).jsonObject
                val path = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (path.startsWith("http")) path else origin.trimEnd('/') + path
            }
        } catch (e: DashboardException) {
            if (e.code != "http_404") throw e
            post("$origin/api/chat/image-upload", payload) { body ->
                val obj = DashboardJson.parseToJsonElement(body).jsonObject
                val path = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifBlank { obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                if (path.startsWith("http")) path else origin.trimEnd('/') + path
            }
        }
    }

    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw httpError(response.code, bytes.decodeToString(), url)
            }
            bytes
        }
    }

    fun useLoopback() {
        gated = false
        cookies.clear()
    }

    fun useGated() {
        gated = true
        sessionToken = null
    }

    /** Loopback dashboards inject an ephemeral token into the SPA. Adopt it. */
    suspend fun adoptLoopbackToken(origin: String): String? {
        if (gated) return null
        val html = runCatching { getHtml(DashboardUrls.machine(origin, "/")) }.getOrNull() ?: return null
        val token = TOKEN_RE.find(html)?.groupValues?.getOrNull(1)
        if (!token.isNullOrBlank()) sessionToken = token
        return token
    }

    suspend fun passwordLogin(
        origin: String,
        username: String,
        password: String,
        provider: String = "basic",
    ) {
        useGated()
        post(
            DashboardUrls.machine(origin, "/auth/password-login"),
            AuthPolicy.jsonLogin(provider, username, password),
        ) { }
    }

    suspend fun offerPair(origin: String, code: String, profileId: String): PairingStatus {
        val id = ProfileScope.requireProfileId(profileId)
        val payload = """{"code":${code.json()},"profile":${id.json()},"protocol_version":1}"""
        return post(DashboardUrls.machine(origin, "/companion/device/pair"), payload) { parsePairing(it) }
    }

    suspend fun pollPair(origin: String, code: String): PairingStatus =
        get(DashboardUrls.machine(origin, "/companion/device/pair/${code.trim()}")) { parsePairing(it) }

    suspend fun approvePair(origin: String, code: String): PairingStatus {
        post(DashboardUrls.machine(origin, "/companion/device/pair/${code.trim()}/approve"), "{}") { }
        return pollPair(origin, code)
    }

    suspend fun revokeDevice(origin: String, deviceId: String) {
        val payload = """{"device_id":${deviceId.json()}}"""
        post(DashboardUrls.machine(origin, "/companion/device/revoke"), payload) { }
    }

    suspend fun registerDevice(
        origin: String,
        cred: DeviceCred,
        deviceName: String = "",
        model: String = "",
        manufacturer: String = "",
        osVersion: String = "",
        protectedPackages: Collection<String> = emptyList(),
        capabilities: List<String> = DeviceLanePolicy.CAPABILITIES,
        wakeTopic: String = "",
    ): DeviceTicket {
        companionAuth = "${cred.deviceId}:${cred.credential}"
        val payload = DeviceLanePolicy.registerJson(
            deviceId = cred.deviceId,
            profileId = cred.profileId,
            credential = cred.credential,
            capabilities = capabilities,
            deviceName = deviceName,
            model = model,
            manufacturer = manufacturer,
            osVersion = osVersion,
            protectedPackages = protectedPackages,
            wakeTopic = wakeTopic,
        )
        return post(DashboardUrls.machine(origin, "/companion/device/register"), payload) { parseDeviceTicket(it) }
    }

    suspend fun renameDevice(origin: String, deviceId: String, name: String) {
        val payload = """{"device_id":${deviceId.json()},"name":${name.json()}}"""
        post(DashboardUrls.machine(origin, "/companion/device/rename"), payload) { }
    }

    suspend fun openDeviceLane(
        origin: String,
        cred: DeviceCred,
        deviceName: String = "",
        model: String = "",
        manufacturer: String = "",
        osVersion: String = "",
        protectedPackages: Collection<String> = emptyList(),
        capabilities: List<String> = DeviceLanePolicy.CAPABILITIES,
        wakeTopic: String = "",
    ) {
        companionAuth = "${cred.deviceId}:${cred.credential}"
        deviceLock.withLock {
            deviceWs?.close()
            val ticket = registerDevice(
                origin, cred,
                deviceName = deviceName,
                model = model,
                manufacturer = manufacturer,
                osVersion = osVersion,
                protectedPackages = protectedPackages,
                capabilities = capabilities,
                wakeTopic = wakeTopic,
            )
            if (ticket.ticket.isBlank()) throw DashboardException("device_ticket", "empty device ticket")
            val socket = DeviceSocket(wsHttp)
            socket.connect(DashboardUrls.deviceWs(origin), DeviceLanePolicy.protocolHeader(ticket.ticket))
            deviceWs = socket
        }
    }

    fun deviceLaneOpen(): Boolean = deviceWs?.isOpen == true

    fun deviceCommands(): Flow<DeviceCommand> = callbackFlow {
        val socket = deviceWs
        if (socket == null) {
            close()
            return@callbackFlow
        }
        val job = launch { socket.commands.collect { trySend(it) } }
        val wait = launch {
            socket.awaitDisconnect()
            close()
        }
        awaitClose {
            job.cancel()
            wait.cancel()
        }
    }.flowOn(Dispatchers.IO)

    fun replyDevice(result: DeviceResult): Boolean = deviceWs?.send(result) == true

    fun sendDeviceEvent(
        event: String,
        deviceId: String,
        profile: String,
        tsMs: Long,
        notificationJson: String,
    ): Boolean = deviceWs?.sendEvent(event, deviceId, profile, tsMs, notificationJson) == true

    fun sendDeviceStatus(deviceId: String, fieldsJson: String): Boolean =
        deviceWs?.sendStatus(deviceId, fieldsJson) == true

    fun sendDeviceRaw(json: String): Boolean = deviceWs?.sendRaw(json) == true

    fun wakeEvents(sseUrl: String): Flow<String> = NtfyClient(wsHttp).events(sseUrl)

    fun closeDeviceLane() {
        deviceWs?.close()
        deviceWs = null
    }

    suspend fun mintWsTicket(origin: String): String {
        val ticket = post(DashboardUrls.machine(origin, "/api/auth/ws-ticket"), "{}") { parseWsTicket(it) }
        if (ticket.isBlank()) throw DashboardException("ticket", "empty ws ticket")
        return ticket
    }

    /** Machine roster. Not profile-scoped — otherwise the switcher would only see the active agent. */
    suspend fun listProfiles(origin: String): List<ProfileRef> =
        get(DashboardUrls.machine(origin, "/api/profiles")) { parseProfiles(it) }

    /**
     * Catch-up. Always scoped. Client still filters by profileId.
     *
     * The two host paths disagree on coverage: the dashboard's `GET /api/sessions` returns only
     * the 20 most recent rows, while the gateway's `session.list` knows the live/lazy sessions and
     * honours `limit`. Neither alone is exhaustive, so the rail takes the **union** keyed by id
     * (richer fields merged per row) instead of guessing which page is "better" (A18.9).
     * Ordering is not decided here — `SessionLists.normalize` does that once, in the domain.
     */
    suspend fun listSessions(origin: String, profileId: String, includeArchived: Boolean = false): List<SessionRef> {
        val socket = rpc
        val rpcRows = if (socket != null && socket.isOpen) {
            runCatching { listSessionsRpc(socket, profileId, includeArchived) }.getOrNull()
        } else null
        val rest = runCatching { listSessionsRest(origin, profileId, includeArchived) }
        // No RPC page → REST is the only source; its failure (auth, 502, cleartext …) must surface.
        if (rpcRows == null) return SessionLists.normalize(rest.getOrThrow())
        return SessionLists.normalize(rpcRows + rest.getOrDefault(emptyList()))
    }

    suspend fun listMessages(origin: String, sessionId: String, profileId: String): List<ChatMessage> =
        pageMessages(origin, sessionId, profileId).messages

    suspend fun pageMessages(
        origin: String,
        sessionId: String,
        profileId: String,
        beforeId: String? = null,
        limit: Int = HistoryPaging.PAGE,
        ended: Boolean = false,
    ): HistoryPage {
        val cap = HistoryPaging.cap(limit)
        val socket = rpc
        if (socket != null && socket.isOpen) {
            if (beforeId.isNullOrBlank() && !ended) {
                runCatching { resumeSession(sessionId, profileId) }
            }
            val live = liveSessionId(sessionId)
            val rpcPage = runCatching { listMessagesRpc(socket, live, profileId, cap, beforeId) }.getOrNull()
            // Empty RPC (first page OR older-page beforeId) → REST fallback. Some gateways
            // answer session.history with [] for Telegram/imported threads that still have REST history.
            if (rpcPage != null && rpcPage.messages.isNotEmpty()) {
                return rpcPage.copy(source = "rpc")
            }
        }
        return listMessagesRest(origin, sessionId, profileId, cap, beforeId).copy(source = "rest")
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

    suspend fun createSession(
        origin: String,
        profileId: String,
        title: String = "",
        model: String? = null,
    ): SessionRef {
        val extra = modelField(model)
        val socket = rpc
        if (socket != null && socket.isOpen) {
            val result = socket.request(
                "session.create",
                """{"profile":${profileId.json()},"title":${title.json()}$extra}""",
            )
            val live = result.str("session_id").ifBlank { result.str("id") }
            val created = parseRpcSession(result, profileId)
            rememberLive(created.id, live.ifBlank { created.id })
            return created
        }
        val url = DashboardUrls.rest(origin, "/api/sessions", profileId)
        val payload = """{"profile":${profileId.json()},"title":${title.json()}$extra}"""
        return post(url, payload) { parseCreatedSession(it, profileId) }
    }

    suspend fun deleteSession(origin: String, sessionId: String, profileId: String) {
        ProfileScope.requireProfileId(profileId)
        delete(DashboardUrls.rest(origin, "/api/sessions/$sessionId", profileId))
    }

    suspend fun interruptTurn(origin: String, sessionId: String, profileId: String) {
        val socket = rpc ?: return
        if (!socket.isOpen) return
        val live = liveSessionId(sessionId)
        socket.request("session.interrupt", """{"session_id":${live.json()},"profile":${profileId.json()}}""")
    }

    fun streamTurn(
        origin: String,
        sessionId: String,
        profileId: String,
        text: String,
        rewind: RewindSubmit? = null,
        model: String? = null,
        partsJson: String = "",
    ): Flow<ChatEvent> {
        val socket = rpc
        if (rewind != null) {
            if (socket == null || !socket.isOpen) {
                return flow { throw DashboardException("rpc_required", "rewind needs live gateway") }
            }
            return streamTurnRpc(socket, sessionId, profileId, text, rewind, model, partsJson)
        }
        if (socket != null && socket.isOpen) {
            return streamTurnRpc(socket, sessionId, profileId, text, model = model, partsJson = partsJson)
        }
        return streamTurnSse(origin, sessionId, profileId, text, model, partsJson)
    }

    private fun streamTurnSse(
        origin: String,
        sessionId: String,
        profileId: String,
        text: String,
        model: String? = null,
        partsJson: String = "",
    ): Flow<ChatEvent> = flow {
        val url = DashboardUrls.rest(origin, "/api/sessions/$sessionId/chat/stream", profileId)
        val payload = """{"input":${text.json()},"profile":${profileId.json()}${modelField(model)}$partsJson}"""
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON))
            .build()
        sseHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpError(response.code, response.body?.string().orEmpty(), url)
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
        rewind: RewindSubmit? = null,
        model: String? = null,
        partsJson: String = "",
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
                val extra = RewindPolicy.jsonExtras(rewind)
                val result = socket.request(
                    "prompt.submit",
                    """{"session_id":${bound.json()},"text":${text.json()},"profile":${profileId.json()}${modelField(model)}$partsJson$extra}""",
                    timeoutMs = 180_000,
                )
                if (rewind != null) {
                    trySend(ChatEvent.Rewound(parseSurvivorRowIds(result).orEmpty()))
                }
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

    // ---- Agent rooms (P21) — plugin-owned routes under /companion/rooms -------------------------

    suspend fun listRooms(origin: String): List<RoomRef> =
        get(DashboardUrls.machine(origin, "/companion/rooms")) { RoomJson.rooms(it) }

    suspend fun createRoom(
        origin: String,
        title: String,
        participants: List<String>,
        policy: RoomPolicySpec = RoomPolicySpec(),
    ): RoomRef {
        val parts = participants.joinToString(",") { it.json() }
        val payload = """{"title":${title.json()},"participants":[$parts],"policy":${policyJson(policy)}}"""
        return post(DashboardUrls.machine(origin, "/companion/rooms"), payload) {
            RoomJson.roomEnvelope(it) ?: throw DashboardException("room_parse", "room missing in reply")
        }
    }

    private fun policyJson(p: RoomPolicySpec): String = buildString {
        append("""{"mode":${p.mode.json()},"max_turns":${p.maxTurns},"max_rounds":${p.maxRounds}""")
        if (p.moderator.isNotBlank()) append(""","moderator":${p.moderator.json()}""")
        append(""","hands":${p.hands.json()}""")
        append("}")
    }

    suspend fun patchRoom(origin: String, roomId: String, title: String? = null, policy: RoomPolicySpec? = null): RoomRef {
        val fields = buildList {
            if (title != null) add(""""title":${title.json()}""")
            if (policy != null) add(""""policy":${policyJson(policy)}""")
        }
        return patch(DashboardUrls.machine(origin, "/companion/rooms/$roomId"), "{${fields.joinToString(",")}}") {
            RoomJson.roomEnvelope(it) ?: throw DashboardException("room_parse", "room missing in reply")
        }
    }

    suspend fun setRoomParticipants(origin: String, roomId: String, add: List<String>, remove: List<String>): RoomRef {
        val payload = """{"add":[${add.joinToString(",") { it.json() }}],"remove":[${remove.joinToString(",") { it.json() }}]}"""
        return post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/participants"), payload) {
            RoomJson.roomEnvelope(it) ?: throw DashboardException("room_parse", "room missing in reply")
        }
    }

    suspend fun pauseRoom(origin: String, roomId: String) {
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/pause"), "{}") { }
    }

    suspend fun continueRoom(origin: String, roomId: String, turns: Int = 6): RoomRef? =
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/continue"), """{"turns":$turns}""") { RoomJson.roomEnvelope(it) }

    suspend fun summarizeRoom(origin: String, roomId: String, by: String = ""): RoomRef? =
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/summarize"), """{"by":${by.json()}}""") { RoomJson.roomEnvelope(it) }

    suspend fun respondRoomApproval(origin: String, roomId: String, requestId: String, decision: String) {
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/approval"), """{"request_id":${requestId.json()},"decision":${decision.json()}}""") { }
    }

    // ---- Coding-agent sessions (P23) --------------------------------------------------------------
    suspend fun agentTools(origin: String, refresh: Boolean = false): Triple<List<AgentTool>, Boolean, String> =
        get(DashboardUrls.machine(origin, "/companion/agents/tools" + if (refresh) "?refresh=1" else "")) { AgentJson.tools(it) }

    suspend fun agentSessions(origin: String): List<AgentSession> =
        get(DashboardUrls.machine(origin, "/companion/agents/sessions")) { AgentJson.sessions(it) }

    suspend fun startAgentSession(
        origin: String,
        tool: String,
        cwd: String = "",
        prompt: String = "",
        title: String = "",
        cols: Int = 100,
        rows: Int = 40,
        mode: String = "pty",
    ): AgentSession {
        val payload = """{"tool":${tool.json()},"mode":${mode.json()},"cwd":${cwd.json()},"prompt":${prompt.json()},"title":${title.json()},"cols":$cols,"rows":$rows}"""
        return post(DashboardUrls.machine(origin, "/companion/agents/sessions"), payload) {
            AgentJson.sessionEnvelope(it) ?: throw DashboardException("agent_parse", "session missing in reply")
        }
    }

    suspend fun agentPane(origin: String, sessionId: String, cols: Int = 0, rows: Int = 0): Pair<AgentPane, AgentSession?> {
        val q = if (cols > 0 && rows > 0) "?cols=$cols&rows=$rows" else ""
        return get(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId/pane$q")) { AgentJson.paneBody(it) }
    }

    suspend fun agentKeys(origin: String, sessionId: String, text: String = "", keys: List<String> = emptyList()) {
        val payload = """{"text":${text.json()},"keys":[${keys.joinToString(",") { it.json() }}]}"""
        post(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId/keys"), payload) { }
    }

    /** Structured sessions: one prompt = one turn. 409 while a turn is running. */
    /** Directory picker listing under the host's allowed roots. Empty [path] = host default. */
    suspend fun agentDirs(origin: String, path: String = "", hidden: Boolean = false): AgentDirListing {
        val q = "?path=${java.net.URLEncoder.encode(path, "UTF-8")}" + if (hidden) "&hidden=1" else ""
        return get(DashboardUrls.machine(origin, "/companion/agents/dirs$q")) { AgentJson.dirs(it) }
    }

    suspend fun agentPrompt(origin: String, sessionId: String, text: String) {
        post(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId/prompt"), """{"text":${text.json()}}""") { }
    }

    suspend fun agentApproval(origin: String, sessionId: String, requestId: String, decision: String) {
        val payload = """{"request_id":${requestId.json()},"decision":${decision.json()}}"""
        post(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId/approval"), payload) { }
    }

    suspend fun agentTranscript(origin: String, sessionId: String, after: Int = 0): Triple<List<AgentEvent>, ApprovalPrompt?, AgentSession?> =
        get(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId/transcript?after=$after")) { AgentJson.transcriptBody(it) }

    suspend fun killAgentSession(origin: String, sessionId: String, forget: Boolean = false) {
        delete(DashboardUrls.machine(origin, "/companion/agents/sessions/$sessionId" + if (forget) "?forget=1" else ""))
    }

    /**
     * Live pane stream for one session: `agent.pane` on every screen change, `agent.status` on
     * transitions. Completes when the socket closes or the session exits; caller reconnects.
     */
    fun agentEvents(origin: String, sessionId: String, cols: Int, rows: Int): Flow<AgentJson.Event> = callbackFlow {
        val url = DashboardUrls.machine(origin, "/companion/agents/events?session_id=$sessionId&cols=$cols&rows=$rows")
        val auth = companionAuth
        val request = Request.Builder().url(url).apply { if (!auth.isNullOrBlank()) header("Authorization", "Companion $auth") }.build()
        val socket = wsHttp.newWebSocket(
            request,
            object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    text.split('\n').forEach { line -> AgentJson.event(line)?.let { trySend(it) } }
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    close(
                        if (code == 401) DashboardException("agent_unauthorized", "pair this phone to use agent sessions")
                        else DashboardException("agent_events", t.message ?: "agent events socket failed"),
                    )
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    // ---- Peer links (A22.11) --------------------------------------------------------------------
    suspend fun listPeers(origin: String): List<PeerLink> =
        get(DashboardUrls.machine(origin, "/companion/peers")) { RoomJson.peers(it) }

    /** Mint a credential on [origin] that another relay (named [forName]) may use to run turns there. */
    suspend fun grantPeer(origin: String, forName: String): Triple<String, String, String> =
        post(DashboardUrls.machine(origin, "/companion/peers/grant"), """{"name":${forName.json()}}""") { body ->
            val g = (Json.parseToJsonElement(body).jsonObject["grant"] as? JsonObject)
            val hostId = g?.get("host_id")?.jsonPrimitive?.contentOrNull.orEmpty()
            val secret = g?.get("secret")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (hostId.isBlank() || secret.isBlank()) throw DashboardException("peer_grant", "grant missing in reply")
            Triple(hostId, secret, g?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty())
        }

    suspend fun addPeer(origin: String, name: String, peerOrigin: String, hostId: String, secret: String) {
        val payload = """{"name":${name.json()},"origin":${peerOrigin.json()},"host_id":${hostId.json()},"secret":${secret.json()}}"""
        post(DashboardUrls.machine(origin, "/companion/peers"), payload) { }
    }

    suspend fun removePeer(origin: String, name: String) {
        delete(DashboardUrls.machine(origin, "/companion/peers/$name"))
    }

    suspend fun roomHistory(origin: String, roomId: String, after: Int = 0): Pair<RoomRef?, List<ChatMessage>> =
        get(DashboardUrls.machine(origin, "/companion/rooms/$roomId/history?after=$after")) { RoomJson.history(it) }

    /** Posts as the operator; the host starts the agents' turns. Returns the operator line's seq. */
    suspend fun postRoom(origin: String, roomId: String, text: String): Int =
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/post"), """{"text":${text.json()}}""") { body ->
            (DashboardJson.parseToJsonElement(body) as? JsonObject)?.get("seq")?.jsonPrimitive?.intOrNull ?: 0
        }

    suspend fun interruptRoom(origin: String, roomId: String) {
        post(DashboardUrls.machine(origin, "/companion/rooms/$roomId/interrupt"), "{}") { }
    }

    suspend fun deleteRoom(origin: String, roomId: String) {
        delete(DashboardUrls.machine(origin, "/companion/rooms/$roomId"))
    }

    /**
     * Live `room.*` events for one room as [ChatEvent]s. Completes when the socket closes;
     * callers reconnect. Heartbeats and unknown frames are dropped.
     */
    fun roomEvents(origin: String, roomId: String): Flow<ChatEvent> = callbackFlow {
        val url = DashboardUrls.machine(origin, "/companion/rooms/events?room_id=$roomId")
            .replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        // Websocket upgrades bypass the REST interceptors on this client, so attach the pairing
        // credential here as well (the relay gates /companion/rooms/events).
        val request = Request.Builder().url(url).apply {
            companionAuth?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Companion $it") }
        }.build()
        val socket = wsHttp.newWebSocket(
            request,
            object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    text.split('\n').forEach { line ->
                        RoomJson.event(line)?.let { trySend(it) }
                    }
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    close(
                        if (code == 401) DashboardException("room_unauthorized", "pair this phone to use rooms")
                        else DashboardException("room_events", t.message ?: "room events socket failed"),
                    )
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureRpc(origin: String, profileId: String, ticket: String? = null): GatewaySocket =
        rpcLock.withLock {
            val key = "$origin|$profileId"
            val current = rpc
            if (current != null && rpcKey == key && current.isOpen) return@withLock current
            current?.close()
            liveByStored.clear()
            val next = GatewaySocket(wsHttp)
            val wsTicket = when {
                !ticket.isNullOrBlank() -> ticket
                gated -> mintWsTicket(origin)
                else -> null
            }
            val wsToken = if (wsTicket.isNullOrBlank()) sessionToken else null
            next.connect(DashboardUrls.ws(origin, profileId, ticket = wsTicket, token = wsToken))
            rpc = next
            rpcKey = key
            next
        }

    /**
     * The dashboard pages `GET /api/sessions` at `limit ≤ 100` (422 above) and reports `total`;
     * default is 20 rows. Walk `offset` until the page is short, `total` is reached, or
     * [SESSION_PAGE] rows are in hand. Hosts that ignore `offset` (mock, older relay) return the
     * same page again — the "nothing new" guard stops that loop.
     */
    private suspend fun listSessionsRest(origin: String, profileId: String, includeArchived: Boolean): List<SessionRef> {
        val seen = LinkedHashMap<String, SessionRef>()
        var offset = 0
        var total = -1
        while (offset < SESSION_PAGE) {
            val url = DashboardUrls.rest(
                origin,
                "/api/sessions",
                profileId,
                buildMap {
                    put("limit", REST_PAGE.toString())
                    put("offset", offset.toString())
                    // Dashboard: exclude (default) | include | only. Standalone relay mirrors it.
                    if (includeArchived) put("archived", "include")
                },
            )
            val (rows, pageTotal) = get(url) { body -> stampProfile(parseSessions(body), profileId) to parseSessionTotal(body) }
            if (pageTotal > 0) total = pageTotal
            val fresh = rows.count { !seen.containsKey(it.id) }
            rows.forEach { seen[it.id] = it }
            if (rows.size < REST_PAGE || fresh == 0) break
            offset += REST_PAGE
            if (total in 0..offset) break
        }
        return seen.values.toList()
    }

    private suspend fun listSessionsRpc(socket: GatewaySocket, profileId: String, includeArchived: Boolean): List<SessionRef> {
        val archived = if (includeArchived) ""","include_archived":true""" else ""
        val result = socket.request(
            "session.list",
            """{"profile":${profileId.json()},"limit":$SESSION_PAGE$archived}""",
        )
        return stampProfile(parseSessions(result.toString()), profileId)
    }

    private suspend fun listMessagesRest(
        origin: String,
        sessionId: String,
        profileId: String,
        limit: Int,
        beforeId: String?,
    ): HistoryPage {
        val extra = buildMap {
            put("limit", limit.toString())
            put("order", "latest")
            if (!beforeId.isNullOrBlank()) put("before", beforeId)
        }
        return try {
            get(
                DashboardUrls.rest(origin, "/api/sessions/$sessionId/messages", profileId, extra),
            ) { HistoryPaging.clip(parseMessages(it), beforeId, limit) }
        } catch (e: DashboardException) {
            // Brand-new sessions (and some gateways) have no transcript resource yet.
            // Treat 404 as empty history so openSession does not show "history failed".
            if (e.code == "http_404") HistoryPage(messages = emptyList(), hasMore = false)
            else throw e
        }
    }

    private suspend fun listMessagesRpc(
        socket: GatewaySocket,
        liveId: String,
        profileId: String,
        limit: Int,
        beforeId: String?,
    ): HistoryPage {
        val before = if (beforeId.isNullOrBlank()) "" else ""","before":${beforeId.json()}"""
        val result = socket.request(
            "session.history",
            """{"session_id":${liveId.json()},"profile":${profileId.json()},"limit":$limit$before}""",
        )
        return HistoryPaging.clip(parseMessages(result.toString()), beforeId, limit)
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

    private fun buildHttp(base: OkHttpClient, attachToken: Boolean): OkHttpClient =
        base.newBuilder()
            .cookieJar(cookies)
            // Cleartext only toward LAN / Tailscale hosts. The manifest network-security config
            // cannot express IP ranges, so the policy lives in OriginPolicy and is enforced here
            // for REST, WebSocket (wsHttp derives from this client) and ntfy SSE alike.
            .addInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps && !OriginPolicy.privateHost(url.host)) {
                    throw java.io.IOException(OriginPolicy.CLEARTEXT_DENIED)
                }
                val req = chain.request().newBuilder()
                val auth = companionAuth
                val token = sessionToken
                if (!auth.isNullOrBlank() && url.encodedPath.startsWith("/companion/")) {
                    // Plugin routes: the pairing credential is the identity. Must not be
                    // overwritten by the dashboard bearer below.
                    req.header("Authorization", "Companion $auth")
                } else if (attachToken && !gated && !token.isNullOrBlank()) {
                    req.header("X-Hermes-Session-Token", token)
                    req.header("Authorization", "Bearer $token")
                }
                chain.proceed(req.build())
            }
            .build()

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "text/html").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, body, url)
            }
            body
        }
    }

    private suspend fun <T> get(url: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, body, url)
            }
            parse(body)
        }
    }

    private fun modelField(model: String?): String =
        if (model.isNullOrBlank()) "" else ""","model":${model.json()}"""

    private suspend fun delete(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, body, url)
            }
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
                throw httpError(response.code, body, url)
            }
            parse(body)
        }
    }

    private suspend fun <T> patch(url: String, json: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .patch(json.toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, body, url)
            }
            parse(body)
        }
    }

    suspend fun getHostMetrics(origin: String): HostMetrics {
        return try {
            get("$origin/companion/host/metrics") { body ->
                val obj = Json.parseToJsonElement(body).jsonObject
                val metricsObj = obj["metrics"] ?: return@get HostMetrics()
                Json { ignoreUnknownKeys = true }.decodeFromJsonElement(HostMetrics.serializer(), metricsObj)
            }
        } catch (e: Exception) {
            // Fallback to native Hermes /api/system/stats
            get("$origin/api/system/stats") { body ->
                val obj = Json.parseToJsonElement(body).jsonObject
                val cpuPercent = obj["cpu_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val cpuCount = obj["cpu_count"]?.jsonPrimitive?.intOrNull ?: 1
                val loadAvg = obj["load_avg"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList()
                val memObj = obj["memory"]?.jsonObject
                val memTotal = memObj?.get("total")?.jsonPrimitive?.longOrNull ?: 0L
                val memUsed = memObj?.get("used")?.jsonPrimitive?.longOrNull ?: 0L
                val memAvail = memObj?.get("available")?.jsonPrimitive?.longOrNull ?: 0L
                val memPercent = memObj?.get("percent")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val diskObj = obj["disk"]?.jsonObject
                val diskTotal = diskObj?.get("total")?.jsonPrimitive?.longOrNull ?: 0L
                val diskUsed = diskObj?.get("used")?.jsonPrimitive?.longOrNull ?: 0L
                val diskFree = diskObj?.get("free")?.jsonPrimitive?.longOrNull ?: 0L
                val diskPercent = diskObj?.get("percent")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val os = obj["os"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val hostname = obj["hostname"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val arch = obj["arch"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pyVer = obj["python_version"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val uptime = obj["uptime_seconds"]?.jsonPrimitive?.longOrNull ?: 0L
                val procObj = obj["process"]?.jsonObject
                val pid = procObj?.get("pid")?.jsonPrimitive?.intOrNull ?: 0
                HostMetrics(
                    cpu = HostCpuMetrics(percent = cpuPercent, cores = cpuCount, loadAvg = loadAvg),
                    memory = HostMemoryMetrics(totalBytes = memTotal, usedBytes = memUsed, freeBytes = memAvail, percent = memPercent),
                    disk = HostDiskMetrics(totalBytes = diskTotal, usedBytes = diskUsed, freeBytes = diskFree, percent = diskPercent),
                    system = HostSystemMetrics(platform = "$os ($hostname)", release = arch, pythonVersion = pyVer, uptimeSeconds = uptime),
                    hermes = HostHermesProcess(pid = pid, status = "online"),
                )
            }
        }
    }

    suspend fun getGitStatus(origin: String, repoPath: String = ""): GitStatus {
        return try {
            get("$origin/companion/git/status") { body ->
                Json { ignoreUnknownKeys = true }.decodeFromString(GitStatus.serializer(), body)
            }
        } catch (e: Exception) {
            val query = if (repoPath.isNotBlank()) "?path=$repoPath" else ""
            get("$origin/api/git/status$query") { body ->
                val obj = Json.parseToJsonElement(body).jsonObject
                val branch = obj["branch"]?.jsonPrimitive?.contentOrNull ?: "main"
                val files = obj["files"]?.jsonArray ?: emptyList()
                val staged = mutableListOf<String>()
                val modified = mutableListOf<String>()
                val untracked = mutableListOf<String>()
                for (f in files) {
                    val fObj = f.jsonObject
                    val path = fObj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (fObj["staged"]?.jsonPrimitive?.booleanOrNull == true) staged.add(path)
                    if (fObj["untracked"]?.jsonPrimitive?.booleanOrNull == true) untracked.add(path)
                    else if (fObj["unstaged"]?.jsonPrimitive?.booleanOrNull == true) modified.add(path)
                }
                GitStatus(branch = branch, stagedFiles = staged, modifiedFiles = modified, untrackedFiles = untracked)
            }
        }
    }

    suspend fun getGitDiff(origin: String, file: String? = null, staged: Boolean = false): GitDiffSummary =
        get(buildString {
            append("$origin/companion/git/diff?staged=$staged")
            if (!file.isNullOrBlank()) append("&file=$file")
        }) { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(GitDiffSummary.serializer(), body)
        }

    suspend fun getGitBranches(origin: String): GitBranches =
        get("$origin/companion/git/branches") { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(GitBranches.serializer(), body)
        }

    suspend fun stageGitFile(origin: String, path: String, stage: Boolean = true): Boolean =
        post("$origin/companion/git/stage", """{"path":"$path","stage":$stage}""") { body ->
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        }

    suspend fun commitGit(origin: String, message: String): Boolean =
        post("$origin/companion/git/commit", """{"message":${message.json()}}""") { body ->
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        }

    suspend fun executeTerminalCommand(origin: String, cmd: String): TerminalExecResult =
        post("$origin/companion/terminal/exec", """{"cmd":${cmd.json()}}""") { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(TerminalExecResult.serializer(), body)
        }

    suspend fun listWorkspaceFiles(origin: String, path: String = ""): List<RemoteFsItem> =
        get("$origin/companion/fs/tree?path=$path") { body ->
            val obj = Json.parseToJsonElement(body).jsonObject
            val items = obj["items"]?.jsonArray ?: return@get emptyList()
            Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(RemoteFsItem.serializer()),
                items,
            )
        }

    suspend fun readWorkspaceFile(origin: String, path: String): String =
        get("$origin/companion/fs/read?path=$path") { body ->
            Json.parseToJsonElement(body).jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
        }

    suspend fun getCronJobs(origin: String): List<CronJob> =
        get("$origin/api/cron/jobs") { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(CronJob.serializer()),
                body,
            )
        }

    suspend fun triggerCronJob(origin: String, jobId: String): Boolean =
        post("$origin/api/cron/jobs/$jobId/trigger", "{}") { true }

    suspend fun toggleCronJob(origin: String, jobId: String, pause: Boolean): Boolean {
        val endpoint = if (pause) "pause" else "resume"
        return post("$origin/api/cron/jobs/$jobId/$endpoint", "{}") { true }
    }

    suspend fun getModelCatalog(origin: String): ModelCatalog =
        get("$origin/api/model/options") { body ->
            val obj = Json.parseToJsonElement(body).jsonObject
            val currentModel = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val currentProvider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val providers = obj["providers"]?.jsonArray ?: emptyList()
            val options = mutableListOf<ModelOption>()
            for (p in providers) {
                val pObj = p.jsonObject
                val pSlug = pObj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: pSlug
                val models = pObj["models"]?.jsonArray ?: emptyList()
                val caps = pObj["capabilities"]?.jsonObject
                for (m in models) {
                    val mName = m.jsonPrimitive.contentOrNull.orEmpty()
                    val mCaps = caps?.get(mName)?.jsonObject
                    val reasoning = mCaps?.get("reasoning")?.jsonPrimitive?.booleanOrNull ?: false
                    val fast = mCaps?.get("fast")?.jsonPrimitive?.booleanOrNull ?: false
                    options.add(ModelOption(id = mName, provider = pSlug, name = "$pName · $mName", reasoning = reasoning, fast = fast))
                }
            }
            ModelCatalog(currentModel = currentModel, currentProvider = currentProvider, models = options)
        }

    suspend fun switchModel(origin: String, model: String, provider: String, profile: String? = null): Boolean {
        val payload = """{"model":${model.json()},"provider":${provider.json()}}"""
        return if (!profile.isNullOrBlank()) {
            post("$origin/api/profiles/$profile/model", payload) { true }
        } else {
            post("$origin/api/model/set", payload) { true }
        }
    }

    suspend fun checkHermesUpdate(origin: String): HermesUpdateStatus =
        get("$origin/api/hermes/update/check") { body ->
            val obj = Json.parseToJsonElement(body).jsonObject
            val currentVer = obj["current_version"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val updateAvail = obj["update_available"]?.jsonPrimitive?.booleanOrNull ?: false
            val canApply = obj["can_apply"]?.jsonPrimitive?.booleanOrNull ?: false
            val behind = obj["behind"]?.jsonPrimitive?.intOrNull ?: 0
            val cmd = obj["update_command"]?.jsonPrimitive?.contentOrNull ?: "hermes update"
            val commits = obj["commits"]?.jsonArray
            val firstSummary = commits?.firstOrNull()?.jsonObject?.get("summary")?.jsonPrimitive?.contentOrNull.orEmpty()
            HermesUpdateStatus(
                currentVersion = currentVer,
                updateAvailable = updateAvail,
                canApply = canApply,
                behind = behind,
                summary = firstSummary,
                updateCommand = cmd,
            )
        }

    suspend fun applyHermesUpdate(origin: String): Boolean =
        post("$origin/api/hermes/update", "{}") { true }

    private fun httpError(code: Int, body: String, url: String): DashboardException {
        val err = runCatching {
            DashboardJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (code == 502 && err == "dashboard_unreachable") {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(':')
            return DashboardException("host_dashboard_down", "start hermes dashboard on $host")
        }
        val path = url.substringAfter("://").substringAfter("/", missingDelimiterValue = url)
        return DashboardException("http_$code", "http $code · /$path")
    }

    companion object {
        /** Most rows the rail pulls from one host per refresh (either path). Hermes caps history at 500; lists follow suit. */
        const val SESSION_PAGE = 500
        /** Dashboard `GET /api/sessions` refuses `limit > 100`. */
        const val REST_PAGE = 100

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
            val name = obj.str("name").ifBlank { obj.str("tool") }.ifBlank { obj.str("tool_name") }
            val rawArgs = obj?.get("args")?.toString() ?: obj?.get("arguments")?.toString().orEmpty()
            val detail = obj.str("detail").ifBlank { obj.str("context") }
                .ifBlank { obj.str("command") }.ifBlank { obj.str("input") }
                .ifBlank { extractToolArgsPreview(rawArgs) }
                .ifBlank { text }
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
