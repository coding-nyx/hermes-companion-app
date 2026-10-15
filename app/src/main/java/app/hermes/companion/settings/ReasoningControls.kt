package app.hermes.companion.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hermes.companion.SessionControlsPanel
import app.hermes.companion.design.CompanionColor

/** Controls Hermes' session configuration. Provider wire clamping remains owned by Hermes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReasoningControls(panel: SessionControlsPanel, onSet: (String, String, Boolean) -> Unit) {
    val settings = panel.settings
    val model = settings?.models?.find { it.id == settings.model && it.provider == settings.provider }
    val value = settings?.reasoning?.lowercase()?.trim()
    val on = value != null && value !in setOf("none", "off", "disabled", "")
    val enabled = settings != null && value != null && !panel.loading && !panel.busy &&
        panel.pending.isEmpty() && panel.confirmationKey == null && "config.reasoning" !in panel.unsupported && !(model?.reasoningSupportReported == true && !model.reasoning)
    var previous by remember(panel.owner) { mutableStateOf("medium") }
    LaunchedEffect(value) { if (on) previous = value!! }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Reasoning", style = MaterialTheme.typography.titleMedium)
            Text(if (value == null) "Reading from Hermes…" else if (on) "On · $value" else "Off",
                style = MaterialTheme.typography.bodySmall, color = CompanionColor.TextDim)
        }
        Switch(checked = on, enabled = enabled && (!on || model?.canDisableReasoning != false),
            onCheckedChange = { onSet("reasoning", if (it) previous else "none", false) },
            modifier = Modifier.testTag("composer.models.reasoning").semantics { contentDescription = "Reasoning" })
    }
    when {
        model?.reasoningSupportReported == true && !model.reasoning -> Text("This model does not offer reasoning controls.", color = CompanionColor.TextDim)
        else -> {
            Text("Effort", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("low", "medium", "high", "xhigh", "max").forEach { effort ->
                    TextButton(onClick = { onSet("reasoning", effort, false) }, enabled = enabled && value != effort,
                        modifier = Modifier.testTag("composer.reasoning.$effort")) {
                        Text((if (value == effort) "✓ " else "") + effort.replaceFirstChar(Char::titlecase))
                    }
                }
            }
            Text(when (model?.canDisableReasoning) {
                false -> "Reasoning is required by this model. You can still change its effort."
                true -> "Applies to this conversation. Changes are shown after Hermes confirms them."
                null -> "These are Hermes session settings. This provider has not reported its reasoning limits; Hermes may map the effort to a supported level."
            }, style = MaterialTheme.typography.bodySmall, color = CompanionColor.TextDim)
        }
    }
}
