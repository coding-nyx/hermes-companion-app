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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    a11yListed: Boolean? = null,
    a11yOffRequested: Boolean = false,
    overlayGranted: Boolean = false,
    notifyGranted: Boolean = false,
    foregroundApp: String = "",
    lastAudit: String = "",
    arm: DeviceArm = DeviceArm.DISARMED,
    armPending: Boolean = false,
    automaticA11y: Boolean = false,
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
    notifStreamApps: Set<String> = emptySet(),
    notifStreamContent: String = "metadata",
    notifStreamCalls: Boolean = true,
    notifStreamDelivery: String = "Off",
    notifStreamRetentionMinutes: Int = 15,
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
    onDisableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    onEnableNls: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onToggleNotifStream: () -> Unit = {},
    onSetStreamApp: (String, Boolean) -> Unit = { _, _ -> },
    onSetStreamContent: (String) -> Unit = {},
    onToggleStreamCalls: () -> Unit = {},
    onSetStreamRetention: (Int) -> Unit = {},
    onPickStreamOrigin: (String) -> Unit = {},
    onPickStreamProfile: (String) -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
    onRenameDevice: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    relayHealth: app.hermes.companion.model.CompanionHealth? = null,
    connectionModeBusy: Boolean = false,
    connectionModeError: String? = null,
    onConnectionMode: (String) -> Unit = {},
    smsReadGranted: Boolean = false,
    smsPermissionCanRequest: Boolean = true,
    smsPermissionMessage: String = "Allow SMS reading only if you want an armed agent to access your message history. This does not allow sending SMS.",
    onRequestSmsPermission: () -> Unit = {},
    onOpenSmsSettings: () -> Unit = {},
    privateEntryActive: Boolean = false,
    onOpenPrivateEntry: () -> Unit = {},
    externalHands: Boolean = false,
    externalHandsReachable: Boolean = false,
    externalHandsArmed: Boolean = false,
    externalHandsBackend: String = "",
    externalHandsTarget: String = "",
    externalHandsA11y: Boolean = false,
    externalHandsPrivacy: Boolean = false,
    externalHandsError: String? = null,
    onConnectHands: () -> Unit = {},
    onDisconnectHands: () -> Unit = {},
    onOpenHands: () -> Unit = {},
 ) {
    var detail by rememberSaveable(deviceId, deviceProfileId) { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var confirmStream by rememberSaveable { mutableStateOf(false) }
    var labelDraft by rememberSaveable(deviceId, deviceLabel) { mutableStateOf(deviceLabel) }
    val dismissKeyboard = rememberDismissKeyboard()
    val routeReady = externalHands && !notifStreamOrigin.isNullOrBlank() && !notifStreamProfile.isNullOrBlank()
    Column(modifier.fillMaxSize().background(CompanionColor.Void).testTag("device.surface")
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GroupedSection("Phone control owner", padded = true) {
            Text("Hands", style = CompanionType.RowTitle, modifier = Modifier.testTag("device.hands.owner"))
            Text("Hands owns mobile permissions, SMS, files, notifications and screen control. Companion forwards authorized commands.", style = CompanionType.Body)
            Text(when {
                !externalHands -> "Connect Hands to enable mobile tools"
                externalHandsPrivacy -> "Private entry · remote access paused"
                !externalHandsReachable -> "Hands unavailable · open the app to reconnect"
                externalHandsArmed -> "Armed in Hands"
                else -> "Connected · control off"
            }, style = CompanionType.RowTitle, color = if (externalHandsArmed) CompanionColor.Warn else CompanionColor.Signal,
                modifier = Modifier.testTag("device.hands.status"))
            if (externalHands) Text("Backend: ${externalHandsBackend.ifBlank { "Check Hands" }} · Target: ${externalHandsTarget.ifBlank { "Check Hands" }}", style = CompanionType.Caption)
            externalHandsError?.let { Text(it, style = CompanionType.Caption, color = CompanionColor.Warn) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!externalHands) Action("CONNECT HANDS", "device.hands.connect", onConnectHands)
                Action("OPEN HANDS", "device.hands.launch", onOpenHands)
                if (externalHandsArmed) Action("DISARM", "device.hands.disarm", onDisarm)
                if (externalHands) Action("DISCONNECT", "device.hands.disconnect", onDisconnectHands)
            }
        }
        ConnectionModePreference(relayHealth?.mode.orEmpty(), relayHealth?.connectionSettings == true,
            phase == PairingPhase.PAIRED, connectionModeBusy, connectionModeError, onConnectionMode)
        when (phase) {
            PairingPhase.IDLE -> GroupedSection("Gateway connection", padded = true) {
                Text("Not paired", style = CompanionType.RowTitle, modifier = Modifier.testTag("device.status"))
                Text("Pair Companion with your host to transport its requests to Hands.", style = CompanionType.Body)
                Action("PAIR", "device.pair", onPair)
            }
            PairingPhase.WAITING -> GroupedSection("Pairing", padded = true) {
                Text(code, style = CompanionType.Mono, modifier = Modifier.testTag("device.pair.code"))
                Text("Waiting for the gateway's approval.", style = CompanionType.Body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("APPROVE", "device.pair.approve", onApprove)
                    Action("CANCEL", "device.pair.cancel", onCancel)
                }
            }
            PairingPhase.PAIRED -> {
                GroupedSection("This phone") {
                    PreferenceRow(deviceLabel.ifBlank { "Phone" }, "Paired · ${deviceProfileId ?: "default"}",
                        value = if (laneOpen) "Connected" else "Connecting", selected = laneOpen,
                        onClick = { detail = "This phone" }, modifier = Modifier.testTag("device.identity"))
                }
                GroupedSection("Notification forwarding") {
                    PreferenceToggle("Forward from Hands", notifStreamEnabled,
                        { if (notifStreamEnabled) onToggleNotifStream() else confirmStream = true },
                        enabled = notifStreamEnabled || routeReady,
                        summary = "Only the notification scope approved for Companion in Hands is forwarded.",
                        modifier = Modifier.testTag("device.stream"))
                    PreferenceRow("Destination", "${notifStreamOrigin ?: "Choose a gateway"}\n${notifStreamProfile ?: "Choose a profile"}",
                        value = "Change", onClick = { detail = "Notification destination" }, modifier = Modifier.testTag("device.stream.route"))
                    PreferenceRow("Delivery", notifStreamDelivery, modifier = Modifier.testTag("device.stream.delivery"))
                    PreferenceRow("Phone approval", "${notifStreamApps.size} apps · $notifStreamContent · $notifStreamRetentionMinutes minutes",
                        value = "Hands", onClick = onOpenHands, modifier = Modifier.testTag("device.stream.open"))
                    Text("This is a passive inbox. Forwarding never wakes an agent or injects messages into a conversation.", style = CompanionType.Caption)
                }
                GroupedSection("Companion preferences") {
                    PreferenceToggle("Keep awake during voice", awakeOnVoice, onToggleAwakeOnVoice,
                        summary = "Only during Companion voice conversations", modifier = Modifier.testTag("device.awake.voice"))
                    PreferenceToggle("Biometric app lock", biometricLock, onToggleBiometricLock,
                        summary = "Authenticate before opening Companion", modifier = Modifier.testTag("device.biometric.lock"))
                }
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { StatusStrip(it, modifier = Modifier.testTag("device.error")) }
    }
    if (detail != null && phase == PairingPhase.PAIRED) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { detail = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SheetHeading(checkNotNull(detail), { detail = null }, closeModifier = Modifier.testTag("device.details.close"))
            if (detail == "This phone") {
                GroupedSection("Identity", padded = true) {
                    HairlineField(labelDraft, { labelDraft = it }, placeholder = "Device name", keyboardType = KeyboardType.Text,
                        onDone = { onRenameDevice(labelDraft); dismissKeyboard() }, modifier = Modifier.testTag("device.label"))
                    ActionButton("Save name", { onRenameDevice(labelDraft); dismissKeyboard() }, Modifier.testTag("device.label.save"))
                    deviceId?.let { Text(it, style = CompanionType.Caption, modifier = Modifier.testTag("device.id")) }
                }
                GroupedSection("Pairing", padded = true) {
                    Text(if (laneOpen) "Gateway connection is ready" else "Gateway connection is reconnecting", style = CompanionType.Body)
                    if (!laneOpen) Action("RE-PAIR", "device.repair", onRepair)
                    Action("REVOKE", "device.revoke") { confirmRevoke = true }
                }
            } else {
                Text("Only this gateway and profile receive the scope approved for Companion in Hands. Changing destination pauses forwarding.", style = CompanionType.Body)
                GroupedSection("Gateway") {
                    pairedHosts.forEach { host -> PreferenceRow(host, selected = host == notifStreamOrigin,
                        value = if (host == notifStreamOrigin) "Selected" else null, onClick = { onPickStreamOrigin(host) },
                        modifier = Modifier.testTag("device.stream.origin")) }
                }
                GroupedSection("Profile") {
                    profilesForStream.forEach { profile -> PreferenceRow(profile, selected = profile == notifStreamProfile,
                        value = if (profile == notifStreamProfile) "Selected" else null, onClick = { onPickStreamProfile(profile) },
                        modifier = Modifier.testTag("device.stream.profile")) }
                }
            }
        }
    }
    if (confirmStream) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmStream = false },
        title = { Text("Forward notifications from Hands?") },
        text = { Text("Gateway: ${notifStreamOrigin.orEmpty()}\nProfile: ${notifStreamProfile.orEmpty()}\nOnly content approved for Companion in Hands will be forwarded. This does not arm control.") },
        confirmButton = { ActionButton("Start forwarding", { confirmStream = false; onToggleNotifStream() },
            Modifier.testTag("device.stream.confirm"), ActionKind.PRIMARY, enabled = routeReady) },
        dismissButton = { ActionButton("Cancel", { confirmStream = false }) })
    if (confirmRevoke) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRevoke = false },
        title = { Text("Remove this phone's gateway pairing?") }, text = { Text("Host tools lose this transport until Companion pairs again.") },
        confirmButton = { ActionButton("Remove pairing", { confirmRevoke = false; detail = null; onRevoke() }, Modifier.testTag("device.revoke.confirm"), ActionKind.DANGER) },
        dismissButton = { ActionButton("Cancel", { confirmRevoke = false }) })
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
                        .heightIn(min = 48.dp).clickable(role = Role.Button) { onRemove(pkg) }
                        .semantics { contentDescription = "Remove protection for $pkg" }
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
        "ENABLE A11Y" -> "Allow accessibility"
        "ENABLE NOTIFY" -> "Allow notifications"
        "ENABLE OVERLAY" -> "Enable overlay"
        "ENABLE NLS" -> "Allow notification access"
        "REVOKE" -> "Remove pairing"
        "ARM" -> "Arm Hands"
        "DISARM" -> "Disarm Hands"
        "OPEN HANDS" -> "Open Hands"
        "CONNECT HANDS" -> "Connect Hands"
        else -> label.lowercase().replaceFirstChar(Char::titlecase)
    }
    app.hermes.companion.design.ActionButton(title, onClick, modifier = Modifier.testTag(tag),
        kind = when (tag) {
            "device.arm.go", "device.pair" -> app.hermes.companion.design.ActionKind.PRIMARY
            "device.disarm", "device.hands.disarm", "device.revoke" -> app.hermes.companion.design.ActionKind.DANGER
            else -> app.hermes.companion.design.ActionKind.GHOST
        })
}

internal data class NotificationStreamStatus(val label: String, val hint: String)

/** Enabled is not equivalent to working: the Android listener must also be available. */
internal fun notificationStreamStatus(enabled: Boolean, accessGranted: Boolean): NotificationStreamStatus = when {
    !enabled -> NotificationStreamStatus("Off", "Notifications are not being shared.")
    !accessGranted -> NotificationStreamStatus("Needs access", "Allow Android notification access to start sharing.")
    else -> NotificationStreamStatus("Enabled", "Allowed notifications are routed to the destination below.")
}

@Composable
private fun PermissionAccessRow(label: String, purpose: String, granted: Boolean, onClick: () -> Unit, tag: String) {
    val status = if (granted) "Allowed" else "Not allowed"
    PreferenceRow(label, purpose, value = status, selected = granted, onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { stateDescription = status })
}
