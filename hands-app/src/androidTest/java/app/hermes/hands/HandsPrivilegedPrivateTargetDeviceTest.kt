package app.hermes.hands

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exact-node private RPC coverage, restricted to the synthetic APK on its agent display. */
@RunWith(AndroidJUnit4::class)
class HandsPrivilegedPrivateTargetDeviceTest : HandsAcceptanceSession() {
    private fun engineEpoch(): Long = HandsRuntime::class.java.getDeclaredField("privilegedSession")
        .apply { isAccessible = true }.getLong(runtime)

    private fun capture(holder: String, display: Int, epoch: Long): String {
        repeat(3) {
            val view = snapshotUntil(holder) { findNode(it, "Agent test input") != null }
            val ref = checkNotNull(findNode(view, "Agent test input")).getString("ref")
            try { return runtime.shizuku.captureSecureTarget(display, ref, view.getString("snapshot_id"), FIXTURE_PACKAGE, epoch) }
            catch (failure: IllegalStateException) {
                if (failure.message != "stale_ref") throw failure
                SystemClock.sleep(150)
            }
        }
        throw AssertionError("The synthetic private target did not remain stable")
    }

    @Test fun exactCapturedFieldSurvivesPrivacyCancellationAndNeverEchoesItsValue() {
        val holder = arm()
        val display = openSyntheticScene(holder)
        val oldEpoch = engineEpoch()
        val handle = capture(holder, display, oldEpoch)
        try {
            assertFalse(runtime.shizuku.fillSecureTarget(UUID.randomUUID().toString(), "must-not-fill"))
            runtime.shizuku.cancelAsync() // Mirrors the phone-local privacy handoff.
            assertTrue("Local exact-node fill must remain possible after remote cancellation",
                runtime.shizuku.fillSecureTarget(handle, "HANDS_ASCII_123"))
            assertFalse("A consumed private handle is one-shot", runtime.shizuku.fillSecureTarget(handle, "must-not-fill"))
            val nextEpoch = runtime.shizuku.beginSession()
            fun localEngine(action: String, args: JSONObject = JSONObject()): JSONObject =
                runtime.shizuku.execute(action, args.put("_hands_session", nextEpoch), display)
            val view = requireOk(localEngine("device.snapshot"))
            assertEquals(FIXTURE_PACKAGE, view.getString("app"))
            assertFalse("The local value must not enter a remote text tree", view.toString().contains("HANDS_ASCII_123"))
            assertTrue(view.toString().contains("[private]"))
            val button = checkNotNull(findNode(view, "Verify typed value"))
            requireOk(localEngine("device.click", JSONObject().put("ref", button.getString("ref")).put("snapshot_id", view.getString("snapshot_id"))))
            val verified = requireOk(localEngine("device.snapshot"))
            assertTrue("The synthetic app must verify the exact value it received", containsText(verified, "Typed value verified"))
            assertPrimaryUnchanged()
        } finally { runtime.shizuku.releaseSecureTarget(handle) }
    }

    @Test fun staleSnapshotExplicitReleaseAndDisplayLossCannotRetargetPrivateInput() {
        val holder = arm()
        val display = openSyntheticScene(holder)
        val epoch = engineEpoch()
        val old = snapshotUntil(holder) { findNode(it, "Agent test input") != null }
        snapshotUntil(holder) { findNode(it, "Agent test input") != null }
        val rejected = assertThrows(IllegalStateException::class.java) {
            runtime.shizuku.captureSecureTarget(display, checkNotNull(findNode(old, "Agent test input")).getString("ref"),
                old.getString("snapshot_id"), FIXTURE_PACKAGE, epoch)
        }
        assertEquals("stale_ref", rejected.message)
        val released = capture(holder, display, epoch)
        runtime.shizuku.releaseSecureTarget(released)
        runtime.shizuku.releaseSecureTarget(released)
        assertFalse(runtime.shizuku.fillSecureTarget(released, "must-not-fill"))

        val captured = capture(holder, display, epoch)
        runtime.shizuku.destroyAgentScreen()
        assertFalse(runtime.shizuku.fillSecureTarget(captured, "must-not-fill"))
        runtime.shizuku.releaseSecureTarget(captured)
        assertPrimaryUnchanged()
    }
}
