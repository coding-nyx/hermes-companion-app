package app.hermes.desk.providers

import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** No secret-bearing object has a generated toString, nor are endpoint response bodies logged. */
class ChatGptAccount(val clientId: String, val subject: String, val email: String,
    val access: String, val refresh: String, val idToken: String, val expiresAt: Long, val scopes: String) {
    val key get() = "$clientId:$subject"
    fun json() = JSONObject().put("client", clientId).put("sub", subject).put("email", email)
        .put("access", access).put("refresh", refresh).put("id", idToken).put("expires", expiresAt).put("scope", scopes)
    companion object {
        fun parse(j: JSONObject) = ChatGptAccount(j.getString("client"), j.getString("sub"), j.optString("email"),
            j.getString("access"), j.getString("refresh"), j.getString("id"), j.getLong("expires"), j.getString("scope"))
    }
}

interface ProviderVault {
    fun hostId(): String
    fun accounts(): List<ChatGptAccount>
    fun saveAccount(account: ChatGptAccount)
    fun clearTokens(key: String)
    fun readDocument(key: String): JSONObject?
    fun writeDocument(key: String, value: JSONObject)
}

object ChatGptOidc {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private val decoder = Base64.getUrlDecoder()
    fun random(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Only RS256 keys from the pinned issuer JWKS; token-supplied jku/x5u are never followed. */
    fun validate(token: String, jwks: JSONObject, audience: String, nonce: String?, nowSeconds: Long): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid identity token" }
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256" && !header.has("crit")) { "Unsupported identity signature" }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map(keys::getJSONObject).singleOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        } ?: error("Identity signing key unavailable")
        val modulus = BigInteger(1, decoder.decode(key.getString("n")))
        require(modulus.bitLength() >= 2048) { "Invalid identity signing key" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger(1, decoder.decode(key.getString("e")))))
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }
        require(signature.verify(decoder.decode(parts[2]))) { "Identity signature did not match" }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        val aud = claims.opt("aud")
        val audiences = if (aud is org.json.JSONArray) (0 until aud.length()).map(aud::getString) else listOf(aud as? String)
        require(claims.optString("iss") == ISSUER && audience in audiences) { "Identity issuer or audience did not match" }
        require(audiences.size <= 1 || claims.optString("azp") == audience) { "Identity authorized party did not match" }
        require(claims.getLong("exp") > nowSeconds && claims.optLong("iat", nowSeconds) <= nowSeconds + 60 && claims.optLong("nbf", 0) <= nowSeconds + 60) { "Identity token expired or is not valid yet" }
        require(claims.optString("sub").isNotBlank()) { "Identity subject is missing" }
        require(nonce == null || claims.optString("nonce") == nonce) { "Identity nonce did not match" }
        return claims
    }
}

/** Browser-based public client with PKCE and an ephemeral listener bound ONLY to loopback. */
class ChatGptAuth(private val vault: ProviderVault, private val http: OkHttpClient = secureHttp()) {
    private val refreshMutex = Mutex()
    @Volatile private var listener: ServerSocket? = null
    fun cancel() { runCatching { listener?.close() }; listener = null }

    suspend fun signIn(existing: ChatGptAccount? = null, openBrowser: (String) -> Unit): ChatGptAccount = withContext(Dispatchers.IO) {
        this@ChatGptAuth.cancel()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        listener = server
        server.soTimeout = 1000
        val state = ChatGptOidc.random(); val nonce = ChatGptOidc.random(); val verifier = ChatGptOidc.random()
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val url = "${ChatGptOidc.ISSUER}/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", existing?.clientId ?: "dynamic_agent_client")
            .addQueryParameter("ext_agent_host_id", vault.hostId()).addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirect).addQueryParameter("scope", ChatGptOidc.SCOPE)
            .addQueryParameter("resource", ChatGptOidc.RESOURCE).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", ChatGptOidc.challenge(verifier)).apply {
                if (existing == null) addQueryParameter("agent_name_hint", "Desk Companion")
                else if (existing.idToken.isNotBlank()) addQueryParameter("id_token_hint", existing.idToken)
            }.build().toString()
        try {
            withContext(Dispatchers.Main) { openBrowser(url) }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            var callback: HttpUrl? = null
            while (callback == null && System.nanoTime() < deadline) {
                ensureActive()
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        // A bounded first line, no headers/body needed; reject non-OAuth requests.
                        val line = StringBuilder()
                        while (line.length < 8192) { val c = socket.getInputStream().read(); if (c < 0 || c == 10) break; line.append(c.toChar()) }
                        val request = line.toString().trim().split(' ')
                        val candidate = request.getOrNull(1)?.takeIf { it.startsWith("/auth/callback?") }?.let { "http://127.0.0.1:${server.localPort}$it".toHttpUrl() }
                        val valid = request.firstOrNull() == "GET" && line.length < 8192 && candidate?.queryParameterValues("state") == listOf(state)
                        val body = if (valid) "Sign-in received. Return to Desk Companion to finish." else "Invalid sign-in callback. Return to the sign-in page."
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                        if (valid) callback = candidate
                    }
                } catch (_: java.net.SocketTimeoutException) { /* cancellation/deadline checked every second */ }
            }
            val result = callback ?: error("Sign-in timed out. Try again.")
            require(result.queryParameter("error") == null) { "ChatGPT sign-in was declined. You can try again." }
            val client = result.queryParameter("client_id") ?: existing?.clientId ?: error("ChatGPT registration was incomplete")
            require(client != "dynamic_agent_client" && client.isNotBlank() && (existing == null || client == existing.clientId)) { "ChatGPT registration did not match" }
            val code = result.queryParameter("code")?.takeIf { it.isNotBlank() } ?: error("Sign-in code was missing")
            val tokens = form("token", mapOf("grant_type" to "authorization_code", "client_id" to client,
                "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to ChatGptOidc.RESOURCE))
            val account = validated(tokens, client, nonce, existing)
            ensureActive()
            vault.saveAccount(account)
            account
        } finally { server.close(); if (listener === server) listener = null }
    }

    suspend fun access(key: String): ChatGptAccount = refreshMutex.withLock {
        val account = vault.accounts().singleOrNull { it.key == key } ?: error("Continue with ChatGPT to reconnect this account")
        if (account.access.isNotBlank() && account.expiresAt > System.currentTimeMillis() + 90_000) return@withLock account
        require(account.refresh.isNotBlank()) { "Continue with ChatGPT to reconnect this account" }
        val tokens = withContext(Dispatchers.IO) { form("token", mapOf("grant_type" to "refresh_token", "client_id" to account.clientId,
            "refresh_token" to account.refresh, "resource" to ChatGptOidc.RESOURCE)) }
        val renewed = withContext(Dispatchers.IO) { validated(tokens, account.clientId, null, account) }
        vault.saveAccount(renewed)
        renewed
    }

    suspend fun signOut(key: String): Boolean = refreshMutex.withLock {
        val account = vault.accounts().singleOrNull { it.key == key } ?: return@withLock true
        val revoked = account.refresh.isBlank() || withContext(Dispatchers.IO) {
            runCatching { form("revoke", mapOf("token" to account.refresh, "token_type_hint" to "refresh_token", "client_id" to account.clientId)); true }.getOrDefault(false)
        }
        vault.clearTokens(key)
        revoked
    }

    private fun validated(tokens: JSONObject, client: String, nonce: String?, existing: ChatGptAccount?): ChatGptAccount {
        val scope = tokens.optString("scope", existing?.scopes.orEmpty())
        require("chatgpt.tokens.use.direct" in scope.split(' ')) { "ChatGPT plan access was not granted. Sign in again and enable plan use." }
        require(tokens.optString("token_type").equals("Bearer", true)) { "Unsupported account token type" }
        val identity = tokens.optString("id_token")
        val claims = if (identity.isNotBlank()) {
            val keys = http.newCall(Request.Builder().url("${ChatGptOidc.ISSUER}/.well-known/jwks.json").build()).execute().use { response ->
                check(response.isSuccessful) { "Could not verify ChatGPT identity" }; JSONObject(response.body!!.string())
            }
            ChatGptOidc.validate(identity, keys, client, nonce, System.currentTimeMillis() / 1000)
        } else {
            require(nonce == null && existing != null) { "Identity token is missing" }
            JSONObject().put("sub", existing.subject).put("email", existing.email)
        }
        require(existing == null || claims.getString("sub") == existing.subject) { "ChatGPT account changed during renewal" }
        val access = tokens.getString("access_token"); require(access.isNotBlank())
        val refresh = tokens.optString("refresh_token", existing?.refresh.orEmpty()); require(refresh.isNotBlank()) { "Renewable session was not granted" }
        val expiry = tokens.getLong("expires_in"); require(expiry in 1..2_592_000)
        return ChatGptAccount(client, claims.getString("sub"), claims.optString("email"), access, refresh,
            identity.ifBlank { existing?.idToken.orEmpty() }, System.currentTimeMillis() + expiry * 1000, scope)
    }

    private fun form(action: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url("${ChatGptOidc.ISSUER}/api/accounts/oauth/$action").post(body).build()).execute().use { response ->
            check(response.isSuccessful) { if (response.code in listOf(400, 401, 403)) "ChatGPT authorization expired or was declined. Continue with ChatGPT again." else "ChatGPT sign-in service is unavailable (${response.code})" }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
    companion object {
        fun secureHttp(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    }
}
