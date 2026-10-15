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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBottomSheet(
    modelCatalog: ModelCatalog?,
    currentModel: String,
    profiles: List<ProfileRef> = emptyList(),
    onSelectModel: (model: String, provider: String) -> Unit,
    onDismiss: () -> Unit,
    /** Catalog fetch in flight: an empty list says LOADING, not "no models". */
    loading: Boolean = false,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    val current = currentModel.ifBlank { modelCatalog?.currentModel.orEmpty() }
    val catalog = modelCatalog?.models.orEmpty()
    val extra = profiles.mapNotNull { profile -> profile.model.trim().takeIf { it.isNotBlank() && catalog.none { row -> row.id == it } }
        ?.let { ModelOption(it, profile.id, "${profile.displayName} · $it") } }
    val models = (catalog + extra).distinctBy { it.provider to it.id }.filter {
        query.isBlank() || listOf(it.id, it.name, it.provider).any { value -> value.contains(query.trim(), true) }
    }.groupBy { it.provider }.toSortedMap()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text, modifier = Modifier.testTag("model.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeading("Choose model", onDismiss, closeModifier = Modifier.testTag("model.sheet.close"))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Current model", style = CompanionType.Caption)
                Text(current.ifBlank { "Host default" }, style = CompanionType.Body)
            }
            HairlineField(query, { query = it }, placeholder = "Search models or providers", modifier = Modifier.testTag("model.sheet.search"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                if (models.isEmpty()) item {
                    if (loading) FetchRow("Loading models…", padded = false, modifier = Modifier.testTag("model.sheet.loading"))
                    else Text("No matching models", style = CompanionType.Caption, modifier = Modifier.padding(vertical = 16.dp))
                }
                models.forEach { (group, entries) ->
                    item { Text(group.ifBlank { "Provider not reported" }, style = CompanionType.Caption, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(entries, key = { "${it.provider}/${it.id}" }) { model ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onSelectModel(model.id, model.provider) }
                            .padding(vertical = 8.dp).testTag("model.sheet.item.${model.id}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(model.id, style = CompanionType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (model.id == current && (modelCatalog?.currentProvider.isNullOrBlank() || model.provider == modelCatalog?.currentProvider)) Text("✓", color = CompanionColor.Signal)
                        }
                    }
                }
            }
            ActionButton(if (custom) "Cancel custom model" else "Enter a custom model", { custom = !custom }, modifier = Modifier.testTag("model.sheet.custom.toggle"))
            if (custom) {
                HairlineField(modelId, { modelId = it }, placeholder = "Model ID", modifier = Modifier.testTag("model.sheet.custom.id"))
                HairlineField(provider, { provider = it }, placeholder = "Provider (optional)", modifier = Modifier.testTag("model.sheet.custom.provider"))
                ActionButton("Apply model", { onSelectModel(modelId.trim(), provider.trim().ifBlank { "custom" }) }, enabled = modelId.isNotBlank(),
                    kind = ActionKind.PRIMARY, modifier = Modifier.testTag("model.sheet.custom.apply"))
            }
        }
    }
}
