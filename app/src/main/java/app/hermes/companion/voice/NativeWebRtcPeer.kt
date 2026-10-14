package app.hermes.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.hermes.companion.data.remote.NativeLiveProtocol
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/** One classic foreground call. A future Telecom owner must provide its own audio controller.
 * All JNI mutations and teardown run on one worker. A closed instance cannot reconnect.
 */
class NativeWebRtcPeer(
    private val context: Context, val owner: NativeCallContext,
    private val audio: VoiceAudioSessionControl = VoiceAudioSession(context),
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "companion-webrtc") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val mutablePhase = MutableStateFlow(NativeCallPhase.IDLE)
    val phase: StateFlow<NativeCallPhase> = mutablePhase.asStateFlow()
    private val mutableEvents = MutableSharedFlow<NativeLiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NativeLiveEvent> = mutableEvents.asSharedFlow()
    @Volatile private var closed = false
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var prepared = false
    private var module: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var channel: DataChannel? = null
    private val waiters = mutableSetOf<CompletableDeferred<*>>()
    private var iceComplete = CompletableDeferred<Unit>()
    private val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(32)
    private val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
    init { scope.launch {
        for (bytes in incoming) {
            if (closed) break
            try {
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val event = NativeLiveProtocol.decode(text) ?: continue
                check(mutableEvents.tryEmit(event)) { "Live event queue is full" }
                if (event is NativeLiveEvent.Error) fail(IllegalStateException(event.message))
                else if (event is NativeLiveEvent.Closed) { cleanup(); mutablePhase.value = NativeCallPhase.ENDED }
            } catch (t: Throwable) { fail(t) }
        }
    } }
    private fun rejectFrame(reason: String) {
        if (overflow.compareAndSet(false, true)) scope.launch { fail(IllegalArgumentException(reason)) }
    }

    suspend fun offer(microphone: Boolean = true): String = withContext(worker) {
        try {
            initialize(microphone)
            attachChannel(requireNotNull(peer).createDataChannel(NativeLiveProtocol.CHANNEL, DataChannel.Init()))
            val description = createDescription(false)
            setDescription(description, local = true)
            awaitIce()
            checkOpen()
            requireNotNull(peer?.localDescription).description
        } catch (t: Throwable) { fail(t); throw t }
    }

    /** Also used for an isolated two-peer transport check; it never contacts a provider. */
    suspend fun answer(offer: String, microphone: Boolean = false): String = withContext(worker) {
        try {
            validateSdp(offer)
            initialize(microphone)
            setDescription(SessionDescription(SessionDescription.Type.OFFER, offer), local = false)
            val description = createDescription(true)
            setDescription(description, local = true)
            awaitIce(); checkOpen()
            requireNotNull(peer?.localDescription).description
        } catch (t: Throwable) { fail(t); throw t }
    }
    suspend fun acceptAnswer(answer: String) = withContext(worker) {
        try {
            checkOpen(); validateSdp(answer)
            setDescription(SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
        } catch (t: Throwable) { fail(t); throw t }
    }
    suspend fun mute(muted: Boolean) = withContext(worker) {
        checkOpen(); track?.setEnabled(!muted); module?.setMicrophoneMute(muted)
    }
    suspend fun speaker(enabled: Boolean): Boolean = withContext(worker) { checkOpen(); audio.useSpeaker(enabled) }
    suspend fun send(messages: List<String>) = withContext(worker) {
        checkOpen()
        val active = requireNotNull(channel) { "Live event channel is unavailable" }
        check(active.state() == DataChannel.State.OPEN) { "Live event channel is not connected" }
        for (message in messages) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            require(bytes.size <= NativeLiveProtocol.MAX_FRAME_BYTES)
            check(active.bufferedAmount() + bytes.size <= 65_536) { "Live event channel is backpressured" }
            check(active.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))) { "Live event send failed" }
        }
    }
    suspend fun close() {
        if (!disposed.compareAndSet(false, true)) return
        withContext(kotlinx.coroutines.NonCancellable + worker) {
            if (!closed) { cleanup(); mutablePhase.value = NativeCallPhase.ENDED }
        }
        scope.cancel(); worker.close()
    }
    private fun initialize(microphone: Boolean) {
        checkOpen(); check(!prepared) { "Peer is already prepared" }; prepared = true
        if (microphone) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
            check(audio.acquire { scope.launch { fail(IllegalStateException("Call audio focus was lost")) } }) { "Another call owns audio" }
        }
        synchronized(initializationLock) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false).createInitializationOptions())
                initialized = true
            }
        }
        module = JavaAudioDeviceModule.builder(context.applicationContext).setAudioAttributes(VoiceAudioSession.attributes)
            .setUseStereoInput(false).setUseStereoOutput(false).setEnableVolumeLogger(false)
            .createAudioDeviceModule().also { it.setAudioRecordEnabled(microphone) }
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = requireNotNull(factory?.createPeerConnection(configuration, observer))
        peer?.setAudioRecording(microphone)
        // An offer-only hardware check never starts playout or capture.
        peer?.setAudioPlayout(microphone)
        if (microphone) {
            source = factory?.createAudioSource(MediaConstraints())
            track = factory?.createAudioTrack("companion-microphone", source)
            peer?.addTrack(requireNotNull(track), listOf("companion-voice"))
        } else peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        mutablePhase.value = NativeCallPhase.CONNECTING
    }
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            scope.launch {
                if (closed) return@launch
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> mutablePhase.value = NativeCallPhase.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> mutablePhase.value = NativeCallPhase.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> fail(IllegalStateException("WebRTC connection failed"))
                    PeerConnection.PeerConnectionState.CLOSED -> { cleanup(); mutablePhase.value = NativeCallPhase.ENDED }
                    else -> Unit
                }
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) = Unit // Candidates are included in the complete offer.
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) { scope.launch { if (!closed) attachChannel(dataChannel) else dataChannel.dispose() } }
        override fun onRenegotiationNeeded() = Unit // No mutation replay or silent reconnection.
    }
    private fun attachChannel(next: DataChannel) {
        if (channel != null || next.label() != NativeLiveProtocol.CHANNEL) { next.close(); next.dispose(); return }
        channel = next
        next.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed) return
                if (buffer.binary || buffer.data.remaining() > NativeLiveProtocol.MAX_FRAME_BYTES) {
                    rejectFrame("Invalid Live event frame"); return
                }
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                if (incoming.trySend(bytes).isFailure) rejectFrame("Live event queue is full")
            }
        })
    }
    private suspend fun createDescription(answer: Boolean): SessionDescription {
        checkOpen(); val deferred = CompletableDeferred<SessionDescription>(); waiters += deferred
        try {
            val callback = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { deferred.complete(sdp) }
                override fun onCreateFailure(error: String) { deferred.completeExceptionally(IllegalStateException(error)) }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            }
            if (answer) peer?.createAnswer(callback, MediaConstraints()) else peer?.createOffer(callback, MediaConstraints())
            return withTimeout(15_000) { deferred.await() }.also { checkOpen() }
        } finally { waiters -= deferred }
    }
    private suspend fun setDescription(sdp: SessionDescription, local: Boolean) {
        checkOpen(); val deferred = CompletableDeferred<Unit>(); waiters += deferred
        try {
            val callback = object : SdpObserver {
                override fun onSetSuccess() { deferred.complete(Unit) }
                override fun onSetFailure(error: String) { deferred.completeExceptionally(IllegalStateException(error)) }
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }
            if (local) peer?.setLocalDescription(callback, sdp) else peer?.setRemoteDescription(callback, sdp)
            withTimeout(15_000) { deferred.await() }; checkOpen()
        } finally { waiters -= deferred }
    }
    private suspend fun awaitIce() { waiters += iceComplete; try { withTimeout(10_000) { iceComplete.await() } } finally { waiters -= iceComplete } }
    private fun checkOpen() { check(!closed) { "Call is closed" } }
    private fun validateSdp(sdp: String) { require(sdp.length <= 262_144 && sdp.startsWith("v=0") && sdp.contains("m=audio")) { "Invalid audio SDP" } }
    private fun fail(error: Throwable) {
        if (closed) return
        mutableEvents.tryEmit(NativeLiveEvent.Error("transport", error.message ?: "Call transport failed"))
        cleanup(); mutablePhase.value = if (error is CancellationException) NativeCallPhase.ENDED else NativeCallPhase.FAILED
    }
    private fun cleanup() {
        if (closed) return
        closed = true
        incoming.cancel()
        waiters.toList().forEach { it.cancel() }; waiters.clear()
        channel?.let { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }; channel = null
        peer?.let { runCatching { it.setAudioRecording(false); it.setAudioPlayout(false); it.close(); it.dispose() } }; peer = null
        track?.let { runCatching { it.dispose() } }; track = null
        source?.let { runCatching { it.dispose() } }; source = null
        factory?.let { runCatching { it.dispose() } }; factory = null
        module?.let { runCatching { it.requestStopRecording(); it.release() } }; module = null
        audio.release()
    }
    companion object {
        private val initializationLock = Any()
        @Volatile private var initialized = false
    }
}
