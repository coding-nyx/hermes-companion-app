package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.VoiceCaption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class VoiceCallRecord(val captions: List<VoiceCaption> = emptyList(), val delegations: List<String> = emptyList())

/** Only text is durable. Provider audio and credentials never enter this journal. */
class VoiceJournal(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(origin: String, profile: String, session: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${origin.trimEnd('/')}\u0000$profile\u0000$session".toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun read(origin: String, profile: String, session: String): VoiceCallRecord =
        prefs.getString(key(origin, profile, session), null)?.let { json.decodeFromString(it) } ?: VoiceCallRecord()
    @Synchronized fun save(origin: String, profile: String, session: String, record: VoiceCallRecord) {
        val text = json.encodeToString(record.copy(captions = record.captions.takeLast(1000), delegations = record.delegations.takeLast(500)))
        require(text.toByteArray().size <= 2 * 1024 * 1024) { "Voice transcript storage is full" }
        check(prefs.edit().putString(key(origin, profile, session), text).commit()) { "Voice transcript could not be saved" }
    }
    @Synchronized fun remove(origin: String, profile: String, session: String) {
        check(prefs.edit().remove(key(origin, profile, session)).commit()) { "Voice transcript could not be removed" }
    }
    companion object {
        fun encrypted(context: Context) = VoiceJournal(EncryptedSharedPreferences.create("companion.voice.v1",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    }
}
