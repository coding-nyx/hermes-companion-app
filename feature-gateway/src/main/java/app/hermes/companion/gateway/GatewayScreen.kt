package app.hermes.companion.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.design.rememberDismissKeyboard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.GatewayHud
import app.hermes.companion.model.HermesUpdateStatus
import app.hermes.companion.model.HostMetrics
import app.hermes.companion.model.HudState
import app.hermes.companion.model.ModelCatalog
import app.hermes.companion.model.SavedGateway

@Composable
fun GatewayScreen(
    status: DashboardStatus?,
    hud: GatewayHud,
    hostMetrics: HostMetrics? = null,
    modelCatalog: ModelCatalog? = null,
    savedGateways: List<SavedGateway> = emptyList(),
    updateStatus: HermesUpdateStatus? = null,
    ntfyTopic: String = "",
    stayConnected: Boolean = false,
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onSelectGateway: (SavedGateway) -> Unit = {},
    onAddGateway: (String, String) -> Unit = { _, _ -> },
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var newGatewayName by remember { mutableStateOf("") }
    var newGatewayOrigin by remember { mutableStateOf("") }
    var showAddGateway by remember { mutableStateOf(false) }
    val originFocus = remember { FocusRequester() }
    val dismissKeyboard = rememberDismissKeyboard()
    fun saveGateway() {
        if (newGatewayOrigin.isBlank()) return
        onAddGateway(newGatewayName.ifBlank { newGatewayOrigin }, newGatewayOrigin)
        newGatewayName = ""
        newGatewayOrigin = ""
        showAddGateway = false
        dismissKeyboard()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .verticalScroll(rememberScrollState())
            .padding(CompanionSpace.Lg)
            .testTag("gateway.surface"),
    ) {
        Text(text = "gateway & telemetry", style = CompanionType.Body)
        Spacer(Modifier.height(CompanionSpace.Lg))

        // HUD Dots
        HudLine("GW", hud.gateway)
        HudLine("TG", hud.telegram)
        HudLine("DC", hud.discord)
        HudLine("API", hud.api)

        Spacer(Modifier.height(CompanionSpace.Lg))
        Hairline()
        Spacer(Modifier.height(CompanionSpace.Lg))

        // Host Telemetry HUD
        Text(text = "host telemetry", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
        Spacer(Modifier.height(CompanionSpace.Sm))
        if (hostMetrics != null) {
            val cpuP = hostMetrics.cpu.percent
            val memP = hostMetrics.memory.percent
            val diskP = hostMetrics.disk.percent
            MonoLine("cpu", "${String.format("%.1f", cpuP)}% (${hostMetrics.cpu.cores} cores)")
            if (hostMetrics.cpu.loadAvg.isNotEmpty()) {
                MonoLine("load", hostMetrics.cpu.loadAvg.joinToString(", ") { String.format("%.2f", it) })
            }
            val memUsedGb = hostMetrics.memory.usedBytes.toDouble() / (1024 * 1024 * 1024)
            val memTotalGb = hostMetrics.memory.totalBytes.toDouble() / (1024 * 1024 * 1024)
            MonoLine("memory", "${String.format("%.1f", memUsedGb)}G / ${String.format("%.1f", memTotalGb)}G · ${String.format("%.1f", memP)}%")
            val diskUsedGb = hostMetrics.disk.usedBytes.toDouble() / (1024 * 1024 * 1024)
            val diskTotalGb = hostMetrics.disk.totalBytes.toDouble() / (1024 * 1024 * 1024)
            MonoLine("disk", "${String.format("%.1f", diskUsedGb)}G / ${String.format("%.1f", diskTotalGb)}G · ${String.format("%.1f", diskP)}%")
            if (hostMetrics.system.platform.isNotBlank()) {
                MonoLine("platform", hostMetrics.system.platform)
            }
            if (hostMetrics.system.uptimeSeconds > 0) {
                val hours = hostMetrics.system.uptimeSeconds / 3600
                val mins = (hostMetrics.system.uptimeSeconds % 3600) / 60
                MonoLine("uptime", "${hours}h ${mins}m")
            }
            if (hostMetrics.hermes.pid > 0) {
                MonoLine("hermes pid", "${hostMetrics.hermes.pid} (${hostMetrics.hermes.status})")
            }
        } else if (status != null) {
            MonoLine("state", status.gatewayState.ifBlank { if (status.gatewayRunning) "running" else "stopped" })
            MonoLine("version", status.version.ifBlank { "—" })
            if (status.exitReason.isNotBlank()) MonoLine("exit", status.exitReason)
            if (status.memoryPressure.isNotBlank()) MonoLine("memory", status.memoryPressure)
            if (status.diskPressure.isNotBlank()) MonoLine("disk", status.diskPressure)
        } else {
            Text(text = "NO TELEMETRY", style = CompanionType.Mono)
        }

        Spacer(Modifier.height(CompanionSpace.Lg))
        Hairline()
        Spacer(Modifier.height(CompanionSpace.Lg))

        // Model & Provider Switching
        Text(text = "model selection", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
        Spacer(Modifier.height(CompanionSpace.Sm))
        if (modelCatalog != null && modelCatalog.models.isNotEmpty()) {
            MonoLine("current model", "${modelCatalog.currentProvider} / ${modelCatalog.currentModel}")
            Spacer(Modifier.height(CompanionSpace.Sm))
            Text(text = "available models (tap to switch):", style = CompanionType.MonoSmall)
            Spacer(Modifier.height(CompanionSpace.Xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                for (opt in modelCatalog.models.take(15)) {
                    val isSelected = opt.id == modelCatalog.currentModel
                    Box(
                        modifier = Modifier
                            .background(if (isSelected) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                            .border(1.dp, if (isSelected) CompanionColor.Signal else CompanionColor.Line)
                            .clickable { onSwitchModel(opt.id, opt.provider) }
                            .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs),
                    ) {
                        Text(
                            text = opt.id,
                            style = CompanionType.MonoSmall.copy(
                                color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                            ),
                        )
                    }
                }
            }
        } else {
            MonoLine("model", "gpt-5.6-terra")
        }

        Spacer(Modifier.height(CompanionSpace.Lg))
        Hairline()
        Spacer(Modifier.height(CompanionSpace.Lg))

        // Multi-Gateway / Installation Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "fleet / installations", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            Text(
                text = if (showAddGateway) "[CANCEL]" else "[+ ADD GATEWAY]",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .clickable {
                        showAddGateway = !showAddGateway
                        if (!showAddGateway) dismissKeyboard()
                    }
                    .padding(CompanionSpace.Xs),
            )
        }
        Spacer(Modifier.height(CompanionSpace.Sm))

        if (showAddGateway) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CompanionColor.VoidElevated)
                    .border(1.dp, CompanionColor.Line)
                    .padding(CompanionSpace.Md),
            ) {
                Text(text = "NAME / LABEL", style = CompanionType.MonoSmall)
                Spacer(Modifier.height(CompanionSpace.Xs))
                HairlineField(
                    value = newGatewayName,
                    onValueChange = { newGatewayName = it },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    placeholder = "hub-11",
                    onNext = { originFocus.requestFocus() },
                    modifier = Modifier.testTag("gateway.add.name"),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(text = "ORIGIN URL (e.g. http://100.85.151.99:9120)", style = CompanionType.MonoSmall)
                Spacer(Modifier.height(CompanionSpace.Xs))
                HairlineField(
                    value = newGatewayOrigin,
                    onValueChange = { newGatewayOrigin = it },
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    placeholder = "http://100.x.y.z:9120",
                    focusRequester = originFocus,
                    onDone = { saveGateway() },
                    modifier = Modifier.testTag("gateway.add.origin"),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Box(
                    modifier = Modifier
                        .background(CompanionColor.SignalDim)
                        .border(1.dp, CompanionColor.Signal)
                        .clickable { saveGateway() }
                        .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm),
                ) {
                    Text(text = "SAVE & SWITCH", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
                }
            }
            Spacer(Modifier.height(CompanionSpace.Sm))
        }

        if (savedGateways.isNotEmpty()) {
            for (gw in savedGateways) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (gw.isActive) CompanionColor.VoidElevated else CompanionColor.Void)
                        .border(1.dp, if (gw.isActive) CompanionColor.SignalDim else CompanionColor.Line)
                        .clickable { onSelectGateway(gw) }
                        .padding(CompanionSpace.Sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = gw.name,
                            style = CompanionType.Mono.copy(
                                color = if (gw.isActive) CompanionColor.Signal else CompanionColor.Text,
                            ),
                        )
                        Text(
                            text = gw.origin,
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        )
                    }
                    if (gw.isActive) {
                        Text(
                            text = "ACTIVE",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        )
                    } else {
                        Text(
                            text = "[SWITCH]",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                        )
                    }
                }
                Spacer(Modifier.height(CompanionSpace.Xs))
            }
        }

        Spacer(Modifier.height(CompanionSpace.Lg))
        Hairline()
        Spacer(Modifier.height(CompanionSpace.Lg))

        // Hermes Updates
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "updates", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            Text(
                text = "[CHECK NOW]",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .clickable(onClick = onCheckUpdate)
                    .padding(CompanionSpace.Xs),
            )
        }
        Spacer(Modifier.height(CompanionSpace.Sm))
        if (updateStatus != null) {
            MonoLine("current version", updateStatus.currentVersion)
            if (updateStatus.updateAvailable) {
                MonoLine("behind", "${updateStatus.behind} commits")
                if (updateStatus.summary.isNotBlank()) {
                    MonoLine("latest commit", updateStatus.summary)
                }
                Spacer(Modifier.height(CompanionSpace.Sm))
                Box(
                    modifier = Modifier
                        .background(CompanionColor.SignalDim)
                        .border(1.dp, CompanionColor.Signal)
                        .clickable(onClick = onApplyUpdate)
                        .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm),
                ) {
                    Text(text = "UPDATE HERMES NOW", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
                }
            } else {
                Text(text = "HERMES IS UP TO DATE", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            }
        } else {
            Text(text = "TAP CHECK NOW TO QUERY HOST", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
        }

        Spacer(Modifier.height(CompanionSpace.Lg))
        Hairline()
        Spacer(Modifier.height(CompanionSpace.Lg))

        // Wake / Ntfy topic
        Text(text = "wake", style = CompanionType.MonoSmall)
        Spacer(Modifier.height(CompanionSpace.Sm))
        HairlineField(
            value = ntfyTopic,
            onValueChange = onNtfyTopicChange,
            modifier = Modifier.testTag("gateway.ntfy"),
            onDone = onSaveNtfy,
        )
        Spacer(Modifier.height(CompanionSpace.Sm))
        Text(
            text = "SAVE TOPIC",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("gateway.ntfy.save")
                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                .clickable(onClick = onSaveNtfy)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(CompanionSpace.Lg))
        Text(
            text = if (stayConnected) "STAY CONNECTED  on" else "STAY CONNECTED  off",
            style = CompanionType.Mono.copy(
                color = if (stayConnected) CompanionColor.Signal else CompanionColor.TextMute,
            ),
            modifier = Modifier
                .testTag("gateway.stay")
                .clickable(onClick = onToggleStay)
                .padding(vertical = CompanionSpace.Xs),
        )
    }
}

@Composable
private fun HudLine(label: String, state: HudState) {
    val color = when (state) {
        HudState.ON -> CompanionColor.Signal
        HudState.DEGRADED -> CompanionColor.Warn
        HudState.OFF -> CompanionColor.TextMute
    }
    Text(
        text = "$label  ${state.name.lowercase()}",
        style = CompanionType.Mono.copy(color = color),
        modifier = Modifier
            .padding(vertical = CompanionSpace.Xs)
            .testTag("gateway.${label.lowercase()}"),
    )
}

@Composable
private fun MonoLine(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = CompanionType.Mono,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = CompanionSpace.Xs),
    )
}
