package app.hermes.companion.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.CompanionState
import app.hermes.companion.MainTab
import app.hermes.companion.design.*
import app.hermes.companion.model.SpeechBackend

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    state: CompanionState,
    onSelectProfile: (String) -> Unit,
    onSwitchModel: (model: String, provider: String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onTab: (MainTab) -> Unit,
    onSpeechBackend: (app.hermes.companion.model.SpeechBackend) -> Unit = {},
    onToggleStay: () -> Unit,
    onToggleKeepA11y: () -> Unit = {},
    onNtfyTopicChange: (String) -> Unit,
    onSaveNtfy: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var page by rememberSaveable(state.origin) { mutableStateOf("Settings") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(onDismissRequest = { if (page == "Settings") onDismiss() else page = "Settings" }, sheetState = sheetState,
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text,
        modifier = Modifier.testTag("settings.sheet")) {
        val scroll = remember(page) { androidx.compose.foundation.ScrollState(0) }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)) {
            SheetHeading(page, onDismiss, onBack = if (page != "Settings") ({ page = "Settings" }) else null,
                closeModifier = Modifier.testTag("settings.sheet.close"))
            when (page) {
                "Settings" -> {
                    GroupedSection("Connection") {
                        PreferenceRow("Host", state.hostName.ifBlank { state.origin ?: "Disconnected" },
                            onClick = { onTab(MainTab.GATEWAY) }, modifier = Modifier.testTag("settings.host.dashboard"))
                        PreferenceRow("Profile", state.activeProfile?.displayName ?: state.activeProfileId ?: "Choose a profile",
                            onClick = { onTab(MainTab.PROFILES) }, modifier = Modifier.testTag("settings.profile"))
                    }
                    GroupedSection("Conversation") {
                        PreferenceRow("Model", state.modelOverride.ifBlank { state.modelCatalog?.currentModel.orEmpty() }.ifBlank { "Host default" },
                            onClick = onOpenModelPicker, modifier = Modifier.testTag("settings.model.change"))
                        PreferenceRow("Speech", if (state.speechBackend == SpeechBackend.HOST) "Host speech" else "System speech",
                            onClick = { page = "Speech" }, modifier = Modifier.testTag("settings.speech.open"))
                    }
                    GroupedSection("App & privacy") {
                        PreferenceRow("Appearance & readability", "Device theme, text size and motion", onClick = { page = "Appearance & readability" }, modifier = Modifier.testTag("settings.appearance"))
                        PreferenceRow("Background connection", if (state.stayConnected) "Enabled" else "Disabled", onClick = { page = "Background connection" }, modifier = Modifier.testTag("settings.background.open"))
                        PreferenceRow("Privacy & device access", "Hands, notifications, protection and app lock", onClick = { onTab(MainTab.DEVICE) })
                    }
                    GroupedSection("About") {
                        PreferenceRow("Host capabilities", "Current feature availability", onClick = { page = "Host capabilities" }, modifier = Modifier.testTag("settings.capabilities"))
                        PreferenceRow("About Companion", "Version and connection", onClick = { page = "About Companion" })
                        PreferenceRow("Disconnect", "End this host connection", onClick = { confirmDisconnect = true }, modifier = Modifier.testTag("settings.disconnect"))
                    }
                }
                "Speech" -> {
                    GroupedSection("Speech backend") {
                        SpeechBackend.entries.forEach { backend ->
                            val enabled = backend != SpeechBackend.HOST || app.hermes.companion.BuildConfig.HOST_SPEECH_ENABLED
                            PreferenceRow(if (backend == SpeechBackend.HOST) "Host speech" else "System speech",
                                if (backend == SpeechBackend.HOST) if (enabled) "Use the selected host and profile" else "Spoken recognition and conversation checks remain deferred"
                                    else "Android speech services", selected = state.speechBackend == backend,
                                value = if (state.speechBackend == backend) "Selected" else null,
                                enabled = enabled, onClick = { onSpeechBackend(backend) }, modifier = Modifier.testTag("settings.speech.${backend.name.lowercase()}"))
                        }
                    }
                    GroupedSection("Voice behavior") {
                        PreferenceRow("Spoken replies", "Brief answers; full responses remain available in chat")
                        PreferenceRow("Provider failures", "A failed request never silently changes the speech backend")
                        PreferenceRow("Speaker playback", "Listening pauses while the phone speaks")
                    }
                }
                "Background connection" -> {
                    GroupedSection("Connection") {
                        PreferenceToggle("Stay connected", state.stayConnected, onToggleStay,
                            summary = "Keep the operator connection active in the background", modifier = Modifier.testTag("settings.stay"))
                        if (state.keepA11yAvailable) PreferenceToggle("Keep screen control ready", state.keepA11yOn, onToggleKeepA11y,
                            summary = "Re-enable accessibility if it drops out. This does not arm Hands.", modifier = Modifier.testTag("settings.keep_a11y"))
                    }
                    GroupedSection("Notification topic", padded = true) {
                        HairlineField(state.ntfyTopic, onNtfyTopicChange, placeholder = "Topic name", onDone = onSaveNtfy,
                            modifier = Modifier.testTag("settings.ntfy.input"))
                        ActionButton("Save topic", onSaveNtfy, modifier = Modifier.testTag("settings.ntfy.save"), kind = ActionKind.PRIMARY)
                    }
                }
                "Appearance & readability" -> {
                    GroupedSection("Appearance") {
                        PreferenceRow("Theme", "Follow device appearance")
                        PreferenceRow("Text size", "Follow Android font size", onClick = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
                        })
                        PreferenceRow("Accessibility", "Android text, contrast and motion settings", onClick = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        })
                    }
                    Text("Labels and actions reflow with large text. Formatting and recovery controls stay available.", style = CompanionType.Caption)
                }
                "Host capabilities" -> {
                    GroupedSection("Current host") {
                        PreferenceRow("Connection", state.hostName.ifBlank { state.origin.orEmpty() })
                        PreferenceRow("Conversations", if (state.gatewayHello != null) "Connected" else "Waiting for host connection")
                        PreferenceRow("Companion extension", when (state.companionAvailability) {
                            app.hermes.companion.model.CompanionAvailability.AVAILABLE -> "Available for paired phone tools and rooms"
                            app.hermes.companion.model.CompanionAvailability.UNAVAILABLE -> "Unavailable on this host"
                            else -> "Not yet confirmed"
                        })
                        PreferenceRow("Native realtime voice", state.nativeVoiceStatus?.reason?.takeIf { it.isNotBlank() } ?: "Disabled pending provider validation")
                    }
                    GroupedSection("Planned") {
                        PreferenceRow("Incoming calls", "Unavailable until invitation delivery and Android call integration pass validation")
                        PreferenceRow("Host administration", "Projects, bots, skills, toolsets, MCP and connectors require verified host contracts")
                    }
                    Text("Authentication or network errors do not mean a feature is unsupported.", style = CompanionType.Caption)
                }
                "About Companion" -> GroupedSection("App") {
                    PreferenceRow("Version", app.hermes.companion.BuildConfig.VERSION_NAME)
                    PreferenceRow("Host", state.hostName.ifBlank { state.origin.orEmpty() })
                    PreferenceRow("Profile", state.activeProfile?.displayName ?: state.activeProfileId.orEmpty())
                }
            }
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect from ${state.hostName.ifBlank { "this host" }}?") },
        text = { Text("Active voice ends. Conversations and drafts stay associated with their original host and profile.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Disconnect", color = CompanionColor.Danger) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Keep connected") } })
}
