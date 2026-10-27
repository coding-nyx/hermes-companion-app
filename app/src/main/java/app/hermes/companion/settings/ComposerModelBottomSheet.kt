package app.hermes.companion.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val enabled = !panel.loading && !panel.busy && panel.pending.isEmpty() && panel.confirmationKey == null && settings != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("composer.models"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeading("Model & reasoning", onDismiss, closeModifier = Modifier.testTag("composer.models.done"))
            GroupSurface(padded = true) {
                Text("This conversation", style = CompanionType.Label, color = CompanionColor.Accent)
                Text(panel.session.title.ifBlank { "Untitled conversation" }, style = CompanionType.Body)
                ProtocolStatus(if (settings == null) "Model status" else "Last confirmed by Hermes",
                    settings?.model ?: if (panel.loading) "Reading model…" else "Model unavailable",
                    tone = if (settings != null) CompanionColor.Signal else CompanionColor.TextDim)
                settings?.provider?.takeIf(String::isNotBlank)?.let {
                    Text("Provider: $it", style = CompanionType.Caption)
                }
            }
            if (panel.loading || panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            panel.error?.let { StatusStrip(it, tone = CompanionColor.Danger) }
            panel.notice?.takeIf(String::isNotBlank)?.let { Text(it, style = CompanionType.Body) }
            if (panel.pending.isNotEmpty()) StatusStrip(
                "Checking the earlier change. Refresh checks its outcome without sending it again.", tone = CompanionColor.Warn)
            if ("config.model" in panel.unsupported) StatusStrip(
                "This host does not support changing the conversation model.", tone = CompanionColor.TextDim)
            if (panel.confirmationKey != null && panel.confirmationValue != null) {
                ActionButton("Confirm model change", { onSet(panel.confirmationKey, panel.confirmationValue, true) },
                    enabled = !panel.busy, kind = ActionKind.PRIMARY)
            }
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerSectionTab("Model", section == ComposerSettingsSection.MODEL,
                    Modifier.testTag("composer.tab.model")) { section = ComposerSettingsSection.MODEL }
                ComposerSectionTab("Reasoning", section == ComposerSettingsSection.REASONING,
                    Modifier.testTag("composer.tab.reasoning")) { section = ComposerSettingsSection.REASONING }
            }
            if (section == ComposerSettingsSection.MODEL) {
            Text("Model", style = MaterialTheme.typography.titleSmall)
            app.hermes.companion.design.SearchField(query, { query = it }, placeholder = "Search models or providers",
                modifier = Modifier.fillMaxWidth().testTag("composer.models.search"))
            Box {
                ActionButton((provider ?: "All providers") + " ▾", { providersOpen = true },
                    modifier = Modifier.testTag("composer.models.providers"))
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
                        ModelChoiceRow(model, selected = selected,
                            enabled = enabled && "config.model" !in panel.unsupported,
                            modifier = Modifier.testTag("composer.models.item.${model.provider}/${model.id}"),
                            onClick = {
                                if (!selected) onSet("model", "${model.id} --provider ${model.provider} --session", false)
                            })
                    }
                }
            }
            } else GroupedSection("Reasoning", padded = true) { ReasoningControls(panel, onSet) }
            ActionButton("Refresh from Hermes", onRefresh, enabled = !panel.busy, modifier = Modifier.testTag("composer.models.refresh"))
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ComposerSectionTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 7.dp, bottomStart = 7.dp)
    Box(modifier.clip(shape).background(if (selected) CompanionColor.SignalSubtle else CompanionColor.VoidElevated)
        .border(1.dp, if (selected) CompanionColor.Signal else CompanionColor.LineStrong, shape)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = CompanionType.Label, color = if (selected) CompanionColor.Signal else CompanionColor.TextDim)
    }
}
