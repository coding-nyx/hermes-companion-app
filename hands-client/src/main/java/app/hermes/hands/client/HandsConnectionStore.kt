package app.hermes.hands.client

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class HandsConnection(val endpoint: String, val token: String)

interface HandsConnectionStore {
    fun load(): HandsConnection?
    fun isSelected(): Boolean = load() != null
    fun save(connection: HandsConnection)
    fun clear()
}

/** Tokens never enter ordinary preferences, UI state, logs or relay responses. */
class EncryptedHandsConnectionStore(context: Context) : HandsConnectionStore {
    private val appContext = context.applicationContext
    // This non-secret mode marker avoids creating a Keystore key for users staying on legacy control.
    private val selection = appContext.getSharedPreferences("hermes-hands-selection", Context.MODE_PRIVATE)
    private val preferences by lazy { EncryptedSharedPreferences.create(
        "hermes-hands-connection",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ) }
    override fun isSelected(): Boolean = selection.getBoolean("selected", false)
    override fun load(): HandsConnection? = runCatching {
        if (!selection.getBoolean("selected", false)) return@runCatching null
        val token = preferences.getString("token", null)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val endpoint = preferences.getString("endpoint", null) ?: return@runCatching null
        HandsConnection(endpoint, token)
    }.getOrNull()
    override fun save(connection: HandsConnection) {
        check(preferences.edit().putString("endpoint", connection.endpoint).putString("token", connection.token).commit())
        check(selection.edit().putBoolean("selected", true).commit())
    }
    override fun clear() {
        check(preferences.edit().clear().commit())
        check(selection.edit().putBoolean("selected", false).commit())
    }
}
