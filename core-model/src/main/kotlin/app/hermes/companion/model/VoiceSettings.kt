package app.hermes.companion.model

import kotlinx.serialization.Serializable

@Serializable
data class VoiceSettingField(val key: String, val value: String, val options: List<String> = emptyList(), val numeric: Boolean = false)

data class HostVoiceSettings(
    val fields: List<VoiceSettingField> = emptyList(),
    val live: NativeVoiceStatus = NativeVoiceStatus(),
    val streaming: Boolean = false,
    val editable: Boolean = false,
    val snapshots: Boolean = false,
    val speechAvailable: Boolean? = null,
    val speechReason: String = "",
    val sttProvider: String = "",
    val ttsProvider: String = "",
) {
    fun value(key: String): String = fields.firstOrNull { it.key == key }?.value.orEmpty()
    val realtime: Boolean get() = value("voice.voice_chat_mode").ifBlank { live.mode } == "gpt-live"
    val description: String get() = if (realtime) "${live.model} · ${live.voice}" else
        "${sttProvider.ifBlank { value("stt.provider").ifBlank { "Host STT" } }} → ${ttsProvider.ifBlank { value("tts.provider").ifBlank { "Host TTS" } }}"
}

@Serializable
data class VoiceTurnMetadata(val callId: String, val turnId: String, val surface: String = "voice-live", val context: String = "", val steering: Boolean = false)

@Serializable
data class VoiceCaption(val role: String, val text: String, val startMs: Long = 0, val endMs: Long = 0, val eventId: String = "")
