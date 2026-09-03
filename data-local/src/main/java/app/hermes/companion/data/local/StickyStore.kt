package app.hermes.companion.data.local

import android.content.Context

/** Last origin + last profile. Not secrets. */
class StickyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE)

    var origin: String?
        get() = prefs.getString(KEY_ORIGIN, null)
        set(value) { prefs.edit().putString(KEY_ORIGIN, value).apply() }

    var profileId: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) { prefs.edit().putString(KEY_PROFILE, value).apply() }

    companion object {
        private const val KEY_ORIGIN = "origin"
        private const val KEY_PROFILE = "profile_id"
    }
}
