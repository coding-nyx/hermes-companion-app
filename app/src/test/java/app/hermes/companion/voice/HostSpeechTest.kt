package app.hermes.companion.voice

import app.hermes.companion.model.SynthesizedSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostSpeechTest {
    @Test fun aRecordingReturnedAfterCancellationIsErasedWithoutUploading() = runTest {
        val wav = byteArrayOf(7, 8, 9)
        val returned = CompletableDeferred<Unit>()
        val transport = Transport()
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? =
                withContext(NonCancellable) { returned.await(); wav }
            override fun cancel() = Unit
        }
        val results = mutableListOf<String>()
        val input = HostSpeechInput(this, capture, transport, StandardTestDispatcher(testScheduler))
        input.startListening(onResult = results::add); runCurrent()
        input.stopListening(); returned.complete(Unit); runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(0, transport.transcriptions)
        assertArrayEquals(ByteArray(3), wav)
    }

    @Test fun authoritativeFinalTextReplacesProvisionalSpeechWithoutDuplicatingIt() = runTest {
        val transport = Transport(); val player = Player()
        val output = HostSpeechOutput(this, transport, player, SpeechCallbacks({}, {}, { fail(it) }), StandardTestDispatcher(testScheduler))
        output.feedToken("provisional reply")
        output.reconcileFinal("correct final reply")
        output.finishFeed(); runCurrent()
        assertEquals(listOf("correct final reply"), transport.spoken)
        assertEquals(1, player.plays)
    }
    @Test fun deniedMicrophonePermissionNeverContactsTheHost() = runTest {
        val transport = Transport(); val errors = mutableListOf<String>()
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? = throw SecurityException("revoked")
            override fun cancel() = Unit
        }
        HostSpeechInput(this, capture, transport, StandardTestDispatcher(testScheduler))
            .startListening(onResult = { fail("unexpected transcript") }, onError = errors::add)
        runCurrent()
        assertEquals(listOf("Microphone permission denied"), errors)
        assertEquals(0, transport.transcriptions)
    }
    @Test fun recordedSpeechIsTranscribedOnceAndTheBufferIsErased() = runTest {
        val wav = byteArrayOf(1, 2, 3)
        val transport = Transport()
        val events = mutableListOf<String>()
        val input = HostSpeechInput(this, Capture(wav), transport, StandardTestDispatcher(testScheduler))
        input.startListening(onResult = { events += it }, onStateChange = { if (it) events += "ready" },
            onProcessing = { events += "processing" })
        runCurrent()
        assertEquals(listOf("ready", "processing", "recognized"), events)
        assertEquals(1, transport.transcriptions)
        assertArrayEquals(ByteArray(3), wav)
    }

    @Test fun silenceDoesNotUploadAudioOrInventATranscript() = runTest {
        val transport = Transport()
        val results = mutableListOf<String>()
        HostSpeechInput(this, Capture(null), transport, StandardTestDispatcher(testScheduler))
            .startListening(onResult = results::add)
        runCurrent()
        assertEquals(listOf(""), results)
        assertEquals(0, transport.transcriptions)
    }

    @Test fun cancellationFencesALateHostResultAndErasesTheRecording() = runTest {
        val response = CompletableDeferred<String>()
        val transport = Transport().apply { deferred = response }
        val wav = byteArrayOf(8, 9)
        val results = mutableListOf<String>()
        val input = HostSpeechInput(this, Capture(wav), transport, StandardTestDispatcher(testScheduler))
        input.startListening(onResult = results::add); runCurrent()
        input.stopListening(); response.complete("late transcript"); runCurrent()
        assertTrue(results.isEmpty())
        assertArrayEquals(ByteArray(2), wav)
    }

    @Test fun providerFailureIsTerminalWithoutAnAutomaticSecondUpload() = runTest {
        val transport = Transport().apply { fail = true }
        val errors = mutableListOf<String>()
        val input = HostSpeechInput(this, Capture(byteArrayOf(1)), transport, StandardTestDispatcher(testScheduler))
        input.startListening(onResult = { fail("unexpected transcript") }, onError = errors::add)
        runCurrent()
        assertTrue(input.terminalErrors)
        assertEquals(1, errors.size)
        assertEquals(1, transport.transcriptions)
    }

    @Test fun cancellationBeforeReadinessDoesNotSubmitOrReportReady() = runTest {
        val transport = Transport()
        val events = mutableListOf<String>()
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? {
                delay(100); onReady(); return byteArrayOf(1)
            }
            override fun cancel() = Unit
        }
        val input = HostSpeechInput(this, capture, transport, StandardTestDispatcher(testScheduler))
        input.startListening(onResult = events::add, onStateChange = { events += "state" })
        runCurrent(); input.stopListening(); advanceTimeBy(200); runCurrent()
        assertTrue(events.isEmpty()); assertEquals(0, transport.transcriptions)
    }

    @Test fun emptyReplyCompletesAndTheNextReplyStillPlays() = runTest {
        val transport = Transport(); val player = Player(); var completed = 0
        val output = HostSpeechOutput(this, transport, player, SpeechCallbacks({}, { completed++ }, { fail(it) }), StandardTestDispatcher(testScheduler))
        output.finishFeed(); runCurrent()
        output.feedToken("Next reply."); output.finishFeed(); runCurrent()
        assertEquals(2, completed)
        assertEquals(listOf("Next reply."), transport.spoken)
        assertEquals(1, player.plays)
    }

    @Test fun longReplyHasNoDuplicatedChunksAndCompletesOnlyAfterPlayback() = runTest {
        val transport = Transport(); val player = Player(); var completed = 0
        val output = HostSpeechOutput(this, transport, player, SpeechCallbacks({}, { completed++ }, { fail(it) }), StandardTestDispatcher(testScheduler))
        val text = (1..350).joinToString(" ") { "word$it" }
        output.feedToken(text); assertEquals(0, transport.spoken.size)
        output.finishFeed(); runCurrent()
        assertEquals(text, transport.spoken.joinToString(" "))
        assertTrue(transport.spoken.all { it.length <= 1_000 })
        assertEquals(transport.spoken.size, player.plays); assertEquals(1, completed)
    }

    @Test fun stoppingAnInFlightSynthesisCannotStartPlayback() = runTest {
        val transport = Transport().apply { synthesis = CompletableDeferred() }
        val player = Player(); var completed = 0
        val output = HostSpeechOutput(this, transport, player, SpeechCallbacks({}, { completed++ }, { fail(it) }), StandardTestDispatcher(testScheduler))
        output.feedToken("Canceled."); output.finishFeed(); runCurrent()
        output.stop(); transport.synthesis!!.complete(SynthesizedSpeech(byteArrayOf(1), "audio/mpeg", "test")); runCurrent()
        assertEquals(0, player.plays); assertEquals(0, completed)
    }

    @Test fun failedPlaybackDoesNotReplayOrContinueToTheNextChunk() = runTest {
        val transport = Transport(); val player = Player().apply { fail = true }; val errors = mutableListOf<String>()
        val output = HostSpeechOutput(this, transport, player, SpeechCallbacks({}, { fail("completion after failure") }, errors::add), StandardTestDispatcher(testScheduler))
        output.feedToken("long ".repeat(500)); output.finishFeed(); runCurrent()
        assertEquals(1, transport.spoken.size); assertEquals(1, player.plays); assertEquals(1, errors.size)
    }

    @Test fun speechSegmentationWaitsForSpeechAndStopsOnSilenceOrItsBound() {
        val silent = SpeechWindow()
        repeat(749) { assertFalse(silent.feed(320, false)) }
        assertTrue(silent.feed(320, false)); assertFalse(silent.hasSpeech)
        val spoken = SpeechWindow()
        repeat(6) { assertFalse(spoken.feed(320, true)) }
        assertTrue(spoken.hasSpeech)
        repeat(44) { assertFalse(spoken.feed(320, false)) }
        assertTrue(spoken.feed(320, false))
        val continuous = SpeechWindow()
        repeat(1499) { assertFalse(continuous.feed(320, true)) }
        assertTrue(continuous.feed(320, true))
    }

    private class Capture(val wav: ByteArray?) : SpeechCapture {
        override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? { onReady(); onActivity(); return wav }
        override fun cancel() = Unit
    }
    private class Transport : SpeechTransport {
        var transcriptions = 0; var fail = false
        var deferred: CompletableDeferred<String>? = null
        var synthesis: CompletableDeferred<SynthesizedSpeech>? = null
        val spoken = mutableListOf<String>()
        override suspend fun transcribe(wav: ByteArray): String {
            transcriptions++; check(wav.isNotEmpty()); if (fail) error("provider down")
            return deferred?.await() ?: "recognized"
        }
        override suspend fun synthesize(text: String): SynthesizedSpeech {
            spoken += text
            return synthesis?.await() ?: SynthesizedSpeech(byteArrayOf(1, 2), "audio/mpeg", "test")
        }
    }
    private class Player : SpeechPlayer {
        var plays = 0; var fail = false
        override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) {
            plays++; if (fail) onError() else { onStart(); onDone() }
        }
        override fun stop() = Unit
    }
}
