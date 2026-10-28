package app.hermes.companion.backend

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.companion.backend.*
import app.hermes.companion.model.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/** The prototype has a separate encrypted namespace; legacy Hermes keys and databases stay intact. */
class BackendVault(context: Context, namespace: String = "companion.backends") : BackendStore {
    private val prefs=EncryptedSharedPreferences.create(namespace,MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    override fun active() = prefs.getString("active",null)
    override fun active(owner: String?) { check(prefs.edit().putString("active",owner).commit()) }
    @Synchronized override fun connections(): List<AgentConnection> = runCatching { json.decodeFromString<List<AgentConnection>>(prefs.getString("connections","[]")!!) }.getOrDefault(emptyList())
    @Synchronized override fun save(connection: AgentConnection) {
        prefs.edit().putString("connections",json.encodeToString(connections().filterNot { it.owner==connection.owner }+connection)).commit().also { check(it) { "Could not save this connection." } }
    }
    @Synchronized override fun forget(connection: AgentConnection) {
        val edit=prefs.edit().putString("connections",json.encodeToString(connections().filterNot { it.owner==connection.owner }))
        prefs.all.keys.filter { it.startsWith(connection.owner+"|") }.forEach(edit::remove)
        check(edit.commit())
    }
    @Synchronized override fun credentials(connection: AgentConnection): AgentCredentials {
        val owner=connection.owner+"|"
        val seed=prefs.getString(owner+"seed",null)?.let { Base64.getDecoder().decode(it) } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it); check(prefs.edit().putString(owner+"seed",Base64.getEncoder().encodeToString(it)).commit())
        }
        return AgentCredentials(seed,prefs.getString(owner+"token","")!!,prefs.getString(owner+"password","")!!,prefs.getString(owner+"device","")!!,prefs.getString(owner+"username","")!!)
    }
    @Synchronized override fun credentials(connection: AgentConnection,credentials: AgentCredentials) {
        val owner=connection.owner+"|"
        check(prefs.edit().putString(owner+"seed",Base64.getEncoder().encodeToString(credentials.seed)).putString(owner+"token",credentials.token)
            .putString(owner+"password",credentials.password).putString(owner+"device",credentials.deviceToken).putString(owner+"username",credentials.username).commit())
    }
    override fun draft(owner: String,thread: String) = prefs.getString(key(owner,thread,"draft"),"")!!
    override fun draft(owner: String,thread: String,text: String) { check(prefs.edit().putString(key(owner,thread,"draft"),text).commit()) }
    override fun history(owner: String,thread: String): List<ChatMessage> = runCatching { json.decodeFromString<List<ChatMessage>>(prefs.getString(key(owner,thread,"history"),"[]")!!) }.getOrDefault(emptyList())
    override fun history(owner: String,thread: String,messages: List<ChatMessage>) { check(prefs.edit().putString(key(owner,thread,"history"),json.encodeToString(messages.takeLast(200))).commit()) }
    override fun pending(owner: String,thread: String) = prefs.getString(key(owner,thread,"pending"),null)
    override fun pending(owner: String,thread: String,operation: String?) { check(prefs.edit().putString(key(owner,thread,"pending"),operation).commit()) }
    private fun key(owner: String,thread: String,kind: String) = "$owner|${Base64.getUrlEncoder().withoutPadding().encodeToString(thread.toByteArray())}|$kind"
}
