package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.DeviceCred
import org.json.JSONObject

/**
 * Device identity + credential **per host** (A8.5). Android Keystore via EncryptedSharedPreferences.
 * Never Room. Each relay keeps its own pairing store, so the phone keeps one credential per origin.
 * The pre-A8.5 single record is migrated on first read; a record with a blank origin is adopted by
 * the first host that asks for it via [adoptLegacy].
 */
class DeviceCredStore(private val prefs: SharedPreferences) {
    fun save(cred: DeviceCred) {
        require(cred.deviceId.isNotBlank()) { "device_id required" }
        require(cred.credential.isNotBlank()) { "credential required" }
        val key = HostKeys.of(cred.origin)
        require(key.isNotBlank()) { "origin required" }
        val all = loadAllMap()
        all[key] = cred.copy(origin = key)
        writeAll(all)
    }

    /** Credential paired with [origin], or null. */
    fun load(origin: String): DeviceCred? = loadAllMap()[HostKeys.of(origin)]

    /** Any credential (legacy callers / "is this phone paired to anything"). */
    fun load(): DeviceCred? = loadAllMap().values.firstOrNull()

    fun loadAll(): List<DeviceCred> = loadAllMap().values.toList()

    /**
     * A legacy record saved without an origin belongs to whichever host the user was connected to.
     * Bind it to [origin] once so per-host lookups work; returns the adopted credential.
     */
    fun adoptLegacy(origin: String): DeviceCred? {
        val all = loadAllMap()
        val orphan = all.remove("") ?: return null
        val key = HostKeys.of(origin)
        if (key.isBlank()) return null
        val adopted = orphan.copy(origin = key)
        all.putIfAbsent(key, adopted)
        writeAll(all)
        return all[key]
    }

    fun clear(origin: String) {
        val all = loadAllMap()
        all.remove(HostKeys.of(origin))
        writeAll(all)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- storage ------------------------------------------------------------------------------

    private fun loadAllMap(): MutableMap<String, DeviceCred> {
        val out = linkedMapOf<String, DeviceCred>()
        prefs.getString(KEY_BY_HOST, null)?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                for (key in obj.keys()) {
                    val c = obj.getJSONObject(key)
                    val id = c.optString("device_id", "")
                    val cred = c.optString("credential", "")
                    if (id.isBlank() || cred.isBlank()) continue
                    out[key] = DeviceCred(
                        deviceId = id,
                        profileId = c.optString("profile_id", ""),
                        credential = cred,
                        origin = key,
                    )
                }
            }
        }
        // Legacy single record → migrate once (blank origin stays under "" until adopted).
        val legacyId = prefs.getString(KEY_DEVICE, null).orEmpty()
        val legacyCred = prefs.getString(KEY_CRED, null).orEmpty()
        if (legacyId.isNotBlank() && legacyCred.isNotBlank()) {
            val key = HostKeys.of(prefs.getString(KEY_ORIGIN, null).orEmpty())
            if (key !in out) {
                out[key] = DeviceCred(
                    deviceId = legacyId,
                    profileId = prefs.getString(KEY_PROFILE, null).orEmpty(),
                    credential = legacyCred,
                    origin = key,
                )
            }
            writeAll(out)
            prefs.edit().remove(KEY_DEVICE).remove(KEY_PROFILE).remove(KEY_CRED).remove(KEY_ORIGIN).apply()
        }
        return out
    }

    private fun writeAll(all: Map<String, DeviceCred>) {
        val obj = JSONObject()
        for ((key, c) in all) {
            obj.put(
                key,
                JSONObject().apply {
                    put("device_id", c.deviceId)
                    put("profile_id", c.profileId)
                    put("credential", c.credential)
                },
            )
        }
        prefs.edit().putString(KEY_BY_HOST, obj.toString()).apply()
    }

    companion object {
        private const val FILE = "companion.device"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_PROFILE = "profile_id"
        private const val KEY_CRED = "credential"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_BY_HOST = "creds_by_host"

        fun encrypted(context: Context): DeviceCredStore {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                FILE,
                master,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DeviceCredStore(prefs)
        }
    }
}
