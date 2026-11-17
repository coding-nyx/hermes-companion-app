package app.hermes.companion.device

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import app.hermes.companion.model.SnapshotNode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DevicePrivacyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun forgetTestValues() {
        (DevicePrivacy::class.java.getDeclaredField("values").apply { isAccessible = true }.get(null) as MutableCollection<*>).clear()
    }
    private fun restartPrivacy() {
        DevicePrivacy::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        DevicePrivacy.initialize(app)
    }
    @Before fun reset() {
        app.getSharedPreferences("hermes_device_privacy", Context.MODE_PRIVATE).edit().clear().commit()
        forgetTestValues()
        restartPrivacy()
    }
    @After fun release() { DevicePrivacy.releaseLocally(app); forgetTestValues() }

    @Test fun shieldSurvivesProcessRestartAndOnlySuccessfulLocalReleaseClearsIt() {
        val before = DevicePrivacy.epoch.get()
        assertTrue(DevicePrivacy.begin(app))
        assertTrue(DevicePrivacy.active)
        assertTrue(DevicePrivacy.epoch.get() > before)
        restartPrivacy()
        assertTrue("A restarted application must remain private", DevicePrivacy.active)
        assertTrue(DevicePrivacy.releaseLocally(app))
        restartPrivacy()
        assertFalse(DevicePrivacy.active)
    }

    @Test fun readFailureFailsClosedAndRepeatedInitializationCannotResetAnActiveShield() {
        val failing = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("storage unavailable")
        }
        DevicePrivacy::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        DevicePrivacy.initialize(failing)
        assertTrue(DevicePrivacy.active)
        DevicePrivacy.initialize(app)
        assertTrue(DevicePrivacy.active)
        assertFalse(DevicePrivacy.releaseLocally(failing))
        assertTrue(DevicePrivacy.active)
    }

    @Test fun onlyStopAndSecureEntryStatusOrCancellationCanCrossTheShield() {
        assertTrue(DevicePrivacy.allows("device.disarm", "{}"))
        assertTrue(DevicePrivacy.allows("device.accessibility.disable", "{}"))
        assertTrue(DevicePrivacy.allows("device.secure_entry", """{"action":"status"}"""))
        assertTrue(DevicePrivacy.allows("device.secure_entry", """{"action":"cancel"}"""))
        listOf("device.snapshot", "device.screenshot", "device.files", "device.sms.list",
            "device.clipboard.get", "device.arm", "device.notifications", "device.type").forEach {
            assertFalse(it, DevicePrivacy.allows(it, "{}"))
        }
        assertFalse(DevicePrivacy.allows("device.secure_entry", """{"action":"request"}"""))
        assertFalse(DevicePrivacy.allows("device.secure_entry", """{"action":"release"}"""))
        assertFalse(DevicePrivacy.allows("device.secure_entry", "malformed"))
    }

    @Test fun knownValuesRemainRedactedAfterLocalReleaseWithoutPersistingThem() {
        val value = "private-test-स्वयं-541938"
        DevicePrivacy.begin(app)
        DevicePrivacy.rememberValue(value)
        DevicePrivacy.releaseLocally(app)
        assertEquals("before [private] after [private]", DevicePrivacy.sanitize("before $value after $value"))
        assertFalse(app.getSharedPreferences("hermes_device_privacy", Context.MODE_PRIVATE).all.toString().contains(value))
    }

    @Test fun redactionDoesNotReprocessItsOwnMarkersForShortValues() {
        DevicePrivacy.rememberValue("q")
        DevicePrivacy.rememberValue("private")
        assertEquals("[private] [private]", DevicePrivacy.sanitize("q private"))
    }

    @Test fun shieldStopsFileMutationsAndScreenReadsEvenOutsideTheCoordinator() {
        val folder = Files.createTempDirectory("privacy-test").toFile()
        try {
            val files = FileToolbox(app, { true }, { emptySet() }, { listOf(folder) }, { true })
            DevicePrivacy.begin(app)
            try {
                files.execute(JSONObject().put("action", "write").put("path", "blocked").put("content", "x").toString())
                fail("File mutation must be blocked")
            } catch (failure: FileToolbox.Failure) { assertEquals("privacy_active", failure.code) }
            assertFalse(folder.resolve("blocked").exists())
            val service = CompanionAccessibilityService()
            try { service.snapshot(); fail("Snapshot must be blocked") }
            catch (failure: IllegalStateException) { assertEquals("privacy_active", failure.message) }
            assertNull(service.screenshotPng())
        } finally { folder.deleteRecursively() }
    }

    @Test fun snapshotCollectorMasksPasswordsAndKeepsEmptyEditableFieldsAddressable() {
        val service = CompanionAccessibilityService()
        val walk = CompanionAccessibilityService::class.java.getDeclaredMethod("walk", AccessibilityNodeInfo::class.java, MutableList::class.java)
            .apply { isAccessible = true }
        val rows = mutableListOf<SnapshotNode>()
        val password = AccessibilityNodeInfo.obtain().apply { isPassword = true; isVisibleToUser = true; text = "must-never-return" }
        val editable = AccessibilityNodeInfo.obtain().apply { isEditable = true; isVisibleToUser = true }
        try {
            walk.invoke(service, password, rows)
            walk.invoke(service, editable, rows)
            assertEquals("[private]", rows[0].text)
            assertEquals(2, rows.size)
            assertEquals("e2", rows[1].ref)
        } finally { password.recycle(); editable.recycle() }
    }

    @Test fun failedSnapshotInvalidatesThePreviousSecureEntryGeneration() {
        val service = org.robolectric.Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        val token = CompanionAccessibilityService::class.java.getDeclaredField("secureSnapshotId").apply { isAccessible = true }
        token.set(service, "previous-private-token")
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        try { service.snapshotWithToken(); fail("Missing root must not return a successful snapshot") }
        catch (failure: DeviceObservationException) { assertEquals("screen_unstable", failure.code) }
        assertNull(token.get(service))
        assertNull(service.captureSecureTarget("e1", "com.example.target", "previous-private-token"))
        service.onDestroy()
    }
}
