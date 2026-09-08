package app.hermes.companion.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.FetchSkeleton
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.model.ModelCatalog
import app.hermes.companion.model.ModelOption
import app.hermes.companion.model.ProfileRef

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
    var searchQuery by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }
    var customProvider by remember { mutableStateOf("") }

    val activeModelId = currentModel.ifBlank { modelCatalog?.currentModel.orEmpty() }

    // Aggregate catalog models + profile models
    val catalogModels = modelCatalog?.models.orEmpty()
    val profileModels = profiles.mapNotNull { prof ->
        val m = prof.model.trim()
        if (m.isNotBlank() && catalogModels.none { it.id == m }) {
            ModelOption(id = m, provider = prof.id, name = "${prof.displayName} · $m")
        } else null
    }.distinctBy { it.id }

    val allModels = (catalogModels + profileModels)
    val filteredModels = if (searchQuery.isBlank()) {
        allModels
    } else {
        val q = searchQuery.trim().lowercase()
        allModels.filter { it.id.lowercase().contains(q) || it.provider.lowercase().contains(q) || it.name.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = CompanionColor.VoidElevated,
        contentColor = CompanionColor.Text,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = CompanionSpace.Sm)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .fillMaxWidth(0.12f)
                        .background(CompanionColor.LineStrong),
                )
            }
        },
        modifier = Modifier.testTag("model.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = CompanionSpace.Lg),
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "SELECT MODEL // LLM ENGINE",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                Text(
                    text = "[✕ CLOSE]",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                    modifier = Modifier
                        .testTag("model.sheet.close")
                        .clickable { onDismiss() }
                        .padding(CompanionSpace.Xs),
                )
            }
            Hairline()

            // Active Model Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CompanionColor.Void)
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE MODEL",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute, fontSize = 10.sp),
                    )
                    Text(
                        text = activeModelId.ifBlank { "default" },
                        style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .border(CompanionSpace.Hairline, CompanionColor.Signal)
                        .background(CompanionColor.SignalDim)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "CURRENT",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                    )
                }
            }
            Hairline()

            // Search input field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            ) {
                HairlineField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "filter models (e.g. gpt, sonnet, minimax)...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model.sheet.search"),
                )
            }

            // Model List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = CompanionSpace.Xs),
            ) {
                if (filteredModels.isEmpty() && loading && searchQuery.isBlank()) {
                    item {
                        FetchRow(label = "LOADING MODELS", modifier = Modifier.testTag("model.sheet.loading"))
                        FetchSkeleton(lines = 3)
                    }
                } else if (filteredModels.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(CompanionSpace.Lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) "NO MODELS MATCHING \"$searchQuery\"" else "NO MODELS LOADED",
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                            )
                        }
                    }
                } else {
                    items(filteredModels, key = { "${it.provider}/${it.id}" }) { model ->
                        val isSelected = model.id == activeModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("model.sheet.item.${model.id}")
                                .background(if (isSelected) CompanionColor.SignalDim.copy(alpha = 0.25f) else CompanionColor.VoidElevated)
                                .clickable {
                                    onSelectModel(model.id, model.provider)
                                    onDismiss()
                                }
                                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                                ) {
                                    Text(
                                        text = model.id,
                                        style = CompanionType.Mono.copy(
                                            color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.fast) {
                                        Box(
                                            modifier = Modifier
                                                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = "FAST",
                                                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 9.sp),
                                            )
                                        }
                                    }
                                    if (model.reasoning) {
                                        Box(
                                            modifier = Modifier
                                                .border(CompanionSpace.Hairline, CompanionColor.Warn)
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = "REASONING",
                                                style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn, fontSize = 9.sp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "provider: ${model.provider.ifBlank { "custom" }}",
                                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontSize = 10.sp),
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .border(CompanionSpace.Hairline, CompanionColor.Signal)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                                    )
                                }
                            }
                        }
                        Hairline()
                    }
                }
            }

            // Custom Model Entry toggle
            Spacer(Modifier.height(CompanionSpace.Sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showCustomInput) "[-] HIDE CUSTOM MODEL" else "[+] ENTER CUSTOM MODEL",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    modifier = Modifier
                        .testTag("model.sheet.custom.toggle")
                        .clickable { showCustomInput = !showCustomInput }
                        .padding(vertical = CompanionSpace.Xs),
                )
            }

            if (showCustomInput) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                    verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                ) {
                    HairlineField(
                        value = customModelName,
                        onValueChange = { customModelName = it },
                        placeholder = "model id (e.g. o3-mini, claude-3-7-sonnet)...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model.sheet.custom.id"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HairlineField(
                            value = customProvider,
                            onValueChange = { customProvider = it },
                            placeholder = "provider (optional)...",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("model.sheet.custom.provider"),
                        )
                        Box(
                            modifier = Modifier
                                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                                .background(CompanionColor.SignalDim)
                                .clickable {
                                    val trimmed = customModelName.trim()
                                    if (trimmed.isNotBlank()) {
                                        onSelectModel(trimmed, customProvider.trim().ifBlank { "custom" })
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                                .testTag("model.sheet.custom.apply"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "APPLY",
                                style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                            )
                        }
                    }
                }
            }
        }
    }
}
