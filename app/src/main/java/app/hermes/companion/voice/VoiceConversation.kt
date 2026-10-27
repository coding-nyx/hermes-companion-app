package app.hermes.companion.voice

import app.hermes.companion.model.SessionRef
import app.hermes.companion.model.VoiceTurnMetadata

/** The same conversation owner admits typed input and both forms of voice input. */
interface VoiceConversation {
    val conversationEpoch: Long
    var onAssistantDelta: ((String) -> Unit)?
    var onTurnCompleted: (() -> Unit)?
    var onTurnInterrupted: (() -> Unit)?
    var onTurnFailed: (() -> Unit)?
    fun newThread()
    suspend fun ensureVoiceSession(): SessionRef
    fun sendPrompt(text: String, displayText: String = text, voice: VoiceTurnMetadata? = null)
    fun steerVoice(text: String, voice: VoiceTurnMetadata)
    fun interrupt()
}
