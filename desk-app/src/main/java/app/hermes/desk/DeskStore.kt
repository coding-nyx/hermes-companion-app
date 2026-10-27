package app.hermes.desk

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.OutboxItem
import app.hermes.desk.feature.*
import org.json.JSONArray
import org.json.JSONObject
import app.hermes.desk.providers.*

/** App-private Keystore storage, including exact reverse-proxy path in every identity key. */
class DeskStore(context: Context) : DeskPersistence, ProviderVault {
    private val prefs = EncryptedSharedPreferences.create("desk.private", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun key(binding: DeskBinding) = JSONObject().put("origin", binding.origin).put("profile", binding.profile).put("session", binding.session).toString()
    private fun profileKey(origin: String, profile: String) = JSONObject().put("origin", origin).put("profile", profile).toString()
    fun hermesConnection(): DeskConnection? = prefs.getString("connection", null)?.let { raw ->
        runCatching { JSONObject(raw).let { DeskConnection(it.getString("origin"), it.optString("user"), it.optString("password"), it.optString("token")) } }.getOrNull()
    }
    override fun connection(): DeskConnection? = when (prefs.getString("activeProvider", "hermes")) {
        "chatgpt" -> DeskConnection(ChatGptBackend.ORIGIN)
        "grok" -> hermesConnection()?.let { DeskConnection(GrokBackend.originFor(it.origin)) }
        else -> hermesConnection()
    }
    override fun saveConnection(connection: DeskConnection) {
        if (connection.origin == ChatGptBackend.ORIGIN || GrokBackend.isOrigin(connection.origin)) {
            check(prefs.edit().putString("activeProvider", if (connection.origin == ChatGptBackend.ORIGIN) "chatgpt" else "grok").putBoolean("autoConnect", true).commit())
            return
        }
        check(prefs.edit().putString("connection", JSONObject().put("origin", connection.origin).put("user", connection.username)
            .put("password", connection.password).put("token", connection.token).toString()).putString("activeProvider", "hermes").putBoolean("autoConnect", true).commit()) { "Could not save connection securely" }
    }
    override fun profile(origin: String) = prefs.getString("profile:$origin", "").orEmpty()
    override fun saveProfile(origin: String, profile: String) { prefs.edit().putString("profile:$origin", profile).apply() }
    override fun session(origin: String, profile: String) = prefs.getString("session:${profileKey(origin, profile)}", "").orEmpty()
    override fun saveSession(binding: DeskBinding) { prefs.edit().putString("session:${profileKey(binding.origin, binding.profile)}", binding.session).apply() }
    override fun draft(binding: DeskBinding) = prefs.getString("draft:${key(binding)}", "").orEmpty()
    override fun saveDraft(binding: DeskBinding, text: String) { prefs.edit().putString("draft:${key(binding)}", text).apply() }
    override fun pending(binding: DeskBinding): OutboxItem? = prefs.getString("pending:${key(binding)}", null)?.let { raw ->
        // Corrupt journal fails closed instead of silently making an uncertain send retryable.
        runCatching {
            val data = JSONObject(raw)
            val baseline = data.getJSONArray("baseline")
            OutboxItem(data.getString("id"), binding.origin, binding.profile, binding.session, data.getString("text"),
                data.getLong("time"), lastError = "awaiting_ack", baselineUserIds = (0 until baseline.length()).map { baseline.getString(it) })
        }.getOrElse { OutboxItem("unknown", binding.origin, binding.profile, binding.session, "", 0, lastError = "awaiting_ack", baselineUserIds = emptyList()) }
    }
    override fun savePending(binding: DeskBinding, item: OutboxItem?): Boolean {
        val edit = prefs.edit()
        if (item == null) edit.remove("pending:${key(binding)}") else edit.putString("pending:${key(binding)}", JSONObject()
            .put("id", item.id).put("text", item.text).put("time", item.createdAtEpochMs)
            .put("baseline", JSONArray(item.baselineUserIds.orEmpty())).toString())
        return edit.commit()
    }
    fun preference(name: String, default: Boolean) = prefs.getBoolean(name, default)
    fun setPreference(name: String, value: Boolean) { prefs.edit().putBoolean(name, value).apply() }
    override fun compactionPending(binding: DeskBinding) = prefs.getBoolean("compact:${key(binding)}", false)
    override fun saveCompactionPending(binding: DeskBinding, pending: Boolean) = prefs.edit().putBoolean("compact:${key(binding)}", pending).commit()
    fun speechMode() = prefs.getString("speechMode", "hermes").orEmpty().takeIf { it in setOf("hermes", "system") } ?: "hermes"
    fun saveSpeechMode(mode: String) { require(mode in setOf("hermes", "system")); prefs.edit().putString("speechMode", mode).apply() }
    fun voicePause() = prefs.getInt("voicePause", 900).takeIf { it in setOf(600, 900, 1400) } ?: 900
    fun saveVoicePause(ms: Int) { require(ms in setOf(600, 900, 1400)); prefs.edit().putInt("voicePause", ms).apply() }
    fun voiceThreshold() = prefs.getInt("voiceThreshold", 600).takeIf { it in setOf(300, 600, 1000) } ?: 600
    fun saveVoiceThreshold(value: Int) { require(value in setOf(300, 600, 1000)); prefs.edit().putInt("voiceThreshold", value).apply() }
    @Synchronized override fun hostId(): String = prefs.getString("chatgpt.host", null) ?: ("urn:uuid:" + java.util.UUID.randomUUID()).also {
        check(prefs.edit().putString("chatgpt.host", it).commit())
    }
    @Synchronized override fun accounts(): List<ChatGptAccount> = prefs.getString("chatgpt.accounts", null)?.let {
        val array = JSONArray(it); (0 until array.length()).map { index -> ChatGptAccount.parse(array.getJSONObject(index)) }
    }.orEmpty()
    @Synchronized override fun saveAccount(account: ChatGptAccount) {
        val updated = accounts().filterNot { it.key == account.key } + account
        check(prefs.edit().putString("chatgpt.accounts", JSONArray().apply { updated.forEach { put(it.json()) } }.toString()).commit()) { "Could not save account securely" }
    }
    @Synchronized override fun clearTokens(key: String) {
        accounts().singleOrNull { it.key == key }?.let {
            saveAccount(ChatGptAccount(it.clientId, it.subject, it.email, "", "", "", 0, it.scopes))
        }
    }
    override fun readDocument(key: String) = prefs.getString("provider.doc:$key", null)?.let(::JSONObject)
    override fun writeDocument(key: String, value: JSONObject) {
        check(prefs.edit().putString("provider.doc:$key", value.toString()).commit()) { "Could not save conversation securely" }
    }
}
