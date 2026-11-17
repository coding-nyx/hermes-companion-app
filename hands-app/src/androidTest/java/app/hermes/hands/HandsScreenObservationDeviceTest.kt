package app.hermes.hands

import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.device.DeviceObservationException
import app.hermes.companion.device.ScreenObservation
import app.hermes.companion.model.SnapshotNode
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.json.JSONObject
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic native Accessibility screens; never enables a service or grants permission. */
@RunWith(AndroidJUnit4::class)
class HandsScreenObservationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var app: HandsApplication
    private lateinit var runtime: HandsRuntime
    private var client: ClientTokens.Client? = null
    private var holder: String? = null
    private val controller = "native-observation-${UUID.randomUUID()}"
    private var originalBackend = ""
    private var originalTarget = ""
    private var originalRules = emptySet<String>()
    private var originalClients = emptySet<String>()
    private var configured = false
    private var serviceStarted = false
    private val generatedImages = mutableListOf<java.io.File>()

    @Before fun prepareExplicitLocalAccessibilityFixture() {
        assumeTrue("Native Accessibility observation is opt-in",
            InstrumentationRegistry.getArguments().getString("hands_native_a11y_observation") == "true")
        app = instrumentation.targetContext.applicationContext as HandsApplication
        runtime = app.runtime
        assumeFalse("Stop existing control before native fixture testing", runtime.state.value.armed || runtime.state.value.consent)
        assumeFalse("Finish private entry locally first", DevicePrivacy.active)
        assumeTrue("Only existing Hands Accessibility permission is accepted", CompanionAccessibilityService.bound)
        assumeTrue("The existing service must belong to Hands", CompanionAccessibilityService.instance?.packageName == app.packageName)
        assumeTrue("Allow Hands notifications locally first", app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
        assumeTrue("Allow the floating screen locally first", android.provider.Settings.canDrawOverlays(app))
        assumeFalse("Unlock the phone before fixture testing", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        assumeTrue("Wake the phone before fixture testing", app.getSystemService(android.os.PowerManager::class.java).isInteractive)
        originalBackend = runtime.state.value.backend
        originalTarget = runtime.state.value.target
        originalRules = runtime.state.value.protectedPackages.toSet()
        originalClients = runtime.tokens.clients().map { it.id }.toSet()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instrumentation.runOnMainSync {
            runtime.configure("accessibility", "primary")
            configured = true
            app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!runtime.state.value.serving) {
                serviceStarted = true
                androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, HandsHostService::class.java))
            }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
        while (!runtime.state.value.serving && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Hands connection service did not become ready", runtime.state.value.serving)
        instrumentation.runOnMainSync { runtime.approve() }
        client = runtime.tokens.issue("Synthetic native observation").first
        val reply = runtime.command(requireNotNull(client), JSONObject().put("action", "device.arm")
            .put("controller_id", controller).put("arguments", JSONObject().put("duration_seconds", 300)))
        assertTrue("Native fixture ARM refused", reply.optBoolean("ok"))
        holder = reply.getJSONObject("result").getString("lease_id")
        assertEquals("l0+l1", reply.getJSONObject("result").getString("lanes"))
    }

    @After fun releaseOnlyTheFixtureHolderAndRestoreConfiguration() {
        if (!::runtime.isInitialized || !configured) return
        try {
            val owned = client
            val lease = holder
            if (owned != null && lease != null) {
                val released = runtime.command(owned, JSONObject().put("action", "device.disarm")
                    .put("controller_id", controller).put("lease_id", lease).put("arguments", JSONObject()))
                assertTrue("The fixture holder could not be released", released.optBoolean("ok"))
            }
        } finally {
            client?.let { runtime.tokens.revoke(it.id) }
            generatedImages.forEach { it.delete() }
            assertEquals("Native fixtures must preserve protected rules", originalRules, runtime.state.value.protectedPackages)
            assertTrue("Pre-existing clients must survive fixture cleanup", runtime.tokens.clients().map { it.id }.containsAll(originalClients))
            if (configured && !runtime.state.value.armed) instrumentation.runOnMainSync { runtime.configure(originalBackend, originalTarget) }
            if (serviceStarted && !runtime.state.value.armed) instrumentation.runOnMainSync {
                app.stopService(android.content.Intent(app, HandsHostService::class.java))
            }
            assertFalse("Cleanup must never stop a different controller", runtime.state.value.armed)
            if (serviceStarted) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                while (runtime.state.value.serving && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertFalse("The test-started service must stop", runtime.state.value.serving)
            }
        }
    }

    private fun awaitService(): CompanionAccessibilityService {
        val service = CompanionAccessibilityService.instance
        assertNotNull("Enable Hands Accessibility locally before this opt-in fixture", service)
        assertTrue("The fixture must own an armed Hands session", runtime.state.value.armed)
        return requireNotNull(service)
    }

    private fun saveSyntheticPixels(kind: String, bytes: ByteArray) {
        java.io.File.createTempFile("hands-observation-$kind-", ".png", app.cacheDir).also {
            generatedImages += it
            it.writeBytes(bytes)
        }
    }

    private fun tree(service: CompanionAccessibilityService, expected: String): ScreenObservation {
        val result = service.snapshot()
        assertTrue("Expected synthetic page $expected; observed package=${result.app}, nodes=${result.nodes.size}", result.nodes.any { it.text == expected })
        assertTrue(result.capturedAtMs > 0)
        assertTrue(result.windowId >= 0)
        return result
    }

    private fun button(tree: ScreenObservation, label: String): SnapshotNode {
        val node = tree.nodes.firstOrNull { it.text.equals(label, ignoreCase = true) && it.clickable }
        assertNotNull("Missing synthetic control $label; observed package=${tree.app}, nodes=${tree.nodes.size}", node)
        return requireNotNull(node)
    }

    private fun validate(service: CompanionAccessibilityService, expected: ScreenObservation) {
        fun state(): String = listOf("uiEpoch", "lastObservationEpoch").joinToString { name ->
            val field = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
            "$name=${field.get(service)}"
        }
        val before = state()
        if (!service.validateObservation(expected.windowId, expected.generation)) {
            val after = state()
            val current = runCatching { service.snapshot() }.fold({ "package=${it.app}, window=${it.windowId}, generation=${it.generation}" }, { it.javaClass.simpleName })
            fail("Ref validation failed; expected window=${expected.windowId}, generation=${expected.generation}; before=$before; after=$after; current=$current")
        }
    }

    @Test fun navigationReturnsCurrentDescriptionsAndDifferentNonblankPixels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 200, 48, 100) }
                fun page(second: Boolean) {
                    layout.removeAllViews()
                    layout.setBackgroundColor(if (second) Color.rgb(16, 20, 28) else Color.WHITE)
                    layout.addView(TextView(activity).apply {
                        text = if (second) "Observation page two" else "Observation page one"
                        textSize = 28f
                        setTextColor(if (second) Color.CYAN else Color.BLACK)
                    })
                    layout.addView(Button(activity).apply {
                        text = if (second) "Return to first page" else "Open second page"
                        minHeight = 160
                        setOnClickListener { page(!second) }
                    })
                }
                page(false)
                activity.setContentView(layout)
            }
            try {
                val service = awaitService()
                repeat(3) {
                    val first = tree(service, "Observation page one")
                    val firstPixels = requireNotNull(service.screenshotPng())
                    if (it == 0) saveSyntheticPixels("light", firstPixels.bytes)
                    assertEquals(first.windowId, firstPixels.windowId)
                    validate(service, first)
                    val next = button(first, "Open second page")
                    assertTrue(service.click(next))
                    assertFalse("Completed navigation must invalidate old refs", service.validateObservation(first.windowId, first.generation))
                    // Deliberately no manual sleep: the observation pipeline owns settling.
                    val second = tree(service, "Observation page two")
                    val secondPixels = requireNotNull(service.screenshotPng())
                    if (it == 0) saveSyntheticPixels("dark", secondPixels.bytes)
                    assertTrue(second.generation > first.generation)
                    assertTrue(first.nodes.map { it.ref }.intersect(second.nodes.map { it.ref }.toSet()).isEmpty())
                    assertFalse(firstPixels.bytes.contentEquals(secondPixels.bytes))
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(secondPixels.bytes, 0, secondPixels.bytes.size))
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    bitmap.recycle()
                    assertTrue(service.click(button(second, "Return to first page")))
                }
                tree(service, "Observation page one")
                assertTrue(service.press("home"))
                val home = service.snapshot()
                assertNotEquals("Home must report the launcher, not the previous app", app.packageName, home.app)
                assertEquals(home.app, requireNotNull(service.screenshotPng()).app)
                assertTrue(service.openApp(app.packageName))
                tree(service, "Observation page one")
                assertEquals(app.packageName, requireNotNull(service.screenshotPng()).app)
            } finally {
                // @After releases only this fixture's holder and restores local settings.
            }
        }
    }

    @Test fun secureWindowReturnsExplicitCaptureFailureAndRecoversAfterRelease() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(TextView(activity).apply {
                    text = "Synthetic capture protection test"
                    setBackgroundColor(Color.WHITE)
                    setTextColor(Color.BLACK)
                    textSize = 26f
                })
            }
            try {
                val service = awaitService()
                tree(service, "Synthetic capture protection test")
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                try {
                    service.screenshotPng()
                    fail("A secure window must not be reported as a readable screenshot")
                } catch (failure: DeviceObservationException) {
                    assertTrue(failure.code, failure.code in setOf("screenshot_secure_window", "screenshot_blank"))
                }
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                tree(service, "Synthetic capture protection test")
                assertNotNull(service.screenshotPng())
            } finally {
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
        }
    }
}
