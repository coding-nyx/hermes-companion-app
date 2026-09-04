package app.hermes.companion.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeChordTest {
    @Test
    fun secondPressWithinWindow() {
        assertTrue(VolumeChord.secondPress(500L, 200L))
        assertFalse(VolumeChord.secondPress(700L, 200L))
        assertFalse(VolumeChord.secondPress(200L, 0L))
    }
}
