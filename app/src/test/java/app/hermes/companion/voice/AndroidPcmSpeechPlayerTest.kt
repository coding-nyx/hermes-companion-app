package app.hermes.companion.voice

import app.hermes.companion.data.remote.SpeechPcmFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPcmSpeechPlayerTest {
    @Test fun drainWaitsForThePlaybackHeadAndHandlesPartialWrites() = runTest {
        val sink = Sink()
        val player = AndroidPcmSpeechPlayer(this, StandardTestDispatcher(testScheduler)) { sink }
        var starts = 0
        try {
            player.begin(SpeechPcmFormat(24_000)) { starts++ }
            player.enqueue(ByteArray(960) { 1 }); runCurrent()
            assertEquals(960, sink.written)
            assertEquals(1, starts)
            val done = async { player.finishAndDrain() }; runCurrent()
            assertFalse(done.isCompleted)
            sink.head = 480; advanceTimeBy(20); runCurrent()
            done.await(); assertEquals(1, sink.releases)
        } finally { player.stop() }
    }

    @Test fun aStalledPlaybackHeadTimesOutAndReleasesTheSink() = runTest {
        val sink = Sink()
        val player = AndroidPcmSpeechPlayer(this, StandardTestDispatcher(testScheduler)) { sink }
        player.begin(SpeechPcmFormat(24_000)); player.enqueue(ByteArray(960)); runCurrent()
        val done = async { runCatching { player.finishAndDrain() }.exceptionOrNull() }
        advanceTimeBy(10_100); runCurrent()
        assertTrue(done.await() is TimeoutCancellationException)
        assertEquals(1, sink.releases); player.stop()
    }

    @Test fun aFailedWriteSurfacesAnErrorAndWipesTheWrittenChunk() = runTest {
        val sink = Sink().apply { fail = true }
        val player = AndroidPcmSpeechPlayer(this, StandardTestDispatcher(testScheduler)) { sink }
        player.begin(SpeechPcmFormat(24_000)); player.enqueue(ByteArray(960) { 7 }); runCurrent()
        val error = runCatching { player.finishAndDrain() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(sink.buffers.single().all { it == 0.toByte() })
        assertEquals(1, sink.releases); player.stop()
    }

    @Test fun cancellingTheOwnerBeforeTheWorkerStartsStillReleasesAllocatedAudio() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val sink = Sink()
        val player = AndroidPcmSpeechPlayer(owner, StandardTestDispatcher(testScheduler)) { sink }
        player.begin(SpeechPcmFormat(24_000)); owner.cancel(); runCurrent()
        assertEquals(1, sink.releases)
        player.stop(); assertEquals(1, sink.releases)
    }

    @Test fun queueBackpressureBoundsAFastProducerAndStopUnblocksTheWriter() = runBlocking {
        val writeStarted = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val releases = AtomicInteger()
        val sink = object : PcmAudioSink {
            override fun play() = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                writeStarted.countDown()
                check(unblocked.await(5, TimeUnit.SECONDS))
                return length
            }
            override fun playedFrames() = 0L
            override fun release() { releases.incrementAndGet(); unblocked.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = AndroidPcmSpeechPlayer(scope, sinkFactory = { sink })
        try {
            player.begin(SpeechPcmFormat(24_000))
            val producer = async(Dispatchers.Default) { runCatching { player.enqueue(ByteArray(24_000 * 2 * 4)) } }
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            delay(50)
            assertFalse("Four seconds must not fit in a two-second queue", producer.isCompleted)
            player.stop()
            withTimeout(2_000) { producer.await() }
            assertEquals(1, releases.get())
        } finally { player.stop(); scope.cancel(); unblocked.countDown() }
    }

    private class Sink : PcmAudioSink {
        var written = 0; var head = 0L; var releases = 0; var fail = false
        val buffers = mutableSetOf<ByteArray>()
        override fun play() = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            buffers += bytes
            if (fail) return -1
            val count = minOf(length, 240); written += count; return count
        }
        override fun playedFrames() = head
        override fun release() { releases++ }
    }
}
