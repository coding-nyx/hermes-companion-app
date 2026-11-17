package app.hermes.hands.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngineSessionsTest {
    @Test fun cancelRejectsACommandThatHadAlreadyPassedItsOuterCheck() {
        val sessions = EngineSessions()
        val epoch = sessions.begin()
        sessions.require(epoch)
        sessions.cancel()
        var started = false
        assertThrows(IllegalStateException::class.java) { sessions.withPermit(epoch) { started = true } }
        assertFalse(started)
    }

    @Test fun reapprovalDoesNotReactivateAnOlderSessionToken() {
        val sessions = EngineSessions()
        val old = sessions.begin()
        sessions.cancel()
        val fresh = sessions.begin()
        assertNotEquals(old, fresh)
        assertThrows(IllegalStateException::class.java) { sessions.require(old) }
        sessions.require(fresh)
    }

    @Test fun creationAndCancellationCannotPassEachOther() {
        val sessions = EngineSessions()
        val epoch = sessions.begin()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            var active = false
            val creating = pool.submit { sessions.withPermit(epoch) {
                active = true
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val cancelling = pool.submit { sessions.cancel { active = false } }
            release.countDown()
            creating.get(2, TimeUnit.SECONDS)
            cancelling.get(2, TimeUnit.SECONDS)
            assertFalse(active)
            assertThrows(IllegalStateException::class.java) { sessions.withPermit(epoch) { fail("Started after cancellation") } }
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
