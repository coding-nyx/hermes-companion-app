package app.hermes.companion.device

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.graphics.Rect
import java.util.concurrent.Executor
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [ObservationAccessibilityShadow::class])
class ScreenObservationServiceTest {
    private lateinit var service: CompanionAccessibilityService

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("hermes_device_privacy", Context.MODE_PRIVATE).edit().clear().commit()
        DevicePrivacy.releaseLocally(app)
        service = Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        ObservationAccessibilityShadow.root = { null }
        ObservationAccessibilityShadow.onClear = {}
        ObservationAccessibilityShadow.cacheClears = 0
        ObservationAccessibilityShadow.screenshotErrorCode = 6
        CompanionAccessibilityService.foregroundApp = "com.example.current"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    }

    @After fun tearDown() {
        service.onDestroy()
        ObservationAccessibilityShadow.root = { null }
        ObservationAccessibilityShadow.onClear = {}
    }

    private fun node(text: String, windowId: Int = 7, visible: Boolean = true): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(windowId)
        shadowOf(window).setBoundsInScreen(Rect(0, 0, 1080, 2340))
        return AccessibilityNodeInfo.obtain().apply {
            packageName = "com.example.current"
            this.text = text
            isVisibleToUser = visible
            shadowOf(this).setRefreshReturnValue(true)
            shadowOf(this).setAccessibilityWindowInfo(window)
        }
    }

    @Test fun cacheIsClearedBeforeReadingAndSnapshotsNeverReuseRefsOrPrivateTokens() {
        val stale = node("Email")
        val current = node("Photos")
        var active = stale
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        ObservationAccessibilityShadow.onClear = { active = current }
        val first = service.snapshot()
        val second = service.snapshot()
        assertEquals("Photos", first.nodes.single().text)
        assertTrue(ObservationAccessibilityShadow.cacheClears >= 2)
        assertNotEquals(first.nodes.single().ref, second.nodes.single().ref)
        assertNotEquals(first.snapshotId, second.snapshotId)
        assertTrue(second.capturedAtMs > 0)
        assertEquals(7, second.windowId)
        assertFalse(service.validateObservation(first.windowId, first.generation))
        assertTrue(service.validateObservation(second.windowId, second.generation))
        assertNull(service.captureSecureTarget(first.nodes.single().ref, first.app, first.snapshotId))
    }

    @Test fun navigationDuringCaptureRetriesAndDoesNotPublishTheOldWindow() {
        val old = node("Email", 7)
        val current = node("Photos", 8)
        var reads = 0
        ObservationAccessibilityShadow.root = {
            AccessibilityNodeInfo.obtain(if (reads++ == 0) old else current)
        }
        val result = service.snapshot()
        assertEquals(8, result.windowId)
        assertEquals("Photos", result.nodes.single().text)
        assertTrue(reads >= 4)
    }

    @Test fun sameWindowAnimationMustSettleBeforePublishingBounds() {
        val moving = node("Page one").apply { setBoundsInScreen(Rect(48, 203, 1032, 301)) }
        val settled = node("Page one").apply { setBoundsInScreen(Rect(48, 200, 1032, 298)) }
        var reads = 0
        ObservationAccessibilityShadow.root = {
            AccessibilityNodeInfo.obtain(if (reads++ == 0) moving else settled)
        }
        val observation = service.snapshot()
        assertEquals(listOf(48, 200, 1032, 298), observation.nodes.single().bounds)
        assertTrue("A changed second read must retry before publishing", reads >= 4)
        assertTrue(service.validateObservation(observation.windowId, observation.generation))
    }

    @Test fun unavailableRootFailsExplicitlyAndClearsThePreviousPrivateToken() {
        val active = node("Email")
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        val first = service.snapshot()
        ObservationAccessibilityShadow.root = { null }
        try { service.snapshot(); fail("An unavailable screen must not become an empty successful tree") }
        catch (failure: DeviceObservationException) { assertEquals("screen_unstable", failure.code) }
        assertFalse(service.validateObservation(first.windowId, first.generation))
        assertNull(service.captureSecureTarget(first.nodes.single().ref, first.app, first.snapshotId))
    }

    @Test fun benignEventsKeepRefsAfterRevalidationWhileChangedContentInvalidatesThem() {
        var active = node("Email")
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        val observation = service.snapshot()
        fun changed(pkg: String) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            event.packageName = pkg
            try { service.onAccessibilityEvent(event) } finally { event.recycle() }
        }
        changed("com.android.systemui")
        assertTrue(service.validateObservation(observation.windowId, observation.generation))
        changed(observation.app)
        assertTrue("An event alone is not proof that the observed controls changed",
            service.validateObservation(observation.windowId, observation.generation))
        val windowsChanged = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        try { service.onAccessibilityEvent(windowsChanged) } finally { windowsChanged.recycle() }
        assertTrue("A late overlay/window event must not discard an unchanged tree after a screenshot",
            service.validateObservation(observation.windowId, observation.generation))
        active = node("Photos")
        changed(observation.app)
        assertFalse(service.validateObservation(observation.windowId, observation.generation))
    }

    @Test fun layoutDriftWithoutAnAccessibilityEventStillInvalidatesOldRefs() {
        var active = node("Email")
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        val observation = service.snapshot()
        active = node("Photos")
        assertFalse(service.validateObservation(observation.windowId, observation.generation))
        assertNull(service.captureSecureTarget(observation.nodes.single().ref, observation.app, observation.snapshotId))
    }

    @Test @Config(sdk = [34]) fun secureWindowCallbackKeepsItsAndroidFailureReason() {
        val active = node("Email")
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        try { service.screenshotPng(); fail("A protected screenshot must fail explicitly") }
        catch (failure: DeviceObservationException) {
            assertEquals("screenshot_secure_window", failure.code)
            assertEquals(6, failure.androidErrorCode)
        }
    }

    @Test fun invisibleStaleControlsAreNotPublished() {
        val active = node("Photos")
        shadowOf(active).addChild(node("Old email form", visible = false))
        ObservationAccessibilityShadow.root = { AccessibilityNodeInfo.obtain(active) }
        assertEquals(listOf("Photos"), service.snapshot().nodes.map { it.text })
    }
}

@Implements(AccessibilityService::class)
class ObservationAccessibilityShadow : ShadowAccessibilityService() {
    companion object {
        var root: () -> AccessibilityNodeInfo? = { null }
        var onClear: () -> Unit = {}
        var cacheClears = 0
        var screenshotErrorCode = 6
    }

    @Implementation protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = root()
    @Implementation protected fun clearCache(): Boolean { cacheClears++; onClear(); return true }
    @Implementation(minSdk = 34) protected fun takeScreenshotOfWindow(
        windowId: Int,
        executor: Executor,
        callback: AccessibilityService.TakeScreenshotCallback,
    ) { executor.execute { callback.onFailure(screenshotErrorCode) } }
}
