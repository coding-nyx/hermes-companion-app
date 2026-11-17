package app.hermes.hands

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in authenticated HTTP test; removes the fixture's old approval before exercising ARM. */
@RunWith(AndroidJUnit4::class)
class HandsServiceGateAcceptanceTest : HandsAcceptanceSession() {
    override fun enabled(): Boolean = InstrumentationRegistry.getArguments()
        .getString("hands_service_gate_acceptance") == "true"

    @Test fun serviceAllowsArmAgainAfterHolderDisarmsWithoutLocalApproval() {
        instrumentation.runOnMainSync { runtime.stopSession() }
        assertTrue(runtime.state.value.serving)
        assertFalse(runtime.state.value.consent)

        val targetMismatch = command("device.arm", JSONObject().put("target", "primary"))
        assertFalse(targetMismatch.getBoolean("ok"))
        assertEquals("target_mismatch", targetMismatch.getJSONObject("error").getString("code"))
        assertTrue(targetMismatch.getJSONObject("error").getString("message").contains("target=agent"))
        val backendMismatch = command("device.arm", JSONObject().put("backend", "hybrid"))
        assertFalse(backendMismatch.getBoolean("ok"))
        assertEquals("backend_mismatch", backendMismatch.getJSONObject("error").getString("code"))
        assertTrue(backendMismatch.getJSONObject("error").getString("message").contains("backend=shizuku"))
        assertFalse(runtime.state.value.armed)

        val args = JSONObject().put("duration_seconds", 300).put("direct_only", true)
        val first = requireOk(command("device.arm", args)).getString("lease_id")
        assertTrue(runtime.state.value.armed)
        requireOk(command("device.disarm", holder = first))
        assertFalse(runtime.state.value.armed)
        assertTrue(runtime.state.value.serving)

        val second = requireOk(command("device.arm", args)).getString("lease_id")
        assertNotEquals(first, second)
        assertTrue(runtime.state.value.armed)
        requireOk(command("device.disarm", holder = second))
        assertPrimaryUnchanged()
    }
}
