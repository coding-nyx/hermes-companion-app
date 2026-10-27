package app.hermes.desk

import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.domain.AuthPolicy
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.model.*
import app.hermes.desk.feature.*
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HermesBackend : DeskBackend {
    override val label = "Hermes"
    override val capabilities = DeskCapabilities()
    private var client = DashboardClient()
    private var origin = ""

    override suspend fun connect(connection: DeskConnection): List<ProfileRef> {
        val url = connection.origin.toHttpUrlOrNull() ?: error("Enter a valid http:// or https:// dashboard address")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Use a dashboard address without credentials, query or fragment"
        }
        require(OriginPolicy.cleartextAllowed(connection.origin)) { "Public hosts require HTTPS" }
        client.closeRpc()
        client = DashboardClient()
        origin = connection.origin.trimEnd('/')
        client.bindOrigin(origin)
        client.sessionToken = connection.token.ifBlank { null }
        val status = client.probe(origin)
        if (status.authRequired) {
            val provider = AuthPolicy.passwordProvider(status.authProviders)
                ?: error("This host requires browser sign-in, which Desk V1 does not support")
            require(connection.password.isNotBlank()) { "Enter the dashboard username and password" }
            client.passwordLogin(origin, connection.username, connection.password, provider)
        } else if (connection.token.isBlank()) client.adoptLoopbackToken(origin)
        return client.listProfiles(origin)
    }

    override suspend fun selectProfile(profile: String): List<SessionRef> {
        client.wsHello(origin, profile)
        return client.listSessions(origin, profile)
    }
    override suspend fun history(binding: DeskBinding) = client.listMessages(binding.origin, binding.session, binding.profile)
    override suspend fun createThread(profile: String) = client.createSession(origin, profile, title = "Desk conversation",
        idempotencyKey = java.util.UUID.randomUUID().toString(), requireRpc = true)
    override fun events(profile: String): Flow<BusFrame> = client.bus(profile)
    override fun belongsTo(binding: DeskBinding, eventSession: String?) = eventSession != null && client.matchesSession(binding.session, eventSession)
    override fun turn(binding: DeskBinding, text: String) = client.streamTurn(binding.origin, binding.session, binding.profile, text)
    override suspend fun pending(binding: DeskBinding) = client.pendingApproval(binding.origin, binding.session, binding.profile)
    override fun runtime(binding: DeskBinding) = client.runtimeSnapshot(binding.session)
    override suspend fun respond(binding: DeskBinding, prompt: ApprovalPrompt, choice: String) = client.respondPrompt(binding.origin, binding.session, binding.profile, prompt, choice)
    override suspend fun interrupt(binding: DeskBinding) = client.interruptTurn(binding.origin, binding.session, binding.profile)
    override suspend fun context(binding: DeskBinding) = client.sessionContext(binding.origin, SessionRef(binding.session, binding.profile, "", 0))
    override suspend fun compact(binding: DeskBinding) = client.compressSession(binding.origin, SessionRef(binding.session, binding.profile, "", 0))
    override suspend fun stageImage(binding: DeskBinding, jpeg: ByteArray) {
        client.stageAttachment(binding.origin, binding.session, binding.profile, jpeg, "desk-view.jpg", "image/jpeg")
    }
    fun speech(binding: DeskBinding): HermesSpeech {
        require(binding.origin == origin)
        return HermesSpeech(client, binding)
    }
    /** Dashboard credential only. Provider access/refresh tokens never leave the host. */
    internal fun dashboardCredential() = client.sessionToken?.takeIf(String::isNotBlank)
        ?: error("Reconnect the Hermes host before using its Grok account broker")
    override suspend fun settings(binding: DeskBinding) = client.sessionSettings(binding.origin, SessionRef(binding.session, binding.profile, "", 0))
    override suspend fun setting(binding: DeskBinding, key: String, value: String, confirmed: Boolean) =
        client.setSessionSetting(binding.origin, SessionRef(binding.session, binding.profile, "", 0), key, value, confirmed)
    override fun close() { client.closeRpc() }
}

/** Capture the authenticated client as well as identity; later connection changes cannot move credentials. */
class HermesSpeech internal constructor(private val client: DashboardClient, private val binding: DeskBinding) {
    suspend fun transcribe(wav: ByteArray) = client.transcribeAudio(binding.origin, binding.profile, wav, "audio/wav").text
    suspend fun synthesize(text: String) = client.synthesizeSpeech(binding.origin, binding.profile, text)
}
