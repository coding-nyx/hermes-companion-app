package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class RoomDeliveryRecord(val id: String, val owner: DraftOwner, val text: String,
    val baselineUserIds: List<String>?, val state: String = "pending", val sequence: Int? = null,
    val createdAt: Long = 0)

/** Separate encrypted v1 journal. Old APKs ignore it; no transcript schema downgrade is needed.
 * Writes are synchronous durability barriers and run off the UI thread before host submission.
 */
class RoomDeliveryStore(private val prefs: SharedPreferences? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var memory = emptyList<RoomDeliveryRecord>()
    private fun normalized(owner: DraftOwner) = owner.copy(origin = owner.origin.trim().trimEnd('/'))
    private fun read(): List<RoomDeliveryRecord> = if (prefs == null) memory else prefs.getString("records", null)?.let { json.decodeFromString(it) } ?: emptyList()
    private fun write(records: List<RoomDeliveryRecord>) {
        val encoded = json.encodeToString(records)
        require(records.size <= 128 && encoded.toByteArray().size <= 8 * 1024 * 1024) { "Pending room delivery storage is full" }
        check(prefs?.edit()?.putString("records", encoded)?.commit() != false) { "Could not save room delivery" }
        memory = records
    }
    @Synchronized fun list(owner: DraftOwner) = read().filter { normalized(it.owner) == normalized(owner) }.sortedBy { it.createdAt }
    @Synchronized fun claim(record: RoomDeliveryRecord): Boolean {
        if (read().any { normalized(it.owner) == normalized(record.owner) && it.text == record.text && it.state != "failed" && it.sequence == null }) return false
        save(record)
        return true
    }
    @Synchronized fun save(record: RoomDeliveryRecord) {
        val records = read(); val old = records.firstOrNull { it.id == record.id }
        require(old == null || normalized(old.owner) == normalized(record.owner)) { "Room delivery owner changed" }
        if (old?.sequence != null && record.sequence == null) return // A late failure cannot undo a receipt.
        write(records.filterNot { it.id == record.id } + record.copy(owner = normalized(record.owner)))
    }
    @Synchronized fun remove(owner: DraftOwner, id: String) {
        write(read().filterNot { it.id == id && normalized(it.owner) == normalized(owner) })
    }
    companion object {
        fun encrypted(context: Context, file: String = "companion.room-delivery.v1") = RoomDeliveryStore(EncryptedSharedPreferences.create(
            file, MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    }
}
