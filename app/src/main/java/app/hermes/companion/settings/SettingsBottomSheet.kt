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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.hermes.companion.model.ModelOption
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.CompanionState
import app.hermes.companion.MainTab
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    state: CompanionState,
    onSelectProfile: (String) -> Unit,
    onSwitchModel: (model: String, provider: String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onTab: (MainTab) -> Unit,
    onToggleStay: () -> Unit,
    onNtfyTopicChange: (String) -> Unit,
    onSaveNtfy: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var expandModelList by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }
    var customProvider by remember { mutableStateOf("") }

    val activeModel = state.modelOverride.ifBlank { state.modelCatalog?.currentModel.orEmpty() }.ifBlank { "default" }
    val activeProfile = state.activeProfile

    val catalogModels = state.modelCatalog?.models.orEmpty()
    val profileModels = state.profiles.mapNotNull { prof ->
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
        modifier = Modifier.testTag("settings.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = CompanionSpace.Xl),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "SETTINGS // CONFIGURATION",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                Text(
                    text = "[✕ CLOSE]",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                    modifier = Modifier
                        .testTag("settings.sheet.close")
                        .clickable { onDismiss() }
                        .padding(CompanionSpace.Xs),
                )
            }
            Hairline()

            // 1. Model Switching Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "LLM MODEL CONFIGURATION",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    )
                    Text(
                        text = if (expandModelList) "[COLLAPSE ▴]" else "[CHANGE MODEL ▾]",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("settings.model.change")
                            .clickable { expandModelList = !expandModelList }
                            .padding(CompanionSpace.Xs),
                    )
                }
                Spacer(Modifier.height(CompanionSpace.Sm))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CompanionColor.Void)
                        .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                        .clickable { expandModelList = !expandModelList }
                        .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE MODEL",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute, fontSize = 10.sp),
                        )
                        Text(
                            text = activeModel,
                            style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (expandModelList) "▴" else "▾",
                        style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                    )
                }

                if (allModels.isEmpty() && state.modelLoading) {
                    FetchRow(label = "LOADING MODELS", padded = false, modifier = Modifier.testTag("settings.model.loading"))
                }
                // Quick model switch chips
                if (allModels.isNotEmpty()) {
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Text(
                        text = "quick switch:",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontSize = 10.sp),
                    )
                    Spacer(Modifier.height(CompanionSpace.Xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
                    ) {
                        for (opt in allModels.take(8)) {
                            val isSelected = opt.id == activeModel
                            Box(
                                modifier = Modifier
                                    .background(if (isSelected) CompanionColor.SignalDim else CompanionColor.Void)
                                    .border(1.dp, if (isSelected) CompanionColor.Signal else CompanionColor.Line)
                                    .clickable { onSwitchModel(opt.id, opt.provider) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = opt.id.substringAfterLast("/").take(18),
                                    style = CompanionType.MonoSmall.copy(
                                        color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                                        fontSize = 11.sp,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Inline Expandable Model Picker
                if (expandModelList) {
                    Spacer(Modifier.height(CompanionSpace.Md))
                    HairlineField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "filter models by name / provider...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings.model.search"),
                    )

                    Spacer(Modifier.height(CompanionSpace.Sm))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(CompanionSpace.Hairline, CompanionColor.Line)
                            .background(CompanionColor.Void),
                    ) {
                        if (filteredModels.isEmpty()) {
                            Text(
                                text = "no models found",
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                                modifier = Modifier.padding(CompanionSpace.Md),
                            )
                        } else {
                            filteredModels.forEachIndexed { index, model ->
                                val isSelected = model.id == activeModel
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) CompanionColor.SignalDim.copy(alpha = 0.15f) else CompanionColor.Void)
                                        .clickable {
                                            onSwitchModel(model.id, model.provider)
                                            expandModelList = false
                                        }
                                        .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
                                        ) {
                                            Text(
                                                text = model.id,
                                                style = CompanionType.Mono.copy(
                                                    color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                                                    fontSize = 13.sp,
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
                                if (index < filteredModels.lastIndex) {
                                    Hairline()
                                }
                            }
                        }
                    }

                    // Custom model accordion inside settings
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Text(
                        text = if (showCustomInput) "[-] HIDE CUSTOM MODEL" else "[+] ENTER CUSTOM MODEL",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("settings.model.custom.toggle")
                            .clickable { showCustomInput = !showCustomInput }
                            .padding(vertical = CompanionSpace.Xs),
                    )

                    if (showCustomInput) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = CompanionSpace.Xs),
                            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                        ) {
                            HairlineField(
                                value = customModelName,
                                onValueChange = { customModelName = it },
                                placeholder = "model id (e.g. o3-mini, claude-3-7-sonnet)...",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings.model.custom.id"),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HairlineField(
                                    value = customProvider,
                                    onValueChange = { customProvider = it },
                                    placeholder = "provider (e.g. openrouter, custom)...",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings.model.custom.provider"),
                                )
                                Box(
                                    modifier = Modifier
                                        .background(if (customModelName.isNotBlank()) CompanionColor.SignalDim else CompanionColor.Void)
                                        .border(
                                            1.dp,
                                            if (customModelName.isNotBlank()) CompanionColor.Signal else CompanionColor.Line,
                                        )
                                        .clickable(enabled = customModelName.isNotBlank()) {
                                            onSwitchModel(
                                                customModelName.trim(),
                                                customProvider.trim().ifBlank { "custom" },
                                            )
                                            customModelName = ""
                                            customProvider = ""
                                            showCustomInput = false
                                            expandModelList = false
                                        }
                                        .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                                        .testTag("settings.model.custom.apply"),
                                ) {
                                    Text(
                                        text = "APPLY",
                                        style = CompanionType.MonoSmall.copy(
                                            color = if (customModelName.isNotBlank()) CompanionColor.Signal else CompanionColor.TextDim,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Hairline()

            // 2. Active Profile Switcher Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            ) {
                Text(
                    text = "ACTIVE PROFILE",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                ) {
                    for (prof in state.profiles) {
                        val isSelected = prof.id == state.activeProfileId
                        Row(
                            modifier = Modifier
                                .background(if (isSelected) CompanionColor.SignalDim else CompanionColor.Void)
                                .border(1.dp, if (isSelected) CompanionColor.Signal else CompanionColor.Line)
                                .clickable { onSelectProfile(prof.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
                        ) {
                            Text(
                                text = prof.glyph,
                                style = CompanionType.MonoSmall.copy(
                                    color = if (isSelected) CompanionColor.Signal else CompanionColor.TextDim,
                                ),
                            )
                            Text(
                                text = prof.displayName,
                                style = CompanionType.MonoSmall.copy(
                                    color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                                ),
                            )
                        }
                    }
                }
            }
            Hairline()

            // 3. Host / Gateway Information Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "HOST // GATEWAY",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    )
                    Text(
                        text = "[HOST DASHBOARD ▸]",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("settings.host.dashboard")
                            .clickable {
                                onDismiss()
                                onTab(MainTab.GATEWAY)
                            }
                            .padding(CompanionSpace.Xs),
                    )
                }
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = "origin: ${state.origin ?: "not connected"}",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                )
                state.status?.let { st ->
                    Spacer(Modifier.height(CompanionSpace.Xs))
                    Text(
                        text = "state: ${st.gatewayState} · version: ${st.version.ifBlank { "dev" }}",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                    )
                }
            }
            Hairline()

            // 4. Notifications & Background Service
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            ) {
                Text(
                    text = "PREFERENCES & SYSTEM",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))

                // Stay Connected Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleStay() }
                        .padding(vertical = CompanionSpace.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "STAY CONNECTED SERVICE",
                            style = CompanionType.MonoSmall,
                        )
                        Text(
                            text = "Keep companion background websocket active",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontSize = 10.sp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .border(CompanionSpace.Hairline, if (state.stayConnected) CompanionColor.Signal else CompanionColor.Line)
                            .background(if (state.stayConnected) CompanionColor.SignalDim else CompanionColor.Void)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (state.stayConnected) "ON" else "OFF",
                            style = CompanionType.MonoSmall.copy(
                                color = if (state.stayConnected) CompanionColor.Signal else CompanionColor.TextMute,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Sm))

                // Ntfy Topic Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                ) {
                    HairlineField(
                        value = state.ntfyTopic,
                        onValueChange = onNtfyTopicChange,
                        placeholder = "ntfy.sh notification topic...",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings.ntfy.input"),
                    )
                    Box(
                        modifier = Modifier
                            .border(CompanionSpace.Hairline, CompanionColor.Signal)
                            .background(CompanionColor.SignalDim)
                            .clickable { onSaveNtfy() }
                            .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                            .testTag("settings.ntfy.save"),
                    ) {
                        Text(
                            text = "SAVE",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        )
                    }
                }
            }
            Hairline()

            // 5. Danger Zone / Disconnect
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Warn)
                        .clickable {
                            onDismiss()
                            onDisconnect()
                        }
                        .padding(CompanionSpace.Sm)
                        .testTag("settings.disconnect"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "[DISCONNECT FROM HOST]",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                    )
                }
            }
        }
    }
}
