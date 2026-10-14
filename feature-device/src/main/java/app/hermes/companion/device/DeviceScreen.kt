package app.hermes.companion.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
                Scanline(Modifier.width(160.dp), active = true)
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
                // 1. HUD Header: Device status & identity card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LiveDot(color = CompanionColor.Signal)
                            Text(
                                text = "PAIRED",
                                style = CompanionType.MonoSmall.copy(
                                    color = CompanionColor.Signal,
                                    fontWeight = FontWeight.Bold,
                                ),
                                modifier = Modifier.testTag("device.status"),
                            )
                        }
                        if (!deviceId.isNullOrBlank()) {
                            Text(
                                text = deviceId,
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                                modifier = Modifier.testTag("device.id"),
                            )
                        }
                    }

                    var labelDraft by rememberSaveable(deviceId, deviceLabel) { mutableStateOf(deviceLabel) }
                    val dismissKeyboard = rememberDismissKeyboard()
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                    ) {
                        HairlineField(
                            value = labelDraft,
                            onValueChange = { labelDraft = it },
                            modifier = Modifier.weight(1f).testTag("device.label"),
                            keyboardType = KeyboardType.Text,
                            placeholder = "friendly device name",
                            onDone = {
                                onRenameDevice(labelDraft)
                                dismissKeyboard()
                            },
                        )
                        Text(
                            text = "SAVE",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                            modifier = Modifier
                                .clickable {
                                    onRenameDevice(labelDraft)
                                    dismissKeyboard()
                                }
                                .padding(CompanionSpace.Sm)
                                .testTag("device.label.save"),
                        )
                    }
                    if (!deviceProfileId.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Profile: $deviceProfileId",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                            modifier = Modifier.align(Alignment.Start),
                        )
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Md))

                // 2. Master Hands Control Deck
                val isArmed = arm != DeviceArm.DISARMED
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            CompanionSpace.Hairline,
                            if (isArmed) CompanionColor.Warn else CompanionColor.SignalDim,
                        )
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isArmed) {
                            LiveDot(color = CompanionColor.Warn)
                        }
                        Text(
                            text = "ARM      ${arm.name.lowercase()}",
                            style = CompanionType.Mono.copy(
                                color = if (isArmed) CompanionColor.Warn else CompanionColor.Signal,
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = Modifier.testTag("device.arm"),
                        )
                    }
                    Spacer(Modifier.height(CompanionSpace.Xs))
                    Text(
                        text = if (isArmed) {
                            "HERMES HAS HANDS · double volume-down to instant kill switch"
                        } else {
                            "Hands disarmed · Agent cannot touch screen or execute mobile gestures"
                        },
                        style = CompanionType.MonoSmall.copy(
                            color = if (isArmed) CompanionColor.Warn else CompanionColor.TextMute,
                        ),
                        modifier = Modifier.padding(horizontal = CompanionSpace.Sm),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    if (!a11yBound) {
                        Action("ENABLE A11Y", "device.a11y.enable", onEnableA11y)
                        Spacer(Modifier.height(CompanionSpace.Xs))
                        Text(
                            text = "Installed apps → Hermes Companion",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                            modifier = Modifier.testTag("device.a11y.hint"),
                        )
                    } else if (isArmed) {
                        Action("DISARM", "device.disarm", onDisarm)
                        Spacer(Modifier.height(CompanionSpace.Xs))
                        Text(
                            text = "double volume-down to disarm",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        )
                    } else if (!notifyGranted) {
                        Action("ENABLE NOTIFY", "device.notify.enable", onEnableNotify)
                    } else {
                        Action("ARM", "device.arm.go", onArm)
                    }
                    if (!overlayGranted) {
                        Spacer(Modifier.height(CompanionSpace.Sm))
                        Action("ENABLE OVERLAY", "device.overlay.enable", onEnableOverlay)
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Md))

                // 3. Subsystem Matrix (2x2 Grid)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                ) {
                    Text(
                        text = "DEVICE SUBSYSTEMS",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (laneOpen) "LANE  live" else "LANE  connecting",
                                style = CompanionType.Mono.copy(
                                    color = if (laneOpen) CompanionColor.Signal else CompanionColor.TextDim,
                                ),
                                modifier = Modifier.testTag("device.lane"),
                            )
                            if (!laneOpen) {
                                Spacer(Modifier.width(CompanionSpace.Sm))
                                SignalCursor()
                            }
                        }
                        Text(
                            text = if (a11yBound) "A11Y     on" else "A11Y     off",
                            style = CompanionType.Mono.copy(
                                color = if (a11yBound) CompanionColor.Signal else CompanionColor.Warn,
                            ),
                            modifier = Modifier.testTag("device.a11y"),
                        )
                    }
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
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
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Md))

                // 4. Live Sensor Context & Foreground App
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                ) {
                    Text(
                        text = "FOREGROUND SENSORS",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (foregroundApp.isNotBlank()) "FG    $foregroundApp" else "FG    (home / desktop)",
                            style = CompanionType.MonoSmall.copy(
                                color = if (foregroundApp.isNotBlank()) CompanionColor.Text else CompanionColor.TextMute,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("device.foreground"),
                        )
                    }
                    if (lastAudit.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = lastAudit,
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("device.audit"),
                        )
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Md))

                // 5. Phone Superpowers (Agent Capability Catalog)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PHONE SUPERPOWERS",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                        )
                        Text(
                            text = if (isArmed) "READY" else "STANDBY",
                            style = CompanionType.MonoSmall.copy(
                                color = if (isArmed) CompanionColor.Signal else CompanionColor.TextDim,
                                fontSize = 9.sp,
                            ),
                        )
                    }
                    Spacer(Modifier.height(CompanionSpace.Sm))
                    val superpowers = listOf(
                        "📸 Snapshot" to "a11y tree & screenshot",
                        "👆 Click & Swipe" to "human touches & gestures",
                        "⌨ Type & Press" to "input injection & hardware keys",
                        "📱 Open App" to "app navigation & recents",
                        "🔔 Shade Stream" to "push alerts & wake agent",
                        "🛡 Protection" to "banking & privacy shields",
                    )
                    superpowers.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                        ) {
                            pair.forEach { (name, desc) ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                                        .background(CompanionColor.Void)
                                        .padding(6.dp),
                                ) {
                                    Text(
                                        text = name,
                                        style = CompanionType.MonoSmall.copy(
                                            color = CompanionColor.Signal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                        ),
                                    )
                                    Text(
                                        text = desc,
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute, fontSize = 9.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(CompanionSpace.Md))

                // 6. Autonomous Safety Policies
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .background(CompanionColor.VoidElevated)
                        .padding(CompanionSpace.Md),
                ) {
                    Text(
                        text = "AUTONOMOUS & SENSING POLICIES",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 10.sp),
                    )
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
                }

                Spacer(Modifier.height(CompanionSpace.Lg))

                Text(
                    text = if (nlsBound) "NLS      on" else "NLS      off",
                    style = CompanionType.Mono.copy(
                        color = if (nlsBound) CompanionColor.Signal else CompanionColor.Warn,
                    ),
                    modifier = Modifier.testTag("device.nls"),
                )
                Text(
                    text = if (notifStreamEnabled) "STREAM   on" else "STREAM   off",
                    style = CompanionType.Mono.copy(
                        color = if (notifStreamEnabled) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                    modifier = Modifier
                        .testTag("device.stream")
                        .clickable(onClick = onToggleNotifStream)
                        .padding(vertical = CompanionSpace.Xs),
                )
                if (streamMuteDefaults > 0) {
                    Text(
                        text = "STREAM MUTE  $streamMuteDefaults built-in (Telegram)",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        modifier = Modifier.testTag("device.stream.mute"),
                    )
                }
                if (notifStreamEnabled) {
                    val sinkHost = notifStreamOrigin.orEmpty().ifBlank { "(sticky host)" }
                    val sinkProfile = notifStreamProfile.orEmpty().ifBlank { "(sticky profile)" }
                    Text(
                        text = "sink → $sinkHost · $sinkProfile",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("device.stream.sink"),
                    )
                    if (pairedHosts.isNotEmpty()) {
                        Spacer(Modifier.height(CompanionSpace.Xs))
                        Text(text = "target gateway", style = CompanionType.MonoSmall)
                        pairedHosts.forEach { host ->
                            val selected = notifStreamOrigin != null && host == notifStreamOrigin
                            Text(
                                text = if (selected) "● $host" else "○ $host",
                                style = CompanionType.MonoSmall.copy(
                                    color = if (selected) CompanionColor.Signal else CompanionColor.TextMute,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickStreamOrigin(host) }
                                    .padding(vertical = CompanionSpace.Xs)
                                    .testTag("device.stream.origin"),
                            )
                        }
                    }
                    if (profilesForStream.isNotEmpty()) {
                        Text(text = "target profile", style = CompanionType.MonoSmall)
                        profilesForStream.forEach { pid ->
                            val selected = pid == notifStreamProfile
                            Text(
                                text = if (selected) "● $pid" else "○ $pid",
                                style = CompanionType.MonoSmall.copy(
                                    color = if (selected) CompanionColor.Signal else CompanionColor.TextMute,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickStreamProfile(pid) }
                                    .padding(vertical = CompanionSpace.Xs)
                                    .testTag("device.stream.profile"),
                            )
                        }
                    }
                    Text(
                        text = "exposes non-muted shade content to the chosen host · Telegram muted",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                        modifier = Modifier.testTag("device.stream.warn"),
                    )
                }
                Spacer(Modifier.height(CompanionSpace.Sm))
                if (!nlsBound) {
                    Action("ENABLE NLS", "device.nls.enable", onEnableNls)
                    Spacer(Modifier.height(CompanionSpace.Xs))
                    Text(
                        text = "Settings → Notification access → Hermes Companion",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        modifier = Modifier.testTag("device.nls.hint"),
                    )
                    Spacer(Modifier.height(CompanionSpace.Sm))
                }

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
            style = CompanionType.Mono,
            modifier = Modifier.testTag("device.protected.count"),
        )
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(
            text = if (custom.isEmpty()) {
                "no built-in denylist · hands can touch any app until you add rules"
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
