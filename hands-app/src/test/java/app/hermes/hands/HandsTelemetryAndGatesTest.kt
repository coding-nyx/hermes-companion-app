package app.hermes.hands

import android.app.Application
import android.content.Context
import app.hermes.hands.engine.ShizukuController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE,
    instrumentedPackages = ["app.hermes.hands"],
    shadows = [HandsTelemetryAndGatesTest.NoCredentials::class])
class HandsTelemetryAndGatesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: ShizukuController
    private lateinit var runtime: HandsRuntime

    @Implements(ClientTokens::class, isInAndroidSdk = false)
    class NoCredentials {
        @Implementation fun __constructor__(context: Context) = Unit
        @Implementation fun clients(): List<ClientTokens.Client> = emptyList()
    }

    @Before fun setup() {
        engine = ShizukuController(app)
        runtime = HandsRuntime(app, engine)
    }

    @After fun cleanup() {
        runtime.disarm()
        engine.close()
    }

    @Test fun telemetryCollectsCompleteDeviceSnapshot() {
        val telemetry = HandsTelemetry.collect(app, runtime, includeMedia = true, includeNotifications = true)
        assertTrue(telemetry.has("battery"))
        assertTrue(telemetry.has("power"))
        assertTrue(telemetry.has("network"))
        assertTrue(telemetry.has("audio"))
        assertTrue(telemetry.has("hands"))

        val hands = telemetry.getJSONObject("hands")
        assertEquals("hybrid", hands.getString("backend"))
        assertEquals("agent", hands.getString("target"))
        assertTrue(hands.has("effective_target"))
    }

    @Test fun gatesGetReportsConfigurationAndCapabilities() {
        val gates = HandsGates.get(app, runtime)
        assertEquals("hybrid", gates.getString("backend"))
        assertEquals("agent", gates.getString("target"))
        assertTrue(gates.has("effective_target"))
        assertTrue(gates.has("accessibility_enabled"))
        assertTrue(gates.has("protected_packages"))
        assertTrue(gates.has("notification_stream"))
        assertTrue(gates.has("stay_awake_plugged"))
        assertTrue(gates.has("screen_off_timeout_ms"))
    }

    @Test fun gatesSetMutatesTargetAndBackendAndProtectedPackages() {
        val args = JSONObject()
            .put("backend", "accessibility")
            .put("target", "auto")
            .put("protected_packages", JSONObject().put("add", JSONArray(listOf("com.example.secret"))))
            .put("stay_awake_plugged", true)

        val updated = HandsGates.set(app, runtime, "test-client", args)
        assertEquals("accessibility", updated.getString("backend"))
        assertEquals("auto", updated.getString("target"))
        assertEquals("primary", updated.getString("effective_target"))
        assertTrue(updated.getBoolean("stay_awake_plugged"))

        val protectedList = (0 until updated.getJSONArray("protected_packages").length())
            .map { updated.getJSONArray("protected_packages").getString(it) }
        assertTrue("com.example.secret" in protectedList)
    }

    @Test fun capabilitiesIncludeNewAutonomousActions() {
        val newActions = setOf(
            "device.telemetry",
            "device.gates.get",
            "device.gates.set",
            "device.shell.exec",
            "device.power.wake",
            "device.keyguard.dismiss",
        )
        assertTrue(HandsRuntime.CAPABILITIES.containsAll(newActions))
    }
}
