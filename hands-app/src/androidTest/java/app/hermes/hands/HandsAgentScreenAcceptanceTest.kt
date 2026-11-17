package app.hermes.hands

import android.hardware.display.DisplayManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Requires an unlocked real device and an already-authorized Shizuku installation. */
@RunWith(AndroidJUnit4::class)
class HandsAgentScreenAcceptanceTest : HandsAcceptanceSession() {
    @Test fun httpAgentScreenControlsOnlySyntheticDisplay() {
        val firstHolder = arm()
        val display = openSyntheticScene(firstHolder)

        clickRef(firstHolder, "Verify isolated tap")
        snapshotUntil(firstHolder) { containsText(it, "Isolated taps: 1") }
        clickRef(firstHolder, "Agent test input")
        requireOk(command("device.type", JSONObject().put("text", "HANDS_ASCII_123"), firstHolder))
        val typed = snapshotUntil(firstHolder) { containsText(it, "HANDS_ASCII_123") && findNode(it, "Verify typed value") != null }

        val shot = requireOk(command("device.screenshot", holder = firstHolder))
        verifyAndSavePng(shot, display)
        val bounds = checkNotNull(findNode(typed, "Verify typed value")).getJSONArray("bounds")
        val screenX = (bounds.getDouble(0) + bounds.getDouble(2)) / 2
        val screenY = (bounds.getDouble(1) + bounds.getDouble(3)) / 2
        val canvas = shot.getJSONArray("window_bounds")
        // Convert through the actual scaled PNG coordinate system, then back to the display.
        val pngX = (screenX - canvas.getDouble(0)) * shot.getInt("w") / (canvas.getDouble(2) - canvas.getDouble(0))
        val pngY = (screenY - canvas.getDouble(1)) * shot.getInt("h") / (canvas.getDouble(3) - canvas.getDouble(1))
        val mappedX = canvas.getDouble(0) + pngX * (canvas.getDouble(2) - canvas.getDouble(0)) / shot.getInt("w")
        val mappedY = canvas.getDouble(1) + pngY * (canvas.getDouble(3) - canvas.getDouble(1)) / shot.getInt("h")
        assertEquals(screenX, mappedX, .5); assertEquals(screenY, mappedY, .5)
        requireOk(command("device.click", JSONObject().put("x", mappedX).put("y", mappedY), firstHolder))
        snapshotUntil(firstHolder) { containsText(it, "Typed value verified") }

        var bottom = false
        repeat(10) {
            if (!bottom) {
                val view = snapshotUntil(firstHolder) { true }
                bottom = findNode(view, "Verify isolated scroll") != null
                if (!bottom) requireOk(command("device.scroll", JSONObject().put("direction", "down"), firstHolder))
            }
        }
        assertTrue("Scrolling must reach the synthetic footer", bottom)
        clickRef(firstHolder, "Verify isolated scroll")
        snapshotUntil(firstHolder) { containsText(it, "Scroll verified") }

        val secondHolder = arm()
        assertNotEquals("Every ARM must return a distinct holder", firstHolder, secondHolder)
        assertTrue(requireOk(command("device.disarm", holder = firstHolder)).getBoolean("armed"))
        val staleCleanup = command("device.disarm", holder = firstHolder)
        assertFalse(staleCleanup.getBoolean("ok"))
        assertEquals("lease_mismatch", staleCleanup.getJSONObject("error").getString("code"))
        snapshotUntil(secondHolder) { containsText(it, "Scroll verified") }

        val foreign = command("device.snapshot", holder = secondHolder, controllerId = "acceptance-other-controller")
        assertFalse("A different controller cannot borrow the holder", foreign.getBoolean("ok"))
        assertEquals("lease_mismatch", foreign.getJSONObject("error").getString("code"))

        // The public adapter intentionally chooses the configured target itself. Probe the
        // privileged backend's invalid-display guard with this same authorized session only.
        val sessionField = HandsRuntime::class.java.getDeclaredField("privilegedSession").apply { isAccessible = true }
        val session = sessionField.getLong(runtime)
        assertTrue(session > 0)
        val invalidDisplay = display + 10_000
        assertNull(context.getSystemService(DisplayManager::class.java).getDisplay(invalidDisplay))
        val invalid = runtime.shizuku.execute("device.click", JSONObject().put("x", 20).put("y", 20)
            .put("_hands_session", session), invalidDisplay)
        assertFalse(invalid.getBoolean("ok"))
        assertEquals("invalid_display", invalid.getJSONObject("error").getString("code"))
        assertPrimaryUnchanged()
        snapshotUntil(secondHolder) { containsText(it, "Scroll verified") }

        instrumentation.runOnMainSync { runtime.disarm() }
        await("the real secondary display to be removed", 8_000) {
            context.getSystemService(DisplayManager::class.java).getDisplay(display) == null
        }
        val afterStop = command("device.click", JSONObject().put("x", 20).put("y", 20), secondHolder)
        assertFalse(afterStop.getBoolean("ok"))
        assertTrue(afterStop.getJSONObject("error").getString("code") in setOf("disarmed", "lease_mismatch"))
        assertFalse(getStatus().getJSONObject("agent_screen").getBoolean("active"))
        assertPrimaryUnchanged()
    }

    @Test fun missingDisplayAndLocalStopNeverFallBackToPrimary() {
        val holder = arm()
        assertFalse(getStatus().getJSONObject("agent_screen").getBoolean("active"))
        for ((action, arguments) in listOf(
            "device.click" to JSONObject().put("x", 100).put("y", 100),
            "device.snapshot" to JSONObject(),
            "device.screenshot" to JSONObject(),
        )) {
            val refused = command(action, arguments, holder)
            assertFalse("$action must refuse a missing agent display", refused.getBoolean("ok"))
            assertEquals("agent_screen_unavailable", refused.getJSONObject("error").getString("code"))
            assertFalse(refused.has("result"))
            assertPrimaryUnchanged()
        }
        instrumentation.runOnMainSync { runtime.disarm() }
        val refused = command("device.arm.renew", JSONObject().put("duration_seconds", 300), holder)
        assertFalse(refused.getBoolean("ok"))
        assertTrue(refused.getJSONObject("error").getString("code") in setOf("disarmed", "lease_mismatch"))
        assertPrimaryUnchanged()
    }
}
