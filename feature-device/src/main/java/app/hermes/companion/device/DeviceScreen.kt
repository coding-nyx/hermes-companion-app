package app.hermes.companion.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.HairlineField
import app.hermes.companion.design.rememberDismissKeyboard
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
    awakeOnVoice: Boolean = false,
    lockedAccess: Boolean = false,
    biometricLock: Boolean = false,
    protectedCustom: List<String> = emptyList(),
    protectedDefaults: Int = 0,
    protectedError: String? = null,
    onPair: () -> Unit,
    onRepair: () -> Unit = onPair,
    onApprove: () -> Unit = {},
    onCancel: () -> Unit,
    onRevoke: () -> Unit,
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onEnableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
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
                val clipboardManager = LocalClipboardManager.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
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
                    Spacer(Modifier.width(CompanionSpace.Md))
                    Action("COPY", "device.copy") {
                        clipboardManager.setText(AnnotatedString(code))
                    }
                }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Action("APPROVE", "device.approve", onApprove)
                    Action("CANCEL", "device.cancel", onCancel)
                }
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
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = if (awakeOnVoice) "AWAKE ON VOICE  on" else "AWAKE ON VOICE  off",
                    style = CompanionType.Mono.copy(
                        color = if (awakeOnVoice) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                    modifier = Modifier
                        .testTag("device.awake.voice")
                        .clickable(onClick = onToggleAwakeOnVoice)
                        .padding(vertical = CompanionSpace.Xs),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = if (lockedAccess) "LOCKED ACCESS  on" else "LOCKED ACCESS  off",
                    style = CompanionType.Mono.copy(
                        color = if (lockedAccess) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                    modifier = Modifier
                        .testTag("device.locked.access")
                        .clickable(onClick = onToggleLockedAccess)
                        .padding(vertical = CompanionSpace.Xs),
                )
                Spacer(Modifier.height(CompanionSpace.Sm))
                Text(
                    text = if (biometricLock) "BIOMETRIC LOCK  on" else "BIOMETRIC LOCK  off",
                    style = CompanionType.Mono.copy(
                        color = if (biometricLock) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                    modifier = Modifier
                        .testTag("device.biometric.lock")
                        .clickable(onClick = onToggleBiometricLock)
                        .padding(vertical = CompanionSpace.Xs),
                )
                Spacer(Modifier.height(CompanionSpace.Lg))
                ProtectedPackages(
                    custom = protectedCustom,
                    defaults = protectedDefaults,
                    error = protectedError,
                    onAdd = onAddProtected,
                    onRemove = onRemoveProtected,
                )
                Spacer(Modifier.height(CompanionSpace.Lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md, Alignment.CenterHorizontally),
                ) {
                    if (!laneOpen) {
                        Action("RE-PAIR", "device.repair", onRepair)
                    }
                    Action("REVOKE", "device.revoke", onRevoke)
                }
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

/** Denylist editor. Built-ins are fixed; user rows are exact ids or `prefix.*`. */
@Composable
private fun ProtectedPackages(
    custom: List<String>,
    defaults: Int,
    error: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val dismissKeyboard = rememberDismissKeyboard()
    Column(modifier = Modifier.fillMaxWidth().testTag("device.protected")) {
        Text(
            text = if (defaults > 0) {
                "BLOCKLIST  $defaults built-in · ${custom.size} custom"
            } else {
                "BLOCKLIST  ${custom.size} blocked"
            },
            style = CompanionType.Mono,
            modifier = Modifier.testTag("device.protected.count"),
        )
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(
            text = if (custom.isEmpty()) {
                "blocklist empty · companion controls all apps"
            } else {
                "hands will not touch these blocked apps"
            },
            style = CompanionType.MonoSmall.copy(
                color = if (custom.isEmpty()) CompanionColor.Signal else CompanionColor.TextMute,
            ),
        )
        Spacer(Modifier.height(CompanionSpace.Sm))
        custom.forEach { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = CompanionSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pkg,
                    style = CompanionType.MonoSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("device.protected.remove")
                        .clickable { onRemove(pkg) }
                        .padding(horizontal = CompanionSpace.Sm),
                )
            }
        }
        if (custom.isNotEmpty()) {
            Spacer(Modifier.height(CompanionSpace.Sm))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HairlineField(
                value = draft,
                onValueChange = { draft = it },
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                placeholder = "com.bank.app or prefix.*",
                onDone = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
                modifier = Modifier.weight(1f).testTag("device.protected.input"),
            )
            Spacer(Modifier.width(CompanionSpace.Sm))
            Action("ADD", "device.protected.add") {
                if (draft.isNotBlank()) {
                    onAdd(draft)
                    draft = ""
                }
                dismissKeyboard()
            }
        }
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(
            text = "enter package to block (e.g. com.bank.app or com.corp.*)",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Xs))
            Text(
                text = error,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                modifier = Modifier.testTag("device.protected.error"),
            )
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
