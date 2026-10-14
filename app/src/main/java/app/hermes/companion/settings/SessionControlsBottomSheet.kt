package app.hermes.companion.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.SessionControlsPanel
import app.hermes.companion.design.CompanionColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionControlsBottomSheet(panel: SessionControlsPanel, onRefresh: () -> Unit,
    onSet: (String, String, Boolean) -> Unit, onRedirect: (String) -> Unit, onCommand: (String) -> Unit,
    onDismiss: () -> Unit) {
    var model by remember(panel.owner, panel.settings?.model) { mutableStateOf(panel.settings?.model.orEmpty()) }
    var redirect by remember(panel.owner) { mutableStateOf("") }
    var command by remember(panel.owner) { mutableStateOf("") }
    val enabled = !panel.loading && !panel.busy
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CompanionColor.VoidElevated) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("session.controls"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Session controls", style = MaterialTheme.typography.titleLarge)
            Text(panel.session.title, style = MaterialTheme.typography.bodyMedium)
            if (panel.loading || panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            panel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            panel.notice?.takeIf(String::isNotBlank)?.let { Text(it) }
            panel.pending.forEach { Text("Awaiting host outcome: ${it.kind}. Refresh checks the result without resending.") }
            Text("Applied model: ${panel.settings?.model ?: "Not reported"}")
            Text("Provider: ${panel.settings?.provider ?: "Not reported"}")
            OutlinedTextField(model, { model = it }, label = { Text("Session model") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = enabled && model.isNotBlank() && "config.model" !in panel.unsupported,
                onClick = { onSet("model", model, false) }) { Text("Apply session model") }
            Text("Reasoning: ${panel.settings?.reasoning ?: "Not reported"}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("none", "low", "medium", "high", "xhigh", "max").forEach { tier ->
                    FilterChip(selected = panel.settings?.reasoning == tier, enabled = enabled && "config.reasoning" !in panel.unsupported,
                        onClick = { onSet("reasoning", tier, false) }, label = { Text(tier) })
                }
            }
            Text("Speed: ${panel.settings?.fast ?: "Not reported"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("normal", "fast").forEach { value -> FilterChip(selected = panel.settings?.fast == value,
                    enabled = enabled && "config.fast" !in panel.unsupported, onClick = { onSet("fast", value, false) }, label = { Text(value) }) }
            }
            if (panel.confirmationKey != null && panel.confirmationValue != null) {
                TextButton(enabled = !panel.busy, onClick = { onSet(panel.confirmationKey, panel.confirmationValue, true) }) { Text("Confirm host request") }
            }
            OutlinedTextField(redirect, { redirect = it }, label = { Text("Redirect the current turn") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = enabled && redirect.isNotBlank() && "redirect" !in panel.unsupported, onClick = { onRedirect(redirect) }) { Text("Redirect") }
            HorizontalDivider()
            Text("Hermes commands", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(command, { command = it }, label = { Text("Command and arguments") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = enabled && command.isNotBlank() && "command" !in panel.unsupported, onClick = { onCommand(command) }) { Text("Run command") }
            panel.commands.filter { command.isBlank() || it.name.contains(command.trim().removePrefix("/"), true) }.take(40).forEach { item ->
                TextButton(onClick = { command = "/${item.name}" }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("/${item.name}"); Text(item.description, style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(enabled = !panel.busy, onClick = onRefresh) { Text("Refresh from Hermes") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
