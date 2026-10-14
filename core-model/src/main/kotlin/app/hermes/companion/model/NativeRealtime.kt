package app.hermes.companion.model

/** A call never follows subsequent operator host/profile/session changes. */
data class NativeCallContext(val origin: String, val profile: String, val sessionId: String, val generation: Long)
data class NativeRealtimeAnswer(val sessionId: String, val sdp: String)
enum class NativeCallPhase { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ENDED, FAILED }
sealed interface NativeLiveEvent {
    data class Started(val id: String?) : NativeLiveEvent
    data class Caption(val role: String, val delta: String) : NativeLiveEvent
    data class Delegation(val id: String) : NativeLiveEvent
    data class Error(val code: String?, val message: String) : NativeLiveEvent
    data class Closed(val reason: String?) : NativeLiveEvent
}
