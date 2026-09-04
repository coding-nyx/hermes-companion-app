package app.hermes.companion.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.PairingPhase

@Composable
fun DeviceScreen(
    phase: PairingPhase,
    code: String,
    deviceId: String?,
    deviceProfileId: String?,
    error: String?,
    laneOpen: Boolean = false,
    a11yBound: Boolean = false,
    overlayGranted: Boolean = false,
    notifyGranted: Boolean = false,
    foregroundApp: String = "",
    lastAudit: String = "",
    arm: DeviceArm = DeviceArm.DISARMED,
    onPair: () -> Unit,
    onCancel: () -> Unit,
    onRevoke: () -> Unit,
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onEnableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("device.surface"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(CompanionSpace.Xl),
            verticalArrangement = if (phase == PairingPhase.PAIRED) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text(text = "device", style = CompanionType.Body)
        Spacer(Modifier.height(CompanionSpace.Xl))
        when (phase) {
            PairingPhase.IDLE -> {
                Text(text = "UNPAIRED", style = CompanionType.Mono, modifier = Modifier.testTag("device.status"))
                Spacer(Modifier.height(CompanionSpace.Md))
                Text(
                    text = "install hermes-companion plugin, then PAIR",
                    style = CompanionType.MonoSmall,
                    modifier = Modifier.testTag("device.plugin.hint"),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = "hermes companion approve CODE",
                    style = CompanionType.MonoSmall,
                )
                Spacer(Modifier.height(CompanionSpace.Lg))
                Action("PAIR", "device.pair", onPair)
            }
            PairingPhase.WAITING -> {
                Text(
                    text = code,
                    style = CompanionType.Display.copy(
                        color = CompanionColor.Signal,
                        fontSize = 28.sp,
                        letterSpacing = 4.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("device.code"),
                )
                Spacer(Modifier.height(CompanionSpace.Md))
                Text(
                    text = "WAITING FOR HOST",
                    style = CompanionType.Mono,
                    modifier = Modifier.testTag("device.status"),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = "hermes companion approve $code",
                    style = CompanionType.MonoSmall,
                )
                Spacer(Modifier.height(CompanionSpace.Lg))
                Action("CANCEL", "device.cancel", onCancel)
            }
            PairingPhase.PAIRED -> {
                Text(
                    text = "PAIRED",
                    style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                    modifier = Modifier.testTag("device.status"),
                )
                Spacer(Modifier.height(CompanionSpace.Md))
                if (!deviceId.isNullOrBlank()) {
                    Text(text = deviceId, style = CompanionType.Mono, modifier = Modifier.testTag("device.id"))
                }
                if (!deviceProfileId.isNullOrBlank()) {
                    Text(text = deviceProfileId, style = CompanionType.MonoSmall)
                }
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = if (laneOpen) "LANE  live" else "LANE  down",
                    style = CompanionType.Mono.copy(
                        color = if (laneOpen) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                    modifier = Modifier.testTag("device.lane"),
                )
                Text(
                    text = if (a11yBound) "A11Y     on" else "A11Y     off",
                    style = CompanionType.Mono.copy(
                        color = if (a11yBound) CompanionColor.Signal else CompanionColor.Warn,
                    ),
                    modifier = Modifier.testTag("device.a11y"),
                )
                Text(
                    text = if (overlayGranted) "OVERLAY  on" else "OVERLAY  off",
                    style = CompanionType.Mono.copy(
                        color = if (overlayGranted) CompanionColor.Signal else CompanionColor.Warn,
                    ),
                    modifier = Modifier.testTag("device.overlay"),
                )
                Text(
                    text = if (notifyGranted) "NOTIFY   on" else "NOTIFY   off",
                    style = CompanionType.Mono.copy(
                        color = if (notifyGranted) CompanionColor.Signal else CompanionColor.Warn,
                    ),
                    modifier = Modifier.testTag("device.notify"),
                )
                Text(
                    text = "ARM      ${arm.name.lowercase()}",
                    style = CompanionType.Mono.copy(
                        color = if (arm == DeviceArm.DISARMED) CompanionColor.TextMute else CompanionColor.Signal,
                    ),
                    modifier = Modifier.testTag("device.arm"),
                )
                if (foregroundApp.isNotBlank()) {
                    Text(
                        text = "FG    $foregroundApp",
                        style = CompanionType.MonoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("device.foreground"),
                    )
                }
                if (lastAudit.isNotBlank()) {
                    Text(
                        text = lastAudit,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("device.audit"),
                    )
                }
                Spacer(Modifier.height(CompanionSpace.Lg))
                if (!a11yBound) {
                    Action("ENABLE A11Y", "device.a11y.enable", onEnableA11y)
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Text(
                        text = "Installed apps → Hermes Companion",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        modifier = Modifier.testTag("device.a11y.hint"),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                } else if (arm != DeviceArm.DISARMED) {
                    Action("DISARM", "device.disarm", onDisarm)
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Text(
                        text = "double volume-down to disarm",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                } else if (!notifyGranted) {
                    Action("ENABLE NOTIFY", "device.notify.enable", onEnableNotify)
                    Spacer(Modifier.height(CompanionSpace.Sm))
                } else {
                    Action("ARM", "device.arm.go", onArm)
                    Spacer(Modifier.height(CompanionSpace.Sm))
                }
                if (!overlayGranted) {
                    Action("ENABLE OVERLAY", "device.overlay.enable", onEnableOverlay)
                    Spacer(Modifier.height(CompanionSpace.Sm))
                }
                Action("REVOKE", "device.revoke", onRevoke)
            }
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = error,
                style = CompanionType.Mono.copy(color = CompanionColor.Danger),
                modifier = Modifier.testTag("device.error"),
            )
        }
        }
    }
}

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
        modifier = Modifier
            .testTag(tag)
            .border(CompanionSpace.Hairline, CompanionColor.Signal)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
