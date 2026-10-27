package app.hermes.companion

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.model.*
import app.hermes.companion.voice.VoiceCallService
import app.hermes.companion.voice.VoiceSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real microphone/FGS lifecycle; synthetic local STT endpoint, no agent or paid provider. */
@RunWith(AndroidJUnit4::class)
class VoiceCallServiceDeviceTest {
    @Test fun callKeepsItsMicrophoneThroughBackgroundSilenceAndExplicitMute() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CompanionApp
        check(app.voiceController?.isActive != true) { "End the operator's voice call before this test" }
        val oldState = app.conversationState
        val oldChat = app.conversationChat
        val oldVoice = app.voiceController
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/audio/settings" -> MockResponse().setBody("""{"ok":true,"version":1,"fields":[{"key":"voice.voice_chat_mode","value":"chained","options":["chained","gpt-live"],"type":"string"}],"live":{"mode":"chained","available":false},"streaming":true,"snapshots":false}""")
                    "/api/audio/transcribe" -> MockResponse().setBody("""{"ok":true,"transcript":"","provider":"fixture"}""")
                    else -> MockResponse().setResponseCode(400)
                }
            }
            server.start()
            val state = MutableStateFlow(CompanionState(origin = server.url("/").toString().trimEnd('/'),
                activeProfileId = "fixture", openSessionId = "fixture-session",
                openSessionRef = SessionRef("fixture-session", "fixture", "Voice lifecycle fixture", 0), speechBackend = SpeechBackend.HOST))
            var voice: VoiceSessionController? = null
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity {
                    val chat = ChatSessionManager(app.clients, app.cache, app.outbox, state, app.appScope, app.drafts)
                    voice = VoiceSessionController(app, state, chat)
                    app.conversationState = state; app.conversationChat = chat; app.voiceController = voice
                    VoiceCallService.start(app)
                }
                withTimeout(20000) { state.first { it.voiceMicrophoneReady } }
                assertTrue(voice!!.isActive)
                instrumentation.runOnMainSync { voice!!.setMicrophoneMuted(true) }
                withTimeout(5000) { state.first { it.voiceMicrophoneMuted && !it.voiceMicrophoneReady } }
                delay(500)
                assertTrue(voice!!.isActive)
                scenario.recreate()
                assertTrue("Recreation must retain the same call", voice!!.isActive)
                assertTrue("Recreation must retain mute", state.value.voiceMicrophoneMuted)
                assertFalse(state.value.voiceMicrophoneReady)
                instrumentation.runOnMainSync { voice!!.setMicrophoneMuted(false) }
                withTimeout(5000) { state.first { it.voiceMicrophoneReady && !it.voiceMicrophoneMuted } }
                scenario.moveToState(Lifecycle.State.CREATED)
                delay(61000)
                assertTrue("A minute of background silence must not end voice", voice!!.isActive)
                assertTrue("The microphone must stay ready without new activity", state.value.voiceMicrophoneReady)
                assertFalse(state.value.voiceMicrophoneMuted)
                instrumentation.runOnMainSync { voice!!.stopStream() }
                withTimeout(5000) { state.first { !it.isVoiceStreamActive } }
                assertFalse(voice!!.isActive)
                android.util.Log.i("VoiceAcceptance", "background_silence_ms=61000; mute_resume_recreation=passed; provider=local-fixture; spoken_acceptance=not_measured")
            } finally {
                instrumentation.runOnMainSync {
                    voice?.stopStream()
                    app.stopService(Intent(app, VoiceCallService::class.java))
                }
                instrumentation.waitForIdleSync()
                delay(500)
                scenario.close()
                instrumentation.runOnMainSync {
                    app.conversationState = oldState; app.conversationChat = oldChat; app.voiceController = oldVoice
                }
            }
        }
        Unit
    }
}
