@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.hermes.desk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermes.desk.feature.DeskState

@Composable
fun DeskConnections(vm: DeskViewModel, state: DeskState, onDismiss: () -> Unit, openBrowser: (String) -> Unit) {
    val accounts by vm.accountLabels.collectAsStateWithLifecycle()
    val busy by vm.connectionBusy.collectAsStateWithLifecycle()
    val signIn by vm.signInPending.collectAsStateWithLifecycle()
    val notice by vm.connectionNotice.collectAsStateWithLifecycle()
    val enabled = !busy && !state.working && !state.loading && !state.connecting
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) { Text("Your connections", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onDismiss) { Text("Close connections") } }
            Text("Choose the conversation provider. Speech is configured separately in Voice setup.")
            ProviderCard("Hermes", "Your profiles, conversations and tools on lab or another trusted host.") {
                Button(vm::useHermes, enabled = enabled && vm.savedHermes != null) { Text("Use saved Hermes") }
                if (vm.savedHermes == null) Text("Enter your host address on the connection screen to begin.")
            }
            ProviderCard("ChatGPT", "Sign in with your ChatGPT plan. These conversations stay in Desk Companion; they do not import your ChatGPT history. Account limits apply.") {
                Button(onClick = { vm.continueChatGpt(openBrowser) }, enabled = enabled) { Text("Continue with ChatGPT") }
                accounts.forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ vm.useChatGpt(key) }, enabled = enabled) { Text("Use account") }
                        TextButton({ vm.continueChatGpt(openBrowser, key) }, enabled = enabled) { Text("Reconnect") }
                        TextButton({ vm.signOutChatGpt(key) }, enabled = enabled) { Text("Sign out") }
                    }
                }
            }
            ProviderCard("Grok", "Native Grok conversations, using lab’s existing OAuth account through Desk Bridge. Provider credentials stay on lab. Hermes conversation history and tools are separate.") {
                Button(vm::useGrok, enabled = enabled && vm.savedHermes != null) { Text("Connect Grok") }
            }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (signIn) TextButton(vm::cancelSignIn) { Text("Cancel sign-in") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun ProviderCard(name: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium); content()
    } }
}

@Composable
fun DeskVoiceSetup(vm: DeskViewModel, state: DeskState, context: Context, onDismiss: () -> Unit, startVoice: () -> Unit) {
    val notice by vm.connectionNotice.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(vm.automaticVoiceEnabled) }
    var pacing by remember { mutableStateOf(vm.voicePause) }
    var threshold by remember { mutableStateOf(vm.voiceThreshold) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) { Text("Voice setup", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onDismiss) { Text("Close voice setup") } }
            Text("1. Choose your speech service", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.speechMode == "hermes", { vm.speechMode("hermes") }, label = { Text("Hermes speech") })
                FilterChip(state.speechMode == "system", { vm.speechMode("system") }, label = { Text("Device speech") })
            }
            Text(if (state.speechMode == "hermes") "Uses your saved Hermes host’s speech models, including when ChatGPT is the conversation provider. App-owned recording avoids recognizer service beeps."
                else "Uses Android’s installed recognition and voice services. Availability, languages, network use and service tones depend on this tablet.")
            if (state.speechMode == "hermes") {
                Text("Pause before replying")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(600 to "Quick", 900 to "Natural", 1400 to "Patient").forEach { (value, label) ->
                        FilterChip(pacing == value, { pacing = value; vm.voicePacing(value) }, label = { Text(label) })
                    }
                }
                Text("Microphone sensitivity")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(300 to "Soft voice", 600 to "Normal", 1000 to "Noisy room").forEach { (value, label) ->
                        FilterChip(threshold == value, { threshold = value; vm.voiceSensitivity(value) }, label = { Text(label) })
                    }
                }
            }
            Text("2. Check audio", style = MaterialTheme.typography.titleMedium)
            Text(if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Microphone permission is granted. Capture starts only when you start voice." else "Microphone access will be requested when you start voice.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(vm::testSpeaker, enabled = state.canSend && state.speechMode == "hermes") { Text("Test speaker") }
                TextButton({ context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) { Text("Audio settings") }
            }
            Text("3. Conversation start", style = MaterialTheme.typography.titleMedium)
            Row { Text("Start voice when opening the desk", modifier = Modifier.weight(1f)); Switch(automatic, { automatic = it; vm.setAutomaticVoice(it) }) }
            Text("After your first voice consent, this saves your preference for starting voice when Desk launches. Ending or muting a conversation keeps it paused. Camera sharing always starts separately.")
            Text("Wake phrase and speaking over a reply are planned next. This build listens between replies; it does not listen for a wake phrase while muted or in the background.", style = MaterialTheme.typography.bodySmall)
            if (notice.isNotBlank()) Text(notice)
            Button(onClick = { onDismiss(); startVoice() }, enabled = state.canSend) { Text("Start voice conversation") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
