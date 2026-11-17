package app.hermes.hands

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One reversible secure setting, using the actual authenticated phone dispatcher. */
@RunWith(AndroidJUnit4::class)
class HandsSecureSettingsDeviceTest : HandsAcceptanceSession() {
    override fun enabled(): Boolean = InstrumentationRegistry.getArguments()
        .getString("hands_secure_settings_acceptance") == "true"

    @Test fun typedToolsDiscoverServicesAndVerifyAReversibleSecureSetting() {
        assumeTrue("Grant Hands WRITE_SECURE_SETTINGS before this opt-in test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
        val key = "show_ime_with_hard_keyboard"
        val resolver = context.contentResolver
        val previous = Settings.Secure.getString(resolver, key)
        val desired = previous != "1"
        val written = if (desired) "1" else "0"
        try {
            val holder = arm()
            val services = requireOk(command("device.accessibility.services", holder = holder)).getJSONArray("services")
            assertTrue("Hands must appear in installed Accessibility service discovery",
                (0 until services.length()).any { services.getJSONObject(it).optBoolean("hands_service") })
            val choices = requireOk(command("device.secure_settings.list", holder = holder)).getJSONArray("settings")
            assertTrue((0 until choices.length()).any { choices.getJSONObject(it).optString("setting") == "show_keyboard_with_hardware_keyboard" })
            val changed = requireOk(command("device.secure_settings.set", JSONObject()
                .put("setting", "show_keyboard_with_hardware_keyboard").put("enabled", desired), holder))
            assertEquals(desired, changed.getBoolean("enabled"))
            assertTrue(changed.getBoolean("verified"))
            assertEquals(written, Settings.Secure.getString(resolver, key))
            assertPrimaryUnchanged()
        } finally {
            // Restore the original raw value (including a previously absent setting), but
            // never overwrite a different value supplied by somebody else during the test.
            val now = Settings.Secure.getString(resolver, key)
            if (now != previous) {
                assertEquals("Setting changed concurrently; leave the newer value untouched", written, now)
                assertTrue(Settings.Secure.putString(resolver, key, previous))
                assertEquals(previous, Settings.Secure.getString(resolver, key))
            }
        }
    }
}
