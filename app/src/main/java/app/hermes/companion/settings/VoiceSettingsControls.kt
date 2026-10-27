package app.hermes.companion.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hermes.companion.CompanionState
import app.hermes.companion.design.*

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VoiceSettingsControls(state: CompanionState, refresh: () -> Unit,
    save: (Map<String, String>) -> Unit, pacing: (Int) -> Unit, loadVoices: (String) -> Unit) {
    val settings = state.hostVoiceSettings
    var changes by remember(state.origin, state.activeProfileId, settings?.fields?.map { it.key to it.value }) { mutableStateOf(emptyMap<String, String>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    fun value(key: String) = changes[key] ?: settings?.value(key).orEmpty()
    val selectedStt = value("stt.provider").let { if (it == "auto") settings?.sttProvider.orEmpty() else it }
    val selectedTts = value("tts.provider")
    GroupedSection("Hermes profile speech") {
        Text("Changes apply to ${state.activeProfileId ?: "the selected profile"} across Hermes clients. Active calls keep their current voice; saved changes apply to the next call.", style = CompanionType.Caption)
        if (state.voiceSettingsLoading) Text("Checking host speech settings…", style = CompanionType.Body)
        if (state.voiceSettingsError.isNotBlank()) StatusStrip(state.voiceSettingsError)
        if (settings?.editable == true) {
            settings.fields.filter { field ->
                field.key in setOf("voice.voice_chat_mode", "stt.provider", "stt.language", "tts.provider", "voice.gpt_live.model", "voice.gpt_live.voice") ||
                    field.key.startsWith("stt.$selectedStt.") || field.key.startsWith("tts.$selectedTts.") ||
                    field.key.startsWith("stt.providers.$selectedStt.") || field.key.startsWith("tts.providers.$selectedTts.")
            }.forEach { field ->
                val label = when(field.key) {
                    "voice.voice_chat_mode" -> "Voice mode"
                    "stt.provider" -> "Recognition provider"
                    "tts.provider" -> "Speech provider"
                    "voice.gpt_live.model" -> "Realtime model"
                    "voice.gpt_live.voice" -> "Realtime voice"
                    else -> field.key.split('.').let { parts ->
                        "${if(parts.first() == "stt") "Recognition" else "Speech"} ${parts.last().replace('_', ' ')}"
                    }
                }
                PreferenceRow(label, value = value(field.key), onClick = { editing = field.key; text = value(field.key); if (field.options.isEmpty() && (field.key.endsWith("voice") || field.key.endsWith("voice_id"))) loadVoices(field.key) },
                    modifier = Modifier.testTag("voice.setting.${field.key}"))
            }
            ActionButton("Save for all Hermes clients", { save(changes) }, enabled = changes.isNotEmpty() && !state.voiceSettingsLoading,
                modifier = Modifier.testTag("voice.settings.save"), kind = ActionKind.PRIMARY)
        } else Text("This host does not expose editable speech settings yet. Existing host speech can still be used.", style = CompanionType.Caption)
        settings?.live?.let { live ->
            Text(if (live.available == true) "Realtime credentials are configured on the host. Device conversation validation is separate."
                else "Realtime unavailable: ${live.reason.ifBlank { "Check the host’s voice configuration" }}", style = CompanionType.Caption)
        }
        if (settings?.speechAvailable == false) Text("Hermes speech unavailable: ${settings.speechReason}", style = CompanionType.Caption)
        ActionButton("Refresh speech settings", refresh, enabled = !state.voiceSettingsLoading)
    }
    GroupedSection("Speaking pace") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(600 to "Quick", 900 to "Natural", 1400 to "Patient").forEach { (ms, label) ->
                ActionButton(label, { pacing(ms) }, kind = if (state.voicePacingMs == ms) ActionKind.PRIMARY else ActionKind.GHOST)
            }
        }
        Text("Silence keeps the conversation open. Pace changes apply to the next call.", style = CompanionType.Caption)
    }
    editing?.let { key ->
        val field = settings?.fields?.firstOrNull { it.key == key }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(key.replace('.', ' ')) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (!field?.options.isNullOrEmpty()) field!!.options.forEach { option ->
                        TextButton(onClick = { changes = changes + (key to option); editing = null }) { Text(option) }
                    } else HairlineField(text, { text = it }, placeholder = "Value", keyboardType = if (field?.numeric == true) KeyboardType.Decimal else KeyboardType.Text)
                }
            }, confirmButton = {
                if (field?.options.isNullOrEmpty()) TextButton(onClick = { changes = changes + (key to text.trim()); editing = null }) { Text("Apply") }
            }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
