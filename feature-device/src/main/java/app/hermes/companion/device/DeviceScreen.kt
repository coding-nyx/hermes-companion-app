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
) {
    var detail by rememberSaveable(deviceId, deviceProfileId) { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var confirmStream by rememberSaveable { mutableStateOf(false) }
    val streamReady = nlsBound && notifStreamApps.isNotEmpty() && !notifStreamOrigin.isNullOrBlank() && !notifStreamProfile.isNullOrBlank()
    var labelDraft by rememberSaveable(deviceId, deviceLabel) { mutableStateOf(deviceLabel) }
    val dismissKeyboard = rememberDismissKeyboard()
    val armed = arm != DeviceArm.DISARMED
    val permissionCount = listOf(a11yBound, overlayGranted, notifyGranted).count { it }
    val streamStatus = notificationStreamStatus(notifStreamEnabled, nlsBound)
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
                Text("Your phone. Your permissions.", style = CompanionType.Caption)
                GroupSurface {
                    PreferenceRow(deviceLabel.ifBlank { "This Android phone" }, "Paired · ${deviceProfileId ?: "Current profile"}",
                        value = if (laneOpen) "Connected" else "Reconnecting", selected = laneOpen,
                        onClick = { detail = "This phone" }, modifier = Modifier.testTag("device.status"))
                }
                GroupedSection("Hands", padded = true) {
                    Text(if (armed) "Screen control is armed" else "Screen control is off", style = CompanionType.RowTitle,
                        color = if (armed) CompanionColor.Warn else CompanionColor.Text,
                        modifier = Modifier.testTag("device.arm").semantics { stateDescription = if (armed) "Armed" else "Disarmed" })
                    Text(if (armed) "Hermes can view and control this phone. Press volume down twice to stop."
                        else "Permission alone never starts control. Arm Hands only when you want Hermes to tap, scroll or type.",
                        style = CompanionType.Body, color = CompanionColor.TextDim)
                    if (!armed) Text(
                        when {
                            !a11yBound -> "Next: allow accessibility for screen control."
                            !notifyGranted -> "Next: allow notifications for the control service."
                            !overlayGranted -> "Control access is ready. Allow the overlay to show the control indicator."
                            else -> "Ready when you are. Hands stays off until you arm it."
                        }, style = CompanionType.Caption, color = CompanionColor.Signal,
                        modifier = Modifier.testTag("device.nextAction"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            armed -> Action("DISARM", "device.disarm", onDisarm)
                            !a11yBound -> Action("ENABLE A11Y", "device.a11y.enable", onEnableA11y)
                            !notifyGranted -> Action("ENABLE NOTIFY", "device.notify.enable", onEnableNotify)
                            else -> Action("ARM", "device.arm.go", onArm)
                        }
                        ActionButton("Control details", { detail = "Hands" }, modifier = Modifier.testTag("device.hands.open"))
                    }
                    if (!a11yBound) Text("Android settings → Installed apps → Hermes Companion", style = CompanionType.Caption,
                        modifier = Modifier.testTag("device.a11y.hint"))
                    Text("Screen actions may require you to unlock. The background gateway connection is separate.",
                        style = CompanionType.Caption, modifier = Modifier.testTag("device.locked.limit"))
                }
                GroupedSection("Phone → Hermes") {
                    PreferenceRow("Notification stream", streamStatus.hint, value = streamStatus.label,
                        selected = notifStreamEnabled && nlsBound, onClick = { detail = "Notification stream" },
                        modifier = Modifier.testTag("device.stream.open").semantics { stateDescription = streamStatus.label })
                    PreferenceRow("Destination", "${notifStreamOrigin ?: "Choose a gateway"}\n${notifStreamProfile ?: "Choose a profile"}",
                        value = "Change", onClick = { detail = "Notification destination" },
                        modifier = Modifier.testTag("device.stream.route"))
                    if (notifStreamEnabled && !nlsBound) PreferenceRow("Allow notification access", "Sharing is enabled, but Android access is missing.",
                        value = "Fix access", onClick = onEnableNls, modifier = Modifier.testTag("device.nls.enable"))
                }
                GroupedSection("Control permissions", trailing = {
                    Text("$permissionCount / 3", style = CompanionType.Caption, color = CompanionColor.Signal,
                        modifier = Modifier.testTag("device.permissions.count").semantics { contentDescription = "$permissionCount of 3 control permissions allowed" })
                }) {
                    PermissionAccessRow("Accessibility", "Screen access while Hands is armed", a11yBound, onEnableA11y, "device.permission.a11y")
                    PermissionAccessRow("Control indicator", "Display over other apps", overlayGranted, onEnableOverlay, "device.permission.overlay")
                    PermissionAccessRow("Service notifications", "Status, alerts and stop control", notifyGranted, onEnableNotify, "device.permission.notify")
                    PreferenceRow("All access settings", "Notification access and locked-screen preferences", onClick = { detail = "Permissions" },
                        modifier = Modifier.testTag("device.permissions.open"))
                }
                GroupedSection("Privacy & protection") {
                    PreferenceToggle("Keep awake during voice", awakeOnVoice, onToggleAwakeOnVoice,
                        summary = "Only during a voice conversation", modifier = Modifier.testTag("device.awake.voice"))
                    PreferenceToggle("Biometric app lock", biometricLock, onToggleBiometricLock,
                        summary = "Authenticate before opening Companion", modifier = Modifier.testTag("device.biometric.lock"))
                    PreferenceRow("Protected apps", if (protectedCustom.isEmpty()) "No custom rules. Add apps you want Hands to avoid."
                        else "${protectedCustom.size} custom rules · blocked before screen control", onClick = { detail = "Protected apps" },
                        modifier = Modifier.testTag("device.protected.open"))
                    PreferenceRow("Activity audit", "Review the latest phone control outcome", onClick = { detail = "Activity audit" },
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
                    onBack = if (detail == "Notification destination" || detail == "Observed apps") ({ detail = "Notification stream" }) else null)
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
                            PermissionAccessRow("Accessibility", "Enable Hermes Companion in Installed apps", a11yBound, onEnableA11y, "device.a11y")
                            PermissionAccessRow("Display over other apps", "Show the control indicator", overlayGranted, onEnableOverlay, "device.overlay")
                            PermissionAccessRow("Notifications", "Show service notifications", notifyGranted, onEnableNotify, "device.notify")
                            PermissionAccessRow("Notification access", "Read allowed notifications for sharing", nlsBound, onEnableNls, "device.nls")
                        }
                        GroupedSection("Locked screen") {
                            PreferenceToggle("Request unlock on resume", lockedAccess, onToggleLockedAccess,
                                summary = "Ask Android to dismiss the lock screen when Companion opens. Android may still require you to unlock. Does not bypass PIN or biometrics.",
                                modifier = Modifier.testTag("device.locked.access"))
                        }
                        Text("Granting permission does not arm Hands.", style = CompanionType.Caption)
                    }
                    "Notification stream" -> {
                        StatusStrip("${streamStatus.label}: ${streamStatus.hint}", tone = if (notifStreamEnabled && !nlsBound) CompanionColor.Warn else CompanionColor.TextDim,
                            modifier = Modifier.testTag("device.stream.status"))
                        GroupedSection("Sharing") {
                            PreferenceRow("Delivery (this app session)", notifStreamDelivery, modifier = Modifier.testTag("device.stream.delivery"))
                            PreferenceToggle("Share notifications", notifStreamEnabled,
                                { if (notifStreamEnabled) onToggleNotifStream() else confirmStream = true },
                                enabled = notifStreamEnabled || streamReady,
                                summary = when { !nlsBound -> "Allow notification access before starting sharing"; streamReady -> "Forward selected apps to your chosen gateway and profile"; else -> "Choose apps, a gateway and a profile first" },
                                modifier = Modifier.testTag("device.stream"))
                            PreferenceRow("Observed apps", "${notifStreamApps.size} selected · tap to select or remove", onClick = { detail = "Observed apps" }, modifier = Modifier.testTag("device.stream.apps.open"))
                            PermissionAccessRow("Notification access", "Read allowed notifications for sharing", nlsBound, onEnableNls, "device.nls")
                            PreferenceRow("Destination", "${notifStreamOrigin ?: "Choose a gateway"} · ${notifStreamProfile ?: "Choose a profile"}",
                                onClick = { detail = "Notification destination" }, modifier = Modifier.testTag("device.stream.sink"))
                        }
                        GroupedSection("What to share") {
                            listOf("metadata" to "App and event only", "title" to "Include title", "full" to "Include title and message").forEach { (mode, label) ->
                                PreferenceRow(label, selected = notifStreamContent == mode,
                                    value = if (notifStreamContent == mode) "Selected" else null,
                                    onClick = { onSetStreamContent(mode) }, modifier = Modifier.testTag("device.stream.content.$mode"))
                            }
                            PreferenceToggle("Call notifications", notifStreamCalls, onToggleStreamCalls,
                                summary = "Include call-category notifications from selected apps. Not a complete call-state feed; no call audio or call-log access.", modifier = Modifier.testTag("device.stream.calls"))
                        }
                        GroupedSection("Offline retention") {
                            listOf(2, 15, 60).forEach { minutes ->
                                PreferenceRow("$minutes minutes", selected = notifStreamRetentionMinutes == minutes,
                                    value = if (notifStreamRetentionMinutes == minutes) "Selected" else null,
                                    onClick = { onSetStreamRetention(minutes) }, modifier = Modifier.testTag("device.stream.retention.$minutes"))
                            }
                        }
                        GroupedSection("Privacy", padded = true) {
                            Text("Offline events are encrypted on this phone, with at most 128 queued. Events expire after the selected retention. If full, the oldest is evicted and counted. Changing settings pauses sharing until you confirm again.", style = CompanionType.Body)
                            Text("Only new notification events are shared, not a snapshot of your notification history. Call notifications are not guaranteed call-state events.", style = CompanionType.Caption)
                            Text("Titles and messages may contain private information. App and event only is the safest setting. Android can redact or withhold sensitive notifications.", style = CompanionType.Body)
                            Text("Companion service channels and Telegram clients are always excluded.", style = CompanionType.Body, modifier = Modifier.testTag("device.stream.mute"))
                            Text("The gateway inbox is passive: an agent must read it explicitly. Sharing never starts autonomous actions or forwards to Telegram. Allowed content is visible to the chosen host.",
                                style = CompanionType.Caption, modifier = Modifier.testTag("device.stream.warn"))
                        }
                    }
                    "Observed apps" -> NotificationAppPicker(notifStreamApps, onSetStreamApp)
                    "Notification destination" -> {
                        Text("Allowed notification content is sent only to this host and profile. Changing the route does not arm phone control.", style = CompanionType.Body,
                            color = CompanionColor.TextDim)
                        GroupedSection("Host") {
                            pairedHosts.forEach { host -> PreferenceRow(host, selected = host == notifStreamOrigin,
                                value = if (host == notifStreamOrigin) "Selected" else null, onClick = { onPickStreamOrigin(host) }, modifier = Modifier.testTag("device.stream.origin")) }
                            if (pairedHosts.isEmpty()) PreferenceRow("No paired gateway", notifStreamOrigin ?: "Select a paired host to change the destination")
                        }
                        GroupedSection("Profile") {
                            profilesForStream.forEach { profile -> PreferenceRow(profile, selected = profile == notifStreamProfile,
                                value = if (profile == notifStreamProfile) "Selected" else null, onClick = { onPickStreamProfile(profile) }, modifier = Modifier.testTag("device.stream.profile")) }
                            if (profilesForStream.isEmpty()) PreferenceRow("No profiles available", "Select and connect to this gateway as your active host to load its profiles. No current-host profile is used as a fallback.")
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
    if (confirmStream) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmStream = false },
        title = { Text("Start notification sharing?") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gateway: ${notifStreamOrigin.orEmpty()}\nProfile: ${notifStreamProfile.orEmpty()}")
            Text("${notifStreamApps.size} selected ${if (notifStreamApps.size == 1) "app" else "apps"}: ${notifStreamApps.sorted().joinToString()}")
            Text("Content: ${when (notifStreamContent) { "full" -> "titles and message text"; "title" -> "titles"; else -> "app and event metadata only" }}. Call notifications: ${if (notifStreamCalls) "included" else "excluded"}.")
            Text("Offline queue: encrypted, at most 128 events, retained for $notifStreamRetentionMinutes minutes. Oldest events are evicted when full.")
            Text("The selected gateway can read and retain this data. Sharing does not arm phone control. You can stop sharing or remove an app at any time.")
        } },
        confirmButton = { androidx.compose.material3.TextButton(enabled = streamReady, modifier = Modifier.testTag("device.stream.confirm"), onClick = { confirmStream = false; onToggleNotifStream() }) { Text("Start sharing") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmStream = false }) { Text("Cancel") } })
    if (confirmRevoke) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRevoke = false },
        title = { Text("Remove this phone’s pairing?") },
        text = { Text("Host tools will lose access until this phone pairs again.") },
        confirmButton = { androidx.compose.material3.TextButton(modifier = Modifier.testTag("device.revoke.confirm"), onClick = { confirmRevoke = false; detail = null; onRevoke() }) { Text("Remove pairing", color = CompanionColor.Danger) } },
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
        else -> label.lowercase().replaceFirstChar(Char::titlecase)
    }
    app.hermes.companion.design.ActionButton(title, onClick, modifier = Modifier.testTag(tag),
        kind = when (tag) {
            "device.arm.go", "device.pair" -> app.hermes.companion.design.ActionKind.PRIMARY
            "device.disarm", "device.revoke" -> app.hermes.companion.design.ActionKind.DANGER
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
