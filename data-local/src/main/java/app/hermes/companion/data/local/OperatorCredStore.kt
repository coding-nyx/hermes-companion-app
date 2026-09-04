package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.SavedGateway
import org.json.JSONArray
import org.json.JSONObject

/** Operator credentials stored securely in Android Keystore via EncryptedSharedPreferences. */
data class OperatorCred(
    val origin: String,
    val username: String = "",
    val password: String = "",
    val sessionToken: String = "",
    val authMode: String = "token",
)

/**
 * One operator credential **per host** (A8.5), keyed by normalised origin, plus the gateway book.
 * The pre-A8.5 single record (`origin/username/...` keys) is migrated into the map on first read.
 */
class OperatorCredStore(private val prefs: SharedPreferences) {
    fun save(cred: OperatorCred) {
        val key = hostKey(cred.origin)
        if (key.isBlank()) return
        val all = loadAllMap()
        all[key] = cred.copy(origin = key)
        writeAll(all)
        // Keep the legacy record pointing at the most recent host for older readers.
        prefs.edit().putString(KEY_LAST_ORIGIN, key).apply()
    }

    /** Credential for [origin], or null. */
    fun load(origin: String): OperatorCred? = loadAllMap()[hostKey(origin)]

    /** Most recently saved credential (legacy single-host callers). */
    fun load(): OperatorCred? {
        val all = loadAllMap()
        val last = prefs.getString(KEY_LAST_ORIGIN, null)?.let { all[it] }
        return last ?: all.values.firstOrNull()
    }

    fun loadAll(): List<OperatorCred> = loadAllMap().values.toList()

    fun clear(origin: String) {
        val all = loadAllMap()
        all.remove(hostKey(origin))
        writeAll(all)
    }

    fun saveGateways(gateways: List<SavedGateway>) {
        val array = JSONArray()
        for (g in gateways) {
            val obj = JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("origin", g.origin)
                put("token", g.token ?: "")
                put("isActive", g.isActive)
                put("kind", g.kind)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_GATEWAYS, array.toString()).apply()
    }

    fun loadGateways(): List<SavedGateway> {
        val raw = prefs.getString(KEY_GATEWAYS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val list = mutableListOf<SavedGateway>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SavedGateway(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        origin = obj.optString("origin", ""),
                        token = obj.optString("token", "").ifBlank { null },
                        isActive = obj.optBoolean("isActive", false),
                        kind = obj.optString("kind", "hermes").ifBlank { "hermes" },
                    ),
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    /** Display name for a host: gateway book name, else the bare host. */
    fun hostName(origin: String): String {
        val key = hostKey(origin)
        loadGateways().firstOrNull { hostKey(it.origin) == key && it.name.isNotBlank() }?.let { return it.name }
        return HostKeys.hostOf(key).ifBlank { key }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- storage ------------------------------------------------------------------------------

    private fun loadAllMap(): MutableMap<String, OperatorCred> {
        val out = linkedMapOf<String, OperatorCred>()
        prefs.getString(KEY_CREDS, null)?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                for (key in obj.keys()) {
                    val c = obj.getJSONObject(key)
                    out[key] = OperatorCred(
                        origin = key,
                        username = c.optString("username", ""),
                        password = c.optString("password", ""),
                        sessionToken = c.optString("session_token", ""),
                        authMode = c.optString("auth_mode", "token"),
                    )
                }
            }
        }
        // Legacy single record → migrate once.
        val legacyOrigin = prefs.getString(KEY_ORIGIN, null).orEmpty()
        if (legacyOrigin.isNotBlank()) {
            val key = hostKey(legacyOrigin)
            if (key.isNotBlank() && key !in out) {
                out[key] = OperatorCred(
                    origin = key,
                    username = prefs.getString(KEY_USER, null).orEmpty(),
                    password = prefs.getString(KEY_PASS, null).orEmpty(),
                    sessionToken = prefs.getString(KEY_TOKEN, null).orEmpty(),
                    authMode = prefs.getString(KEY_AUTH_MODE, "token").orEmpty(),
                )
                writeAll(out)
                prefs.edit()
                    .remove(KEY_ORIGIN).remove(KEY_USER).remove(KEY_PASS).remove(KEY_TOKEN).remove(KEY_AUTH_MODE)
                    .putString(KEY_LAST_ORIGIN, prefs.getString(KEY_LAST_ORIGIN, key))
                    .apply()
            }
        }
        return out
    }

    private fun writeAll(all: Map<String, OperatorCred>) {
        val obj = JSONObject()
        for ((key, c) in all) {
            obj.put(
                key,
                JSONObject().apply {
                    put("username", c.username)
                    put("password", c.password)
                    put("session_token", c.sessionToken)
                    put("auth_mode", c.authMode)
                },
            )
        }
        prefs.edit().putString(KEY_CREDS, obj.toString()).apply()
    }

    private fun hostKey(origin: String): String = HostKeys.of(origin)

    companion object {
        private const val FILE = "companion.operator"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_GATEWAYS = "saved_gateways"
        private const val KEY_CREDS = "creds_by_host"
        private const val KEY_LAST_ORIGIN = "last_origin"

        fun encrypted(context: Context): OperatorCredStore {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                FILE,
                master,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return OperatorCredStore(prefs)
        }
    }
}
