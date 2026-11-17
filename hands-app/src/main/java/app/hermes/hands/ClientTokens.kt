package app.hermes.hands

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only token digests are retained. Revocation invalidates every associated lease. */
class ClientTokens(context: Context) {
    data class Client(val id: String, val label: String)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    private val prefs = EncryptedSharedPreferences.create("hands.clients", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    @Synchronized fun issue(label: String): Pair<Client, String> {
        check(prefs.all.size < 32) { "Revoke an existing client before adding another." }
        val client = Client(UUID.randomUUID().toString(), label.take(100))
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        check(prefs.edit().putString(digest(token), JSONObject().put("id", client.id).put("label", client.label).toString()).commit()) { "Could not save the client credential." }
        _revision.value++
        return client to token
    }

    @Synchronized fun authenticate(token: String): Client? {
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
        return prefs.getString(digest(token), null)?.let { raw ->
            runCatching { JSONObject(raw).let { Client(it.getString("id"), it.getString("label")) } }.getOrNull()
        }
    }

    @Synchronized fun clients(): List<Client> = prefs.all.values.mapNotNull { raw ->
        runCatching { JSONObject(raw as String).let { Client(it.getString("id"), it.getString("label")) } }.getOrNull()
    }
    @Synchronized fun revokeAll() { check(prefs.edit().clear().commit()); _revision.value++ }
    @Synchronized fun revoke(clientId: String) {
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (runCatching { JSONObject(value as String).optString("id") == clientId }.getOrDefault(false)) editor.remove(key)
        }
        check(editor.commit()) { "Could not revoke the client credential." }
        _revision.value++
    }

    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
