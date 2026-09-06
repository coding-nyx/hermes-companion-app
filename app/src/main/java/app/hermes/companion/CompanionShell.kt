package app.hermes.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.profiles.ProfileBottomSheet
import app.hermes.companion.settings.ModelBottomSheet
import app.hermes.companion.settings.SettingsBottomSheet
import app.hermes.companion.chat.ChatScreen
import app.hermes.companion.connect.ConnectScreen
import app.hermes.companion.console.ConsoleScreen
import app.hermes.companion.device.DeviceScreen
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HudDot
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.domain.NotificationStreamPolicy
import app.hermes.companion.gateway.GatewayScreen
import androidx.compose.material3.ExperimentalMaterial3Api
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.HudState
import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.SessionRef
import app.hermes.companion.rooms.RoomCreateSheet
import app.hermes.companion.profiles.ProfilesScreen
import app.hermes.companion.reminders.RemindersScreen
import app.hermes.companion.review.CodeReviewScreen
import app.hermes.companion.threads.ThreadsScreen

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompanionShell(
    state: CompanionState,
    onOriginChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onConnect: () -> Unit,
    onSelectConnectGateway: (GatewayChoice) -> Unit = {},
    onForgetConnectGateway: (GatewayChoice) -> Unit = {},
    onSelectProfile: (String) -> Unit,
    onTab: (MainTab) -> Unit,
    onOpenSession: (SessionRef) -> Unit,
    onNewThread: () -> Unit,
    onRetrySessions: () -> Unit = {},
    onRequestDelete: (SessionRef) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onCloseChat: () -> Unit,
    onOpenRoom: (RoomRef) -> Unit = {},
    onNewRoom: () -> Unit = {},
    onCreateRoom: (title: String, participants: List<String>, maxRounds: Int) -> Unit = { _, _, _ -> },
    onDismissRoomCreate: () -> Unit = {},
    onDeleteRoom: (RoomRef) -> Unit = {},
    onMention: (String) -> Unit = {},
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String) -> Unit,
    onLoadOlder: () -> Unit = {},
    onRewind: (ChatMessage) -> Unit = {},
    onCancelRewind: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onToggleVoiceStream: () -> Unit = {},
    onPair: () -> Unit = {},
    onCancelPair: () -> Unit = {},
    onRevokePair: () -> Unit = {},
    onRepairPair: () -> Unit = {},
    onApprovePair: () -> Unit = {},
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onEnableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    onEnableNls: () -> Unit = {},
    onToggleNotifStream: () -> Unit = {},
    onPickStreamOrigin: (String) -> Unit = {},
    onPickStreamProfile: (String) -> Unit = {},
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
    onRenameDevice: (String) -> Unit = {},
    onConfirmDeepLink: () -> Unit = {},
    onDismissDeepLink: () -> Unit = {},
    onExecuteTerminal: (String) -> Unit = {},
    onClearTerminal: () -> Unit = {},
    onRefreshGit: () -> Unit = {},
    onSelectGitFile: (String) -> Unit = {},
    onCloseGitDiff: () -> Unit = {},
    onStageGitFile: (String, Boolean) -> Unit = { _, _ -> },
    onCommitGit: (String) -> Unit = {},
    onRefreshCron: () -> Unit = {},
    onTriggerCron: (String) -> Unit = {},
    onToggleCron: (String, Boolean) -> Unit = { _, _ -> },
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onRefreshModels: () -> Unit = {},
    onSelectGateway: (SavedGateway) -> Unit = {},
    onAddGateway: (String, String) -> Unit = { _, _ -> },
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {},
    onToggleAttach: () -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onPickCamera: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onOpenMedia: (app.hermes.companion.model.ChatBlock) -> Unit = {},
    onFetchMedia: suspend (String) -> ByteArray? = { null },
) {
    if (state.origin == null) {
        ConnectScreen(
            origin = state.originInput,
            loading = state.loading,
            error = state.error,
            onOriginChange = onOriginChange,
            onConnect = onConnect,
            authRequired = state.authRequired,
            username = state.username,
            password = state.password,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            gateways = state.connectChoices,
            onSelectGateway = onSelectConnectGateway,
            onForgetGateway = onForgetConnectGateway,
        )
        return
    }

    val inChat = state.openSessionId != null || state.inRoom
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = inChat && !imeVisible, onBack = onCloseChat)

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .statusBarsPadding()
            .displayCutoutPadding()
            // Tap on empty chrome drops focus (and with it the IME). Clickable children win first.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        var showProfileSheet by rememberSaveable { mutableStateOf(false) }
        var showModelSheet by rememberSaveable { mutableStateOf(false) }
        var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(showModelSheet, showSettingsSheet) {
            if ((showModelSheet || showSettingsSheet) && state.modelCatalog == null) {
                onRefreshModels()
            }
        }
        Header(
            state = state,
            inChat = inChat,
            onCloseChat = onCloseChat,
            onProfileTap = { showProfileSheet = true },
            onOpenSettings = { showSettingsSheet = true },
        )
        Hairline()
        if (showProfileSheet) {
            ProfileBottomSheet(
                profiles = state.profiles,
                activeProfileId = state.activeProfileId,
                onSelect = { id ->
                    showProfileSheet = false
                    onSelectProfile(id)
                },
                onOpenAll = {
                    showProfileSheet = false
                    if (inChat) onCloseChat()
                    onTab(MainTab.PROFILES)
                },
                onDismiss = { showProfileSheet = false },
            )
        }
        if (showModelSheet) {
            ModelBottomSheet(
                modelCatalog = state.modelCatalog,
                currentModel = state.modelOverride,
                profiles = state.profiles,
                onSelectModel = { model, provider ->
                    onSwitchModel(model, provider)
                },
                onDismiss = { showModelSheet = false },
            )
        }
        if (state.roomCreateOpen) {
            RoomCreateSheet(
                profiles = state.profiles,
                onCreate = onCreateRoom,
                onDismiss = onDismissRoomCreate,
            )
        }
        if (showSettingsSheet) {
            SettingsBottomSheet(
                state = state,
                onSelectProfile = onSelectProfile,
                onSwitchModel = onSwitchModel,
                onOpenModelPicker = {
                    showSettingsSheet = false
                    showModelSheet = true
                },
                onTab = { tab ->
                    showSettingsSheet = false
                    if (inChat) onCloseChat()
                    onTab(tab)
                },
                onToggleStay = onToggleStay,
                onNtfyTopicChange = onNtfyTopicChange,
                onSaveNtfy = onSaveNtfy,
                onDisconnect = onDisconnect,
                onDismiss = { showSettingsSheet = false },
            )
        }
        state.pendingDeepLink?.let { req ->
            DeepLinkStrip(req, onConfirmDeepLink, onDismissDeepLink)
            Hairline()
        }
        // Chat pads for the IME itself; every other tab gets it here so inputs ride above the keyboard.
        val body = Modifier.weight(1f).then(if (inChat) Modifier else Modifier.imePadding())
        if (inChat) {
            ChatScreen(
                messages = state.messages,
                draft = state.draft,
                streaming = state.streaming,
                error = state.error,
                approval = state.approval,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onInterrupt = onInterrupt,
                onApproval = onApproval,
                hasMoreOlder = state.historyHasMore,
                loadingOlder = state.historyLoading,
                onLoadOlder = onLoadOlder,
                rewindTargetId = state.rewindTargetId,
                onRewind = onRewind,
                onCancelRewind = onCancelRewind,
                isListeningVoice = state.isListeningVoice,
                onVoiceClick = onVoiceClick,
                loading = state.transcriptLoading,
                historySource = state.historySource,
                onRetryHistory = { state.openSession?.let(onOpenSession) },
                modelOverride = state.modelOverride,
                modelCatalog = state.modelCatalog,
                onSwitchModel = onSwitchModel,
                onOpenModelPicker = { showModelSheet = true },
                pendingAttachments = state.pendingAttachments,
                attachOpen = state.attachOpen,
                onToggleAttach = onToggleAttach,
                onPickPhoto = onPickPhoto,
                onPickCamera = onPickCamera,
                onPickVideo = onPickVideo,
                onPickFile = onPickFile,
                onRemoveAttachment = onRemoveAttachment,
                onOpenMedia = onOpenMedia,
                onFetchMedia = onFetchMedia,
                threadId = state.openRoomId ?: state.openSessionId,
                participants = state.openRoom?.participants.orEmpty(),
                pendingSpeaker = state.roomSpeaking,
                onMention = onMention,
                modifier = body,
            )
        } else {
            when (state.tab) {
                MainTab.THREADS -> ThreadsScreen(
                    sessions = state.visibleSessions,
                    loading = state.sessionsLoading,
                    error = state.error,
                    activeProfileId = state.activeProfileId,
                    pendingDelete = state.pendingDelete,
                    rooms = state.rooms,
                    roomsError = state.roomsError,
                    onOpenRoom = onOpenRoom,
                    onNewRoom = onNewRoom,
                    onDeleteRoom = onDeleteRoom,
                    onOpen = onOpenSession,
                    onNew = onNewThread,
                    onRetry = onRetrySessions,
                    onDeleteRequest = onRequestDelete,
                    onConfirmDelete = onConfirmDelete,
                    onCancelDelete = onCancelDelete,
                    modifier = body,
                )
                MainTab.CONSOLE -> ConsoleScreen(
                    logs = state.terminalLogs,
                    isExecuting = state.terminalExecuting,
                    onExecute = onExecuteTerminal,
                    onClear = onClearTerminal,
                    modifier = body,
                )
                MainTab.REVIEW -> CodeReviewScreen(
                    status = state.gitStatus,
                    diff = state.gitDiff,
                    selectedFile = state.gitSelectedFile,
                    isLoading = state.gitLoading,
                    onRefresh = onRefreshGit,
                    onSelectFile = onSelectGitFile,
                    onCloseDiff = onCloseGitDiff,
                    onStageFile = onStageGitFile,
                    onCommit = onCommitGit,
                    modifier = body,
                )
                MainTab.REMINDERS -> RemindersScreen(
                    jobs = state.cronJobs,
                    isLoading = state.cronLoading,
                    onRefresh = onRefreshCron,
                    onTriggerJob = onTriggerCron,
                    onToggleJob = onToggleCron,
                    modifier = body,
                )
                MainTab.PROFILES -> ProfilesScreen(
                    profiles = state.profiles,
                    activeId = state.activeProfileId,
                    onSelect = onSelectProfile,
                    modifier = body,
                )
                MainTab.GATEWAY -> GatewayScreen(
                    status = state.status,
                    hud = state.hud,
                    hostMetrics = state.hostMetrics,
                    modelCatalog = state.modelCatalog,
                    modelOverride = state.modelOverride,
                    savedGateways = state.savedGateways,
                    hostHealth = state.hostHealth,
                    updateStatus = state.updateStatus,
                    ntfyTopic = state.ntfyTopic,
                    stayConnected = state.stayConnected,
                    onNtfyTopicChange = onNtfyTopicChange,
                    onSaveNtfy = onSaveNtfy,
                    onToggleStay = onToggleStay,
                    onSwitchModel = onSwitchModel,
                    onOpenModelPicker = { showModelSheet = true },
                    onSelectGateway = onSelectGateway,
                    onAddGateway = onAddGateway,
                    onCheckUpdate = onCheckUpdate,
                    onApplyUpdate = onApplyUpdate,
                    hostLoading = state.hostLoading,
                    modelLoading = state.modelLoading,
                    updateLoading = state.updateLoading,
                    modifier = body,
                )
                MainTab.DEVICE -> DeviceScreen(
                    phase = state.pairingPhase,
                    code = state.pairingCode,
                    deviceId = state.deviceId,
                    deviceLabel = state.deviceLabel,
                    deviceProfileId = state.deviceProfileId,
                    error = state.error,
                    laneOpen = state.deviceLane,
                    a11yBound = state.a11yBound,
                    overlayGranted = state.overlayGranted,
                    notifyGranted = state.notifyGranted,
                    foregroundApp = state.foregroundApp,
                    lastAudit = state.lastAudit,
                    arm = state.arm,
                    awakeOnVoice = state.awakeOnVoice,
                    lockedAccess = state.lockedAccess,
                    biometricLock = state.biometricLock,
                    onPair = onPair,
                    onRepair = onRepairPair,
                    onApprove = onApprovePair,
                    onCancel = onCancelPair,
                    onRevoke = onRevokePair,
                    onArm = onArm,
                    onDisarm = onDisarm,
                    onEnableA11y = onEnableA11y,
                    onEnableOverlay = onEnableOverlay,
                    onEnableNotify = onEnableNotify,
                    onEnableNls = onEnableNls,
                    onToggleNotifStream = onToggleNotifStream,
                    onPickStreamOrigin = onPickStreamOrigin,
                    onPickStreamProfile = onPickStreamProfile,
                    nlsBound = state.nlsBound,
                    notifStreamEnabled = state.notifStreamEnabled,
                    notifStreamOrigin = state.notifStreamOrigin,
                    notifStreamProfile = state.notifStreamProfile,
                    pairedHosts = state.pairedHosts,
                    profilesForStream = state.profiles.map { it.id },
                    onToggleAwakeOnVoice = onToggleAwakeOnVoice,
                    onToggleLockedAccess = onToggleLockedAccess,
                    onToggleBiometricLock = onToggleBiometricLock,
                    protectedCustom = state.protectedCustom,
                    protectedDefaults = DeviceLanePolicy.PROTECTED_PACKAGES.size,
                    streamMuteDefaults = NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES.size,
                    protectedError = state.protectedError,
                    onAddProtected = onAddProtected,
                    onRemoveProtected = onRemoveProtected,
                    onRenameDevice = onRenameDevice,
                    modifier = body,
                )
            }
        }
        // The bar gives its height to the keyboard while typing (A18.4 / A18.7).
        if (!imeVisible) {
            Hairline()
            NavBar(
                state = state,
                inChat = inChat,
                onTab = { tab ->
                    if (inChat && tab != MainTab.THREADS) {
                        onCloseChat()
                    }
                    onTab(tab)
                },
                onToggleVoiceStream = onToggleVoiceStream,
            )
        }
    }
}

/** Inline profile switcher dropped from the header glyph: one chip per profile, `ALL ▸` opens the tab. */
@Composable
private fun ProfilePicker(state: CompanionState, onSelect: (String) -> Unit, onOpenAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("header.profile.picker"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        state.profiles.forEach { profile ->
            val selected = profile.id == state.activeProfileId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
                modifier = Modifier
                    .testTag("header.profile.${profile.id}")
                    .clickable { onSelect(profile.id) }
                    .padding(vertical = CompanionSpace.Xs),
            ) {
                ProfileGlyph(code = profile.glyph, selected = selected)
                Text(
                    text = profile.displayName,
                    style = CompanionType.MonoSmall.copy(
                        color = if (selected) CompanionColor.Signal else CompanionColor.TextDim,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "ALL ▸",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("header.profile.all")
                .clickable(onClick = onOpenAll)
                .padding(CompanionSpace.Xs),
        )
    }
}

/** External deep link wants to switch profile / open a session. Nothing moves until the user says so. */
@Composable
private fun DeepLinkStrip(req: DeepLinkRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("deeplink.strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = if (req.origin.isBlank()) "open ${req.sessionId} · ${req.profileId}?"
            else "open ${req.sessionId} · ${req.profileId} @ ${req.origin.substringAfter("://")}?",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "OPEN",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier.testTag("deeplink.open").clickable(onClick = onConfirm).padding(CompanionSpace.Xs),
        )
        Text(
            text = "DISMISS",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            modifier = Modifier.testTag("deeplink.dismiss").clickable(onClick = onDismiss).padding(CompanionSpace.Xs),
        )
    }
}

@Composable
private fun Header(
    state: CompanionState,
    inChat: Boolean,
    onCloseChat: () -> Unit,
    onProfileTap: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        val active = state.activeProfile
        if (active != null) {
            ProfileGlyph(
                code = active.glyph,
                selected = true,
                onClick = onProfileTap,
                modifier = Modifier.testTag("header.profile"),
            )
        }
        if (inChat) {
            Text(
                text = "◂",
                style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .testTag("chat.back")
                    .clickable(onClick = onCloseChat)
                    .padding(end = CompanionSpace.Sm),
            )
        }
        val hostPrefix = if (inChat || state.hostName.isBlank()) "" else "${state.hostName} · "
        Text(
            text = hostPrefix + when {
                inChat -> state.openRoom?.let { r -> "${r.title} · " + r.participants.joinToString(" ") { it.glyph } }
                    ?: state.openSession?.title ?: "chat"
                state.tab == MainTab.THREADS -> "threads"
                state.tab == MainTab.CONSOLE -> "console"
                state.tab == MainTab.REVIEW -> "code review"
                state.tab == MainTab.REMINDERS -> "cron & reminders"
                state.tab == MainTab.PROFILES -> "profiles"
                state.tab == MainTab.GATEWAY -> "gateway"
                else -> "device"
            },
            style = CompanionType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HudGlyph("GW", state.hud.gateway)
        HudGlyph("TG", state.hud.telegram)
        HudGlyph("DC", state.hud.discord)
        HudGlyph("API", state.hud.api)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onOpenSettings)
                .testTag("header.settings"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚙",
                style = CompanionType.Mono.copy(color = CompanionColor.Signal, fontSize = 16.sp),
            )
        }
    }
}

@Composable
private fun HudGlyph(label: String, state: HudState) {
    HudDot(
        label = label,
        on = state == HudState.ON,
        degraded = state == HudState.DEGRADED,
        modifier = Modifier.testTag("hud.${label.lowercase()}"),
    )
}

@Composable
private fun NavBar(
    state: CompanionState,
    inChat: Boolean,
    onTab: (MainTab) -> Unit,
    onToggleVoiceStream: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .navigationBarsPadding()
            .height(56.dp)
            .padding(horizontal = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavGlyphItem(
                glyph = "▤",
                label = "chat",
                tab = MainTab.THREADS,
                isSelected = (state.tab == MainTab.THREADS && !inChat) || inChat,
                hasBadge = state.sessions.isNotEmpty(),
                badgeColor = CompanionColor.Signal,
                onTab = onTab,
            )
            NavGlyphItem(
                glyph = ">_",
                label = "term",
                tab = MainTab.CONSOLE,
                isSelected = state.tab == MainTab.CONSOLE && !inChat,
                onTab = onTab,
            )
            NavGlyphItem(
                glyph = "±",
                label = "diff",
                tab = MainTab.REVIEW,
                isSelected = state.tab == MainTab.REVIEW && !inChat,
                onTab = onTab,
            )
            NavGlyphItem(
                glyph = "◷",
                label = "cron",
                tab = MainTab.REMINDERS,
                isSelected = state.tab == MainTab.REMINDERS && !inChat,
                onTab = onTab,
            )
            NavGlyphItem(
                glyph = "⌂",
                label = "host",
                tab = MainTab.GATEWAY,
                isSelected = state.tab == MainTab.GATEWAY && !inChat,
                hasBadge = state.hud.gateway != HudState.ON,
                badgeColor = CompanionColor.Warn,
                onTab = onTab,
            )
            NavGlyphItem(
                glyph = "✋",
                label = "hands",
                tab = MainTab.DEVICE,
                isSelected = state.tab == MainTab.DEVICE && !inChat,
                hasBadge = state.arm != DeviceArm.DISARMED,
                badgeColor = CompanionColor.Warn,
                onTab = onTab,
            )
        }

        // Zone 3: Live Conversational Stream Toggle
        VoiceStreamButton(
            state = state.voiceStreamState,
            onClick = onToggleVoiceStream,
        )
    }
}

@Composable
private fun NavGlyphItem(
    glyph: String,
    label: String,
    tab: MainTab,
    isSelected: Boolean,
    hasBadge: Boolean = false,
    badgeColor: Color = CompanionColor.Signal,
    onTab: (MainTab) -> Unit,
) {
    val fg = if (isSelected) CompanionColor.Signal else CompanionColor.TextMute
    Column(
        modifier = Modifier
            .testTag("nav.${tab.name.lowercase()}")
            .clickable { onTab(tab) }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Text(
                text = glyph,
                style = CompanionType.Mono.copy(
                    color = fg,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
            )
            if (hasBadge) {
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(4.dp)
                        .background(badgeColor, CircleShape),
                )
            }
        }
        Text(
            text = label,
            style = CompanionType.MonoSmall.copy(
                color = fg,
                fontSize = 9.sp,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(2.dp)
                .background(if (isSelected) CompanionColor.Signal else Color.Transparent),
        )
    }
}

@Composable
private fun VoiceStreamButton(
    state: VoiceStreamState,
    onClick: () -> Unit,
) {
    val isActive = state != VoiceStreamState.IDLE
    val borderColor = if (isActive) CompanionColor.Signal else CompanionColor.LineStrong
    val bgColor = if (isActive) CompanionColor.SignalDim else CompanionColor.Void
    val iconColor = if (isActive) CompanionColor.Signal else CompanionColor.TextDim

    val iconText = when (state) {
        VoiceStreamState.LISTENING -> "ılı."
        VoiceStreamState.SPEAKING -> "🔊"
        VoiceStreamState.THINKING -> "◐"
        VoiceStreamState.IDLE -> "🎙"
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .border(CompanionSpace.Hairline, borderColor)
            .background(bgColor)
            .testTag("nav.stream")
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = iconText,
            style = CompanionType.Mono.copy(color = iconColor, fontSize = 13.sp),
        )
    }
}
