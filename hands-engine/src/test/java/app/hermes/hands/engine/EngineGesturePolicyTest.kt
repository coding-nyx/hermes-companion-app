package app.hermes.hands.engine

import org.junit.Assert.*
import org.junit.Test

class EngineGesturePolicyTest {
    @Test fun legacyDeltaStartsAtDisplayCenter() {
        assertEquals(listOf(360.0, 640.0, 440.0, 340.0), EngineGesturePolicy.delta(720, 1280, 80.0, -300.0))
        assertThrows(IllegalArgumentException::class.java) { EngineGesturePolicy.delta(720, 1280, 900.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { EngineGesturePolicy.delta(720, 1280, 0.0, 0.0) }
    }

    @Test fun referenceScrollStaysInsideItsContainer() {
        val points = EngineGesturePolicy.scroll(720, 1280, "down", listOf(100, 100, 300, 500))
        assertEquals(listOf(200.0, 420.0, 200.0, 180.0), points)
        assertTrue(points[1] > points[3]) // Reveal content below with an upward finger gesture.
    }

    @Test fun PartiallyClippedContainerCannotSendInputOutsideTheDisplay() {
        val points = EngineGesturePolicy.scroll(720, 1280, "left", listOf(-100, -100, 900, 1400))
        points.forEachIndexed { index, value -> EnginePolicy.coordinate(value, if (index % 2 == 0) 720 else 1280) }
        assertThrows(IllegalArgumentException::class.java) { EngineGesturePolicy.scroll(720, 1280, "sideways") }
    }
}
