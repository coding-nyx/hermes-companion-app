package app.hermes.hands

import org.junit.Assert.*
import org.junit.Test

class ControlLeaseTest {
    private var time = 1_000L
    private val gate = ControlLease { time }
    private fun refuses(code: String, action: () -> Unit) {
        try { action(); fail("Expected refusal: $code") }
        catch (e: IllegalStateException) { assertEquals(code, e.message) }
    }

    @Test fun leaseMustBeEnabledBeforeAcquisition() {
        refuses("local_consent_required") { gate.acquire("client:agent", 300) }
        assertFalse(gate.approved())
    }
    @Test fun uniqueHoldersAndOldCleanupPreserveNewTask() {
        gate.approve()
        val reasoning = gate.acquire("client:agent", 300)
        val tool = gate.acquire("client:agent", 600)
        assertNotEquals(reasoning.id, tool.id)
        assertFalse(gate.release("client:agent", reasoning.id))
        assertEquals(tool, gate.require("client:agent", tool.id))
        assertTrue(gate.active())
    }
    @Test fun controllerCannotBorrowOrStopAnotherController() {
        gate.approve()
        val holder = gate.acquire("one:profile", 300)
        refuses("controller_busy") { gate.acquire("two:profile", 300) }
        refuses("lease_mismatch") { gate.release("two:profile", holder.id) }
        assertTrue(gate.active())
    }
    @Test fun renewalDoesNotRequireAnotherLocalApproval() {
        gate.approve()
        val holder = gate.acquire("one", 60)
        time += 50_000
        val renewed = gate.renew("one", holder.id, 7200)
        assertEquals(time + 7_200_000L, renewed.deadline)
        time = renewed.deadline
        refuses("disarmed") { gate.require("one", holder.id) }
        refuses("local_consent_required") { gate.acquire("one", 60) }
    }
    @Test fun heartbeatCannotReviveAnExpiredHolder() {
        gate.approve()
        val holder = gate.acquire("one", 30)
        time += 30_001
        refuses("disarmed") { gate.renew("one", holder.id, 300) }
        assertFalse(gate.approved())
    }
    @Test fun disarmInvalidatesInFlightEpochAndAllHolders() {
        gate.approve()
        val holder = gate.acquire("one", 60)
        gate.revoke()
        assertNotEquals(holder.epoch, gate.currentEpoch())
        refuses("disarmed") { gate.require("one", holder.id) }
    }
    @Test fun expiryOfOneHolderDoesNotExpireAnother() {
        gate.approve()
        val short = gate.acquire("one", 30)
        val long = gate.acquire("one", 60)
        time += 30_001
        refuses("lease_mismatch") { gate.require("one", short.id) }
        assertEquals(long.id, gate.require("one", long.id).id)
    }
}
