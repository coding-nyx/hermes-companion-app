package app.hermes.companion.voice

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],application=Application::class)
class VoiceInputManagerTest {
    @Test fun readinessComesFromTheServiceAndStoppedResultsCannotBecomeAnotherTurn() {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE),ResolveInfo().apply {
            serviceInfo=ServiceInfo().apply {packageName="fixture.speech";name="fixture.speech.Service"}
        })
        val input=VoiceInputManager(context)
        val ready=mutableListOf<Boolean>();val results=mutableListOf<String>()
        input.startListening(onResult=results::add,onStateChange=ready::add)
        shadowOf(Looper.getMainLooper()).idle()
        val recognizer=ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val service=shadowOf(recognizer)
        assertTrue(ready.isEmpty())
        service.triggerOnReadyForSpeech(Bundle())
        assertEquals(listOf(true),ready)
        val text=Bundle().apply {putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf("one question"))}
        service.triggerOnResults(text)
        input.stopListening()
        assertTrue(service.isDestroyed)
        service.triggerOnResults(text)
        assertEquals(listOf("one question"),results)
    }
}
