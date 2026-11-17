package app.hermes.hands

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/** Cross-artifact contracts, read without starting a runtime or loading any provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class HandsOwnershipContractTest {
    private val root: File get() = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "hands-mcp/tool_catalog.json").isFile }

    @Test fun everyMcpPhoneActionIsImplementedAndEveryPublicCapabilityHasAMcpRoute() {
        val catalog = JSONObject(File(root, "hands-mcp/tool_catalog.json").readText()).getJSONArray("tools")
        val mapped = (0 until catalog.length()).mapNotNull { catalog.getJSONObject(it).optString("action").takeIf(String::isNotEmpty) }.toSet()
        // File transfers expand to device.files and device discovery reads status. These are
        // declarative client handlers, not another Android implementation or arbitrary action API.
        val routedByHandler = setOf("device.files", "device.status")
        assertEquals("Catalog advertises unsupported Android actions", emptySet<String>(), mapped - HandsRuntime.CAPABILITIES)
        val internalAliases = setOf("device.arm.renew", "device.noop", "device.permissions", "device.power.battery", "device.power.thermal",
            "device.notifications.nls.status", "device.notifications.status", "device.notifications.enable",
            "device.notifications.stream.on", "device.notifications.stream.off")
        assertEquals("New phone capability needs an MCP route or an explicit internal-alias decision",
            emptySet<String>(), HandsRuntime.CAPABILITIES - mapped - routedByHandler - internalAliases)
        val names = (0 until catalog.length()).map { catalog.getJSONObject(it).getString("name") }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it in setOf("mobile_command", "mobile_select_device", "mobile_notifications_inject") })
    }

    @Test fun mergedHandsOwnsMobilePermissionsWhileCompanionOwnsNoMobileProvidersOrServices() {
        val hands = merged("hands-app")
        val companion = merged("app")
        val mobile = setOf("android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS", "android.permission.CALL_PHONE", "android.permission.READ_CALENDAR",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION", "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.REQUEST_DELETE_PACKAGES",
            "android.permission.ACCESS_NOTIFICATION_POLICY", "android.permission.WRITE_SETTINGS", "com.android.alarm.permission.SET_ALARM")
        assertTrue("Hands lost a permission needed by its migrated tools", names(hands, "uses-permission").containsAll(mobile))
        assertTrue("Hands must declare the operator-granted secure-settings permission",
            names(hands, "uses-permission").contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertFalse("Companion remains a client", names(companion, "uses-permission").contains("android.permission.WRITE_SECURE_SETTINGS"))
        val queries = hands.getElementsByTagName("queries")
        assertTrue("Hands must discover installed accessibility services on Android 11+",
            (0 until queries.length).any { i ->
                val actions = (queries.item(i) as Element).getElementsByTagName("action")
                (0 until actions.length).any { j ->
                    (actions.item(j) as Element).getAttributeNS(ANDROID, "name") == "android.accessibilityservice.AccessibilityService"
                }
            })
        assertEquals("Companion must forward mobile data access to Hands", emptySet<String>(), names(companion, "uses-permission") intersect mobile)
        val accessibility = "app.hermes.companion.device.CompanionAccessibilityService"
        val notificationListener = "app.hermes.companion.device.CompanionNotificationListener"
        val services = names(hands, "service")
        assertTrue(services.containsAll(setOf(accessibility, notificationListener, "app.hermes.hands.HandsHostService")))
        assertFalse("Legacy foreground controller must not be merged into Hands", services.contains("app.hermes.companion.device.HandsService"))
        assertTrue(names(companion, "service").none { it in setOf(accessibility, notificationListener, "app.hermes.companion.device.HandsService") })
        assertTrue(names(companion, "receiver").none { it in setOf("app.hermes.companion.device.DisarmReceiver", "app.hermes.companion.device.StopStreamReceiver") })
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", named(hands, "service", accessibility).getAttributeNS(ANDROID, "permission"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", named(hands, "service", notificationListener).getAttributeNS(ANDROID, "permission"))
        listOf("app.hermes.hands.SecureEntryActivity", "app.hermes.hands.NotificationSettingsActivity").forEach {
            assertEquals("false", named(hands, "activity", it).getAttributeNS(ANDROID, "exported"))
        }
        assertEquals("false", (hands.getElementsByTagName("application").item(0) as Element).getAttributeNS(ANDROID, "allowBackup"))
    }
    private fun merged(module: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(root, "$module/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml").also {
            check(it.isFile) { "Run :$module:processDebugManifest before the ownership contract" }
        })
    private fun names(doc: org.w3c.dom.Document, tag: String): Set<String> =
        (0 until doc.getElementsByTagName(tag).length).map { (doc.getElementsByTagName(tag).item(it) as Element).getAttributeNS(ANDROID, "name") }.toSet()
    private fun named(doc: org.w3c.dom.Document, tag: String, name: String): Element =
        (0 until doc.getElementsByTagName(tag).length).map { doc.getElementsByTagName(tag).item(it) as Element }
            .first { it.getAttributeNS(ANDROID, "name") == name }
    companion object { private const val ANDROID = "http://schemas.android.com/apk/res/android" }
}
