package app.hermes.companion.device

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HandsKeepAwakeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val holds = mutableListOf<HandsKeepAwake>()

    @Before fun allowVisibleUnlockedHold() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsScreenOn(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        ShadowPowerManager.clearWakeLocks()
    }

    @After fun cleanup() {
        holds.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
        HandsBridge.onDisarm = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun hold(tag: String = "hands") = HandsKeepAwake(app, tag).also { holds += it }
    private fun overlays(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(
        app.getSystemService(WindowManager::class.java),
    ).views.filter { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun advanceBy(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        settleVisibleWindows()
    }

    private fun windowVisibility(view: View, visibility: Int) {
        val attachInfo = ReflectionHelpers.getField<Any?>(view, "mAttachInfo")
        assertNotNull("The accepted overlay must have attached before setting its platform visibility", attachInfo)
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", visibility)
    }

    private fun settleVisibleWindows() {
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric lays out app-context overlays with a GONE window despite accepting and
        // attaching them. Supply the platform window state explicitly, without bypassing the
        // production checks for View visibility, permission, power, keyguard or lease expiry.
        overlays().forEach { windowVisibility(it, View.VISIBLE) }
    }

    private fun visibilityDiagnostics(): String = "windows=${overlays().map { "visibility=${it.visibility},window=${it.windowVisibility}" }} " +
        "interactive=${app.getSystemService(PowerManager::class.java).isInteractive} " +
        "locked=${app.getSystemService(KeyguardManager::class.java).isKeyguardLocked} " +
        "overlay=${Settings.canDrawOverlays(app)} elapsed=${android.os.SystemClock.elapsedRealtime()}"

    private fun movableIndicator(): View {
        settleVisibleWindows()
        return overlays().single().also { indicator ->
            if (indicator.width <= 0 || indicator.height <= 0) {
                indicator.measure(View.MeasureSpec.makeMeasureSpec(app.resources.displayMetrics.widthPixels,
                    View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                indicator.layout(0, 0, indicator.measuredWidth, indicator.measuredHeight)
            }
            assertTrue("A native touch fixture needs a laid-out button", indicator.width > 0 && indicator.height > 0)
        }
    }

    private fun touch(indicator: View, action: Int, x: Float, y: Float, downTime: Long, offsetMs: Long = 0) {
        val event = MotionEvent.obtain(downTime, downTime + offsetMs, action, x, y, 0)
        try { indicator.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun independentOwnersShareOneVisibleWindowAndRetainSeparateDeadlines() {
        val armed = hold()
        val standalone = hold("standalone-screen")
        armed.renew(120_000)
        standalone.renew(30_000)
        settleVisibleWindows()

        assertEquals(1, overlays().size)
        val params = overlays().single().layoutParams as WindowManager.LayoutParams
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertTrue(visibilityDiagnostics(), armed.isHeld)
        assertTrue(standalone.isHeld)

        advanceBy(31_000)
        assertTrue(armed.isHeld)
        assertFalse(standalone.isHeld)
        assertEquals(1, overlays().size)

        standalone.renew(60_000)
        armed.release()
        assertFalse(armed.isHeld)
        assertTrue(standalone.isHeld)
        assertEquals(1, overlays().size)
        standalone.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlays().isEmpty())
    }

    @Test fun renewalExtendsDeadlineAndOneReleaseEndsTheHold() {
        val owner = hold()
        owner.renew(30_000)
        advanceBy(29_000)
        owner.renew(30_000)
        advanceBy(2_000)
        assertTrue(visibilityDiagnostics(), owner.isHeld)
        assertEquals(1, overlays().size)
        owner.release()
        owner.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(owner.isHeld)
        assertTrue(overlays().isEmpty())
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun timeoutRemovesTheIndicatorAndReleasesCpuHoldWithoutAnActivity() {
        val owner = hold()
        owner.renew(30_000)
        val wake = ShadowPowerManager.getLatestWakeLock()

        advanceBy(30_001)

        assertFalse(owner.isHeld)
        assertFalse(wake.isHeld)
        assertTrue(overlays().isEmpty())
    }

    @Test fun missingOverlayAccessFailsTruthfullyAndDoesNotAcquireAnyHold() {
        ShadowSettings.setCanDrawOverlays(false)
        val owner = hold()
        val failure = runCatching { owner.renew(30_000) }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertEquals("overlay_permission_required", failure?.message)
        assertFalse(owner.isHeld)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(overlays().isEmpty())
    }

    @Test
    @Config(shadows = [RejectedOverlayWindowManager::class])
    fun failedWindowAttachmentCannotClaimOrAcquireAHold() {
        val owner = hold()
        val failure = runCatching { owner.renew(30_000) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("overlay_unavailable", failure?.message)
        assertFalse(owner.isHeld)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(overlays().isEmpty())
    }

    @Test fun sleepingAndLockedPhonesAreNeverWokenOrClaimedHeld() {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val owner = hold()
        shadowOf(power).setIsScreenOn(false)
        assertEquals("screen_off", runCatching { owner.renew(30_000) }.exceptionOrNull()?.message)
        assertFalse(owner.isHeld)
        assertFalse(power.isInteractive)
        shadowOf(power).setIsScreenOn(true)
        shadowOf(keyguard).setKeyguardLocked(true)
        assertEquals("screen_locked", runCatching { owner.renew(30_000) }.exceptionOrNull()?.message)
        assertFalse(owner.isHeld)
        assertTrue(keyguard.isKeyguardLocked)
        assertTrue(overlays().isEmpty())
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun manualScreenOffClearsAllOwnersAndDoesNotAutomaticallyRearm() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val armed = hold()
        val standalone = hold("standalone-screen")
        armed.renew(120_000)
        standalone.renew(60_000)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsScreenOn(false)

        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(armed.isHeld)
        assertFalse(standalone.isHeld)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(overlays().isEmpty())
        assertEquals(1, stops)

        shadowOf(app.getSystemService(PowerManager::class.java)).setIsScreenOn(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(armed.isHeld)
        assertFalse(standalone.isHeld)
        assertTrue(overlays().isEmpty())
        assertEquals(1, stops)
    }

    @Test fun visibleStopButtonClearsAllOwnersAndInvokesKillSwitchOnce() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val armed = hold()
        val standalone = hold("standalone-screen")
        armed.renew(120_000)
        standalone.renew(60_000)
        val button = overlays().single()

        assertTrue(button.performClick())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(armed.isHeld)
        assertFalse(standalone.isHeld)
        assertTrue(overlays().isEmpty())
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, stops)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stops)
    }

    @Test fun movementWithinTouchSlopKeepsNativeTapToStop() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        val indicator = movableIndicator()
        val x = indicator.width / 2f
        val y = indicator.height / 2f
        val tinyMove = ViewConfiguration.get(app).scaledTouchSlop / 2f
        val down = android.os.SystemClock.uptimeMillis()

        touch(indicator, MotionEvent.ACTION_DOWN, x, y, down)
        touch(indicator, MotionEvent.ACTION_MOVE, x + tinyMove, y, down, 10)
        touch(indicator, MotionEvent.ACTION_UP, x + tinyMove, y, down, 20)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(owner.isHeld)
        assertTrue(overlays().isEmpty())
        assertEquals(1, stops)
    }

    @Test fun draggingPastTouchSlopMovesWindowAndKeepsTheHold() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        val indicator = movableIndicator()
        val params = indicator.layoutParams as WindowManager.LayoutParams
        val before = Triple(params.gravity, params.x, params.y)
        val x = indicator.width / 2f
        val y = indicator.height / 2f
        val move = ViewConfiguration.get(app).scaledTouchSlop * 5f
        val down = android.os.SystemClock.uptimeMillis()

        touch(indicator, MotionEvent.ACTION_DOWN, x, y, down)
        touch(indicator, MotionEvent.ACTION_MOVE, x + move, y + move, down, 10)
        touch(indicator, MotionEvent.ACTION_UP, x + move, y + move, down, 20)

        assertEquals(Gravity.TOP or Gravity.LEFT, params.gravity)
        assertNotEquals(before, Triple(params.gravity, params.x, params.y))
        settleVisibleWindows()
        assertEquals(0, stops)
        assertTrue(owner.isHeld)
        assertEquals(1, overlays().size)
    }

    @Test fun extremeDragClampsBothEdgesWithinDisplayAndSafeInsets() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        val indicator = movableIndicator()
        val params = indicator.layoutParams as WindowManager.LayoutParams
        val metrics = app.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val margin = (8 * app.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val x = indicator.width / 2f
        val y = indicator.height / 2f
        val down = android.os.SystemClock.uptimeMillis()

        touch(indicator, MotionEvent.ACTION_DOWN, x, y, down)
        touch(indicator, MotionEvent.ACTION_MOVE, x + 1_000_000, y + 1_000_000, down, 10)
        assertTrue(params.x >= margin)
        assertTrue(params.y >= margin)
        assertTrue(params.x + insets.left + indicator.width <= metrics.bounds.width() - insets.right - margin)
        assertTrue(params.y + insets.top + indicator.height <= metrics.bounds.height() - insets.bottom - margin)

        touch(indicator, MotionEvent.ACTION_MOVE, x - 1_000_000, y - 1_000_000, down, 20)
        assertEquals(margin, params.x)
        assertEquals(margin, params.y)
        touch(indicator, MotionEvent.ACTION_UP, x - 1_000_000, y - 1_000_000, down, 30)
        settleVisibleWindows()
        assertEquals(0, stops)
        assertTrue(owner.isHeld)
    }

    @Test fun accessibilityLongClickMovesCornerAndTheNextTapStillStops() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        val indicator = movableIndicator()
        val params = indicator.layoutParams as WindowManager.LayoutParams
        val before = params.gravity

        assertTrue(indicator.performLongClick())
        assertNotEquals(before, params.gravity)
        assertEquals(Gravity.BOTTOM or Gravity.END, params.gravity)
        settleVisibleWindows()
        assertEquals(0, stops)
        assertTrue(owner.isHeld)

        val down = android.os.SystemClock.uptimeMillis()
        touch(indicator, MotionEvent.ACTION_DOWN, indicator.width / 2f, indicator.height / 2f, down)
        touch(indicator, MotionEvent.ACTION_UP, indicator.width / 2f, indicator.height / 2f, down, 20)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(owner.isHeld)
        assertTrue(overlays().isEmpty())
        assertEquals(1, stops)
    }

    @Test fun stopRevokesImmediateOldRenewalUntilExplicitFreshConsent() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        overlays().single().performClick()

        // The phone-local STOP must win before its posted coordinator callback runs.
        assertEquals("hold_cancelled", runCatching { owner.renew(60_000) }.exceptionOrNull()?.message)
        assertFalse(owner.isHeld)
        assertTrue(overlays().isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stops)

        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(owner.isHeld)
        assertTrue(overlays().isEmpty())
        assertEquals("hold_cancelled", runCatching { owner.renew(60_000) }.exceptionOrNull()?.message)

        owner.beginSession()
        owner.renew(60_000)
        assertTrue(owner.isHeld)
        assertEquals(1, overlays().size)
        assertEquals(1, stops)
    }

    @Test fun queuedBackgroundRenewalCannotCrossTheLocalStopBoundary() {
        var stops = 0
        HandsBridge.onDisarm = { stops++ }
        val owner = hold()
        owner.renew(60_000)
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            started.countDown()
            failure.set(runCatching { owner.renew(60_000) }.exceptionOrNull())
        }
        try {
            worker.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // Wait only for the posted request to reach the paused main queue. No main work
            // executes until after STOP, and the helper's own two-second timeout is not used.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (main.isIdle && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertFalse("The background renewal must be queued before STOP", main.isIdle)
            overlays().single().performClick()
            main.idle()
            worker.join(1_000)

            assertFalse("The old queued renewal must complete without hanging", worker.isAlive)
            assertEquals("hold_cancelled", failure.get()?.message)
            assertFalse(owner.isHeld)
            assertTrue(overlays().isEmpty())
            assertEquals(1, stops)
        } finally {
            if (worker.isAlive) worker.interrupt()
            main.idle()
            worker.join(1_000)
        }
    }

    @Test fun releaseCancelsAnInitialQueuedRenewalWithoutCancellingANewSession() {
        val owner = hold()
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            started.countDown()
            failure.set(runCatching { owner.renew(60_000) }.exceptionOrNull())
        }
        try {
            worker.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (main.isIdle && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertFalse("The initial renewal must be queued before release", main.isIdle)
            owner.release()
            owner.beginSession()
            owner.renew(60_000)
            settleVisibleWindows()
            worker.join(1_000)
            assertFalse(worker.isAlive)
            assertEquals("hold_cancelled", failure.get()?.message)
            assertTrue(owner.isHeld)
            assertEquals(1, overlays().size)
        } finally {
            if (worker.isAlive) worker.interrupt()
            main.idle()
            worker.join(1_000)
        }
    }

    @Test fun visibleHoldDoesNotChangeTheUsersScreenTimeout() {
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15_000)
        val owner = hold()
        owner.renew(60_000)
        assertEquals(15_000, Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT))
        owner.release()
        assertEquals(15_000, Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT))
    }

    @Test fun acceptedWindowMustRemainVisibleToClaimAHold() {
        val owner = hold()
        owner.renew(60_000)
        settleVisibleWindows()
        val indicator = overlays().single()
        assertTrue(owner.isHeld)

        windowVisibility(indicator, View.GONE)
        assertFalse("An accepted hidden window must not claim a screen hold", owner.isHeld)
        windowVisibility(indicator, View.VISIBLE)
        assertTrue(owner.isHeld)
        indicator.visibility = View.INVISIBLE
        assertFalse("The indicator itself must also remain visible", owner.isHeld)
    }

    @Test
    @Config(shadows = [RecordingPowerManager::class])
    fun onlyOneBoundedPartialWakeLockIsUsedForMultipleOwners() {
        val power = app.getSystemService(PowerManager::class.java)
        val recording = Shadow.extract<RecordingPowerManager>(power)
        val first = hold()
        val second = hold("standalone-screen")
        assertNull(ShadowPowerManager.getLatestWakeLock())
        first.renew(60_000)
        val wake = ShadowPowerManager.getLatestWakeLock()
        second.renew(120_000)
        first.renew(30_000)
        assertEquals(listOf(PowerManager.PARTIAL_WAKE_LOCK), recording.requestedFlags)
        assertSame(wake, ShadowPowerManager.getLatestWakeLock())
        assertTrue(wake.isHeld)
        first.release()
        advanceBy(120_001)
        assertFalse(wake.isHeld)
        assertFalse(second.isHeld)
    }

    @Implements(PowerManager::class)
    class RecordingPowerManager : ShadowPowerManager() {
        val requestedFlags = mutableListOf<Int>()

        @Implementation
        public override fun newWakeLock(flags: Int, tag: String): PowerManager.WakeLock {
            requestedFlags += flags
            return super.newWakeLock(flags, tag)
        }
    }


    @Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
    class RejectedOverlayWindowManager : ShadowWindowManagerImpl() {
        @Implementation
        public override fun addView(view: View, params: ViewGroup.LayoutParams) {
            throw WindowManager.BadTokenException("Overlay rejected")
        }
    }
}
