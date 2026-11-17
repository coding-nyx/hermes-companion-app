package app.hermes.hands

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HandsSecureSettingsToolsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val reader = ComponentName("app.synthetic.reader", "app.synthetic.reader.Service")
    private val other = ComponentName("app.synthetic.other", "app.synthetic.other.Service")
    private val hidden = ComponentName("app.protected.reader", "app.protected.reader.Service")
    private lateinit var fake: Fake
    private lateinit var tools: HandsSecureSettingsTools
    private class Fake : HandsSecureSettingsTools.Platform {
        var permission = true
        var services = emptyList<HandsSecureSettingsTools.Service>()
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<Pair<String, String>>()
        var denyWrite = false
        var failRead = false
        val deniedKeys = mutableSetOf<String>()
        var ignoreWrite = false
        var onWrite: (() -> Unit)? = null
        override fun granted() = permission
        override fun installed() = services
        override fun read(key: String): String? {
            if (failRead) throw SecurityException("synthetic read refusal")
            return values[key]
        }
        override fun write(key: String, value: String): Boolean {
            writes += key to value
            onWrite?.invoke()
            if (denyWrite || key in deniedKeys) return false
            if (!ignoreWrite) values[key] = value
            return true
        }
    }
    @Before fun setup() {
        fake = Fake()
        fun entry(component: ComponentName, permission: String? = Manifest.permission.BIND_ACCESSIBILITY_SERVICE) =
            HandsSecureSettingsTools.Service(component, component.packageName, permission)
        fake.services = listOf(entry(reader), entry(other), entry(hidden), entry(ComponentName(app.packageName, "${app.packageName}.Hands")),
            entry(ComponentName("app.fake", "app.fake.NotAService"), null))
        fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] = other.flattenToShortString()
        tools = HandsSecureSettingsTools(app, { setOf("app.protected.*") }, fake)
    }
    private fun execute(action: String, args: JSONObject = JSONObject(), permit: () -> Unit = {}) =
        checkNotNull(tools.execute(action, args, permit))
    private fun service(component: ComponentName, enabled: Boolean) = JSONObject()
        .put("component", component.flattenToShortString()).put("enabled", enabled)
    private fun code(value: JSONObject) = value.getJSONObject("error").getString("code")

    @Test fun installedDiscoveryDoesNotRevealProtectedOrNonAccessibilityServices() {
        val result = execute("device.accessibility.services").getJSONObject("result")
        val items = result.getJSONArray("services")
        assertEquals(3, items.length())
        assertTrue(result.getBoolean("can_write_secure_settings"))
        val rows = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.getString("component") }
        assertTrue(rows.getValue(other.flattenToString()).getBoolean("enabled"))
        assertFalse(rows.getValue(reader.flattenToString()).getBoolean("enabled"))
        assertTrue(rows.values.any { it.getBoolean("hands_service") })
        assertFalse(result.toString().contains("app.protected"))
        assertFalse(result.toString().contains("app.fake"))
    }
    @Test fun enablingOrDisablingOnlyChangesTheNamedServiceAndKeepsGlobalAccessibilityOn() {
        assertTrue(execute("device.accessibility.service.set", service(reader, true)).getBoolean("ok"))
        val enabled = fake.values.getValue(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertEquals("${other.flattenToShortString()}:${reader.flattenToString()}", enabled)
        assertEquals("1", fake.values[Settings.Secure.ACCESSIBILITY_ENABLED])
        fake.writes.clear()
        assertTrue(execute("device.accessibility.service.set", service(reader, false)).getBoolean("ok"))
        assertEquals(other.flattenToShortString(), fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES])
        assertTrue(fake.writes.none { it.first == Settings.Secure.ACCESSIBILITY_ENABLED })
    }
    @Test fun onlyInstalledUnprotectedNonHandsComponentsMayBeChanged() {
        assertEquals("protected_package", code(execute("device.accessibility.service.set", service(hidden, true))))
        assertEquals("own_service", code(execute("device.accessibility.service.set", service(ComponentName(app.packageName, "${app.packageName}.Hands"), true))))
        assertEquals("unknown_service", code(execute("device.accessibility.service.set", service(ComponentName("app.synthetic.absent", "app.synthetic.absent.Service"), true))))
        assertEquals("unknown_service", code(execute("device.accessibility.service.set", service(ComponentName("app.fake", "app.fake.NotAService"), true))))
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun privilegedWritesNeverProceedWhenGrantOrPermitIsMissing() {
        fake.permission = false
        val reply = execute("device.accessibility.service.set", service(reader, true))
        assertEquals("permission_denied", code(reply))
        assertTrue(reply.getJSONObject("error").getString("message").contains("WRITE_SECURE_SETTINGS"))
        assertEquals("permission_denied", code(execute("device.secure_settings.set", JSONObject().put("setting", "display_inversion").put("enabled", true))))
        assertTrue(fake.writes.isEmpty())
        fake.permission = true
        assertThrows(IllegalStateException::class.java) { execute("device.accessibility.service.set", service(reader, true)) { error("disarmed") } }
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun revokedPermitStopsBeforeMutationAndSuppressesResultsAfterMutation() {
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            execute("device.accessibility.service.set", service(reader, true)) { if (++checks == 2) error("disarmed") }
        }
        assertTrue(fake.writes.isEmpty())
        checks = 0
        assertThrows(IllegalStateException::class.java) {
            execute("device.secure_settings.set", JSONObject().put("setting", "display_inversion").put("enabled", true)) {
                if (++checks >= 3) error("disarmed")
            }
        }
        assertEquals(1, fake.writes.size)
    }
    @Test fun revokedPermitRollsBackOnlyTheNewServiceAndPreservesConcurrentChanges() {
        val addedLater = ComponentName("app.synthetic.concurrent", "app.synthetic.concurrent.Service")
        var calls = 0
        val original = other.flattenToShortString()
        fake.onWrite = {
            if (++calls == 1) {
                // Simulate another Android settings client adding a service just after our write.
                fake.onWrite = null
            }
        }
        var permits = 0
        assertThrows(IllegalStateException::class.java) {
            execute("device.accessibility.service.set", service(reader, true)) {
                if (++permits == 3) {
                    fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] =
                        fake.values.getValue(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) + ":" + addedLater.flattenToString()
                    error("disarmed")
                }
            }
        }
        assertEquals("$original:${addedLater.flattenToString()}", fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES])
        assertTrue(fake.writes.none { it.first == Settings.Secure.ACCESSIBILITY_ENABLED })
    }
    @Test fun revokedPermitNeverReenablesAnExplicitlyDisabledService() {
        fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] = "${other.flattenToShortString()}:${reader.flattenToString()}"
        var permits = 0
        assertThrows(IllegalStateException::class.java) {
            execute("device.accessibility.service.set", service(reader, false)) { if (++permits == 3) error("disarmed") }
        }
        assertEquals(other.flattenToShortString(), fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES])
    }
    @Test fun settingsAreFiniteTypedAndMustBeVerifiedByReadback() {
        val enable = JSONObject().put("setting", "display_inversion").put("enabled", true)
        assertTrue(execute("device.secure_settings.set", enable).getJSONObject("result").getBoolean("verified"))
        assertEquals("1", fake.values[Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED])
        assertEquals("invalid_args", code(execute("device.secure_settings.set", JSONObject().put("setting", "adb_enabled").put("enabled", true))))
        assertEquals("invalid_args", code(execute("device.secure_settings.set", JSONObject().put("setting", "display_inversion").put("enabled", "true"))))
        fake.ignoreWrite = true
        assertEquals("setting_unverified", code(execute("device.secure_settings.set", JSONObject().put("setting", "display_inversion").put("enabled", false))))
    }
    @Test fun absentSecureTogglesAreUnknownAndUnsetServiceListMeansEmpty() {
        fake.values.clear()
        val serviceRows = execute("device.accessibility.services").getJSONObject("result").getJSONArray("services")
        assertFalse(serviceRows.getJSONObject(0).getBoolean("enabled"))
        val settings = execute("device.secure_settings.list").getJSONObject("result").getJSONArray("settings")
        assertEquals(2, settings.length())
        assertTrue(settings.getJSONObject(0).isNull("enabled"))
        assertTrue(settings.getJSONObject(1).isNull("enabled"))
    }
    @Test fun failedGlobalEnableRollsBackOnlyTheNewComponent() {
        fake.deniedKeys += Settings.Secure.ACCESSIBILITY_ENABLED
        val result = execute("device.accessibility.service.set", service(reader, true))
        assertEquals("setting_unverified", code(result))
        assertEquals(other.flattenToShortString(), fake.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES])
    }
    @Test fun rejectsUnexpectedFieldsBeforeAnyPlatformCall() {
        assertEquals("invalid_args", code(execute("device.secure_settings.list", JSONObject().put("key", "adb_enabled"))))
        assertEquals("invalid_args", code(execute("device.accessibility.service.set", service(reader, true).put("arbitrary", "value"))))
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun platformReadRefusalDoesNotMisreportTheSecureSettingsGrant() {
        fake.permission = false
        fake.failRead = true
        assertEquals("settings_unavailable", code(execute("device.accessibility.services")))
        assertTrue(fake.writes.isEmpty())
    }
}
