package app.hermes.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.hermes.companion.model.DeviceCred

/** Device identity + credential. Android Keystore via EncryptedSharedPreferences. Never Room. */
class DeviceCredStore(private val prefs: SharedPreferences) {
    fun save(cred: DeviceCred) {
        require(cred.deviceId.isNotBlank()) { "device_id required" }
        require(cred.credential.isNotBlank()) { "credential required" }
        prefs.edit()
            .putString(KEY_DEVICE, cred.deviceId)
            .putString(KEY_PROFILE, cred.profileId)
            .putString(KEY_CRED, cred.credential)
            .putString(KEY_ORIGIN, cred.origin)
            .apply()
    }

    fun load(): DeviceCred? {
        val id = prefs.getString(KEY_DEVICE, null).orEmpty()
        val cred = prefs.getString(KEY_CRED, null).orEmpty()
        if (id.isBlank() || cred.isBlank()) return null
        return DeviceCred(
            deviceId = id,
            profileId = prefs.getString(KEY_PROFILE, null).orEmpty(),
            credential = cred,
            origin = prefs.getString(KEY_ORIGIN, null).orEmpty(),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE = "companion.device"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_PROFILE = "profile_id"
        private const val KEY_CRED = "credential"
        private const val KEY_ORIGIN = "origin"

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
