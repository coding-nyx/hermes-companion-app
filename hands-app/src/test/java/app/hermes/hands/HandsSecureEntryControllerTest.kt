package app.hermes.hands

import android.app.Application
import android.content.Context
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.hands.engine.ShizukuController
import java.util.UUID
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
@Config(application = Application::class, sdk = [35], instrumentedPackages = ["app.hermes.hands"],
    shadows = [HandsSecureEntryControllerTest.NoCredentials::class])
class HandsSecureEntryControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: ShizukuController
    private lateinit var runtime: HandsRuntime
    @Before fun setup() {
        DevicePrivacy.initialize(context)
        DevicePrivacy.releaseLocally(context)
        engine = ShizukuController(context)
        runtime = HandsRuntime(context, engine)
    }
    @After fun cleanup() { runtime.disarm(); engine.close(); DevicePrivacy.releaseLocally(context) }

    @Test fun remoteRequestsRejectAllValueFieldsBeforeDeviceAccessOrPermit() {
        val controller = HandsSecureEntryController(context, runtime)
        var permits = 0
        listOf("text", "value", "content", "data_base64", "handle").forEach { key ->
            val args = request().put(key, SECRET)
            val failure = runCatching { controller.command("host", args) { permits++ } }.exceptionOrNull()
            assertEquals("invalid_args", failure?.message)
            assertFalse(controller.state.value.toString().contains(SECRET))
        }
        assertEquals(0, permits)
        assertFalse(DevicePrivacy.active)
    }
    @Test fun deniedPermitCannotCaptureOrStartThePrivateShield() {
        val controller = HandsSecureEntryController(context, runtime)
        val failure = runCatching { controller.command("host", request()) { error("disarmed") } }.exceptionOrNull()
        assertEquals("disarmed", failure?.message)
        assertEquals("idle", controller.state.value.status)
        assertFalse(DevicePrivacy.active)
    }
    @Test fun processRecoveryKeepsTheShieldAndRejectsUnownedRemoteRequests() {
        assertTrue(DevicePrivacy.begin(context))
        val controller = HandsSecureEntryController(context, runtime)
        assertEquals(SecureEntryState(status = "expired", privacyActive = true), controller.state.value)
        listOf("status", "cancel").forEach { action ->
            val failure = runCatching { controller.command("other-host", JSONObject().put("action", action)
                .put("request_id", UUID.randomUUID().toString())) { fail("Metadata must not arm or capture") } }.exceptionOrNull()
            assertEquals("request_not_found", failure?.message)
            assertTrue(DevicePrivacy.active)
            assertEquals("expired", controller.state.value.status)
        }
        assertFalse(controller.submit(UUID.randomUUID().toString(), SECRET))
        assertFalse(controller.state.value.toString().contains(SECRET))
        assertTrue(controller.releaseLocally())
        assertEquals("released", controller.state.value.status)
        assertFalse(DevicePrivacy.active)
    }
    @Test fun remoteReleaseMalformedIdsAndSecretBearingMetadataNeverReachTheDevice() {
        val controller = HandsSecureEntryController(context, runtime)
        listOf(JSONObject().put("action", "release"), JSONObject().put("action", "status").put("request_id", SECRET),
            JSONObject().put("action", "cancel").put("request_id", UUID.randomUUID().toString()).put("value", SECRET)).forEach { args ->
            val failure = runCatching { controller.command("host", args) { fail("Invalid requests must fail before a permit") } }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure?.message.orEmpty().contains(SECRET))
        }
    }
    @Test fun theLocalRecoveryActivityCarriesNoValueOrRequestExtras() {
        HandsSecureEntryController(context, runtime).open()
        val intent = org.robolectric.Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(SecureEntryActivity::class.java.name, intent.component?.className)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
    }
    @Test fun migratedProtectionRulesRetainCanonicalWildcardsAndRejectMalformedImports() {
        runtime.protect(setOf("com.synthetic.*", "app.synthetic.profile"))
        assertEquals(setOf("com.synthetic.*", "app.synthetic.profile"), runtime.state.value.protectedPackages)
        assertTrue(DeviceLanePolicy.isProtected("com.synthetic.chat", runtime.state.value.protectedPackages))
        assertTrue(DeviceLanePolicy.isProtected("com.synthetic.chat.child", runtime.state.value.protectedPackages))
        assertFalse(DeviceLanePolicy.isProtected("com.syntheticother.chat", runtime.state.value.protectedPackages))
        assertThrows(IllegalArgumentException::class.java) { runtime.protect(setOf("com.synthetic*")) }
        assertEquals(setOf("com.synthetic.*", "app.synthetic.profile"), runtime.state.value.protectedPackages)
    }
    private fun request() = JSONObject().put("action", "request").put("package", "app.synthetic.profile")
        .put("ref", "e1").put("label", "Postal address").put("snapshot_id", UUID.randomUUID().toString())

    /** Credential storage is outside this test; no keystore, token or network is created. */
    @Implements(ClientTokens::class, isInAndroidSdk = false)
    class NoCredentials {
        @Implementation fun __constructor__(context: Context) = Unit
        @Implementation fun clients(): List<ClientTokens.Client> = emptyList()
    }
    companion object { private const val SECRET = "DUMMY-PRIVATE-1049" }
}
