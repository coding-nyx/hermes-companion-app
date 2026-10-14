package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.ChatAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Paths are part of the identity: two dashboards behind the same proxy must not share drafts. */
@Serializable
data class DraftOwner(val origin: String, val profile: String, val session: String)

@Serializable
data class ComposerDraft(val text: String = "", val attachments: List<ChatAttachment> = emptyList()) {
    val empty: Boolean get() = text.isEmpty() && attachments.isEmpty()
}

/** Separate, versioned encrypted storage leaves the v7 transcript DB and APK rollback intact.
 * apply() publishes synchronously and schedules disk IO; Android drains it on lifecycle stops.
 * flush() is an off-main-thread durability barrier for device/process-recreation checks.
 */
class ComposerDraftStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(owner: DraftOwner) = json.encodeToString(owner.copy(origin = owner.origin.trim().trimEnd('/')))

    @Synchronized fun load(owner: DraftOwner): ComposerDraft =
        prefs.getString(key(owner), null)?.let { json.decodeFromString<ComposerDraft>(it) } ?: ComposerDraft()

    @Synchronized fun save(owner: DraftOwner, draft: ComposerDraft) {
        if (load(owner) == draft) return
        val edit = prefs.edit()
        if (draft.empty) edit.remove(key(owner)) else edit.putString(key(owner), json.encodeToString(draft))
        edit.apply()
    }

    /** Clear only the submitted fields; a newer edit or a newly picked file remains recoverable. */
    @Synchronized fun consume(owner: DraftOwner, submitted: ComposerDraft) {
        val current = load(owner)
        save(owner, current.copy(text = if (current.text == submitted.text) "" else current.text,
            attachments = current.attachments.filterNot { it in submitted.attachments }))
    }

    @Synchronized fun promote(from: DraftOwner, to: DraftOwner, expected: ComposerDraft) {
        val edit = prefs.edit()
        if (load(to).empty) edit.putString(key(to), json.encodeToString(expected))
        if (load(from) == expected) edit.remove(key(from))
        edit.apply()
    }

    fun flush(): Boolean = prefs.edit().commit()

    companion object {
        fun encrypted(context: Context, file: String = "companion.drafts.v1"): ComposerDraftStore = ComposerDraftStore(EncryptedSharedPreferences.create(
            file, MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    }
}
