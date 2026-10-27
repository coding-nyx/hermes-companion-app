package app.hermes.desk.feature

import app.hermes.companion.VoiceStreamState
import app.hermes.companion.model.*
import kotlinx.coroutines.flow.Flow

data class DeskConnection(val origin: String, val username: String = "", val password: String = "", val token: String = "")
data class DeskBinding(val origin: String, val profile: String, val session: String)
data class DeskCapabilities(val conversations: Boolean = true, val decisions: Boolean = true, val interrupt: Boolean = true,
    val compaction: Boolean = true, val companionChat: Boolean = true, val liveEvents: Boolean = true)

/** A backend owns its conversation semantics; the character and speech layer are independent. */
interface DeskBackend {
    val label: String
    val capabilities: DeskCapabilities
    suspend fun connect(connection: DeskConnection): List<ProfileRef>
    suspend fun selectProfile(profile: String): List<SessionRef>
    suspend fun history(binding: DeskBinding): List<ChatMessage>
    suspend fun createThread(profile: String): SessionRef
    fun events(profile: String): Flow<BusFrame>
    fun belongsTo(binding: DeskBinding, eventSession: String?): Boolean
    fun turn(binding: DeskBinding, text: String): Flow<ChatEvent>
    suspend fun pending(binding: DeskBinding): ApprovalPrompt?
    fun runtime(binding: DeskBinding): SessionRuntimeSnapshot? = null
    suspend fun respond(binding: DeskBinding, prompt: ApprovalPrompt, choice: String)
    suspend fun interrupt(binding: DeskBinding)
    suspend fun context(binding: DeskBinding): SessionContext? = null
    suspend fun compact(binding: DeskBinding): SessionCompression = error("This host does not support compaction")
    suspend fun stageImage(binding: DeskBinding, jpeg: ByteArray) { error("This host does not support images") }
    suspend fun settings(binding: DeskBinding): NativeSessionSettings? = null
    suspend fun setting(binding: DeskBinding, key: String, value: String, confirmed: Boolean): NativeConfigResult = error("Session settings unavailable")
    fun close()
}

interface DeskPersistence {
    fun connection(): DeskConnection?
    fun saveConnection(connection: DeskConnection)
    fun profile(origin: String): String
    fun saveProfile(origin: String, profile: String)
    fun session(origin: String, profile: String): String
    fun saveSession(binding: DeskBinding)
    fun pending(binding: DeskBinding): OutboxItem?
    /** Must be durable before dispatch; false means sending is refused. */
    fun savePending(binding: DeskBinding, item: OutboxItem?): Boolean
    fun draft(binding: DeskBinding): String
    fun saveDraft(binding: DeskBinding, text: String)
    fun compactionPending(binding: DeskBinding): Boolean = false
    fun saveCompactionPending(binding: DeskBinding, pending: Boolean): Boolean = !pending
}

data class DeskState(
    val backend: String = "Hermes",
    val origin: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val profiles: List<ProfileRef> = emptyList(),
    val profile: String = "",
    val sessions: List<SessionRef> = emptyList(),
    val session: SessionRef? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val working: Boolean = false,
    val turnActive: Boolean = false,
    val pending: ApprovalPrompt? = null,
    val decisionSending: Boolean = false,
    val uncertain: Boolean = false,
    val tool: String = "",
    val error: String? = null,
    val voice: VoiceStreamState = VoiceStreamState.IDLE,
    val ambient: Boolean = false,
    val privateCaptions: Boolean = false,
    val keepAwake: Boolean = true,
    val reducedMotion: Boolean = false,
    val microphoneReady: Boolean = false,
    val speechMode: String = "hermes",
    val visionSharing: Boolean = false,
    val compacting: Boolean = false,
    val compactionUncertain: Boolean = false,
    val context: SessionContext? = null,
    val modelSettings: NativeSessionSettings? = null,
    val settingsBusy: Boolean = false,
    val settingConfirmation: DeskSettingConfirmation? = null,
    val settingNotice: String? = null,
    val capabilities: DeskCapabilities = DeskCapabilities(),
) {
    val binding: DeskBinding? get() = session?.let { DeskBinding(origin, profile, it.id) }
    val canSend: Boolean get() = connected && !connecting && !loading && session != null && !working && !turnActive && !uncertain && pending == null && !compacting && !compactionUncertain && !settingsBusy
    val caption: String get() = when {
        privateCaptions -> "Your conversation stays private"
        pending != null -> "Your decision is needed"
        compactionUncertain -> "Context maintenance needs checking. Open history before continuing."
        compacting -> "Keeping our conversation in order."
        else -> messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.reasoning && it.text.isNotBlank() }?.text
            ?: "A little space for your ideas."
    }
    val status: String get() = when {
        connecting -> "Connecting"
        !connected -> "Not connected"
        loading -> "Opening conversation"
        uncertain -> "Check message delivery"
        pending != null -> "Waiting for you"
        voice == VoiceStreamState.LISTENING -> if (microphoneReady) "Listening · microphone on" else "Preparing microphone"
        voice == VoiceStreamState.TRANSCRIBING -> "Recognizing speech"
        voice == VoiceStreamState.SPEAKING -> "Speaking"
        working -> "Working"
        else -> "Ready · microphone off"
    }
}

data class DeskSettingConfirmation(val key: String, val value: String, val message: String)

sealed interface DeskSignal {
    data object StopVoice : DeskSignal
    data class Event(val value: ChatEvent) : DeskSignal
}
