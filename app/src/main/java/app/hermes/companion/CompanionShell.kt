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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
import app.hermes.companion.gateway.GatewayScreen
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.HudState
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef
import app.hermes.companion.profiles.ProfilesScreen
import app.hermes.companion.reminders.RemindersScreen
import app.hermes.companion.review.CodeReviewScreen
import app.hermes.companion.threads.ThreadsScreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionShell(
    state: CompanionState,
    onOriginChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onConnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onTab: (MainTab) -> Unit,
    onOpenSession: (SessionRef) -> Unit,
    onNewThread: () -> Unit,
    onCloseChat: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String) -> Unit,
    onLoadOlder: () -> Unit = {},
    onRewind: (ChatMessage) -> Unit = {},
    onCancelRewind: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onPair: () -> Unit = {},
    onCancelPair: () -> Unit = {},
    onRevokePair: () -> Unit = {},
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onEnableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
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
    onSelectGateway: (SavedGateway) -> Unit = {},
    onAddGateway: (String, String) -> Unit = { _, _ -> },
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {},
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
        )
        return
    }

    val inChat = state.openSessionId != null
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = inChat && !imeVisible, onBack = onCloseChat)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .statusBarsPadding()
            .displayCutoutPadding(),
    ) {
        // Header glyph is the profile switcher (A18.6): tap → inline picker under the header.
        var profilePicker by rememberSaveable { mutableStateOf(false) }
        Header(
            state = state,
            inChat = inChat,
            onCloseChat = onCloseChat,
            onProfileTap = { profilePicker = !profilePicker },
        )
        Hairline()
        if (profilePicker) {
            ProfilePicker(
                state = state,
                onSelect = { id ->
                    profilePicker = false
                    onSelectProfile(id)
                },
                onOpenAll = {
                    profilePicker = false
                    if (inChat) onCloseChat()
                    onTab(MainTab.PROFILES)
                },
            )
            Hairline()
        }
        state.pendingDeepLink?.let { req ->
            DeepLinkStrip(req, onConfirmDeepLink, onDismissDeepLink)
            Hairline()
        }
        val body = Modifier.weight(1f)
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
                modifier = body,
            )
        } else {
            when (state.tab) {
                MainTab.THREADS -> ThreadsScreen(
                    sessions = state.visibleSessions,
                    onOpen = onOpenSession,
                    onNew = onNewThread,
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
                    savedGateways = state.savedGateways,
                    updateStatus = state.updateStatus,
                    ntfyTopic = state.ntfyTopic,
                    stayConnected = state.stayConnected,
                    onNtfyTopicChange = onNtfyTopicChange,
                    onSaveNtfy = onSaveNtfy,
                    onToggleStay = onToggleStay,
                    onSwitchModel = onSwitchModel,
                    onSelectGateway = onSelectGateway,
                    onAddGateway = onAddGateway,
                    onCheckUpdate = onCheckUpdate,
                    onApplyUpdate = onApplyUpdate,
                    modifier = body,
                )
                MainTab.DEVICE -> DeviceScreen(
                    phase = state.pairingPhase,
                    code = state.pairingCode,
                    deviceId = state.deviceId,
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
                    onPair = onPair,
                    onCancel = onCancelPair,
                    onRevoke = onRevokePair,
                    onArm = onArm,
                    onDisarm = onDisarm,
                    onEnableA11y = onEnableA11y,
                    onEnableOverlay = onEnableOverlay,
                    onEnableNotify = onEnableNotify,
                    onToggleAwakeOnVoice = onToggleAwakeOnVoice,
                    onToggleLockedAccess = onToggleLockedAccess,
                    protectedCustom = state.protectedCustom,
                    protectedDefaults = DeviceLanePolicy.PROTECTED_PACKAGES.size,
                    protectedError = state.protectedError,
                    onAddProtected = onAddProtected,
                    onRemoveProtected = onRemoveProtected,
                    modifier = body,
                )
            }
            Hairline()
            NavBar(state.tab, onTab)
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
            text = "open ${req.sessionId} · ${req.profileId}?",
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
        Text(
            text = when {
                inChat -> state.openSession?.title ?: "chat"
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
private fun NavBar(tab: MainTab, onTab: (MainTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = CompanionSpace.Sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem("CHAT", MainTab.THREADS, tab, onTab)
        NavItem("TERM", MainTab.CONSOLE, tab, onTab)
        NavItem("DIFF", MainTab.REVIEW, tab, onTab)
        NavItem("CRON", MainTab.REMINDERS, tab, onTab)
        NavItem("HOST", MainTab.GATEWAY, tab, onTab)
        NavItem("HANDS", MainTab.DEVICE, tab, onTab)
    }
}

@Composable
private fun NavItem(label: String, value: MainTab, current: MainTab, onTab: (MainTab) -> Unit) {
    val selected = current == value
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(
            color = if (selected) CompanionColor.Signal else CompanionColor.TextMute,
        ),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier = Modifier
            .testTag("nav.${value.name.lowercase()}")
            .clickable { onTab(value) }
            .padding(horizontal = CompanionSpace.Xs, vertical = CompanionSpace.Sm),
    )
}
