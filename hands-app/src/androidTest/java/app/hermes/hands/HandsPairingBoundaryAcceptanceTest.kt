package app.hermes.hands

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only after any long-duration phone proof. This subclass deliberately observes primary
 * only while our synthetic test APK owns it; no personal application is read or controlled.
 */
@RunWith(AndroidJUnit4::class)
class HandsPairingBoundaryAcceptanceTest : HandsAcceptanceSession() {
    private val workers = Executors.newCachedThreadPool()
    private var touchedRuntime = false
    private lateinit var holder: String
    private lateinit var commandLock: ReentrantLock
    private lateinit var boundary: LocalPairingBoundary

    @Before fun prepareARealPrimaryController() {
        if (runtime.remoteAccess.value.enabled) {
            instrumentation.runOnMainSync { runtime.disableRemote(clearPreference = false) }
        }
        assumeFalse("Finish any independent screen-hold proof first", getStatus().getJSONObject("keep_awake").optBoolean("standalone"))
        touchedRuntime = true
        commandLock = HandsRuntime::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(runtime) as ReentrantLock
        boundary = HandsRuntime::class.java.getDeclaredField("pairingBoundary").apply { isAccessible = true }.get(runtime) as LocalPairingBoundary
        instrumentation.runOnMainSync {
            runtime.configure("shizuku", "primary")
            runtime.approve()
        }
        holder = arm()
        // Deliberately move primary to ONLY our synthetic app. No personal app is inspected.
        primaryPackage = null
        requireOk(command("device.open_app", JSONObject().put("package", FIXTURE_PACKAGE), holder))
        await("the synthetic primary fixture", 8_000) { runtime.shizuku.foregroundPackage(0) == FIXTURE_PACKAGE }
        primaryPackage = FIXTURE_PACKAGE
        val before = snapshotUntil(holder) { containsText(it, "HANDS ACCEPTANCE FIXTURE") }
        assertEquals(0, before.getInt("display_id"))
        // Local test setup only: no remote command can enable this listener.
        runtime.enableRemote()
        assertTrue(runtime.remoteAccess.value.enabled)
    }

    @After fun cleanBoundaryAcceptanceBeforeSharedFixtureCleanup() {
        try {
            if (touchedRuntime) {
                instrumentation.runOnMainSync { runtime.disarm() }
                runtime.disableRemote()
                // If a guard regression accidentally enabled this test's standalone hold,
                // remove it; setup excluded pre-existing standalone user holds.
                runtime.command(client, envelope("device.display.keep_awake", JSONObject()
                    .put("action", "set").put("enabled", false)))
            }
        } finally {
            try {
                workers.shutdownNow()
                assertTrue("Boundary acceptance worker leaked", workers.awaitTermination(8, TimeUnit.SECONDS))
            } finally { if (touchedRuntime) restoreOwnPrimaryUi() }
        }
    }

    @Test fun realRuntimeDrainsTheOldPrimaryControllerBeforePairingCodeAppears() {
        val clientsBefore = runtime.tokens.clients().map { it.id }.toSet()
        val oldEpoch = boundary.epoch
        val oldWait = startRealInFlightWait()
        val opening = workers.submit(Callable { runtime.openRemotePairing() })
        await("the real runtime pairing protection", 1_000) { boundary.isProtected }
        assertTrue(boundary.epoch > oldEpoch)
        assertFalse("The existing wait must still be draining", oldWait.isDone)
        assertTrue("A pairing code appeared before old work finished", runtime.remotePairing.state.value.code.isEmpty())
        assertNull(runtime.remotePairing.state.value.pending)
        assertOldControllerPaused()
        assertTrue("The code must remain hidden while the old command owns the lock", oldWait.isDone || runtime.remotePairing.state.value.code.isEmpty())

        assertRefused(oldWait.get(7, TimeUnit.SECONDS), setOf("disarmed", "pairing_active"))
        opening.get(7, TimeUnit.SECONDS)
        assertFalse(commandLock.isLocked)
        assertTrue(runtime.remotePairing.state.value.open)
        assertTrue(runtime.remotePairing.state.value.code.matches(Regex("[0-9]{8}")))
        assertTrue(boundary.isProtected)
        assertFalse("Pairing must not arm control", getStatus().getBoolean("armed"))
        assertFalse(getStatus().getBoolean("consent_approved"))
        assertOldControllerPaused()

        // The local code UI can now become visible safely. Only our own main app is opened.
        restoreOwnPrimaryUi()
        val stillPaused = command("device.snapshot", holder = holder)
        assertRefused(stillPaused, setOf("pairing_active"))
        val localFailure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { localFailure.set(runCatching { runtime.approve() }.exceptionOrNull()) }
        assertTrue("Local control approval must refuse an active pairing window", localFailure.get() is IllegalStateException)

        val staleRelease = command("device.disarm", holder = holder)
        assertRefused(staleRelease, setOf("lease_mismatch", "disarmed"))
        assertTrue("An old holder cleanup closed the new pairing window", runtime.remotePairing.state.value.open)
        assertTrue(boundary.isProtected)

        // A genuine pending model request must also be cancelled by the runtime's Stop path.
        val code = runtime.remotePairing.state.value.code
        val pending = workers.submit(Callable {
            runtime.remotePairing.request(JSONObject().put("code", code).put("client_name", "Synthetic boundary peer"), "192.0.2.50")
        })
        await("the synthetic pending approval", 1_000) { runtime.remotePairing.state.value.pending != null }
        assertTrue(runtime.remotePairing.state.value.code.isEmpty())
        assertRefused(command("device.clipboard.get", holder = holder), setOf("pairing_active"))
        assertTrue(requireOk(command("device.disarm")).has("armed"))
        assertRefused(pending.get(2, TimeUnit.SECONDS), setOf("pairing_cancelled"))
        assertFalse(runtime.remotePairing.state.value.open)
        assertNull(runtime.remotePairing.state.value.pending)
        assertFalse(boundary.isProtected)
        assertFalse(getStatus().getBoolean("armed"))
        assertEquals("No client token may be issued without local Approve", clientsBefore, runtime.tokens.clients().map { it.id }.toSet())
        assertRefused(command("device.arm.renew", JSONObject().put("duration_seconds", 300), holder), setOf("disarmed", "lease_mismatch"))
        assertPrimaryUnchanged()
    }

    @Test fun realLocalStopInvalidatesAnOpeningTicketAndCannotQueueAReopenedCode() {
        val oldWait = startRealInFlightWait()
        val opening = workers.submit(Callable { runCatching { runtime.openRemotePairing() }.exceptionOrNull() })
        await("the opening protection", 1_000) { boundary.isProtected }
        assertTrue(runtime.remotePairing.state.value.code.isEmpty())
        val openingEpoch = boundary.epoch
        // Authenticated global Stop is deliberately available while opening is protected.
        requireOk(command("device.disarm"))
        assertTrue(boundary.epoch > openingEpoch)
        assertRefused(oldWait.get(7, TimeUnit.SECONDS), setOf("disarmed", "pairing_active"))
        assertEquals("pairing_cancelled", opening.get(7, TimeUnit.SECONDS)?.message)
        instrumentation.runOnMainSync { /* Drain any queued main-thread state observers. */ }
        assertFalse(runtime.remotePairing.state.value.open)
        assertTrue(runtime.remotePairing.state.value.code.isEmpty())
        assertNull(runtime.remotePairing.state.value.pending)
        assertFalse(boundary.isProtected)
        assertFalse(commandLock.isLocked)
        assertFalse(getStatus().getBoolean("armed"))
        assertFalse(getStatus().getBoolean("consent_approved"))
        assertRefused(command("device.disarm", holder = holder), setOf("lease_mismatch", "disarmed"))
        assertTrue(runtime.remotePairing.state.value.code.isEmpty())
        assertPrimaryUnchanged()
    }

    private fun startRealInFlightWait(): Future<JSONObject> {
        val waiting = workers.submit(Callable { command("device.wait", JSONObject().put("ms", 3_000), holder) })
        await("the actual runtime command lock", 1_000) { commandLock.isLocked && !waiting.isDone }
        assertTrue(runtime.remotePairing.state.value.code.isEmpty())
        return waiting
    }

    private fun assertOldControllerPaused() {
        val blocked = listOf(
            "device.snapshot" to JSONObject(),
            "device.click" to JSONObject().put("x", 100).put("y", 100),
            "device.type" to JSONObject().put("text", "MUST_NOT_BE_TYPED"),
            "device.arm" to JSONObject().put("duration_seconds", 300),
            "device.clipboard.get" to JSONObject(),
            "device.display.keep_awake" to JSONObject().put("action", "set").put("enabled", true).put("duration_seconds", 30),
        )
        for ((action, arguments) in blocked) assertRefused(command(action, arguments, holder), setOf("pairing_active"))
        val status = requireOk(command("device.status", holder = holder))
        assertFalse(status.has("code")); assertFalse(status.has("pairing_code"))
        assertFalse(status.getBoolean("armed"))
        requireOk(command("device.display.keep_awake", JSONObject().put("action", "status"), holder))
        requireOk(command("device.display.keep_awake", JSONObject().put("action", "set").put("enabled", false), holder))
    }

    private fun restoreOwnPrimaryUi() {
        primaryPackage = null
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        await("the controller's own primary UI", 8_000) { runtime.shizuku.foregroundPackage(0) == context.packageName }
        primaryPackage = context.packageName
    }

    private fun assertRefused(reply: JSONObject, allowed: Set<String>) {
        assertFalse("A protected action unexpectedly succeeded", reply.optBoolean("ok"))
        assertFalse("Protected failures must not contain phone content", reply.has("result"))
        assertTrue("Unexpected refusal code", reply.getJSONObject("error").getString("code") in allowed)
    }

    private fun envelope(action: String, arguments: JSONObject): JSONObject = JSONObject()
        .put("action", action).put("arguments", arguments).put("controller_id", controller)
}
