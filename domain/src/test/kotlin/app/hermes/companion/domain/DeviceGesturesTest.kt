package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGesturesTest {
    @Test
    fun fitCapsLongEdge() {
        assertEquals(1080 to 2400, DeviceGestures.fit(1080, 2400, 2400))
        assertEquals(486 to 1080, DeviceGestures.fit(1080, 2400, 1080))
        assertEquals(1080 to 480, DeviceGestures.fit(2160, 960, 1080))
    }

    @Test
    fun waitIsCapped() {
        assertEquals(0L, DeviceGestures.waitMs(-5))
        assertEquals(DeviceGestures.WAIT_MAX_MS, DeviceGestures.waitMs(60_000))
        assertEquals(250L, DeviceGestures.waitMs(250))
    }

    @Test
    fun swipeAndScroll() {
        val swipe = DeviceGestures.swipe(10f, 10f, 10f, 400f)
        assertNotNull(swipe)
        assertNull(DeviceGestures.swipe(1f, 1f, 1f, 1f))
        val up = DeviceGestures.scroll("up", 500f, 1000f, 200f)!!
        assertTrue(up.y1 > up.y2)
    }

    @Test
    fun rateLimitTenPerSecond() {
        val hits = mutableListOf<Long>()
        repeat(10) { assertTrue(DeviceGestures.allowRate(1_000L, hits)) }
        assertFalse(DeviceGestures.allowRate(1_000L, hits))
        assertTrue(DeviceGestures.allowRate(2_100L, hits))
    }
}
