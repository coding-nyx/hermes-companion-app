package app.hermes.companion.voice

import org.junit.Assert.*
import org.junit.Test

class MessageReadAloudTest {
    @Test fun replacingPlaybackFencesTheOldCompletionAndReleasesEachLease() {
        val audio = Audio(); val states = mutableListOf<String?>(); val outputs = mutableListOf<Output>()
        val controller = MessageReadAloud(audio, states::add, { fail(it) })
        controller.play("a", "first") { Output(it).also(outputs::add) }
        controller.play("b", "second") { Output(it).also(outputs::add) }
        outputs[0].callbacks.onCompleted()
        assertEquals("b", states.last()); assertTrue(audio.held)
        outputs[1].callbacks.onCompleted()
        assertNull(states.last()); assertFalse(audio.held); assertEquals(2, audio.releases)
        assertEquals(listOf("first"), outputs[0].text)
        assertEquals(1, outputs[0].shutdowns); assertEquals(1, outputs[1].shutdowns)
    }
    @Test fun focusLossStopsPlaybackAndDoesNotRestartIt() {
        val audio = Audio(); var current: String? = null; lateinit var output: Output
        val controller = MessageReadAloud(audio, { current = it }, { fail(it) })
        controller.play("a", "text") { Output(it).also { output = it } }
        audio.lost(); output.callbacks.onCompleted()
        assertNull(current); assertEquals(1, output.shutdowns); assertEquals(1, audio.releases)
    }
    @Test fun activeCallRefusalDoesNotInitializeSpeech() {
        val audio = Audio().apply { granted = false }; val errors = mutableListOf<String>()
        MessageReadAloud(audio, {}, errors::add).play("a", "text") { fail("must not initialize"); Output(it) }
        assertEquals(1, errors.size); assertEquals(0, audio.releases)
    }
    @Test fun synchronousFactoryFailureCannotLeaveAnOutputOrAudioLeaseAlive() {
        val audio = Audio(); val errors = mutableListOf<String>(); lateinit var output: Output
        val controller = MessageReadAloud(audio, {}, errors::add)
        controller.play("a", "text") { cb -> cb.onError("failed"); Output(cb).also { output = it } }
        assertFalse(audio.held); assertEquals(1, audio.releases); assertEquals(1, output.shutdowns); assertEquals(listOf("failed"), errors)
    }
    private class Output(val callbacks: SpeechCallbacks) : SpeechOutput {
        val text = mutableListOf<String>(); var shutdowns = 0
        override fun feedToken(delta: String) { text += delta }
        override fun finishFeed() = Unit
        override fun stop() = Unit
        override fun shutdown() { shutdowns++ }
    }
    private class Audio : VoiceAudioSessionControl {
        var granted = true; var held = false; var releases = 0; var lost: () -> Unit = {}
        override fun acquire(onLost: () -> Unit): Boolean { lost = onLost; held = granted; return granted }
        override fun release() { if (held) { held = false; releases++ } }
        override fun useSpeaker(enabled: Boolean) = held
    }
}
