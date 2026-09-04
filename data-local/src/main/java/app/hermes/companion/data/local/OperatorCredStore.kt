package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Operator credentials stored securely in Android Keystore via EncryptedSharedPreferences. */
data class OperatorCred(
    val origin: String,
    val username: String = "",
    val password: String = "",
    val sessionToken: String = "",
    val authMode: String = "token",
)

class OperatorCredStore(private val prefs: SharedPreferences) {
    fun save(cred: OperatorCred) {
        prefs.edit()
            .putString(KEY_ORIGIN, cred.origin)
            .putString(KEY_USER, cred.username)
            .putString(KEY_PASS, cred.password)
            .putString(KEY_TOKEN, cred.sessionToken)
            .putString(KEY_AUTH_MODE, cred.authMode)
            .apply()
    }

    fun load(): OperatorCred? {
        val origin = prefs.getString(KEY_ORIGIN, null).orEmpty()
        if (origin.isBlank()) return null
        return OperatorCred(
            origin = origin,
            username = prefs.getString(KEY_USER, null).orEmpty(),
            password = prefs.getString(KEY_PASS, null).orEmpty(),
            sessionToken = prefs.getString(KEY_TOKEN, null).orEmpty(),
            authMode = prefs.getString(KEY_AUTH_MODE, "token").orEmpty(),
        )
    }

    fun saveGateways(gateways: List<app.hermes.companion.model.SavedGateway>) {
        val array = org.json.JSONArray()
        for (g in gateways) {
            val obj = org.json.JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("origin", g.origin)
                put("token", g.token ?: "")
                put("isActive", g.isActive)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_GATEWAYS, array.toString()).apply()
    }

    fun loadGateways(): List<app.hermes.companion.model.SavedGateway> {
        val raw = prefs.getString(KEY_GATEWAYS, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<app.hermes.companion.model.SavedGateway>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    app.hermes.companion.model.SavedGateway(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        origin = obj.optString("origin", ""),
                        token = obj.optString("token", "").ifBlank { null },
                        isActive = obj.optBoolean("isActive", false),
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE = "companion.operator"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_GATEWAYS = "saved_gateways"

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
