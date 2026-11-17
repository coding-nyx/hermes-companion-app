package app.hermes.companion.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ScreenObservationTest {
    @Test fun gestureAcceptanceCannotPublishCompletionUntilAndroidCallback() {
        val completion = GestureCompletion()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> {
                started.countDown()
                completion.await(1_000)
                true
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse("Dispatch alone must leave the gesture pending", waiting.isDone)
            completion.complete()
            assertTrue(waiting.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun cancelledGestureAndMissingCallbackAreExplicitAndNeverSuccessful() {
        val cancelled = GestureCompletion().apply { cancel() }
        try { cancelled.await(100); fail("Cancelled gesture succeeded") }
        catch (failure: DeviceObservationException) { assertEquals("gesture_cancelled", failure.code) }
        try { GestureCompletion().await(1); fail("Missing callback succeeded") }
        catch (failure: DeviceObservationException) { assertEquals("gesture_timeout", failure.code) }
    }

    @Test fun privacyCanInterruptAnInFlightGestureWait() {
        try {
            GestureCompletion().await(500) { throw DeviceObservationException("privacy_active", "privacy_active") }
            fail("Private gesture wait succeeded")
        } catch (failure: DeviceObservationException) { assertEquals("privacy_active", failure.code) }
    }

    @Test fun captureFailuresKeepAndroidReasonRatherThanBecomingTimeouts() {
        val secure = ScreenCapturePolicy.failure(6)
        assertEquals("screenshot_secure_window", secure.code)
        assertEquals(6, secure.androidErrorCode)
        assertEquals("screenshot_rate_limited", ScreenCapturePolicy.failure(3).code)
        assertEquals("a11y_unavailable", ScreenCapturePolicy.failure(2).code)
        assertEquals("screenshot_invalid_display", ScreenCapturePolicy.failure(4).code)
        assertEquals("screenshot_invalid_window", ScreenCapturePolicy.failure(5).code)
        assertEquals("screenshot_failed", ScreenCapturePolicy.failure(1).code)
        assertEquals(99, ScreenCapturePolicy.failure(99).androidErrorCode)
    }

    @Test fun blackScreenWithOnlyAnEdgeHandleIsUnusableWithoutClaimingItIsSecure() {
        assertTrue(ScreenCapturePolicy.isBlank(498, 1080) { x, y ->
            if (x > 491 && y in 236..394) 0xff747474.toInt() else 0xff000000.toInt()
        })
        assertTrue(ScreenCapturePolicy.isBlank(100, 100) { _, _ -> 0xff000000.toInt() })
    }

    @Test fun darkUiWithReadableContentAndBrightUiAreNotClassifiedAsBlack() {
        assertFalse(ScreenCapturePolicy.isBlank(100, 100) { _, _ -> 0xff07080a.toInt() })
        assertFalse(ScreenCapturePolicy.isBlank(100, 100) { x, y ->
            if (x in 20..80 && y in 40..50) 0xffeeeeee.toInt() else 0xff000000.toInt()
        })
        assertFalse(ScreenCapturePolicy.isBlank(100, 100) { _, _ -> 0xffffffff.toInt() })
    }
}
