package app.hermes.companion.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.SessionControlsPanel
import app.hermes.companion.design.*

enum class ComposerSettingsSection { MODEL, REASONING }

/** Session inventory and applied settings come from the same captured host/profile/session. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerModelBottomSheet(
    panel: SessionControlsPanel,
    onSet: (String, String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    initialSection: ComposerSettingsSection = ComposerSettingsSection.MODEL,
) {
    var query by remember(panel.owner) { mutableStateOf("") }
    var provider by remember(panel.owner) { mutableStateOf<String?>(null) }
    var providersOpen by remember { mutableStateOf(false) }
    var section by remember(panel.owner, initialSection) { mutableStateOf(initialSection) }
    val settings = panel.settings
    val models = settings?.models.orEmpty().distinctBy { it.provider to it.id }
    val providers = models.map { it.provider }.distinct().sorted()
    val filtered = models.filter {
        (provider == null || it.provider == provider) &&
            (query.isBlank() || listOf(it.id, it.name, it.provider).any { value -> value.contains(query.trim(), true) })
    }.groupBy { it.provider }.toSortedMap()
    val current = models.find { it.id == settings?.model && it.provider == settings.provider }
    val enabled = !panel.loading && !panel.busy && panel.pending.isEmpty() && panel.confirmationKey == null && settings != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("composer.models"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeading("Model & reasoning", onDismiss, closeModifier = Modifier.testTag("composer.models.done"))
            Text("For this conversation", style = MaterialTheme.typography.bodySmall, color = CompanionColor.TextDim)
            if (panel.loading || panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(settings?.model ?: "Reading model from Hermes…", style = MaterialTheme.typography.titleMedium)
            settings?.provider?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CompanionColor.TextDim) }
            panel.error?.let { Text(it, color = CompanionColor.Danger) }
            panel.notice?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (panel.pending.isNotEmpty()) Text("Checking the earlier change. Refresh checks its outcome without sending it again.")
            if (panel.confirmationKey != null && panel.confirmationValue != null) {
                Button(onClick = { onSet(panel.confirmationKey, panel.confirmationValue, true) }, enabled = !panel.busy) {
                    Text("Confirm model change")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { section = ComposerSettingsSection.MODEL }, modifier = Modifier.testTag("composer.tab.model")) { Text(if (section == ComposerSettingsSection.MODEL) "✓ Model" else "Model") }
                TextButton(onClick = { section = ComposerSettingsSection.REASONING }, modifier = Modifier.testTag("composer.tab.reasoning")) { Text(if (section == ComposerSettingsSection.REASONING) "✓ Reasoning" else "Reasoning") }
            }
            if (section == ComposerSettingsSection.MODEL) {
            Text("Model", style = MaterialTheme.typography.titleSmall)
            app.hermes.companion.design.HairlineField(query, { query = it }, placeholder = "Search models or providers",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                modifier = Modifier.fillMaxWidth().testTag("composer.models.search"))
            Box {
                OutlinedButton(onClick = { providersOpen = true }, modifier = Modifier.testTag("composer.models.providers")) {
                    Text((provider ?: "All providers") + " ▾")
                }
                DropdownMenu(providersOpen, { providersOpen = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    DropdownMenuItem(text = { Text("All providers") }, onClick = { provider = null; providersOpen = false })
                    providers.forEach { option -> DropdownMenuItem(text = { Text(option) },
                        onClick = { provider = option; providersOpen = false }, modifier = Modifier.testTag("composer.models.provider.$option")) }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("composer.models.list")) {
                if (filtered.isEmpty()) item { Text(if (panel.loading) "Loading models…" else "No matching models", Modifier.padding(12.dp)) }
                filtered.forEach { (group, entries) ->
                    item(key = "provider:$group") {
                        Text(group.ifBlank { "Provider not reported" }, Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = CompanionColor.TextDim)
                    }
                    items(entries, key = { "${it.provider}/${it.id}" }) { model ->
                        val selected = model.id == settings?.model && model.provider == settings.provider
                        ListItem(
                            headlineContent = { Text(model.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (model.reasoning) ({ Text("Reasoning") }) else null,
                            trailingContent = if (selected) ({ Text("✓", color = CompanionColor.Signal) }) else null,
                            colors = ListItemDefaults.colors(containerColor = CompanionColor.VoidElevated),
                            modifier = Modifier.testTag("composer.models.item.${model.provider}/${model.id}")
                                .semantics { this.selected = selected }
                                .heightIn(min = 48.dp).clickable(enabled = enabled && !selected && "config.model" !in panel.unsupported, role = Role.RadioButton) {
                                    onSet("model", "${model.id} --provider ${model.provider} --session", false)
                                },
                        )
                    }
                }
            }
            } else GroupedSection("Reasoning", padded = true) { ReasoningControls(panel, onSet) }
            TextButton(onClick = onRefresh, enabled = !panel.busy, modifier = Modifier.testTag("composer.models.refresh")) { Text("Refresh from Hermes") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
