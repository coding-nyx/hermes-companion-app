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
import app.hermes.companion.model.CompanionAvailability
import app.hermes.companion.model.NativeVoiceStatus
import app.hermes.companion.model.SynthesizedSpeech
import app.hermes.companion.model.SpeechTranscript
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
import app.hermes.companion.model.SessionRuntimeSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val attachToken: Boolean,
) {
    @Volatile
    var sessionToken: String? = null

    @Volatile
    var gated: Boolean = false

    private val bootstrapRefreshLock = Mutex()
    private val credentialScope = HostCredentialScope()
    private val cookies = MemoryCookieJar(credentialScope::permits)

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

    fun bindOrigin(origin: String) = credentialScope.bind(origin)

    private fun hostMachine(origin: String, path: String): String {
        bindOrigin(origin)
        return DashboardUrls.machine(origin, path)
    }
    private fun hostRest(origin: String, path: String, profileId: String, extra: Map<String, String> = emptyMap()): String {
        bindOrigin(origin)
        return DashboardUrls.rest(origin, path, profileId, extra)
    }
    private fun hostWs(origin: String, profileId: String, ticket: String?, token: String?): String {
        bindOrigin(origin)
        return DashboardUrls.ws(origin, profileId, ticket, token)
    }
    private fun hostDeviceWs(origin: String): String {
        bindOrigin(origin)
        return DashboardUrls.deviceWs(origin)
    }

    private val rpcLock = Mutex()
    private val botSocketLock = Mutex()
    private val botSockets = ConcurrentHashMap<String, GatewaySocket>()
    private val deviceLock = Mutex()
    @Volatile private var rpc: GatewaySocket? = null
    @Volatile private var rpcKey: String? = null
    @Volatile private var deviceWs: DeviceSocket? = null
    private val liveByStored = ConcurrentHashMap<String, String>()
    private val historyOffsets = ConcurrentHashMap<String, Int>()
    private val runtimeSnapshots = ConcurrentHashMap<String, SessionRuntimeSnapshot>()
    private val retiredPrompts = LinkedHashMap<Pair<String, String>, Unit>()
    private val extensionLock = Mutex()
    private val extensionAvailability = ConcurrentHashMap<String, CompanionAvailability>()
    private val speechSupport = ConcurrentHashMap<Pair<String, String>, NativeSpeechSupport>()
    private val audioApiBase = ConcurrentHashMap<String, String>()
    private val extensionHttp by lazy {
        this.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS).build()
    }

    fun runtimeSnapshot(sessionId: String): SessionRuntimeSnapshot? = runtimeSnapshots[sessionId]
    fun legacyOperator(): Boolean = rpc?.hello?.instanceId == "companion-standalone"
    /** SSE turn stream: a tool call can sit silent for minutes, so no read timeout. */
    private val sseHttp: OkHttpClient by lazy {
        this.http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val wsHttp: OkHttpClient by lazy {
        this.http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(origin: String): DashboardStatus =
        get(hostMachine(origin, "/api/status")) { parseStatus(it) }

    fun invalidateCompanionAvailability(origin: String) {
        extensionAvailability.remove(origin.trimEnd('/'))
    }

    /** A missing optional integration must never block native chat. Cache conclusive results only. */
    suspend fun companionAvailability(origin: String): CompanionAvailability = extensionLock.withLock {
        val key = origin.trimEnd('/')
        extensionAvailability[key]?.let { return@withLock it }
        val availability = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(hostMachine(origin, "/companion/health"))
                    .header("Accept", "application/json").build()
                extensionHttp.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 || response.code == 405 -> CompanionAvailability.UNAVAILABLE
                        !response.isSuccessful -> CompanionAvailability.UNKNOWN
                        else -> {
                            val body = response.body?.string().orEmpty()
                            // Native dashboards may serve the SPA with HTTP 200 for unknown GET paths.
                            val obj = runCatching { DashboardJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                            when {
                                (obj?.get("relay") as? JsonPrimitive)?.contentOrNull == "ok" -> CompanionAvailability.AVAILABLE
                                body.trimStart().startsWith("<") -> CompanionAvailability.UNAVAILABLE
                                else -> CompanionAvailability.UNKNOWN
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                CompanionAvailability.UNKNOWN
            }
        }
        currentCoroutineContext().ensureActive()
        if (availability != CompanionAvailability.UNKNOWN) extensionAvailability[key] = availability
        availability
    }

    suspend fun companionHealth(origin: String): CompanionHealth =
        get(hostMachine(origin, "/companion/health")) { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            CompanionHealth(
                relay = obj["relay"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                mode = obj["mode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                upstream = obj["upstream"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hermesVersion = obj["hermes_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                profilesDir = obj["profiles_dir"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                drift = obj["drift"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                connectionSettings = obj["connection_settings"]?.jsonPrimitive?.booleanOrNull == true,
                modeSource = obj["mode_source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    suspend fun setConnectionMode(origin: String, mode: String, expectedMode: String) {
        require(mode in setOf("proxy", "standalone") && expectedMode in setOf("proxy", "standalone"))
        post(hostMachine(origin, "/companion/connection/mode"), buildJsonObject {
            put("mode", mode); put("expected_mode", expectedMode)
        }.toString()) { body ->
            val result = DashboardJson.parseToJsonElement(body).jsonObject
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true || result["mode"]?.jsonPrimitive?.contentOrNull != mode)
                throw DashboardException("connection_mode", "The host did not confirm the new connection mode. Refresh and check before retrying.")
        }
    }

    private fun audioPath(origin: String, path: String): String =
        (audioApiBase[origin.trimEnd('/')] ?: "/api") + path.removePrefix("/api")

    suspend fun audioSettings(origin: String, profileId: String): app.hermes.companion.model.HostVoiceSettings {
        fun decode(body: String): app.hermes.companion.model.HostVoiceSettings {
            val settings = parseAudioSettings(body)
            val base = DashboardJson.parseToJsonElement(body).jsonObject.str("api_base")
            // The host may only select its own known plugin namespace, never a URL.
            if (base == "/api/plugins/hermes-companion") audioApiBase[origin.trimEnd('/')] = base
            return settings
        }
        return try { get(hostRest(origin, audioPath(origin, "/api/audio/settings"), profileId), ::decode) }
        catch (error: DashboardException) {
            if (error.code !in setOf("http_404", "http_405")) throw error
            get(hostRest(origin, "/api/plugins/hermes-companion/audio/settings", profileId), ::decode)
        }
    }

    suspend fun audioVoiceCatalog(origin: String, profileId: String, provider: String): List<String> =
        get(hostRest(origin, audioPath(origin, "/api/audio/settings/voices"), profileId, mapOf("provider" to provider))) { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            (obj["voices"] as? JsonArray).orEmpty().take(5000).map { it.jsonPrimitive.content }
        }

    suspend fun beginSpeechSnapshot(origin: String, profileId: String): String =
        post(hostRest(origin, audioPath(origin, "/api/audio/settings/snapshot"), profileId), "{}") { body ->
            val token = DashboardJson.parseToJsonElement(body).jsonObject.str("snapshot")
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Invalid speech snapshot" }
            token
        }
    suspend fun endSpeechSnapshot(origin: String, profileId: String, snapshot: String) {
        require(snapshot.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        delete(hostRest(origin, audioPath(origin, "/api/audio/settings/snapshot/$snapshot"), profileId))
    }
    private fun speechSnapshotQuery(snapshot: String) = if (snapshot.isBlank()) emptyMap() else mapOf("voice_snapshot" to snapshot)

    suspend fun saveAudioSettings(origin: String, profileId: String, changes: Map<String, String>, numeric: Set<String>): app.hermes.companion.model.HostVoiceSettings {
        require(changes.isNotEmpty())
        val values = JsonObject(changes.mapValues { (key, value) ->
            if (key in numeric) JsonPrimitive(value.toDouble().also { require(it.isFinite()) }) else JsonPrimitive(value)
        })
        return patch(hostRest(origin, audioPath(origin, "/api/audio/settings"), profileId), JsonObject(mapOf("changes" to values)).toString(), ::parseAudioSettings)
    }

    private fun parseAudioSettings(body: String): app.hermes.companion.model.HostVoiceSettings {
        if (body.trimStart().startsWith("<")) throw DashboardException("http_404", "Native audio settings are not supported on this host")
        val obj = DashboardJson.parseToJsonElement(body).jsonObject
        require(obj["ok"]?.jsonPrimitive?.booleanOrNull == true && obj["version"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported host voice settings" }
        val fields = (obj["fields"] as? JsonArray).orEmpty().map { value ->
            val field = value.jsonObject
            app.hermes.companion.model.VoiceSettingField(field.str("key"), field["value"]?.jsonPrimitive?.content.orEmpty(),
                (field["options"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }, field.str("type") == "number")
        }
        val live = obj["live"] as? JsonObject ?: JsonObject(emptyMap())
        val speech = obj["speech"] as? JsonObject ?: JsonObject(emptyMap())
        return app.hermes.companion.model.HostVoiceSettings(fields,
            NativeVoiceStatus(live.str("mode"), (live["available"] as? JsonPrimitive)?.booleanOrNull,
                live.str("reason"), live.str("model"), live.str("voice")),
            obj["streaming"]?.jsonPrimitive?.booleanOrNull == true, editable = true, snapshots = obj["snapshots"]?.jsonPrimitive?.booleanOrNull == true,
            speechAvailable = speech["available"]?.jsonPrimitive?.booleanOrNull, speechReason = speech.str("reason"),
            sttProvider = speech.str("stt_provider"), ttsProvider = speech.str("tts_provider"))
    }

    /** Native audio APIs keep provider credentials on the host. Never query voice-config keys. */
    suspend fun voiceLiveStatus(origin: String, profileId: String): NativeVoiceStatus =
        get(hostRest(origin, audioPath(origin, "/api/audio/voice-live/status"), profileId)) { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            NativeVoiceStatus(obj.str("mode"), (obj["available"] as? JsonPrimitive)?.booleanOrNull,
                obj.str("reason"), obj.str("model"), obj.str("voice"))
        }

    /** One SDP exchange; failures never replay creation or switch providers. */
    suspend fun createRealtimeSession(origin: String, profileId: String, sdp: String,
        history: JsonArray = JsonArray(emptyList()), snapshot: String = ""): app.hermes.companion.model.NativeRealtimeAnswer {
        require(profileId.isNotBlank())
        require(sdp.startsWith("v=0") && sdp.length <= 262_144 && sdp.contains("m=audio")) { "Valid audio SDP offer required" }
        require(history.size <= 200 && history.toString().length <= 1_000_000) { "Call history is too large" }
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("sdp", JsonPrimitive(sdp)); put("history", history)
        }.toString()
        return post(hostRest(origin, audioPath(origin, "/api/audio/voice-live/session"), profileId, speechSnapshotQuery(snapshot)), payload) { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            val transport = obj["transport"] as? JsonObject
            val answer = (transport?.get("sdp") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = ((obj["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull == false || transport?.get("type")?.jsonPrimitive?.contentOrNull != "webrtc" ||
                id.isBlank() || id.length > 256 || !answer.startsWith("v=0") || !answer.contains("m=audio") || answer.length > 262_144)
                throw DashboardException("realtime_invalid", "Host returned an invalid WebRTC session")
            app.hermes.companion.model.NativeRealtimeAnswer(id, answer)
        }
    }

    suspend fun openSpeechStream(origin: String, profileId: String, snapshot: String = ""): NativeSpeechStream {
        bindOrigin(origin)
        require(profileId.isNotBlank()) { "speech profile required" }
        // Match ensureRpc: gated hosts accept tickets; ungated loopback hosts accept their
        // bootstrap token and explicitly reject tickets. Do not retry a rejected credential.
        val credential = if (gated) mapOf("ticket" to mintWsTicket(origin)) else {
            val token = sessionToken?.takeIf { it.isNotBlank() }
                ?: throw DashboardException("speech_stream_auth", "Authenticate the host before using speech")
            mapOf("token" to token)
        }
        val url = hostRest(origin, audioPath(origin, "/api/audio/speak-stream"), profileId, credential + speechSnapshotQuery(snapshot))
        // A ticket and host credentials must never follow a proxy redirect to another origin.
        // A PCM reader may be backpressured longer than a ping interval. The speech coordinator
        // owns first-audio and post-finish idle deadlines, so this socket does not race them.
        val streamHttp = this.http.newBuilder().followRedirects(false).followSslRedirects(false)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(0, TimeUnit.MILLISECONDS).build()
        val opened = OkHttpNativeSpeechStream(streamHttp, Request.Builder().url(url).build())
        val key = origin.trim().trimEnd('/') to profileId.trim()
        return object : NativeSpeechStream by opened {
            override val events = opened.events.onEach { event ->
                when (event) {
                    SpeechStreamEvent.Ready -> speechSupport[key] = NativeSpeechSupport.SUPPORTED
                    SpeechStreamEvent.Unsupported -> speechSupport[key] = NativeSpeechSupport.UNSUPPORTED
                    else -> Unit // Auth, network, malformed data and provider failures do not establish absence.
                }
            }
        }
    }

    fun speechStreamSupport(origin: String, profileId: String): NativeSpeechSupport =
        speechSupport[origin.trim().trimEnd('/') to profileId.trim()] ?: NativeSpeechSupport.UNKNOWN

    suspend fun synthesizeSpeech(origin: String, profileId: String, text: String, snapshot: String = ""): SynthesizedSpeech {
        require(text.isNotBlank()) { "speech text required" }
        return post(hostRest(origin, audioPath(origin, "/api/audio/speak"), profileId, speechSnapshotQuery(snapshot)), """{"text":${text.json()}}""") { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            val data = obj.str("data_url")
            val header = data.substringBefore(",")
            val encoded = data.substringAfter(",", "")
            if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull != true || !header.startsWith("data:audio/") ||
                !header.endsWith(";base64") || encoded.isBlank() || encoded.length > 35_000_000) {
                throw DashboardException("audio_invalid", "Hermes returned no valid speech audio")
            }
            val bytes = try { java.util.Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw DashboardException("audio_invalid", "Hermes returned invalid speech audio") }
            if (bytes.isEmpty() || bytes.size > 25 * 1024 * 1024) throw DashboardException("audio_invalid", "Speech audio is empty or too large")
            SynthesizedSpeech(bytes, header.removePrefix("data:").removeSuffix(";base64"), obj.str("provider"))
        }
    }

    suspend fun transcribeAudio(origin: String, profileId: String, bytes: ByteArray, mime: String, snapshot: String = ""): SpeechTranscript {
        require(bytes.isNotEmpty() && bytes.size <= 25 * 1024 * 1024) { "recording must be between 1 byte and 25 MB" }
        require(mime.startsWith("audio/") && !mime.contains(';') && !mime.contains(',')) { "audio MIME type required" }
        val data = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        return post(hostRest(origin, audioPath(origin, "/api/audio/transcribe"), profileId, speechSnapshotQuery(snapshot)),
            """{"data_url":${data.json()},"mime_type":${mime.json()}}""") { body ->
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw DashboardException("transcription_failed", obj.str("error").ifBlank { "Speech transcription failed" })
            SpeechTranscript(obj.str("transcript"), obj.str("provider")) // Empty speech is a successful silence result.
        }
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
            post(hostMachine(origin, "/companion/media"), payload) { body ->
                val obj = DashboardJson.parseToJsonElement(body).jsonObject
                val path = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (path.startsWith("http")) path else origin.trimEnd('/') + path
            }
        } catch (e: DashboardException) {
            if (e.code != "http_404") throw e
            post(hostMachine(origin, "/api/chat/image-upload"), payload) { body ->
                val obj = DashboardJson.parseToJsonElement(body).jsonObject
                val path = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifBlank { obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                if (path.startsWith("http")) path else origin.trimEnd('/') + path
            }
        }
    }

    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        // Media GET may follow redirects; the network guard removes host credentials on every
        // hop outside the connection scope. Host mutations and ticket sockets do not redirect.
        http.newBuilder().followRedirects(true).followSslRedirects(true).build().newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw httpError(response.code, bytes.decodeToString(), url)
            }
            bytes
        }
    }

    fun useLoopback() {
        closeBotSockets()
        gated = false
        cookies.clear()
        speechSupport.clear()
    }

    fun useGated() {
        closeBotSockets()
        gated = true
        sessionToken = null
        speechSupport.clear()
    }

    /** Loopback dashboards inject an ephemeral token into the SPA. Adopt it. */
    suspend fun adoptLoopbackToken(origin: String): String? {
        if (gated) return null
        val html = runCatching { getHtml(hostMachine(origin, "/")) }.getOrNull() ?: return null
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
        bindOrigin(origin)
        useGated()
        post(
            hostMachine(origin, "/auth/password-login"),
            AuthPolicy.jsonLogin(provider, username, password),
        ) { }
    }

    suspend fun offerPair(origin: String, code: String, profileId: String): PairingStatus {
        val id = ProfileScope.requireProfileId(profileId)
        val payload = """{"code":${code.json()},"profile":${id.json()},"protocol_version":1}"""
        return post(hostMachine(origin, "/companion/device/pair"), payload) { parsePairing(it) }
    }

    suspend fun pollPair(origin: String, code: String): PairingStatus =
        get(hostMachine(origin, "/companion/device/pair/${code.trim()}")) { parsePairing(it) }

    suspend fun approvePair(origin: String, code: String): PairingStatus {
        post(hostMachine(origin, "/companion/device/pair/${code.trim()}/approve"), "{}") { }
        return pollPair(origin, code)
    }

    suspend fun revokeDevice(origin: String, deviceId: String) {
        val payload = """{"device_id":${deviceId.json()}}"""
        post(hostMachine(origin, "/companion/device/revoke"), payload) { }
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
        bindOrigin(origin)
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
        return post(hostMachine(origin, "/companion/device/register"), payload) { parseDeviceTicket(it) }
    }

    suspend fun renameDevice(origin: String, deviceId: String, name: String) {
        val payload = """{"device_id":${deviceId.json()},"name":${name.json()}}"""
        post(hostMachine(origin, "/companion/device/rename"), payload) { }
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
        bindOrigin(origin)
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
            socket.connect(hostDeviceWs(origin), DeviceLanePolicy.protocolHeader(ticket.ticket))
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
        val url = hostMachine(origin, "/api/auth/ws-ticket")
        val ticketHttp = http.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = ticketHttp.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(
                        DashboardException("ticket_connection", "Could not authenticate the host WebSocket")))
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    val result = runCatching { response.use {
                        val body = response.peekBody(65_537).string()
                        if (body.length > 65_536) throw DashboardException("ticket", "Invalid WebSocket ticket response")
                        if (!response.isSuccessful) throw httpError(response.code, body, url)
                        parseWsTicket(body).also { ticket ->
                            if (ticket.isBlank()) throw DashboardException("ticket", "empty ws ticket")
                        }
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    /** Machine roster. Not profile-scoped — otherwise the switcher would only see the active agent. */
    suspend fun listProfiles(origin: String): List<ProfileRef> =
        get(hostMachine(origin, "/api/profiles")) { parseProfiles(it) }

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
        bindOrigin(origin)
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
        bindOrigin(origin)
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
        bindOrigin(origin)
        runtimeSnapshots[sessionId]?.approval?.takeUnless { promptRetired(sessionId, it.requestId) }?.let { return it }
        val url = hostRest(origin, "/api/sessions/$sessionId/approval", profileId)
        return try {
            get(url) { parseApproval(it)?.takeUnless { prompt -> promptRetired(sessionId, prompt.requestId) } }
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
        bindOrigin(origin)
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
                    """{"session_id":${live.json()},"profile":${profileId.json()},"request_id":${prompt.requestId.json()},"choice":${decision.json()}}"""
            }
            try {
                val result = socket.request(method, params)
                val resolved = result["resolved"] as? JsonPrimitive
                if (resolved?.intOrNull == 0 || resolved?.booleanOrNull == false || result.str("status") == "expired") {
                    retirePrompt(sessionId, prompt.requestId)
                    throw DashboardException("request_expired", "This request is no longer pending")
                }
                retirePrompt(sessionId, prompt.requestId)
                return
            } catch (e: DashboardException) {
                if (e.code != "rpc_-32601") throw e
            }
        }
        val url = hostRest(origin, "/api/sessions/$sessionId/approval", profileId)
        val payload = """{"request_id":${prompt.requestId.json()},"decision":${decision.json()},"choice":${decision.json()}}"""
        post(url, payload) { it }
        retirePrompt(sessionId, prompt.requestId)
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
                decodeBus(ev, profileId)?.let { send(it) }
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
        runtimeSnapshots.clear()
    }

    fun matchesSession(storedOrLive: String?, other: String?): Boolean {
        if (storedOrLive.isNullOrBlank() || other.isNullOrBlank()) return false
        if (storedOrLive == other) return true
        return liveSessionId(storedOrLive) == other || liveSessionId(other) == storedOrLive
    }

    internal fun decodeBus(ev: RpcEvent, profileId: String): BusFrame? {
        if (ev.type == "sessions.changed") {
            val payload = ev.payload ?: return BusFrame.SessionRefetch
            val change = parseSessionChange(payload, "") ?: return BusFrame.SessionRefetch
            if (change.session.profileId.isBlank()) return BusFrame.SessionRefetch
            return BusFrame.SessionPatch(change)
        }
        val chat = RpcCodec.toChatEvent(ev) ?: return null
        if (chat is ChatEvent.PromptExpired && ev.sessionId != null) retirePrompt(ev.sessionId, chat.requestId)
        if (chat is ChatEvent.Approval && ev.sessionId != null && promptRetired(ev.sessionId, chat.prompt.requestId)) return null
        return BusFrame.Chat(chat, ev.sessionId)
    }

    private fun promptRetired(session: String, request: String): Boolean = synchronized(retiredPrompts) {
        retiredPrompts.keys.any { (sid, id) -> id == request && matchesSession(sid, session) }
    }
    fun isPromptRetired(session: String, request: String): Boolean = promptRetired(session, request)
    private fun retirePrompt(session: String, request: String) {
        synchronized(retiredPrompts) {
            retiredPrompts[session to request] = Unit
            while (retiredPrompts.size > 256) retiredPrompts.remove(retiredPrompts.keys.first())
        }
        runtimeSnapshots.entries.forEach { entry ->
            val snapshot = entry.value
            if (matchesSession(entry.key, session) && snapshot.approval?.requestId == request)
                runtimeSnapshots.replace(entry.key, snapshot, snapshot.copy(approval = null))
        }
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
        runtimeSnapshots.clear()
    }

    /** Bot/group operations never replace the operator chat's socket or its live-session map. */
    internal suspend fun botRequest(origin: String, profile: String, method: String, params: JsonObject): JsonObject {
        ProfileScope.requireProfileId(profile)
        val socket = botSocketLock.withLock {
            bindOrigin(origin)
            botSockets[profile]?.takeIf { it.isOpen } ?: GatewaySocket(wsHttp).also { next ->
                val ticket = if (gated) mintWsTicket(origin) else null
                next.connect(hostWs(origin, profile, ticket, if (ticket == null) sessionToken else null))
                botSockets.put(profile, next)?.close()
            }
        }
        return socket.request(method, JsonObject(params + ("profile" to JsonPrimitive(profile))).toString())
    }

    fun closeBotSockets() {
        botSockets.values.forEach { it.close() }
        botSockets.clear()
    }

    suspend fun createSession(
        origin: String,
        profileId: String,
        title: String = "",
        model: String? = null,
        idempotencyKey: String = "",
        requireRpc: Boolean = false,
    ): SessionRef {
        bindOrigin(origin)
        val extra = modelField(model)
        val keyField = if (idempotencyKey.isBlank()) "" else ""","idempotency_key":${idempotencyKey.json()}"""
        val socket = if (requireRpc) ensureRpc(origin, profileId) else rpc
        if (socket != null && socket.isOpen) {
            val basic = """{"profile":${profileId.json()},"title":${title.json()}$extra}"""
            val result = try {
                socket.request("session.create", basic.dropLast(1) + "$keyField}")
            } catch (e: DashboardException) {
                // A validation refusal did not create anything, so an older server can safely
                // retry without the new optional field. Never do this after a lost response.
                if (keyField.isBlank() || !optionalKeyRejected("session.create", e)) throw e
                socket.request("session.create", basic)
            }
            val live = result.str("session_id").ifBlank { result.str("id") }
            val created = parseRpcSession(result, profileId)
            rememberLive(created.id, live.ifBlank { created.id })
            return created
        }
        val url = hostRest(origin, "/api/sessions", profileId)
        val payload = """{"profile":${profileId.json()},"title":${title.json()}$extra}"""
        return post(url, payload) { parseCreatedSession(it, profileId) }
    }

    suspend fun deleteSession(origin: String, sessionId: String, profileId: String) {
        ProfileScope.requireProfileId(profileId)
        delete(hostRest(origin, "/api/sessions/$sessionId", profileId))
    }

    suspend fun updateSession(origin: String, session: SessionRef, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null): SessionRef {
        val fields = buildList {
            title?.let { add("\"title\":${it.json()}") }
            pinned?.let { add("\"pinned\":$it") }
            archived?.let { add("\"archived\":$it") }
        }
        require(fields.isNotEmpty())
        return patch(hostRest(origin, "/api/sessions/${session.id}", session.profileId), "{${fields.joinToString(",")}}") { body ->
            val result = DashboardJson.parseToJsonElement(body).jsonObject
            session.copy(
                title = title?.let { result.str("title").ifBlank { it } } ?: session.title,
                pinned = pinned?.let { (result["pinned"] as? JsonPrimitive)?.booleanOrNull ?: it } ?: session.pinned,
                archived = archived?.let { (result["archived"] as? JsonPrimitive)?.booleanOrNull ?: it } ?: session.archived,
            )
        }
    }

    suspend fun branchSession(origin: String, session: SessionRef, idempotencyKey: String): SessionRef {
        val socket = ensureRpc(origin, session.profileId)
        val basic = """{"profile":${session.profileId.json()},"parent_session_id":${session.id.json()}}"""
        val result = try {
            socket.request("session.branch_stored", basic.dropLast(1) + ""","idempotency_key":${idempotencyKey.json()}}""",
                timeoutMs = 120_000)
        } catch (e: DashboardException) {
            // Older hosts explicitly reject the optional key before doing any work.
            // Never retry a timeout/disconnect or an unrelated rejection of the branch.
            if (!optionalKeyRejected("session.branch_stored", e)) throw e
            socket.request("session.branch_stored", basic, timeoutMs = 120_000)
        }
        val created = parseRpcSession(result, session.profileId)
        rememberLive(created.id, result.str("session_id"))
        return created.copy(title = "Branch of ${session.title}")
    }

    private fun optionalKeyRejected(method: String, error: DashboardException): Boolean {
        if (error.code == "rpc_-32602") return true // JSON-RPC invalid parameters, before dispatch.
        if (error.code != "rpc_4000") return false
        val message = error.message.orEmpty().lowercase()
        val methodPattern = Regex.escape(method)
        val shortRejection = Regex(
            """^(unknown|unexpected|unsupported|extra) (parameter|param|field|input)(s|\(s\))?( for $methodPattern)?: ['\"\[]?idempotency_key['\"\]]?$"""
        ).matches(message) || message == "idempotency_key: extra inputs are not permitted"
        // Installed Hermes contracts/registry.py emits this exact extra_forbidden response;
        // rpc_dispatch.py returns it before invoking the mutation handler. A mention of the
        // key in an arbitrary provider/business error is not proof of non-delivery.
        val validation = "invalid params for $method: idempotency_key: extra inputs are not permitted"
        val versionHint = " — the client and the hermes backend are out of sync (different versions); run `hermes update` and restart both"
        return shortRejection || message == validation || message == validation + versionHint
    }

    suspend fun sessionContext(origin: String, session: SessionRef): app.hermes.companion.model.SessionContext {
        val socket = ensureRpc(origin, session.profileId)
        if (liveByStored[session.id] == null) resumeSession(session.id, session.profileId)
        return parseSessionContext(socket.request("session.context_breakdown",
            """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()}}"""))
    }

    /** Dedicated mutation, deliberately no retries or slash fallback on uncertain delivery.
     * Not exposed in the operator UI until pending/late acknowledgments are reconciled. */
    suspend fun compressSession(origin: String, session: SessionRef, focusTopic: String = ""): app.hermes.companion.model.SessionCompression {
        val socket = ensureRpc(origin, session.profileId)
        if (liveByStored[session.id] == null) resumeSession(session.id, session.profileId)
        val topic = focusTopic.trim().takeIf { it.isNotEmpty() }?.let { ",\"focus_topic\":${it.json()}" }.orEmpty()
        return parseSessionCompression(socket.request("session.compress",
            """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()}$topic}""",
            timeoutMs = 630_000))
    }

    private suspend fun nativeSession(origin: String, session: SessionRef): GatewaySocket {
        require(app.hermes.companion.domain.DraftThread.isPersisted(session.id)) { "Create a host thread first" }
        ProfileScope.requireProfileId(session.profileId)
        val socket = ensureRpc(origin, session.profileId)
        if (liveByStored[session.id] == null) resumeSession(session.id, session.profileId)
        return socket
    }

    suspend fun sessionSettings(origin: String, session: SessionRef): app.hermes.companion.model.NativeSessionSettings {
        val socket = nativeSession(origin, session)
        val fields = "\"session_id\":${liveSessionId(session.id).json()},\"profile\":${session.profileId.json()}"
        val model = socket.request("model.options", "{$fields,\"explicit_only\":true}")
        val reasoning = socket.request("config.get", "{$fields,\"key\":\"reasoning\"}")
        val fast = socket.request("config.get", "{$fields,\"key\":\"fast\"}")
        return app.hermes.companion.model.NativeSessionSettings(model.str("model").takeIf { it.isNotBlank() },
            model.str("provider").takeIf { it.isNotBlank() }, reasoning.str("value").takeIf { it.isNotBlank() },
            fast.str("value").takeIf { it.isNotBlank() }, parseModelCatalog(model).models)
    }

    suspend fun setSessionSetting(origin: String, session: SessionRef, key: String, value: String, confirmed: Boolean = false): app.hermes.companion.model.NativeConfigResult {
        require(key in setOf("model", "reasoning", "fast")) { "Unsupported session setting" }
        val socket = nativeSession(origin, session)
        val result = socket.request("config.set", """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()},"scope":"session","key":${key.json()},"value":${value.json()},"confirm_expensive_model":$confirmed}""")
        return app.hermes.companion.model.NativeConfigResult(result.str("key"), result.str("value").takeIf { it.isNotBlank() },
            result.str("scope").takeIf { it.isNotBlank() }, (result["deferred"] as? JsonPrimitive)?.booleanOrNull == true,
            (result["confirm_required"] as? JsonPrimitive)?.booleanOrNull == true, result.str("confirm_message"), result.str("warning"))
    }

    suspend fun commandCatalog(origin: String, session: SessionRef): List<app.hermes.companion.model.NativeCommand> {
        val socket = nativeSession(origin, session)
        val result = socket.request("commands.catalog", """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()}}""")
        return (result["pairs"] as? JsonArray).orEmpty().mapNotNull { pair ->
            val row = pair as? JsonArray ?: return@mapNotNull null
            val name = (row.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("/") ?: return@mapNotNull null
            if (name.isBlank() || name.any(Char::isWhitespace)) return@mapNotNull null
            app.hermes.companion.model.NativeCommand(name, (row.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty())
        }.distinctBy { it.name }
    }

    suspend fun dispatchCommand(origin: String, session: SessionRef, name: String, argument: String): app.hermes.companion.model.NativeCommandResult {
        require(name.isNotBlank() && name.none(Char::isWhitespace))
        val socket = nativeSession(origin, session)
        val params = """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()}}"""
        val command = "/$name" + if (argument.isBlank()) "" else " $argument"
        val result = try {
            socket.request("slash.exec", params.dropLast(1) + ",\"command\":${command.json()}}")
        } catch (error: DashboardException) {
            // Only an explicit routing refusal/method absence proves nothing ran.
            if (error.code != "rpc_-32601" && !(error.code.startsWith("rpc_") && error.message.orEmpty().contains("use command.dispatch"))) throw error
            socket.request("command.dispatch", """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()},"name":${name.json()},"arg":${argument.json()}}""")
        }
        return app.hermes.companion.model.NativeCommandResult(result.str("type").ifBlank { "exec" }, result.str("output"), result.str("message"),
            result.str("display"), result.str("target"), result.str("notice"))
    }

    suspend fun redirectSession(origin: String, session: SessionRef, text: String): app.hermes.companion.model.NativeRedirectResult {
        require(text.isNotBlank())
        val socket = nativeSession(origin, session)
        val result = socket.request("session.redirect", """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()},"text":${text.json()}}""")
        return app.hermes.companion.model.NativeRedirectResult(result.str("status"), result.str("text"))
    }

    suspend fun steerSession(origin: String, session: SessionRef, text: String) {
        val socket = ensureRpc(origin, session.profileId)
        if (liveByStored[session.id] == null) resumeSession(session.id, session.profileId)
        val result = socket.request("session.steer",
            """{"session_id":${liveSessionId(session.id).json()},"profile":${session.profileId.json()},"text":${text.json()}}""")
        if (result.str("status") == "rejected") throw DashboardException("steer_rejected", "Hermes could not steer this turn")
    }

    /** Stage attachments with the actual native contract; never pass phone-local URLs to Hermes. */
    suspend fun stageAttachment(origin: String, sessionId: String, profileId: String, bytes: ByteArray, name: String, mime: String): String {
        require(bytes.isNotEmpty()) { "attachment is empty" }
        val socket = ensureRpc(origin, profileId)
        if (liveByStored[sessionId] == null) resumeSession(sessionId, profileId)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val sid = liveSessionId(sessionId)
        val image = mime.startsWith("image/")
        val payload = if (image) {
            """{"session_id":${sid.json()},"profile":${profileId.json()},"content_base64":${encoded.json()},"filename":${name.json()}}"""
        } else {
            """{"session_id":${sid.json()},"profile":${profileId.json()},"data_url":${("data:$mime;base64,$encoded").json()},"name":${name.json()}}"""
        }
        val result = socket.request(if (image) "image.attach_bytes" else "file.attach", payload, timeoutMs = 90_000)
        if ((result["attached"] as? JsonPrimitive)?.booleanOrNull != true) throw DashboardException("attachment_failed", "Hermes did not attach $name")
        if (image) return ""
        return result.str("ref_text").ifBlank {
            val path = result.str("ref_path")
            if (path.isBlank()) throw DashboardException("attachment_failed", "Hermes returned no reference for $name")
            "@file:$path"
        }
    }

    suspend fun interruptTurn(origin: String, sessionId: String, profileId: String) {
        val socket = ensureRpc(origin, profileId)
        if (liveByStored[sessionId] == null) resumeSession(sessionId, profileId)
        val live = liveSessionId(sessionId)
        socket.request("session.interrupt", """{"session_id":${live.json()},"profile":${profileId.json()}}""")
    }

    private fun voiceFields(voice: app.hermes.companion.model.VoiceTurnMetadata?): String =
        if (voice == null) "" else ",\"surface\":${voice.surface.json()},\"voice_context\":${voice.context.takeLast(6000).json()}"

    fun streamTurn(
        origin: String,
        sessionId: String,
        profileId: String,
        text: String,
        rewind: RewindSubmit? = null,
        model: String? = null,
        partsJson: String = "",
        /**
         * Legacy SSE id only. Native prompt.submit rejects this field, so native delivery uses
         * explicit acknowledgments and durable-row reconciliation rather than guessing support.
         */
        clientId: String = "",
        voice: app.hermes.companion.model.VoiceTurnMetadata? = null,
    ): Flow<ChatEvent> {
        bindOrigin(origin)
        val socket = rpc
        if (rewind != null) {
            if (socket == null || !socket.isOpen) {
                return flow { throw DashboardException("rpc_required", "rewind needs live gateway") }
            }
            return streamTurnRpc(socket, sessionId, profileId, text, rewind, model, partsJson, clientId, voice)
        }
        if (socket != null && socket.isOpen) {
            return streamTurnRpc(socket, sessionId, profileId, text, model = model, partsJson = partsJson, clientId = clientId, voice = voice)
        }
        return streamTurnSse(origin, sessionId, profileId, text, model, partsJson, clientId, voice)
    }

    private fun streamTurnSse(
        origin: String,
        sessionId: String,
        profileId: String,
        text: String,
        model: String? = null,
        partsJson: String = "",
        clientId: String = "",
        voice: app.hermes.companion.model.VoiceTurnMetadata? = null,
    ): Flow<ChatEvent> = flow {
        val url = hostRest(origin, "/api/sessions/$sessionId/chat/stream", profileId)
        val idField = if (clientId.isBlank()) "" else ",\"client_id\":${clientId.json()}"
        val voiceFields = voiceFields(voice)
        val payload = """{"input":${text.json()},"profile":${profileId.json()}${modelField(model)}$idField$partsJson$voiceFields}"""
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
        clientId: String = "",
        voice: app.hermes.companion.model.VoiceTurnMetadata? = null,
    ): Flow<ChatEvent> = callbackFlow {
        if (liveByStored[sessionId] == null) {
            resumeSession(sessionId, profileId)
        }
        val bound = liveSessionId(sessionId)
        val finished = CompletableDeferred<Unit>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.events.collect { ev ->
                if (!ev.belongsTo(sessionId, bound)) return@collect
                val chat = RpcCodec.toChatEvent(ev) ?: return@collect
                send(chat)
                if ((chat is ChatEvent.Completed || chat is ChatEvent.Final) && !finished.isCompleted) {
                    finished.complete(Unit)
                }
            }
        }
        val disconnectJob = launch {
            socket.awaitDisconnect()
            close(DashboardException("ws_closed", "connection lost; checking delivery on reconnect"))
        }
        val submitJob = launch {
            try {
                val extra = RewindPolicy.jsonExtras(rewind)
                val legacyFields = if (socket.hello?.instanceId == "companion-standalone") {
                    modelField(model) + partsJson + if (clientId.isBlank()) "" else ""","client_id":${clientId.json()}"""
                } else ""
                val voiceFields = voiceFields(voice)
                val result = socket.request(
                    "prompt.submit",
                    // Native Hermes forbids unknown params. Models belong to the session;
                    // images/files are staged through native attachment RPCs before submit.
                    """{"session_id":${bound.json()},"text":${text.json()},"profile":${profileId.json()}$extra$legacyFields$voiceFields}""",
                    timeoutMs = 180_000,
                )
                val status = result.str("status").ifBlank { "streaming" }
                if (status == "rejected" || status == "error") {
                    throw DashboardException("submit_rejected", result.str("message").ifBlank { "Hermes rejected the message" })
                }
                send(ChatEvent.Accepted(status, (result["user_row_id"] as? JsonPrimitive)?.longOrNull))
                if (rewind != null) {
                    trySend(ChatEvent.Rewound(parseSurvivorRowIds(result).orEmpty()))
                }
                // Work and approval waits can exceed three minutes. Disconnect/heartbeat owns
                // liveness; a turn timeout must never cause an acknowledged prompt to be resent.
                if ((result["voice_stopped"] as? JsonPrimitive)?.booleanOrNull == true) {
                    send(ChatEvent.Final(status = "interrupted"))
                } else finished.await()
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose {
            collectJob.cancel()
            submitJob.cancel()
            disconnectJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // ---- Agent rooms (P21) — plugin-owned routes under /companion/rooms -------------------------

    suspend fun listRooms(origin: String): List<RoomRef> =
        get(hostMachine(origin, "/companion/rooms")) { RoomJson.rooms(it) }

    suspend fun createRoom(
        origin: String,
        title: String,
        participants: List<String>,
        policy: RoomPolicySpec = RoomPolicySpec(),
    ): RoomRef {
        val parts = participants.joinToString(",") { it.json() }
        val payload = """{"title":${title.json()},"participants":[$parts],"policy":${policyJson(policy)}}"""
        return post(hostMachine(origin, "/companion/rooms"), payload) {
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
        return patch(hostMachine(origin, "/companion/rooms/$roomId"), "{${fields.joinToString(",")}}") {
            RoomJson.roomEnvelope(it) ?: throw DashboardException("room_parse", "room missing in reply")
        }
    }

    suspend fun setRoomParticipants(origin: String, roomId: String, add: List<String>, remove: List<String>): RoomRef {
        val payload = """{"add":[${add.joinToString(",") { it.json() }}],"remove":[${remove.joinToString(",") { it.json() }}]}"""
        return post(hostMachine(origin, "/companion/rooms/$roomId/participants"), payload) {
            RoomJson.roomEnvelope(it) ?: throw DashboardException("room_parse", "room missing in reply")
        }
    }

    suspend fun pauseRoom(origin: String, roomId: String) {
        post(hostMachine(origin, "/companion/rooms/$roomId/pause"), "{}") { }
    }

    suspend fun continueRoom(origin: String, roomId: String, turns: Int = 6): RoomRef? =
        post(hostMachine(origin, "/companion/rooms/$roomId/continue"), """{"turns":$turns}""") { RoomJson.roomEnvelope(it) }

    suspend fun summarizeRoom(origin: String, roomId: String, by: String = ""): RoomRef? =
        post(hostMachine(origin, "/companion/rooms/$roomId/summarize"), """{"by":${by.json()}}""") { RoomJson.roomEnvelope(it) }

    suspend fun respondRoomApproval(origin: String, roomId: String, requestId: String, decision: String) {
        post(hostMachine(origin, "/companion/rooms/$roomId/approval"), """{"request_id":${requestId.json()},"decision":${decision.json()}}""") { }
    }

    // ---- Coding-agent sessions (P23) --------------------------------------------------------------
    suspend fun agentTools(origin: String, refresh: Boolean = false): Triple<List<AgentTool>, Boolean, String> =
        get(hostMachine(origin, "/companion/agents/tools" + if (refresh) "?refresh=1" else "")) { AgentJson.tools(it) }

    suspend fun agentSessions(origin: String): List<AgentSession> =
        get(hostMachine(origin, "/companion/agents/sessions")) { AgentJson.sessions(it) }

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
        return post(hostMachine(origin, "/companion/agents/sessions"), payload) {
            AgentJson.sessionEnvelope(it) ?: throw DashboardException("agent_parse", "session missing in reply")
        }
    }

    suspend fun agentPane(origin: String, sessionId: String, cols: Int = 0, rows: Int = 0): Pair<AgentPane, AgentSession?> {
        val q = if (cols > 0 && rows > 0) "?cols=$cols&rows=$rows" else ""
        return get(hostMachine(origin, "/companion/agents/sessions/$sessionId/pane$q")) { AgentJson.paneBody(it) }
    }

    suspend fun agentKeys(origin: String, sessionId: String, text: String = "", keys: List<String> = emptyList()) {
        val payload = """{"text":${text.json()},"keys":[${keys.joinToString(",") { it.json() }}]}"""
        post(hostMachine(origin, "/companion/agents/sessions/$sessionId/keys"), payload) { }
    }

    /** Structured sessions: one prompt = one turn. 409 while a turn is running. */
    /** Directory picker listing under the host's allowed roots. Empty [path] = host default. */
    suspend fun agentDirs(origin: String, path: String = "", hidden: Boolean = false): AgentDirListing {
        val q = "?path=${java.net.URLEncoder.encode(path, "UTF-8")}" + if (hidden) "&hidden=1" else ""
        return get(hostMachine(origin, "/companion/agents/dirs$q")) { AgentJson.dirs(it) }
    }

    suspend fun agentPrompt(origin: String, sessionId: String, text: String) {
        post(hostMachine(origin, "/companion/agents/sessions/$sessionId/prompt"), """{"text":${text.json()}}""") { }
    }

    suspend fun agentApproval(origin: String, sessionId: String, requestId: String, decision: String) {
        val payload = """{"request_id":${requestId.json()},"decision":${decision.json()}}"""
        post(hostMachine(origin, "/companion/agents/sessions/$sessionId/approval"), payload) { }
    }

    suspend fun agentTranscript(origin: String, sessionId: String, after: Int = 0): Triple<List<AgentEvent>, ApprovalPrompt?, AgentSession?> =
        get(hostMachine(origin, "/companion/agents/sessions/$sessionId/transcript?after=$after")) { AgentJson.transcriptBody(it) }

    suspend fun killAgentSession(origin: String, sessionId: String, forget: Boolean = false) {
        delete(hostMachine(origin, "/companion/agents/sessions/$sessionId" + if (forget) "?forget=1" else ""))
    }

    /**
     * Live pane stream for one session: `agent.pane` on every screen change, `agent.status` on
     * transitions. Completes when the socket closes or the session exits; caller reconnects.
     */
    fun agentEvents(origin: String, sessionId: String, cols: Int, rows: Int): Flow<AgentJson.Event> = callbackFlow {
        val url = hostMachine(origin, "/companion/agents/events?session_id=$sessionId&cols=$cols&rows=$rows")
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
        get(hostMachine(origin, "/companion/peers")) { RoomJson.peers(it) }

    /** Mint a credential on [origin] that another relay (named [forName]) may use to run turns there. */
    suspend fun grantPeer(origin: String, forName: String): Triple<String, String, String> =
        post(hostMachine(origin, "/companion/peers/grant"), """{"name":${forName.json()}}""") { body ->
            val g = (Json.parseToJsonElement(body).jsonObject["grant"] as? JsonObject)
            val hostId = g?.get("host_id")?.jsonPrimitive?.contentOrNull.orEmpty()
            val secret = g?.get("secret")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (hostId.isBlank() || secret.isBlank()) throw DashboardException("peer_grant", "grant missing in reply")
            Triple(hostId, secret, g?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty())
        }

    suspend fun addPeer(origin: String, name: String, peerOrigin: String, hostId: String, secret: String) {
        val payload = """{"name":${name.json()},"origin":${peerOrigin.json()},"host_id":${hostId.json()},"secret":${secret.json()}}"""
        post(hostMachine(origin, "/companion/peers"), payload) { }
    }

    suspend fun removePeer(origin: String, name: String) {
        delete(hostMachine(origin, "/companion/peers/$name"))
    }

    suspend fun roomHistory(origin: String, roomId: String, after: Int = 0): Pair<RoomRef?, List<ChatMessage>> =
        get(hostMachine(origin, "/companion/rooms/$roomId/history?after=$after")) { RoomJson.history(it) }

    /** Posts as the operator; the host starts the agents' turns. Returns the operator line's seq. */
    suspend fun postRoom(origin: String, roomId: String, text: String): Int =
        post(hostMachine(origin, "/companion/rooms/$roomId/post"), """{"text":${text.json()}}""") { body ->
            (DashboardJson.parseToJsonElement(body) as? JsonObject)?.get("seq")?.jsonPrimitive?.intOrNull ?: 0
        }

    suspend fun interruptRoom(origin: String, roomId: String) {
        post(hostMachine(origin, "/companion/rooms/$roomId/interrupt"), "{}") { }
    }

    suspend fun deleteRoom(origin: String, roomId: String) {
        delete(hostMachine(origin, "/companion/rooms/$roomId"))
    }

    /**
     * Live `room.*` events for one room as [ChatEvent]s. Completes when the socket closes;
     * callers reconnect. Heartbeats and unknown frames are dropped.
     */
    fun roomEvents(origin: String, roomId: String): Flow<ChatEvent> = callbackFlow {
        val url = hostMachine(origin, "/companion/rooms/events?room_id=$roomId")
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
            bindOrigin(origin)
            val key = "$origin|$profileId"
            val current = rpc
            if (current != null && rpcKey == key && current.isOpen) return@withLock current
            current?.close()
            liveByStored.clear()
            runtimeSnapshots.clear()
            val next = GatewaySocket(wsHttp)
            val wsTicket = when {
                !ticket.isNullOrBlank() -> ticket
                gated -> mintWsTicket(origin)
                else -> null
            }
            val wsToken = if (wsTicket.isNullOrBlank()) sessionToken else null
            next.connect(hostWs(origin, profileId, ticket = wsTicket, token = wsToken))
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
            val url = hostRest(
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
        val result = socket.request(
            "session.list",
            """{"profile":${profileId.json()},"limit":$SESSION_PAGE}""",
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
        val cursor = beforeId?.let { historyOffsets["$profileId|$sessionId|$it"] }
        val extra = buildMap {
            put("limit", limit.toString())
            put("order", "latest")
            if (cursor != null) put("offset", cursor.toString())
            else if (!beforeId.isNullOrBlank()) put("before", beforeId)
        }
        return try {
            get(
                hostRest(origin, "/api/sessions/$sessionId/messages", profileId, extra),
            ) { body ->
                val rows = parseMessages(body)
                val root = DashboardJson.parseToJsonElement(body) as? JsonObject
                if (root?.get("pagination") is JsonObject) {
                    rows.firstOrNull()?.let { historyOffsets["$profileId|$sessionId|${it.id}"] = (cursor ?: 0) + limit }
                    HistoryPage(rows, rows.size >= limit)
                } else {
                    val anchorPresent = beforeId != null && rows.any { it.id == beforeId }
                    HistoryPaging.clip(rows, beforeId.takeIf { anchorPresent }, limit)
                }
            }
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
        val result = socket.request(
            "session.history",
            """{"session_id":${liveId.json()},"profile":${profileId.json()}}""",
        )
        return HistoryPaging.clip(parseMessages(result.toString()), beforeId, limit)
    }

    private suspend fun resumeSession(sessionId: String, profileId: String): Pair<String, List<ChatMessage>> {
        val socket = rpc ?: throw DashboardException("ws_closed", "gateway not connected")
        val basic = """{"session_id":${sessionId.json()},"profile":${profileId.json()}}"""
        val result = try {
            socket.request("session.resume", basic.dropLast(1) + ""","omit_messages":true,"defer_history":true,"inline_images":false}""")
        } catch (e: DashboardException) {
            if (e.code != "rpc_4000" && e.code != "rpc_-32602") throw e
            socket.request("session.resume", basic)
        }
        val live = result.str("session_id").ifBlank { sessionId }
        val stored = result.str("stored_session_id").ifBlank { sessionId }
        rememberLive(stored, live)
        rememberLive(sessionId, live)
        val inflight = result["inflight"] as? JsonObject
        val running = (result["running"] as? JsonPrimitive)?.booleanOrNull == true ||
            result.str("status") in setOf("working", "waiting", "streaming") ||
            (inflight?.get("streaming") as? JsonPrimitive)?.booleanOrNull == true
        val snapshot = SessionRuntimeSnapshot(running, inflight?.str("assistant").orEmpty(),
            (result["pending_approval"] as? JsonObject)?.let { parseApproval(it.toString()) }
                ?.takeUnless { promptRetired(stored, it.requestId) || promptRetired(live, it.requestId) })
        runtimeSnapshots[stored] = snapshot
        runtimeSnapshots[sessionId] = snapshot
        runtimeSnapshots[live] = snapshot
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
        val sid = sessionId ?: return false
        return sid == stored || sid == live
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun buildHttp(base: OkHttpClient, attachToken: Boolean): OkHttpClient =
        base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
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
                if (!auth.isNullOrBlank() && credentialScope.pluginRoute(url)) {
                    // Plugin routes: the pairing credential is the identity. Must not be
                    // overwritten by the dashboard bearer below.
                    req.header("Authorization", "Companion $auth")
                } else if (credentialScope.permits(url) && attachToken && !gated && !token.isNullOrBlank()) {
                    req.header("X-Hermes-Session-Token", token)
                    req.header("Authorization", "Bearer $token")
                }
                chain.proceed(req.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (!request.url.isHttps && !OriginPolicy.privateHost(request.url.host))
                    throw java.io.IOException(OriginPolicy.CLEARTEXT_DENIED)
                val scoped = if (credentialScope.permits(request.url)) request else request.newBuilder()
                    .removeHeader("Authorization").removeHeader("X-Hermes-Session-Token")
                    .removeHeader("Cookie").build()
                chain.proceed(scoped)
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

    private suspend fun <T> get(url: String, parse: (String) -> T): T = getRead(url, true, parse)

    private suspend fun <T> getRead(url: String, mayRecover: Boolean, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val (code, body, sentToken) = http.newCall(request).execute().use { response ->
            Triple(response.code, response.body?.string().orEmpty(), response.request.header("X-Hermes-Session-Token"))
        }
        if (code !in 200..299) {
            val origin = credentialScope.nativeReadOrigin(request.url)
            if (code == 401 && mayRecover && attachToken && !gated && !sentToken.isNullOrBlank() && origin != null &&
                recoverBootstrapToken(origin, sentToken)) {
                return@withContext getRead(url, false, parse)
            }
            throw httpError(code, body, url)
        }
        parse(body)
    }

    /** Coalesce successful rotation recovery. Never re-login or replay POST/DELETE/RPC mutations. */
    private suspend fun recoverBootstrapToken(origin: String, rejected: String?): Boolean = bootstrapRefreshLock.withLock {
        if (gated) return@withLock false
        val current = sessionToken
        if (!current.isNullOrBlank() && current != rejected) return@withLock true
        val fresh = adoptLoopbackToken(origin)
        !fresh.isNullOrBlank() && fresh != rejected
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
            getNativeHostMetrics(origin)
        } catch (e: DashboardException) {
            // Compatibility is explicit: auth/provider/network failures must not switch services.
            if (e.code !in setOf("http_404", "http_405", "http_501")) throw e
            if (companionAvailability(origin) != CompanionAvailability.AVAILABLE) throw e
            get(hostMachine(origin, "/companion/host/metrics")) { body ->
                val obj = Json.parseToJsonElement(body).jsonObject
                val metricsObj = obj["metrics"] ?: throw DashboardException("metrics_parse", "host metrics missing")
                Json { ignoreUnknownKeys = true }.decodeFromJsonElement(HostMetrics.serializer(), metricsObj)
            }
        }
    }

    private suspend fun getNativeHostMetrics(origin: String): HostMetrics =
        get(hostMachine(origin, "/api/system/stats")) { body ->
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

    suspend fun getGitStatus(origin: String, repoPath: String = ""): GitStatus {
        return try {
            get(hostMachine(origin, "/companion/git/status")) { body ->
                Json { ignoreUnknownKeys = true }.decodeFromString(GitStatus.serializer(), body)
            }
        } catch (e: Exception) {
            val query = if (repoPath.isNotBlank()) "?path=$repoPath" else ""
            get(hostMachine(origin, "/api/git/status$query")) { body ->
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
            append(hostMachine(origin, "/companion/git/diff?staged=$staged"))
            if (!file.isNullOrBlank()) append("&file=$file")
        }) { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(GitDiffSummary.serializer(), body)
        }

    suspend fun getGitBranches(origin: String): GitBranches =
        get(hostMachine(origin, "/companion/git/branches")) { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(GitBranches.serializer(), body)
        }

    suspend fun stageGitFile(origin: String, path: String, stage: Boolean = true): Boolean =
        post(hostMachine(origin, "/companion/git/stage"), """{"path":"$path","stage":$stage}""") { body ->
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        }

    suspend fun commitGit(origin: String, message: String): Boolean =
        post(hostMachine(origin, "/companion/git/commit"), """{"message":${message.json()}}""") { body ->
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        }

    suspend fun executeTerminalCommand(origin: String, cmd: String): TerminalExecResult =
        post(hostMachine(origin, "/companion/terminal/exec"), """{"cmd":${cmd.json()}}""") { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(TerminalExecResult.serializer(), body)
        }

    suspend fun listWorkspaceFiles(origin: String, path: String = ""): List<RemoteFsItem> =
        get(hostMachine(origin, "/companion/fs/tree?path=$path")) { body ->
            val obj = Json.parseToJsonElement(body).jsonObject
            val items = obj["items"]?.jsonArray ?: return@get emptyList()
            Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(RemoteFsItem.serializer()),
                items,
            )
        }

    suspend fun readWorkspaceFile(origin: String, path: String): String =
        get(hostMachine(origin, "/companion/fs/read?path=$path")) { body ->
            Json.parseToJsonElement(body).jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
        }

    suspend fun getCronJobs(origin: String): List<CronJob> =
        get(hostMachine(origin, "/api/cron/jobs")) { body ->
            Json { ignoreUnknownKeys = true }.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(CronJob.serializer()),
                body,
            )
        }

    suspend fun triggerCronJob(origin: String, jobId: String): Boolean =
        post(hostMachine(origin, "/api/cron/jobs/$jobId/trigger"), "{}") { true }

    suspend fun toggleCronJob(origin: String, jobId: String, pause: Boolean): Boolean {
        val endpoint = if (pause) "pause" else "resume"
        return post(hostMachine(origin, "/api/cron/jobs/$jobId/$endpoint"), "{}") { true }
    }

    suspend fun getModelCatalog(origin: String): ModelCatalog =
        get(hostMachine(origin, "/api/model/options")) { body ->
            val obj = Json.parseToJsonElement(body).jsonObject
            parseModelCatalog(obj)
        }

    suspend fun switchModel(origin: String, model: String, provider: String, profile: String? = null): Boolean {
        val payload = """{"model":${model.json()},"provider":${provider.json()}}"""
        return if (!profile.isNullOrBlank()) {
            post(hostMachine(origin, "/api/profiles/$profile/model"), payload) { true }
        } else {
            post(hostMachine(origin, "/api/model/set"), payload) { true }
        }
    }

    suspend fun checkHermesUpdate(origin: String): HermesUpdateStatus =
        get(hostMachine(origin, "/api/hermes/update/check")) { body ->
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
        post(hostMachine(origin, "/api/hermes/update"), "{}") { true }

    private fun httpError(code: Int, body: String, url: String): DashboardException {
        val err = runCatching {
            val obj = DashboardJson.parseToJsonElement(body).jsonObject
            if (url.endsWith("/companion/connection/mode")) (obj["message"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
            else (obj["error"] as? JsonPrimitive)?.contentOrNull ?: (obj["detail"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        if (code == 502 && err == "dashboard_unreachable") {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(':')
            return DashboardException("host_dashboard_down", "start hermes dashboard on $host")
        }
        val path = url.substringAfter("://").substringAfter("/", missingDelimiterValue = url)
        return DashboardException("http_$code", err?.take(512)?.takeIf { it.isNotBlank() } ?: "http $code · /$path")
    }

    companion object {
        /** Most rows the rail pulls from one host per refresh (either path). Hermes caps history at 500; lists follow suit. */
        const val SESSION_PAGE = 500
        /** Dashboard `GET /api/sessions` refuses `limit > 100`. */
        const val REST_PAGE = 100

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val sseJson = Json { ignoreUnknownKeys = true }
        private val TOKEN_RE = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")

        private fun String.json(): String = JsonPrimitive(this).toString()

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
                    if (obj.isNullOrEmpty()) ChatEvent.Completed else ChatEvent.Final(
                        text = text, status = obj.str("status").ifBlank { "complete" }, error = obj.str("error"))
                event == "error" -> ChatEvent.Final(status = "error", error = obj.str("message").ifBlank { text })
                else -> if (text.isNotBlank()) ChatEvent.AssistantDelta(text) else null
            }
        }

        private fun JsonObject?.str(key: String): String =
            this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
