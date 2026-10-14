package app.hermes.companion.voice

import app.hermes.companion.data.remote.*
import app.hermes.companion.model.SynthesizedSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingHostSpeechOutputTest {
    @Test fun aSilentUpgradeHasABoundedDeadlineAndNoWholeFileRetry() = runTest {
        val f = Fixture(this, ready = false)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            advanceTimeBy(15_001); runCurrent()
            assertEquals(1, f.errors.size); assertTrue(f.stream.stopped)
            assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }

    @Test fun firstAudioHasADeadlineWhileTheHermesReplyIsStillOpen() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("First sentence. "); runCurrent()
            advanceTimeBy(2_001); runCurrent()
            assertEquals(1, f.errors.size); assertTrue(f.stream.stopped)
            assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }
    @Test fun unsupportedHandshakeBeforeReadinessStillFallsBackWithoutSendingText() = runTest {
        val f = Fixture(this, ready = false)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            assertTrue(f.stream.sent.isEmpty())
            f.emit(SpeechStreamEvent.Unsupported)
            assertEquals(listOf("Reply."), f.transport.synthesized)
            assertEquals(1, f.completed); assertTrue(f.errors.isEmpty())
        } finally { f.close() }
    }
    @Test fun completionWaitsForPlaybackDrainAndFinalTextReplacesOnlyUnsentSuffix() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("First sentence. pending draft"); runCurrent()
            assertEquals(listOf("First sentence. "), f.stream.sent)
            f.output.reconcileFinal("First sentence. final suffix.")
            f.output.finishFeed(); runCurrent()
            assertEquals(listOf("First sentence. ", "final suffix. "), f.stream.sent)
            f.emit(SpeechStreamEvent.Start(SpeechPcmFormat(24_000)))
            val bytes = byteArrayOf(1, 2, 3, 4)
            f.emit(SpeechStreamEvent.Pcm(bytes)); f.emit(SpeechStreamEvent.End)
            assertEquals(1, f.started); assertEquals(0, f.completed)
            assertArrayEquals(ByteArray(4), bytes)
            f.pcm.drained.complete(Unit); runCurrent()
            assertEquals(1, f.completed); assertTrue(f.errors.isEmpty()); assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }

    @Test fun explicitFallbackWaitsForFinalTextAndSendsOneWholeFileRequest() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("First sentence. provisional"); runCurrent()
            f.emit(SpeechStreamEvent.Fallback)
            assertTrue(f.transport.synthesized.isEmpty())
            f.output.reconcileFinal("First sentence. final."); f.output.finishFeed(); runCurrent()
            assertEquals(listOf("First sentence. final."), f.transport.synthesized)
            assertEquals(1, f.completed); assertEquals(1, f.fallback.plays)
        } finally { f.close() }
    }

    @Test fun missingStreamUsesHostWholeFileSpeechWithoutSwitchingProviders() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            f.emit(SpeechStreamEvent.Unsupported)
            assertEquals(listOf("Reply."), f.transport.synthesized)
            assertEquals(1, f.completed)
        } finally { f.close() }
    }

    @Test fun fallbackAfterAnyReceivedPcmFailsWithoutReplayingTheReply() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            f.emit(SpeechStreamEvent.Start(SpeechPcmFormat(24_000)))
            f.emit(SpeechStreamEvent.Pcm(byteArrayOf(1, 2)))
            assertEquals(1, f.started)
            f.emit(SpeechStreamEvent.Fallback)
            assertEquals(1, f.errors.size); assertEquals(0, f.completed)
            assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }

    @Test fun authNetworkAndProviderFailuresNeverRetryAsWholeFileSpeech() = runTest {
        for (code in listOf("http_401", "connection", "provider")) {
            val f = Fixture(this)
            try {
                f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
                f.emit(SpeechStreamEvent.Failed(DashboardException(code, "failed")))
                assertEquals(1, f.errors.size); assertTrue(f.transport.synthesized.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun revisingASentPrefixStopsPlaybackWithoutSpeakingTheFinalAgain() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("Old sentence. more"); runCurrent()
            f.output.reconcileFinal("Corrected sentence. more"); runCurrent()
            assertTrue(f.stream.stopped); assertEquals(1, f.completed)
            assertEquals(listOf("Old sentence. "), f.stream.sent); assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }

    @Test fun incompleteMarkdownAndReasoningDoNotLeakIntoCommittedSpeech() {
        val text = StreamingSpeechText()
        text.append("<think>Secret reasoning. ")
        assertNull(text.next(false))
        text.append("More.</think> Visible **answer**. ")
        val batch = text.next(false)!!
        assertEquals("Visible answer.", batch.text); text.commit(batch)
        text.append("```kotlin\nsecret.code()\n")
        assertNull(text.next(false))
        text.append("``` Done. ")
        assertEquals("code snippet  Done.", text.next(false)!!.text.trim())
    }

    @Test fun stoppingDuringDrainNeverCompletesOrStartsAnotherTurn() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            f.emit(SpeechStreamEvent.Start(SpeechPcmFormat(24_000)))
            f.emit(SpeechStreamEvent.Pcm(byteArrayOf(1, 2))); f.emit(SpeechStreamEvent.End)
            f.output.stop(); f.pcm.drained.complete(Unit); runCurrent()
            assertEquals(0, f.completed); assertTrue(f.errors.isEmpty()); assertTrue(f.stream.stopped)
        } finally { f.close() }
    }

    @Test fun stalledFinishedStreamsHaveAnIdleDeadlineWithoutFallback() = runTest {
        val f = Fixture(this)
        try {
            f.output.feedToken("Reply."); f.output.finishFeed(); runCurrent()
            advanceTimeBy(1_001); runCurrent()
            assertEquals(1, f.errors.size); assertTrue(f.stream.stopped)
            assertTrue(f.transport.synthesized.isEmpty())
        } finally { f.close() }
    }

    @Test fun playbackBackpressureDoesNotCountAsNetworkSilence() = runTest {
        val f = Fixture(this)
        try {
            f.pcm.enqueueGate = CompletableDeferred()
            f.output.feedToken("Long reply."); f.output.finishFeed(); runCurrent()
            f.emit(SpeechStreamEvent.Start(SpeechPcmFormat(24_000)))
            f.emit(SpeechStreamEvent.Pcm(byteArrayOf(1, 2)))
            assertEquals("Speaking should appear before the entire frame finishes queuing", 1, f.started)
            advanceTimeBy(3_000); runCurrent()
            assertTrue(f.errors.isEmpty())
            f.pcm.enqueueGate!!.complete(Unit); runCurrent()
            f.emit(SpeechStreamEvent.End); f.pcm.drained.complete(Unit); runCurrent()
            assertEquals(1, f.completed)
        } finally { f.close() }
    }

    @Test fun emptyRepliesCompleteWithoutOpeningAProviderStream() = runTest {
        val f = Fixture(this)
        try { f.output.finishFeed(); runCurrent(); assertEquals(0, f.transport.opens); assertEquals(1, f.completed) }
        finally { f.close() }
    }

    private class Fixture(val test: TestScope, ready: Boolean = true) {
        val stream = Stream().also { if (ready) it.inbox.trySend(SpeechStreamEvent.Ready) }
        val transport = Transport(stream)
        val pcm = Pcm()
        val fallback = Player()
        var started = 0; var completed = 0
        val errors = mutableListOf<String>()
        val output = StreamingHostSpeechOutput(test, transport, pcm, fallback,
            SpeechCallbacks({ started++ }, { completed++ }, errors::add), StandardTestDispatcher(test.testScheduler),
            finishIdleTimeoutMs = 1_000, firstAudioTimeoutMs = 2_000)
        fun emit(event: SpeechStreamEvent) { stream.inbox.trySend(event); if (event == SpeechStreamEvent.End ||
            event == SpeechStreamEvent.Fallback || event == SpeechStreamEvent.Unsupported || event is SpeechStreamEvent.Failed) stream.inbox.close()
            test.runCurrent() }
        fun close() = output.shutdown()
    }
    private class Stream : NativeSpeechStream {
        val inbox = Channel<SpeechStreamEvent>(Channel.UNLIMITED)
        override val events = inbox.receiveAsFlow()
        val sent = mutableListOf<String>(); var stopped = false
        override fun sendText(text: String): Boolean { sent += text; return !stopped }
        override fun finish() = !stopped
        override fun stop() { stopped = true; inbox.cancel() }
    }
    private class Transport(val stream: Stream) : StreamingSpeechTransport {
        var opens = 0
        val synthesized = mutableListOf<String>()
        override suspend fun openSpeechStream(): NativeSpeechStream { opens++; return stream }
        override suspend fun transcribe(wav: ByteArray): String = error("No capture")
        override suspend fun synthesize(text: String): SynthesizedSpeech {
            synthesized += text; return SynthesizedSpeech(byteArrayOf(1, 2), "audio/wav", "same-host-provider")
        }
    }
    private class Pcm : PcmSpeechPlayer {
        val drained = CompletableDeferred<Unit>()
        var enqueueGate: CompletableDeferred<Unit>? = null
        private var start: () -> Unit = {}
        private var started = false
        override suspend fun begin(format: SpeechPcmFormat, onStart: () -> Unit) { start = onStart; started = false }
        override suspend fun enqueue(bytes: ByteArray) {
            if (!started) { started = true; start() }
            enqueueGate?.await()
        }
        override suspend fun finishAndDrain() { drained.await() }
        override fun stop() = Unit
    }
    private class Player : SpeechPlayer {
        var plays = 0
        override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) {
            plays++; onStart(); onDone()
        }
        override fun stop() = Unit
    }
}
