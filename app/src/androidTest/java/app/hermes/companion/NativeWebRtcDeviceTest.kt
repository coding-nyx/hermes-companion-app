package app.hermes.companion

import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.NativeLiveProtocol
import app.hermes.companion.model.*
import app.hermes.companion.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real pinned JNI SDK, two local peers; no provider, microphone or speaker access. */
@RunWith(AndroidJUnit4::class)
class NativeWebRtcDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun peer(id: Long = 1) = NativeWebRtcPeer(context, NativeCallContext("http://fixture", "test", "fixture", id))
    @Test fun pinnedSdkCreatesAudioAndEventsOfferAndReleasesRepeatedly() = runBlocking {
        repeat(3) {
            val peer = peer(it.toLong())
            try {
                val offer = peer.offer(microphone = false)
                assertTrue(offer.startsWith("v=0")); assertTrue(offer.contains("m=audio")); assertTrue(offer.contains("m=application"))
                assertEquals(NativeCallPhase.CONNECTING, peer.phase.value)
            } finally { peer.close() }
            assertEquals(NativeCallPhase.ENDED, peer.phase.value)
            peer.close()
            assertNotNull(runCatching { peer.send(NativeLiveProtocol.append("late")) }.exceptionOrNull())
        }
    }
    @Test fun localDataChannelDeliversOnceAndHangupCannotReconnect() = runBlocking {
        val first = peer(); val second = peer(2)
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(15_000) { second.events.filterIsInstance<NativeLiveEvent.Caption>().first() } }
            val offer = first.offer(false); val answer = second.answer(offer, false); first.acceptAnswer(answer)
            withTimeout(15_000) { first.phase.first { it == NativeCallPhase.CONNECTED } }
            // SCTP can become ready just after ICE reports connected. Retry only local channel-readiness checks.
            withTimeout(5_000) {
                while (true) {
                    val sent = runCatching { first.send(listOf("""{"type":"session.output_transcript.delta","delta":"Synthetic caption"}""")) }
                    if (sent.isSuccess) break
                    delay(25)
                }
            }
            assertEquals(NativeLiveEvent.Caption("assistant", "Synthetic caption"), received.await())
            first.close(); assertEquals(NativeCallPhase.ENDED, first.phase.value)
            assertNotNull(runCatching { first.acceptAnswer(answer) }.exceptionOrNull())
        } finally { first.close(); second.close() }
    }
    @Test fun deniedMicrophoneFailsBeforeAcquiringAudioAndMalformedAnswerReleases() = runBlocking {
        var acquired = false
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (permission == android.Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
        }
        val audio = object : VoiceAudioSessionControl {
            override fun acquire(onLost: () -> Unit): Boolean { acquired = true; return true }
            override fun release() = Unit
            override fun useSpeaker(enabled: Boolean) = true
        }
        val p = NativeWebRtcPeer(denied, NativeCallContext("http://fixture", "test", "fixture", 3), audio)
        try { assertNotNull(runCatching { p.offer(true) }.exceptionOrNull()); assertFalse(acquired); assertEquals(NativeCallPhase.FAILED, p.phase.value) }
        finally { p.close() }
        val next = peer(4)
        try { next.offer(false); assertNotNull(runCatching { next.acceptAnswer("bad") }.exceptionOrNull()); assertEquals(NativeCallPhase.FAILED, next.phase.value) }
        finally { next.close() }
    }
}
