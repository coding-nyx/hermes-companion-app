package app.hermes.companion.voice

import org.junit.Assert.*
import org.junit.Test

class ContinuousUtteranceDetectorTest {
    @Test fun aMinuteOfSilenceNeverEndsListeningOrCreatesAnUtterance() {
        val detector = ContinuousUtteranceDetector()
        repeat(3000) {
            val frame = detector.feed(ShortArray(320))
            assertFalse(frame.started); assertNull(frame.pcm)
        }
        var starts = 0
        repeat(20) { if (detector.feed(ShortArray(320) { 3000 }).started) starts++ }
        var pcm: ByteArray? = null
        repeat(45) { detector.feed(ShortArray(320)).pcm?.let { pcm = it } }
        assertEquals(1, starts)
        val result = requireNotNull(pcm)
        val voiced = result.asList().chunked(2).count { it[0] != 0.toByte() || it[1] != 0.toByte() }
        assertEquals(20 * 320, voiced) // The onset is retained while speech is being confirmed.
        assertTrue(result.size < 16000 * 2 * 2)
        result.fill(0)
    }

    @Test fun resetDiscardsAnInterruptedCaptureAndPacingControlsOnlyUtteranceBoundaries() {
        val detector = ContinuousUtteranceDetector(1400)
        repeat(20) { detector.feed(ShortArray(320) { 3000 }) }
        detector.reset()
        repeat(100) { assertNull(detector.feed(ShortArray(320)).pcm) }
        repeat(10) { detector.feed(ShortArray(320) { 5000 }) }
        repeat(69) { assertNull(detector.feed(ShortArray(320)).pcm) }
        assertNotNull(detector.feed(ShortArray(320)).pcm)
    }
}
