package app.hermes.desk.providers

import app.hermes.desk.HermesBackend
import app.hermes.desk.DeskStore
import app.hermes.desk.feature.*
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.model.ProfileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Native Grok conversations. Lab is an OAuth credential broker, not the agent backend. */
class GrokBackend private constructor(private val delegate: ChatGptBackend, private val credentialHost: HermesBackend) : DeskBackend by delegate {
    override fun close() { delegate.close(); credentialHost.close() }
    companion object {
        fun originFor(hermesOrigin: String): String = hermesOrigin.toHttpUrl().newBuilder().port(9121)
            .encodedPath("/desk-grok").query(null).fragment(null).build().toString().trimEnd('/')
        fun isOrigin(origin: String) = runCatching { origin.toHttpUrl().encodedPath == "/desk-grok" }.getOrDefault(false)
        fun create(store: DeskStore, auth: ChatGptAuth, origin: String): GrokBackend {
            val connection = store.hermesConnection() ?: error("Connect a Hermes host to reuse its Grok OAuth account")
            require(origin == originFor(connection.origin) && OriginPolicy.cleartextAllowed(origin)) { "Grok broker does not match the saved host" }
            val credentialHost = HermesBackend()
            val http = ChatGptAuth.secureHttp()
            val source: suspend (DeskConnection) -> List<ProfileRef> = {
                credentialHost.connect(connection)
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("$origin/v1/models")
                        .header("Authorization", "Bearer ${credentialHost.dashboardCredential()}").build()).execute().use { response ->
                        check(response.isSuccessful) { "Grok broker is unavailable (${response.code}). Check lab’s Desk Bridge and OAuth account." }
                    }
                }
                // Grok owns one local account conversation namespace per host, not Hermes profiles.
                listOf(ProfileRef("host-grok", "Grok account on ${connection.origin.toHttpUrl().host}", ""))
            }
            val delegate = ChatGptBackend(store, auth, http, ResponsesTransport(http, "$origin/v1/responses", "Grok"),
                label = "Grok", origin = origin, providerId = "grok", profileSource = source,
                tokenSource = { credentialHost.dashboardCredential() })
            return GrokBackend(delegate, credentialHost)
        }
    }
}
