package app.hermes.companion.device

import app.hermes.companion.design.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontWeight
import app.hermes.companion.design.LiveDot
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.Scanline
import app.hermes.companion.design.SignalCursor
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.HairlineField
import app.hermes.companion.design.rememberDismissKeyboard
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.PairingPhase

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    phase: PairingPhase,
    code: String,
    deviceId: String?,
    deviceLabel: String = "",
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
    streamMuteDefaults: Int = 0,
    nlsBound: Boolean = false,
    notifStreamEnabled: Boolean = false,
    notifStreamOrigin: String? = null,
    notifStreamProfile: String? = null,
    pairedHosts: List<String> = emptyList(),
    profilesForStream: List<String> = emptyList(),
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
    onEnableNls: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onToggleNotifStream: () -> Unit = {},
    onPickStreamOrigin: (String) -> Unit = {},
    onPickStreamProfile: (String) -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
    onRenameDevice: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var detail by rememberSaveable(deviceId, deviceProfileId) { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var labelDraft by rememberSaveable(deviceId, deviceLabel) { mutableStateOf(deviceLabel) }
    val dismissKeyboard = rememberDismissKeyboard()
    val armed = arm != DeviceArm.DISARMED
    Column(modifier.fillMaxSize().background(CompanionColor.Void).testTag("device.surface")
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        when (phase) {
            PairingPhase.IDLE -> {
                Text("Connect your phone", style = CompanionType.Display)
                GroupedSection("Pairing", padded = true) {
                    Text("Not paired", style = CompanionType.RowTitle, modifier = Modifier.testTag("device.status"))
                    Text("Enable the Companion extension on your host, then approve this phone.", style = CompanionType.Body,
                        modifier = Modifier.testTag("device.plugin.hint"))
                    Action("PAIR", "device.pair", onPair)
                }
            }
            PairingPhase.WAITING -> {
                val clipboard = LocalClipboardManager.current
                Text("Pair your phone", style = CompanionType.Display)
                GroupedSection("Pairing code", padded = true) {
                    Text(code, style = CompanionType.Display.copy(color = CompanionColor.Signal, fontSize = 28.sp, letterSpacing = 4.sp),
                        maxLines = 1, softWrap = false, modifier = Modifier.testTag("device.code"))
                    Text("Waiting for host approval", style = CompanionType.Caption, modifier = Modifier.testTag("device.status"))
                    Text("hermes companion approve $code", style = CompanionType.Mono)
                    Action("COPY", "device.copy") { clipboard.setText(AnnotatedString(code)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("APPROVE", "device.approve", onApprove)
                    Action("CANCEL", "device.cancel", onCancel)
                }
            }
            PairingPhase.PAIRED -> {
                GroupedSection("Your phone") {
                    PreferenceRow(deviceLabel.ifBlank { "This Android phone" }, "Paired · ${deviceProfileId ?: "Current profile"}",
                        value = if (laneOpen) "Connected" else "Connecting", selected = laneOpen,
                        onClick = { detail = "This phone" }, modifier = Modifier.testTag("device.status"))
                }
                GroupedSection("Hands", padded = true) {
                    Text(if (armed) "Screen control is armed" else "Screen control is off", style = CompanionType.RowTitle,
                        color = if (armed) CompanionColor.Warn else CompanionColor.Text, modifier = Modifier.testTag("device.arm"))
                    Text(if (armed) "Hermes can view and control this phone. Press volume down twice to stop."
                        else "Allow Hermes to tap, scroll and type when you choose.", style = CompanionType.Body, color = CompanionColor.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            armed -> Action("DISARM", "device.disarm", onDisarm)
                            !a11yBound -> Action("ENABLE A11Y", "device.a11y.enable", onEnableA11y)
                            !notifyGranted -> Action("ENABLE NOTIFY", "device.notify.enable", onEnableNotify)
                            else -> Action("ARM", "device.arm.go", onArm)
                        }
                        ActionButton("Details", { detail = "Hands" }, modifier = Modifier.testTag("device.hands.open"))
                    }
                    if (!a11yBound) Text("Installed apps → Hermes Companion", style = CompanionType.Caption, modifier = Modifier.testTag("device.a11y.hint"))
                }
                GroupedSection("Privacy & access") {
                    PreferenceToggle("Keep awake during voice", awakeOnVoice, onToggleAwakeOnVoice,
                        summary = "Only during a voice conversation", modifier = Modifier.testTag("device.awake.voice"))
                    PreferenceToggle("Biometric app lock", biometricLock, onToggleBiometricLock,
                        summary = "Authenticate before opening Companion", modifier = Modifier.testTag("device.biometric.lock"))
                    PreferenceRow("Permissions", "Android access and locked-screen preferences", onClick = { detail = "Permissions" },
                        modifier = Modifier.testTag("device.permissions.open"))
                }
                GroupedSection("Sharing & protection") {
                    PreferenceRow("Notification stream", if (notifStreamEnabled) "On · ${notifStreamProfile ?: "Current profile"}" else "Off",
                        onClick = { detail = "Notification stream" }, modifier = Modifier.testTag("device.stream.open"))
                    PreferenceRow("Protected apps", "${protectedCustom.size} custom rules", onClick = { detail = "Protected apps" },
                        modifier = Modifier.testTag("device.protected.open"))
                    PreferenceRow("Activity audit", "Recent phone control outcome", onClick = { detail = "Activity audit" },
                        modifier = Modifier.testTag("device.audit.open"))
                }
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { StatusStrip(it, modifier = Modifier.testTag("device.error")) }
    }
    if (detail != null && phase == PairingPhase.PAIRED) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { detail = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
            Column(Modifier.fillMaxWidth().verticalScroll(remember(detail) { androidx.compose.foundation.ScrollState(0) }).padding(horizontal = 24.dp).padding(bottom = 24.dp)
                .testTag("device.details"), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                SheetHeading(detail!!, onClose = { detail = null }, closeModifier = Modifier.testTag("device.details.close"),
                    onBack = if (detail == "Notification destination") ({ detail = "Notification stream" }) else null)
                when (detail) {
                    "This phone" -> {
                        GroupedSection("Identity", padded = true) {
                            Text("Device name", style = CompanionType.RowTitle)
                            HairlineField(labelDraft, { labelDraft = it }, placeholder = "Device name", keyboardType = KeyboardType.Text,
                                onDone = { onRenameDevice(labelDraft); dismissKeyboard() }, modifier = Modifier.testTag("device.label"))
                            ActionButton("Save name", { onRenameDevice(labelDraft); dismissKeyboard() }, modifier = Modifier.testTag("device.label.save"))
                            if (!deviceId.isNullOrBlank()) Text(deviceId, style = CompanionType.Caption, modifier = Modifier.testTag("device.id"))
                        }
                        GroupedSection("Pairing", padded = true) {
                            Text(if (laneOpen) "Control connection is ready" else "Control connection is reconnecting", style = CompanionType.Body)
                            if (!laneOpen) Action("RE-PAIR", "device.repair", onRepair)
                            Action("REVOKE", "device.revoke") { confirmRevoke = true }
                        }
                    }
                    "Hands" -> {
                        GroupedSection("Control state") {
                            PreferenceRow("Hands", if (armed) "Armed" else "Disarmed")
                            PreferenceRow("Emergency stop", "Press volume down twice")
                            PreferenceRow("Foreground app", foregroundApp.ifBlank { "Home screen" }, modifier = Modifier.testTag("device.foreground"))
                        }
                        GroupedSection("Safeguards") {
                            PreferenceRow("Permissions", "Accessibility, overlay and notifications", onClick = { detail = "Permissions" })
                            PreferenceRow("Protected apps", "Blocked before snapshots and gestures", onClick = { detail = "Protected apps" })
                            PreferenceRow("Agent rooms", "Phone control is blocked during room turns")
                        }
                        if (armed) Action("DISARM", "device.disarm", onDisarm)
                    }
                    "Permissions" -> {
                        GroupedSection("Phone control") {
                            PreferenceRow("Control connection", if (laneOpen) "Connected" else "Connecting", modifier = Modifier.testTag("device.lane"))
                            PreferenceRow("Accessibility", if (a11yBound) "Allowed" else "Enable Hermes Companion in Installed apps",
                                onClick = onEnableA11y, modifier = Modifier.testTag("device.a11y"))
                            PreferenceRow("Display over other apps", if (overlayGranted) "Allowed" else "Required for the control indicator",
                                onClick = onEnableOverlay, modifier = Modifier.testTag("device.overlay"))
                            PreferenceRow("Notifications", if (notifyGranted) "Allowed" else "Grant notification permission",
                                onClick = onEnableNotify, modifier = Modifier.testTag("device.notify"))
                        }
                        GroupedSection("Locked screen") {
                            PreferenceToggle("Access while locked", lockedAccess, onToggleLockedAccess,
                                summary = "Allow the configured locked-screen behavior", modifier = Modifier.testTag("device.locked.access"))
                        }
                        Text("Granting permission does not arm Hands.", style = CompanionType.Caption)
                    }
                    "Notification stream" -> {
                        GroupedSection("Sharing") {
                            PreferenceToggle("Share notifications", notifStreamEnabled, onToggleNotifStream,
                                summary = "Send allowed notifications to the selected host and profile", modifier = Modifier.testTag("device.stream"))
                            PreferenceRow("Notification access", if (nlsBound) "Allowed" else "Enable Android notification access",
                                onClick = onEnableNls, modifier = Modifier.testTag("device.nls"))
                            PreferenceRow("Destination", "${notifStreamOrigin ?: "Current host"} · ${notifStreamProfile ?: "Current profile"}",
                                onClick = { detail = "Notification destination" }, modifier = Modifier.testTag("device.stream.sink"))
                        }
                        GroupedSection("Privacy", padded = true) {
                            Text("Companion service channels and Telegram clients are always excluded.", style = CompanionType.Body, modifier = Modifier.testTag("device.stream.mute"))
                            Text("Allowed content is visible to the chosen host. Injection into a conversation always requires an explicit decision.",
                                style = CompanionType.Caption, modifier = Modifier.testTag("device.stream.warn"))
                        }
                    }
                    "Notification destination" -> {
                        GroupedSection("Host") {
                            pairedHosts.forEach { host -> PreferenceRow(host, selected = host == notifStreamOrigin,
                                value = if (host == notifStreamOrigin) "Selected" else null, onClick = { onPickStreamOrigin(host) }, modifier = Modifier.testTag("device.stream.origin")) }
                            if (pairedHosts.isEmpty()) PreferenceRow("Current host", notifStreamOrigin ?: "Select a paired host to change the destination")
                        }
                        GroupedSection("Profile") {
                            profilesForStream.forEach { profile -> PreferenceRow(profile, selected = profile == notifStreamProfile,
                                value = if (profile == notifStreamProfile) "Selected" else null, onClick = { onPickStreamProfile(profile) }, modifier = Modifier.testTag("device.stream.profile")) }
                        }
                    }
                    "Protected apps" -> GroupedSection("App rules", padded = true) {
                        ProtectedPackages(protectedCustom, protectedDefaults, protectedError, onAddProtected, onRemoveProtected)
                    }
                    "Activity audit" -> GroupedSection("Latest outcome") {
                        PreferenceRow("Control event", lastAudit.ifBlank { "No recent phone control events" }, modifier = Modifier.testTag("device.audit"))
                        PreferenceRow("Foreground app", foregroundApp.ifBlank { "Home screen" })
                    }
                }
            }
        }
    }
    if (confirmRevoke) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRevoke = false },
        title = { Text("Remove this phone’s pairing?") },
        text = { Text("Host tools will lose access until this phone pairs again.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmRevoke = false; detail = null; onRevoke() }) { Text("Remove pairing", color = CompanionColor.Danger) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } })
}

/** Denylist editor. Built-in list is empty; user rows are exact ids or `prefix.*`. */
@Composable
@Suppress("UNUSED_PARAMETER")
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
            text = if (custom.isEmpty()) {
                "BLOCKLIST empty · add packages to protect"
            } else {
                "BLOCKLIST  ${custom.size} custom"
            },
            style = CompanionType.Caption,
            modifier = Modifier.testTag("device.protected.count"),
        )
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(
            text = if (custom.isEmpty()) {
                "no built-in denylist · hands can touch any app until you add rules"
            } else {
                "hands will not touch these blocked apps"
            },
            style = CompanionType.Caption.copy(
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
                    style = CompanionType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ActionText(
                    text = "✕",
                    style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("device.protected.remove")
                        .heightIn(min = 48.dp).clickable { onRemove(pkg) }
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
            style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Xs))
            Text(
                text = error,
                style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                modifier = Modifier.testTag("device.protected.error"),
            )
        }
    }
}

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit) {
    val title = when (label) {
        "ENABLE A11Y" -> "Enable screen control"
        "ENABLE NOTIFY" -> "Allow notifications"
        "ENABLE OVERLAY" -> "Enable overlay"
        "ENABLE NLS" -> "Allow notification access"
        "REVOKE" -> "Remove pairing"
        "ARM" -> "Arm Hands"
        "DISARM" -> "Disarm Hands"
        else -> label.lowercase().replaceFirstChar(Char::titlecase)
    }
    app.hermes.companion.design.ActionButton(title, onClick, modifier = Modifier.testTag(tag),
        kind = when (tag) {
            "device.arm.go", "device.pair" -> app.hermes.companion.design.ActionKind.PRIMARY
            "device.disarm", "device.revoke" -> app.hermes.companion.design.ActionKind.DANGER
            else -> app.hermes.companion.design.ActionKind.GHOST
        })
}

@Composable
private fun PermissionLine(label: String, value: String, tag: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = CompanionType.Label, modifier = Modifier.weight(1f))
        Text(value, style = CompanionType.Caption, color = if (granted) CompanionColor.Signal else CompanionColor.TextDim,
            modifier = Modifier.testTag(tag))
    }
}
