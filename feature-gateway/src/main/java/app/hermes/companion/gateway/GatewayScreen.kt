package app.hermes.companion.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.HairlineField
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.GatewayHud
import app.hermes.companion.model.HudState

@Composable
fun GatewayScreen(
    status: DashboardStatus?,
    hud: GatewayHud,
    ntfyTopic: String = "",
    stayConnected: Boolean = false,
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .verticalScroll(rememberScrollState())
            .padding(CompanionSpace.Lg)
            .testTag("gateway.surface"),
    ) {
        Text(text = "gateway", style = CompanionType.Body)
        Spacer(Modifier.height(CompanionSpace.Lg))
        HudLine("GW", hud.gateway)
        HudLine("TG", hud.telegram)
        HudLine("DC", hud.discord)
        HudLine("API", hud.api)
        Spacer(Modifier.height(CompanionSpace.Xl))
        if (status == null) {
            Text(text = "NO STATUS", style = CompanionType.Mono)
        } else {
            MonoLine("state", status.gatewayState.ifBlank { if (status.gatewayRunning) "running" else "stopped" })
            MonoLine("version", status.version.ifBlank { "—" })
            if (status.exitReason.isNotBlank()) MonoLine("exit", status.exitReason)
            if (status.memoryPressure.isNotBlank()) MonoLine("memory", status.memoryPressure)
            if (status.diskPressure.isNotBlank()) MonoLine("disk", status.diskPressure)
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(text = "platforms", style = CompanionType.MonoSmall)
            Spacer(Modifier.height(CompanionSpace.Sm))
            if (status.platforms.isEmpty()) {
                Text(text = "none", style = CompanionType.Mono)
            } else {
                status.platforms.forEach { platform ->
                    val detail = buildString {
                        append(platform.state.ifBlank { "off" })
                        if (platform.error.isNotBlank()) append(" · ${platform.error}")
                    }
                    MonoLine(platform.name, detail)
                }
            }
        }
        Spacer(Modifier.height(CompanionSpace.Xl))
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
