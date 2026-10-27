package app.hermes.desk.feature

import org.junit.Assert.*
import org.junit.Test

class DeskVisionFramesTest {
    private val one = DeskBinding("https://lab.test", "ash", "one")
    private val other = one.copy(session = "other")
    @Test fun spokenReferenceKeepsTheFrameFromBeforeTranscription() {
        var now = 0L
        val frames = DeskVisionFrames { now }
        val old = byteArrayOf(1)
        frames.update(one, old); frames.freeze(one)
        now = 1_000; frames.update(one, byteArrayOf(2))
        assertEquals(0.toByte(), old[0])
        assertArrayEquals(byteArrayOf(1), frames.take(one, spoken = true))
        assertArrayEquals(byteArrayOf(2), frames.take(one))
    }
    @Test fun expiredOrWrongConversationFramesAreNeverShared() {
        var now = 0L
        val frames = DeskVisionFrames { now }
        frames.update(one, byteArrayOf(1)); frames.freeze(one)
        assertNull(frames.take(other))
        assertNull(frames.take(other, spoken = true))
        now = 6_000
        assertNull(frames.take(one))
    }
    @Test fun stoppingSharingClearsBothLiveAndFrozenFrames() {
        val frames = DeskVisionFrames { 0L }
        val bytes = byteArrayOf(1)
        frames.update(one, bytes); frames.freeze(one); frames.clear()
        assertEquals(0.toByte(), bytes[0])
        assertNull(frames.take(one)); assertNull(frames.take(one, spoken = true))
    }
}
