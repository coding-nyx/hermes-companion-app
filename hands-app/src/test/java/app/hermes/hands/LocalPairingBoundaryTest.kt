package app.hermes.hands

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocalPairingBoundaryTest {
    private val executor = Executors.newCachedThreadPool()

    @After fun cleanup() {
        executor.shutdownNow()
        assertTrue("Pairing drain worker leaked", executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun refused(code: String, operation: () -> Unit) {
        try { operation(); fail("Expected $code") }
        catch (failure: IllegalStateException) { assertEquals(code, failure.message) }
    }

    @Test fun protectionAndStopPrecedeDrainAndCodeStaysHiddenUntilOldCommandFinishes() {
        val commands = ReentrantLock()
        val stopped = CountDownLatch(1)
        val visible = AtomicBoolean()
        val readied = AtomicBoolean()
        lateinit var boundary: LocalPairingBoundary
        boundary = LocalPairingBoundary(commands,
            stop = {
                assertTrue(boundary.isProtected)
                assertEquals(1L, boundary.epoch)
                stopped.countDown()
            },
            show = {
                assertTrue("Pairing must be shown under the command barrier", commands.isHeldByCurrentThread)
                assertTrue(readied.get())
                visible.set(true)
            },
            ready = { assertTrue(commands.isHeldByCurrentThread); readied.set(true) },
            active = visible::get,
            waitMillis = 1_000,
        )
        commands.lock()
        try {
            val opening = executor.submit(Callable { boundary.open() })
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            assertTrue(boundary.isProtected)
            assertTrue(boundary.`protected`)
            assertFalse("Old input is still running; no code may be visible", visible.get())
            assertFalse("Readiness must be checked after draining", readied.get())
            commands.unlock()
            opening.get(1, TimeUnit.SECONDS)
            assertTrue(visible.get())
            assertTrue("The active pairing window must retain protection", boundary.isProtected)
            assertFalse(commands.isLocked)
            visible.set(false)
            assertFalse(boundary.isProtected)
        } finally { if (commands.isHeldByCurrentThread) commands.unlock() }
    }

    @Test fun drainTimeoutNeverShowsCodeAndEpochStillInvalidatesOldResults() {
        val commands = ReentrantLock()
        val stopped = AtomicInteger()
        val shown = AtomicInteger()
        val ready = AtomicInteger()
        val boundary = LocalPairingBoundary(commands, { stopped.incrementAndGet(); Unit },
            { shown.incrementAndGet(); Unit }, { ready.incrementAndGet(); Unit }, { false }, 20)
        val oldEpoch = boundary.epoch
        commands.lock()
        try {
            val result = executor.submit(Callable {
                runCatching { boundary.open() }.exceptionOrNull()
            }).get(1, TimeUnit.SECONDS)
            assertTrue(result is IllegalStateException)
            assertEquals("pairing_drain_timeout", result?.message)
            assertEquals(1, stopped.get())
            assertEquals(0, ready.get())
            assertEquals(0, shown.get())
            assertNotEquals(oldEpoch, boundary.epoch)
            assertFalse(boundary.isProtected)
        } finally { commands.unlock() }
        boundary.open()
        assertEquals(2L, boundary.epoch)
        assertEquals(1, shown.get())
        assertFalse(commands.isLocked)
    }

    @Test fun failedStopClearsOpeningWithoutShowingOrHoldingTheLock() {
        val commands = ReentrantLock()
        val stops = AtomicInteger()
        val shown = AtomicInteger()
        val ready = AtomicInteger()
        val boundary = LocalPairingBoundary(commands,
            stop = { if (stops.incrementAndGet() == 1) throw IllegalStateException("cancel_failed") },
            show = { shown.incrementAndGet(); Unit }, ready = { ready.incrementAndGet(); Unit }, active = { false })
        refused("cancel_failed") { boundary.open() }
        assertEquals(1L, boundary.epoch)
        assertFalse(boundary.isProtected)
        assertFalse(commands.isLocked)
        assertEquals(0, ready.get()); assertEquals(0, shown.get())
        boundary.open()
        assertEquals(2L, boundary.epoch)
        assertEquals(1, shown.get())
    }

    @Test fun readinessIsRecheckedAfterDrainAndFailureReleasesTheBarrier() {
        val commands = ReentrantLock()
        val ready = AtomicInteger()
        val shown = AtomicInteger()
        val boundary = LocalPairingBoundary(commands, {}, { shown.incrementAndGet(); Unit }, {
            assertTrue(commands.isHeldByCurrentThread)
            if (ready.incrementAndGet() == 1) throw IllegalStateException("network_disabled")
        }, { false })
        refused("network_disabled") { boundary.open() }
        assertEquals(1L, boundary.epoch)
        assertEquals(0, shown.get())
        assertFalse(commands.isLocked)
        assertFalse(boundary.isProtected)
        boundary.open()
        assertEquals(1, shown.get())
        assertEquals(2L, boundary.epoch)
    }

    @Test fun concurrentOpenCannotRunAnotherStopOrClearTheFirstOpeningFlag() {
        val commands = ReentrantLock()
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger()
        val shown = AtomicInteger()
        val boundary = LocalPairingBoundary(commands, { stops.incrementAndGet(); stopped.countDown() },
            { shown.incrementAndGet(); Unit }, {}, { false }, 1_000)
        commands.lock()
        try {
            val first = executor.submit(Callable { boundary.open() })
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            refused("pairing_opening") { boundary.open() }
            assertTrue(boundary.isProtected)
            assertEquals(1L, boundary.epoch)
            assertEquals(1, stops.get())
            assertEquals(0, shown.get())
            commands.unlock()
            first.get(1, TimeUnit.SECONDS)
            assertEquals(1, shown.get())
            assertFalse(boundary.isProtected)
        } finally { if (commands.isHeldByCurrentThread) commands.unlock() }
    }

    @Test fun interruptionCancelsTheOpeningAndPreservesTheInterruptFlag() {
        val commands = ReentrantLock()
        val stopped = CountDownLatch(1)
        val shown = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val boundary = LocalPairingBoundary(commands, { stopped.countDown() }, { shown.set(true) }, {}, { false })
        val worker = Thread {
            try { boundary.open() } catch (error: Throwable) { failure.set(error) }
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        commands.lock()
        try {
            worker.start()
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(1_000)
            assertFalse(worker.isAlive)
            assertEquals("pairing_cancelled", failure.get()?.message)
            assertTrue(interrupted.get())
            assertFalse(shown.get())
            assertFalse(boundary.isProtected)
            assertEquals(1L, boundary.epoch)
        } finally {
            commands.unlock()
            worker.interrupt()
            worker.join(1_000)
        }
    }

    @Test fun reentrantLockOwnershipCannotMasqueradeAsACompletedOldCommand() {
        val commands = ReentrantLock()
        val stopped = AtomicBoolean()
        val shown = AtomicBoolean()
        val boundary = LocalPairingBoundary(commands, { stopped.set(true) }, { shown.set(true) }, {}, { false })
        commands.lock()
        try {
            refused("pairing_reentrant_open") { boundary.open() }
            assertTrue(stopped.get())
            assertFalse(shown.get())
            assertEquals(1, commands.holdCount)
            assertFalse(boundary.isProtected)
            assertEquals(1L, boundary.epoch)
        } finally { commands.unlock() }
    }

    @Test fun showFailureReleasesTheLockAndAllowsAnotherOpeningAttempt() {
        val commands = ReentrantLock()
        val calls = AtomicInteger()
        val boundary = LocalPairingBoundary(commands, {}, {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("pairing_unavailable")
        }, {}, { false })
        refused("pairing_unavailable") { boundary.open() }
        assertFalse(boundary.isProtected)
        assertFalse(commands.isLocked)
        boundary.open()
        assertEquals(2, calls.get())
        assertEquals(2L, boundary.epoch)
    }

    @Test fun unavailableActiveStateFailsClosed() {
        val boundary = LocalPairingBoundary(ReentrantLock(), {}, {}, {}, { throw IllegalStateException("unavailable") })
        assertTrue(boundary.isProtected)
        assertTrue(boundary.`protected`)
        assertEquals(0L, boundary.epoch)
    }

    @Test fun failedOpeningStillPreventsPublishingAPreBoundaryPassiveResult() {
        val boundary = LocalPairingBoundary(ReentrantLock(), {}, {}, { throw IllegalStateException("privacy_active") }, { false })
        val captured = boundary.epoch
        refused("privacy_active") { boundary.open() }
        val mayPublishOldResult = boundary.epoch == captured && !boundary.isProtected
        assertFalse(mayPublishOldResult)
        assertFalse(boundary.isProtected)
    }

    @Test fun stopDuringCommandDrainCancelsTheTicketBeforeAnyCodeCanBeShown() {
        val commands = ReentrantLock()
        val stopped = CountDownLatch(1)
        val shown = AtomicInteger()
        val ready = AtomicInteger()
        val boundary = LocalPairingBoundary(commands, { stopped.countDown() }, { shown.incrementAndGet(); Unit },
            { ready.incrementAndGet(); Unit }, { false }, 1_000)
        commands.lock()
        try {
            val pending = executor.submit(Callable { runCatching { boundary.open() }.exceptionOrNull() })
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            val openingEpoch = boundary.epoch
            boundary.cancel()
            assertTrue(boundary.epoch > openingEpoch)
            assertEquals(0, shown.get())
            commands.unlock()
            assertEquals("pairing_cancelled", pending.get(1, TimeUnit.SECONDS)?.message)
            assertEquals(0, ready.get())
            assertEquals(0, shown.get())
            assertFalse(boundary.isProtected)
            assertFalse(commands.isLocked)
        } finally { if (commands.isHeldByCurrentThread) commands.unlock() }
    }

    @Test fun stopSerializesWithReadyAndShowThenCallerClosesTheVisibleWindow() {
        val commands = ReentrantLock()
        val enteredReady = CountDownLatch(1)
        val finishReady = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
        val visible = AtomicBoolean()
        val events = CopyOnWriteArrayList<String>()
        val boundary = LocalPairingBoundary(commands, {}, {
            events += "show"
            visible.set(true)
        }, {
            enteredReady.countDown()
            check(finishReady.await(1, TimeUnit.SECONDS))
        }, visible::get)
        val pending = executor.submit(Callable { boundary.open() })
        assertTrue(enteredReady.await(1, TimeUnit.SECONDS))
        val openingEpoch = boundary.epoch
        val cancel = executor.submit {
            cancelStarted.countDown()
            boundary.cancel()
            events += "cancel"
            // Runtime closes RemotePairing after invalidating the boundary.
            visible.set(false)
            events += "close"
            cancelFinished.countDown()
        }
        try {
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS))
            assertFalse("Stop must serialize behind the final decision", cancelFinished.await(20, TimeUnit.MILLISECONDS))
            assertFalse(visible.get())
        } finally { finishReady.countDown() }
        pending.get(1, TimeUnit.SECONDS)
        cancel.get(1, TimeUnit.SECONDS)
        assertEquals(listOf("show", "cancel", "close"), events.toList())
        assertFalse(visible.get())
        assertFalse(boundary.isProtected)
        assertFalse(commands.isLocked)
        assertTrue(boundary.epoch > openingEpoch)
    }

    @Test fun cancellationFromReadinessCannotBeFollowedByShow() {
        val commands = ReentrantLock()
        val shown = AtomicBoolean()
        lateinit var boundary: LocalPairingBoundary
        boundary = LocalPairingBoundary(commands, {}, { shown.set(true) }, { boundary.cancel() }, { false })
        refused("pairing_cancelled") { boundary.open() }
        assertFalse(shown.get())
        assertFalse(boundary.isProtected)
        assertFalse(commands.isLocked)
        assertTrue(boundary.epoch >= 2)
    }

    @Test fun cancelInvalidatesEpochButCallerStillOwnsClosingAnAlreadyActiveWindow() {
        val active = AtomicBoolean(true)
        val boundary = LocalPairingBoundary(ReentrantLock(), {}, {}, {}, active::get)
        val previous = boundary.epoch
        boundary.cancel()
        assertTrue(boundary.epoch > previous)
        assertTrue(boundary.isProtected)
        active.set(false)
        assertFalse(boundary.isProtected)
    }

    @Test fun localCredentialApprovalUsesOverrideCallbacksBehindTheSameProtectionBarrier() {
        val commands = ReentrantLock()
        val active = AtomicBoolean()
        val stopped = AtomicInteger()
        val defaults = AtomicInteger()
        val order = mutableListOf<String>()
        lateinit var boundary: LocalPairingBoundary
        boundary = LocalPairingBoundary(commands, { stopped.incrementAndGet(); Unit },
            { defaults.incrementAndGet(); Unit }, { defaults.incrementAndGet(); Unit }, active::get)
        boundary.open(show = {
            assertTrue(commands.isHeldByCurrentThread)
            assertTrue(boundary.isProtected)
            order += "show local approval"
            active.set(true)
        }, ready = {
            assertTrue(commands.isHeldByCurrentThread)
            assertTrue(boundary.isProtected)
            order += "check local privacy"
        })
        assertEquals(listOf("check local privacy", "show local approval"), order)
        assertEquals(0, defaults.get())
        assertEquals(1, stopped.get())
        assertTrue(boundary.isProtected)
        assertEquals(1L, boundary.epoch)
        assertFalse(commands.isLocked)
        boundary.cancel()
        active.set(false)
        assertFalse(boundary.isProtected)
    }

    @Test fun oldApprovalCleanupCannotClearTheNewAuthorizationOrAdvanceItsEpoch() {
        val authorization = AtomicReference<String?>("old-activity")
        val boundary = LocalPairingBoundary(ReentrantLock(), {}, {}, {}, { authorization.get() != null })
        boundary.open(show = { authorization.set("new-activity") }, ready = {})
        val currentEpoch = boundary.epoch
        assertFalse(boundary.cancelIf({ authorization.get() == "old-activity" }, { authorization.set(null) }))
        assertEquals("new-activity", authorization.get())
        assertEquals(currentEpoch, boundary.epoch)
        assertTrue(boundary.isProtected)
        assertTrue(boundary.cancelIf({ authorization.get() == "new-activity" }, {
            assertTrue(boundary.epoch > currentEpoch)
            authorization.set(null)
        }))
        assertNull(authorization.get())
        assertFalse(boundary.isProtected)
    }

    @Test fun unconditionalCancelAdvancesEpochBeforeAtomicStateCleanup() {
        val active = AtomicBoolean(true)
        val boundary = LocalPairingBoundary(ReentrantLock(), {}, {}, {}, active::get)
        val previous = boundary.epoch
        boundary.cancel {
            assertTrue(boundary.epoch > previous)
            active.set(false)
        }
        assertFalse(boundary.isProtected)
    }

    @Test fun negativeWaitIsRejected() {
        try {
            LocalPairingBoundary(ReentrantLock(), {}, {}, {}, { false }, -1)
            fail("A negative drain timeout must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
