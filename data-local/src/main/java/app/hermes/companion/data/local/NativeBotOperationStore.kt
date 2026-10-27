package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.BotOwner
import app.hermes.companion.model.NativeBotOperation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Stable native operation identities survive process death; failed reads never become an empty journal. */
class NativeBotOperationStore(private val prefs: SharedPreferences? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var memory = emptyList<NativeBotOperation>()
    private fun read(): List<NativeBotOperation> = prefs?.getString("pending", null)?.let { json.decodeFromString(it) } ?: memory
    private fun write(records: List<NativeBotOperation>) {
        require(records.size <= 64) { "Resolve pending bot operations before creating more" }
        val text = json.encodeToString(records)
        require(text.toByteArray().size <= 2 * 1024 * 1024) { "Pending bot messages are too large" }
        check(prefs?.edit()?.putString("pending", text)?.commit() != false) { "Could not save pending bot operation" }
        memory = records
    }
    @Synchronized fun list(owner: BotOwner): List<NativeBotOperation> = read().filter { it.owner == owner }
    @Synchronized fun save(operation: NativeBotOperation) {
        val records = read()
        require(records.none { it.id == operation.id && it != operation }) { "Operation identity changed" }
        write(records.filterNot { it.id == operation.id } + operation)
    }
    @Synchronized fun remove(owner: BotOwner, id: String) { write(read().filterNot { it.owner == owner && it.id == id }) }
    companion object {
        fun encrypted(context: Context) = NativeBotOperationStore(EncryptedSharedPreferences.create(
            "companion.native-bots.v1", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    }
}
