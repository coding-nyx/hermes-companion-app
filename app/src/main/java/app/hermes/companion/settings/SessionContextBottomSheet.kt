package app.hermes.companion.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.SessionContextPanel
import app.hermes.companion.design.*
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionContextBottomSheet(panel: SessionContextPanel, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CompanionColor.VoidElevated) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("session.context"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeading("Context usage", onDismiss)
            Text(panel.session.title, style = MaterialTheme.typography.bodyMedium)
            if (panel.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (panel.error != null) Text(panel.error, color = MaterialTheme.colorScheme.error)
            when {
                panel.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading from Hermes…") }
                else -> panel.value?.let { context ->
                    val maximum = context.maximum?.takeIf { it > 0 }
                    val used = context.used
                    Text(when {
                        used != null && maximum != null -> "${tokens(used)} / ${tokens(maximum)} tokens"
                        used != null -> "${tokens(used)} tokens · limit not reported"
                        else -> "Usage not reported by this host"
                    }, style = MaterialTheme.typography.titleMedium)
                    context.percent?.let { percent ->
                        LinearProgressIndicator(progress = { (percent / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(String.format(Locale.getDefault(), "%.1f%% of context window", percent))
                    }
                    Text(when (context.estimated) {
                        true -> "Estimated usage"
                        false -> "Provider-reported usage"
                        null -> "Measurement source not reported"
                    })
                    if (context.model.isNotBlank()) Text("Model: ${context.model}")
                    GroupedSection("Breakdown", padded = true) {
                    if (context.categories.isEmpty()) Text("Category breakdown is not available yet.")
                    context.categories.forEach { category ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(category.label, Modifier.weight(1f))
                            Text("${tokens(category.tokens)} tokens")
                        }
                    }
                    }
                    context.estimatedTotal?.let { Text("Estimated total: ${tokens(it)} tokens") }
                    Text("Category estimates can differ from provider usage.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(enabled = !panel.loading && !panel.refreshing, onClick = onRefresh) { Text("Refresh context") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun tokens(value: Long) = NumberFormat.getIntegerInstance().format(value)
