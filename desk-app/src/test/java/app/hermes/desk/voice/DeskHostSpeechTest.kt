@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.hermes.desk.voice

import app.hermes.companion.model.SynthesizedSpeech
import app.hermes.companion.voice.SpeechCallbacks
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class DeskHostSpeechTest {
    @Test fun noiseOnlyTranscriptionResumesCaptureWithoutAResumeTap() = runTest {
        var captures = 0; var result = ""
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? {
                captures++; onReady(); return byteArrayOf(captures.toByte())
            }
            override fun cancel() = Unit
        }
        val input = DeskHostInput(backgroundScope, capture) { if (captures == 1) "" else "Hello again" }
        input.startListening(onResult = { result = it }); runCurrent()
        assertEquals("", result)
        advanceTimeBy(250); runCurrent()
        assertEquals(2, captures); assertEquals("Hello again", result)
    }
    @Test fun quietCaptureWindowsKeepListeningWithoutGeneratingEmptyRequests() = runTest {
        var captures = 0; var transcriptions = 0; var result = ""
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit): ByteArray? {
                captures++; onReady()
                return if (captures <= 2) null else byteArrayOf(1, 2, 3)
            }
            override fun cancel() = Unit
        }
        val input = DeskHostInput(backgroundScope, capture) { transcriptions++; "Hello" }
        input.startListening(onResult = { result = it })
        runCurrent()
        assertEquals(3, captures); assertEquals(1, transcriptions); assertEquals("Hello", result)
    }

    @Test fun cancelledTranscriptionCannotSubmitALateUtterance() = runTest {
        val pending = CompletableDeferred<String>(); var results = 0
        val capture = object : SpeechCapture {
            override suspend fun record(onReady: () -> Unit, onActivity: () -> Unit) = byteArrayOf(1)
            override fun cancel() = Unit
        }
        val input = DeskHostInput(backgroundScope, capture) { withContext(NonCancellable) { pending.await() } }
        input.startListening(onResult = { results++ }); runCurrent()
        input.stopListening(); pending.complete("Late input"); runCurrent()
        assertEquals(0, results)
    }

    @Test fun clausesAreSpokenBeforeFinalAndFinalDoesNotReplayThem() = runTest {
        val spoken = mutableListOf<String>(); var done = 0
        val output = DeskHostOutput(backgroundScope, { text -> spoken += text; SynthesizedSpeech(byteArrayOf(1), "audio/wav", "host") }, ImmediatePlayer(),
            SpeechCallbacks({}, { done++ }, { error(it) }))
        output.feedToken("One sentence. "); runCurrent()
        assertEquals(listOf("One sentence."), spoken)
        assertEquals(0, done)
        output.reconcileFinal("One sentence. Another thought.")
        output.finishFeed(); runCurrent()
        assertEquals(listOf("One sentence.", "Another thought."), spoken)
        assertEquals(1, done)
    }

    @Test fun stoppingDuringSynthesisDiscardsLateAudio() = runTest {
        val pending = CompletableDeferred<SynthesizedSpeech>(); var plays = 0; var completions = 0
        val player = object : SpeechPlayer {
            override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) { plays++ }
            override fun stop() = Unit
        }
        val output = DeskHostOutput(backgroundScope, { withContext(NonCancellable) { pending.await() } }, player,
            SpeechCallbacks({}, { completions++ }, { error(it) }))
        output.feedToken("A sentence. "); runCurrent()
        output.stop()
        val bytes = byteArrayOf(1, 2)
        pending.complete(SynthesizedSpeech(bytes, "audio/wav", "host")); runCurrent()
        assertEquals(0, plays); assertEquals(0, completions)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private class ImmediatePlayer : SpeechPlayer {
        override fun play(audio: SynthesizedSpeech, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) { onStart(); onDone() }
        override fun stop() = Unit
    }
}
