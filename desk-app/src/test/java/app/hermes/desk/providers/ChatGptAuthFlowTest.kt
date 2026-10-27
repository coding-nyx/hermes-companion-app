@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.hermes.desk.providers

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptAuthFlowTest {
    private class Vault : ProviderVault {
        var saved: ChatGptAccount? = null
        override fun hostId() = "urn:uuid:7160d52c-6239-4c33-8687-d037a5b8a5db"
        override fun accounts() = listOfNotNull(saved)
        override fun saveAccount(account: ChatGptAccount) { saved = account }
        override fun clearTokens(key: String) = Unit
        override fun readDocument(key: String): JSONObject? = null
        override fun writeDocument(key: String, value: JSONObject) = Unit
    }
    @Test fun loopbackRejectsWrongStateThenValidatesIdentityBeforeSavingCredentials() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vault = Vault(); val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun enc(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val public = pair.public as RSAPublicKey
        val keys = JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "fixture").put("kty", "RSA")
            .put("n", enc(public.modulus.toByteArray())).put("e", enc(public.publicExponent.toByteArray()))))
        var nonce = ""; val exchanges = AtomicInteger()
        val http = ChatGptAuth.secureHttp().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("jwks.json")) keys.toString() else {
                exchanges.incrementAndGet()
                assertEquals("oaiapp_fixture", (request.body as FormBody).let { form -> (0 until form.size).associate { form.name(it) to form.value(it) } }["client_id"])
                val header = enc("""{"alg":"RS256","kid":"fixture"}""".toByteArray())
                val claims = JSONObject().put("iss", ChatGptOidc.ISSUER).put("aud", "oaiapp_fixture").put("sub", "fixture-user")
                    .put("nonce", nonce).put("exp", System.currentTimeMillis() / 1000 + 3600)
                val signed = "$header.${enc(claims.toString().toByteArray())}"
                val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(signed.toByteArray()) }.sign()
                JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh").put("token_type", "Bearer")
                    .put("id_token", "$signed.${enc(signature)}").put("expires_in", 3600).put("scope", ChatGptOidc.SCOPE).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = ChatGptAuth(vault, http)
        var callback: Job? = null
        try {
            val account = withTimeout(10_000) { auth.signIn { url ->
                val authorize = url.toHttpUrl()
                assertEquals("dynamic_agent_client", authorize.queryParameter("client_id"))
                assertEquals("S256", authorize.queryParameter("code_challenge_method"))
                nonce = authorize.queryParameter("nonce")!!
                val redirect = authorize.queryParameter("redirect_uri")!!.toHttpUrl()
                assertEquals("127.0.0.1", redirect.host)
                callback = launch(Dispatchers.IO) {
                    val plain = ChatGptAuth.secureHttp()
                    val invalid = redirect.newBuilder().addQueryParameter("state", "wrong-state").addQueryParameter("code", "injected").build()
                    plain.newCall(Request.Builder().url(invalid).build()).execute().use { assertEquals(400, it.code) }
                    assertEquals(0, exchanges.get()); assertNull(vault.saved)
                    val valid = redirect.newBuilder().addQueryParameter("state", authorize.queryParameter("state"))
                        .addQueryParameter("code", "fixture-code").addQueryParameter("client_id", "oaiapp_fixture").build()
                    plain.newCall(Request.Builder().url(valid).build()).execute().close()
                }
            } }
            callback?.join()
            assertEquals(1, exchanges.get()); assertEquals("fixture-user", account.subject)
            assertSame(account, vault.saved)
        } finally { auth.cancel(); callback?.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
