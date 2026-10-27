package app.hermes.companion

import app.hermes.companion.design.ActionText

import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.profiles.ProfileBottomSheet
import app.hermes.companion.settings.ModelBottomSheet
import app.hermes.companion.settings.SettingsBottomSheet
import app.hermes.companion.chat.ChatScreen
import app.hermes.companion.connect.ConnectScreen
import app.hermes.companion.console.AgentConsoleScreen
import app.hermes.companion.model.AgentSession
import app.hermes.companion.console.ConsoleScreen
import app.hermes.companion.device.DeviceScreen
import app.hermes.companion.domain.PairDeepLink
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.ThreadSort
import app.hermes.companion.domain.ThreadTime
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.design.StatusStrip
import app.hermes.companion.design.SwitchingPane
import app.hermes.companion.design.LiveDot
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
    botActions: BotModeActions = BotModeActions(),
    onTab: (MainTab) -> Unit,
    onOpenSession: (SessionRef) -> Unit,
    onNewThread: () -> Unit,
    onResumeLocalDraft: () -> Unit = {},
    onRenameSession: (String) -> Unit = {},
    onPinSession: () -> Unit = {},
    onArchiveSession: () -> Unit = {},
    onBranchSession: () -> Unit = {},
    onSessionContext: () -> Unit = {},
    onDismissSessionContext: () -> Unit = {},
    onSessionControls: () -> Unit = {},
    onDismissSessionControls: () -> Unit = {},
    onSetSessionSetting: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRedirectSession: (String) -> Unit = {},
    onNativeCommand: (String) -> Unit = {},
    onRetryMessage: (String) -> Unit = {},
    onRetrySessions: () -> Unit = {},
    onThreadSort: (ThreadSort) -> Unit = {},
    onToggleArchived: (Boolean) -> Unit = {},
    onRequestDelete: (SessionRef) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onCloseChat: () -> Unit,
    onOpenRoom: (RoomRef) -> Unit = {},
    onNewRoom: () -> Unit = {},
    onCreateRoom: (RoomCreateSpec) -> Unit = {},
    onRoomPause: () -> Unit = {},
    onRoomContinue: (Int) -> Unit = {},
    onRoomSummarize: () -> Unit = {},
    onRoomAddParticipant: (String) -> Unit = {},
    onRoomRemoveParticipant: (String) -> Unit = {},
    onRoomRename: (String) -> Unit = {},
    onLinkHost: (SavedGateway) -> Unit = {},
    onUnlinkPeer: (String) -> Unit = {},
    // Coding-agent sessions (P23)
    onAgentRefresh: () -> Unit = {},
    onAgentToggleNew: (Boolean) -> Unit = {},
    onAgentStart: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onAgentSubmit: (String) -> Unit = {},
    onAgentBrowse: (String, Boolean) -> Unit = { _, _ -> },
    onAgentApprove: (String) -> Unit = {},
    onAgentOpen: (AgentSession) -> Unit = {},
    onAgentClose: () -> Unit = {},
    onAgentInput: (String) -> Unit = {},
    onAgentSendText: (String, Boolean) -> Unit = { _, _ -> },
    onAgentKey: (String) -> Unit = {},
    onAgentKill: (AgentSession?) -> Unit = {},
    onAgentForget: (AgentSession) -> Unit = {},
    onAgentCols: (Int) -> Unit = {},
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
    onReadAloud: (ChatMessage) -> Unit = {},
    onSpeechBackend: (app.hermes.companion.model.SpeechBackend) -> Unit = {},
    onRefreshVoiceSettings: () -> Unit = {},
    onSaveVoiceSettings: (Map<String, String>) -> Unit = {},
    onVoicePacing: (Int) -> Unit = {},
    onLoadVoiceOptions: (String) -> Unit = {},
    onStopVoicePlayback: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onToggleVoiceStream: () -> Unit = {},
    onToggleVoiceSpeaker: () -> Unit = {},
    onToggleVoiceMicrophone: () -> Unit = {},
    onStopVoiceStream: () -> Unit = {},
    onReturnToVoiceConversation: () -> Unit = {},
    onPair: () -> Unit = {},
    onCancelPair: () -> Unit = {},
    onRevokePair: () -> Unit = {},
    onRepairPair: () -> Unit = {},
    onApprovePair: () -> Unit = {},
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onEnableA11y: () -> Unit = {},
    onDisableA11y: () -> Unit = {},
    onEnableOverlay: () -> Unit = {},
    onEnableNotify: () -> Unit = {},
    onEnableNls: () -> Unit = {},
    onToggleNotifStream: () -> Unit = {},
    onPickStreamOrigin: (String) -> Unit = {},
    onPickStreamProfile: (String) -> Unit = {},
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    onToggleKeepA11y: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onToggleAwakeOnVoice: () -> Unit = {},
    onToggleLockedAccess: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onAddProtected: (String) -> Unit = {},
    onRemoveProtected: (String) -> Unit = {},
    onRenameDevice: (String) -> Unit = {},
    onConfirmDeepLink: () -> Unit = {},
    onDismissDeepLink: () -> Unit = {},
    onConfirmPairLink: () -> Unit = {},
    onDismissPairLink: () -> Unit = {},
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
    onConnectionMode: (String) -> Unit = {},
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
    onDismissError: () -> Unit = {},
    onPairLink: (String, String, String) -> Unit = { _, _, _ -> },
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    if (state.origin == null) {
        // Parent takes the status bar so ConnectScreen's own inset padding doesn't double under the strip.
        Column(Modifier.fillMaxSize().background(CompanionColor.Void).statusBarsPadding()) {
        state.pendingPairLink?.let { req ->
            PairLinkStrip(req, onConfirmPairLink, onDismissPairLink)
        }
        ConnectScreen(
            origin = state.originInput,
            loading = state.loading,
            error = state.error,
            onOriginChange = onOriginChange,
            onConnect = onConnect,
            onPairLink = onPairLink,
            authRequired = state.authRequired,
            username = state.username,
            password = state.password,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            gateways = state.connectChoices,
            onSelectGateway = onSelectConnectGateway,
            onForgetGateway = onForgetConnectGateway,
            modifier = Modifier.weight(1f),
        )
        }
        return
    }

    val inChat = state.tab == MainTab.THREADS && !state.chatMinimized && (state.openSessionId != null || state.inRoom)
    val inAnyChat = inChat || (state.tab == MainTab.BOTS && state.botMode.selected != null)
    BackHandler(enabled = inChat && !imeVisible, onBack = onCloseChat)
    BackHandler(enabled = state.tab == MainTab.BOTS && state.botMode.selected != null && !imeVisible, onBack = botActions.closeGroup)
    BackHandler(enabled = !inChat && state.openAgentId != null && state.tab == MainTab.CONSOLE, onBack = onAgentClose)

    val compactTyping = imeVisible && androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .statusBarsPadding()
            .displayCutoutPadding()
            .then(if (inAnyChat) Modifier.navigationBarsPadding() else Modifier)
            .imePadding()
            // Tap on empty chrome drops focus (and with it the IME). Clickable children win first.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        var showProfileSheet by rememberSaveable { mutableStateOf(false) }
        var showHostSheet by rememberSaveable { mutableStateOf(false) }
        var showModelSheet by rememberSaveable { mutableStateOf(false) }
        var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
        var showComposerModels by remember(state.origin, state.activeProfileId, state.openSessionId) { mutableStateOf(false) }
        var composerSection by remember { mutableStateOf(app.hermes.companion.settings.ComposerSettingsSection.MODEL) }
        var showSessionSheet by remember(state.openSessionId) { mutableStateOf(false) }
        var showMoreSheet by rememberSaveable { mutableStateOf(false) }
        var showVoiceControls by remember { mutableStateOf(false) }
        var showVoiceStart by remember { mutableStateOf(false) }
        var pendingVoiceChange by remember { mutableStateOf<(() -> Unit)?>(null) }
        val openVoice: () -> Unit = {
            focusManager.clearFocus(); keyboardController?.hide()
            if (state.isVoiceStreamActive) showVoiceControls = true
            else onToggleVoiceStream()
        }
        fun changeConversation(action: () -> Unit) {
            if (state.isVoiceStreamActive) pendingVoiceChange = action else action()
        }
        LaunchedEffect(state.isVoiceStreamActive) { if (!state.isVoiceStreamActive) showVoiceControls = false }
        if (pendingVoiceChange != null) {
            androidx.compose.material3.AlertDialog(onDismissRequest = { pendingVoiceChange = null },
                title = { Text("End voice to switch conversations?") },
                text = { Text("Voice is attached to ${state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "this conversation" }}. Your conversation and draft stay saved.") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    val action = pendingVoiceChange; pendingVoiceChange = null
                    onStopVoiceStream(); action?.invoke()
                }) { Text("End voice and switch") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingVoiceChange = null }) { Text("Stay here") } })
        }
        if (showVoiceControls && state.isVoiceStreamActive) VoiceControlsSheet(state,
            onDismiss = { showVoiceControls = false }, onMute = onToggleVoiceMicrophone,
            onSpeaker = onToggleVoiceSpeaker, onInterrupt = onStopVoicePlayback, onStop = { showVoiceControls = false; onStopVoiceStream() },
            onConversation = { showVoiceControls = false; onReturnToVoiceConversation() })
        if (showVoiceStart) VoiceStartSheet(state, onDismiss = { showVoiceStart = false },
            onStart = { showVoiceStart = false; onToggleVoiceStream() },
            onChoose = { showVoiceStart = false; onTab(MainTab.THREADS) })

        LaunchedEffect(showModelSheet, showSettingsSheet) {
            if ((showModelSheet || showSettingsSheet) && state.modelCatalog == null) {
                onRefreshModels()
            }
        }
        if ((!compactTyping || !inChat) && state.tab != MainTab.BOTS) {
        Header(
            state = state,
            inChat = inChat,
            onCloseChat = onCloseChat,
            onOpenSettings = { showSettingsSheet = true },
            onSessionActions = { showSessionSheet = true },
            onNewRoom = { changeConversation(onNewRoom) }, onRefreshConversations = onRetrySessions,
            onNewChat = { changeConversation(onNewThread) },
            onHost = { showHostSheet = true }, onAgent = { showProfileSheet = true },
        )
        }
        if (showHostSheet) HostSwitchSheet(state,
            onSelect = { host -> showHostSheet = false; changeConversation { onSelectGateway(host) } },
            onManage = { showHostSheet = false; onTab(MainTab.GATEWAY) }, onDismiss = { showHostSheet = false })
        if (showSessionSheet && !state.inRoom) {
            state.openSession?.takeUnless { app.hermes.companion.domain.DraftThread.isDraft(it) }?.let { session ->
                app.hermes.companion.settings.SessionBottomSheet(
                    session, state.sessionActionsBusy, state.streaming,
                    onRenameSession, onPinSession, onArchiveSession, onBranchSession,
                    onDismiss = { showSessionSheet = false },
                    onContext = onSessionContext, onControls = onSessionControls,
                )
            }
        }
        state.sessionControls?.let { panel ->
            if (showComposerModels) {
                app.hermes.companion.settings.ComposerModelBottomSheet(panel, onSetSessionSetting, onSessionControls,
                    { showComposerModels = false; onDismissSessionControls() }, initialSection = composerSection)
            } else app.hermes.companion.settings.SessionControlsBottomSheet(panel, onSessionControls, onSetSessionSetting,
                onRedirectSession, onNativeCommand, onDismissSessionControls)
        }
        state.contextPanel?.let { panel ->
            app.hermes.companion.settings.SessionContextBottomSheet(panel, onSessionContext, onDismissSessionContext)
        }
        if (showMoreSheet) {
            NavigationMoreSheet(state, { tab ->
                onTab(tab)
            }, { showSettingsSheet = true }, { showMoreSheet = false })
        }
        if (showProfileSheet) {
            ProfileBottomSheet(
                profiles = state.chatProfiles,
                activeProfileId = state.activeProfileId,
                onSelect = { id ->
                    showProfileSheet = false
                    if (id != state.activeProfileId) changeConversation { onSelectProfile(id) }
                },
                onOpenAll = {
                    showProfileSheet = false
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
                loading = state.modelLoading,
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
                error = state.error,
                peers = state.peers,
                peerProfiles = state.peerProfiles,
                peersLoading = state.peersLoading,
                hostName = state.hostName,
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
                    onTab(tab)
                },
                onSpeechBackend = onSpeechBackend,
                onRefreshVoiceSettings = onRefreshVoiceSettings, onSaveVoiceSettings = onSaveVoiceSettings, onVoicePacing = onVoicePacing, onLoadVoiceOptions = onLoadVoiceOptions,
                onToggleStay = onToggleStay,
                onToggleKeepA11y = onToggleKeepA11y,
                onNtfyTopicChange = onNtfyTopicChange,
                onSaveNtfy = onSaveNtfy,
                onDisconnect = onDisconnect,
                onDismiss = { showSettingsSheet = false },
            )
        }
        state.pendingDeepLink?.let { req ->
            DeepLinkStrip(req, {
                if (WakeNavigationPolicy.requiresVoiceConfirmation(state, req)) changeConversation(onConfirmDeepLink)
                else onConfirmDeepLink()
            }, onDismissDeepLink)
            Hairline()
        }
        state.pendingPairLink?.let { req ->
            PairLinkStrip(req, onConfirmPairLink, onDismissPairLink)
            Hairline()
        }
        // Tabs without an error slot of their own surface host failures here (threads/chat/device have one).
        val ownsError = inChat || state.tab == MainTab.THREADS || state.tab == MainTab.DEVICE
        if (!state.error.isNullOrBlank() && !ownsError && !state.loading) {
            StatusStrip(
                text = state.error,
                actionLabel = "DISMISS",
                onAction = onDismissError,
                modifier = Modifier.testTag("shell.error"),
            )
            Hairline()
        }
        // Chat pads for the IME itself; every other tab gets it here so inputs ride above the keyboard.
        val body = Modifier.weight(1f).then(if (inChat) Modifier else Modifier.imePadding())
        if (state.loading) {
            // Host switch / reconnect: never paint the old host's threads under the new host's name.
            SwitchingPane(
                hostName = state.hostName,
                origin = state.originInput,
                modifier = body,
            )
        } else if (inChat) {
            ChatScreen(
                nativeCommands = if (state.nativeCommandsOwner?.let { it.origin == state.origin && it.profile == state.activeProfileId && it.session == state.openSessionId } == true) state.nativeCommands else emptyList(),
                messages = state.messages,
                draft = state.draft,
                streaming = state.streaming,
                submitting = state.submitting,
                creatingThread = state.creatingThread,
                onRetryMessage = onRetryMessage,
                error = state.error,
                approval = state.approval,
                approvalBusy = state.approval != null && state.approvalSubmittingRequestId == state.approval.requestId,
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
                onReadAloud = onReadAloud,
                readAloudMessageId = state.readAloudMessageId,
                onVoiceClick = onVoiceClick,
                onVoiceLongClick = openVoice,
                compactComposer = compactTyping,
                loading = state.transcriptLoading,
                historySource = state.historySource,
                onRetryHistory = { state.openSession?.let(onOpenSession) },
                modelOverride = state.modelOverride,
                modelCatalog = state.modelCatalog,
                onSwitchModel = onSwitchModel,
                onOpenModelPicker = {
                    if (app.hermes.companion.domain.DraftThread.isPersisted(state.openSessionId)) {
                        composerSection = app.hermes.companion.settings.ComposerSettingsSection.MODEL
                        showComposerModels = true
                        onSessionControls()
                    }
                },
                onOpenReasoningPicker = {
                    if (app.hermes.companion.domain.DraftThread.isPersisted(state.openSessionId)) {
                        composerSection = app.hermes.companion.settings.ComposerSettingsSection.REASONING
                        showComposerModels = true
                        onSessionControls()
                    }
                },
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
                profiles = state.chatProfiles,
                assistantName = state.openRoom?.title ?: state.chatAgentName,
                room = state.openRoom,
                roomCandidates = roomCandidates(state),
                onRoomPause = onRoomPause,
                onRoomContinue = onRoomContinue,
                onRoomSummarize = onRoomSummarize,
                onRoomAddParticipant = onRoomAddParticipant,
                onRoomRemoveParticipant = onRoomRemoveParticipant,
                onRoomRename = onRoomRename,
                onArm = onArm,
                onDisarm = onDisarm,
                modifier = body,
            )
        } else {
            when (state.tab) {
                MainTab.BOTS -> BotsScreen(state.botMode, state.hostName,
                    onRefresh = botActions.refresh, onOpenBot = { changeConversation { botActions.openBot(it) } },
                    onSaveBot = botActions.saveBot, onCreateGroup = botActions.createGroup,
                    onOpenGroup = botActions.openGroup, onCloseGroup = botActions.closeGroup,
                    onDraft = botActions.draft, onSend = botActions.send, onReply = botActions.reply,
                    onMention = botActions.mention, onRename = botActions.rename, onStop = botActions.stop,
                    onDisband = botActions.disband, onApprove = botActions.approve, onRetry = botActions.retry,
                    onRecover = botActions.recover, onSchedules = { onTab(MainTab.REMINDERS) },
                    onGateways = { onTab(MainTab.GATEWAY) }, modifier = body,
                    onHostSwitch = { showHostSheet = true }, onSettings = { showSettingsSheet = true },
                    onChats = { onTab(MainTab.THREADS) })
                MainTab.THREADS -> ThreadsScreen(
                    showToolbar = false,
                    sessions = state.visibleSessions,
                    displayTitle = state::chatTitle,
                    loading = state.sessionsLoading,
                    error = state.error,
                    activeProfileId = state.activeProfileId,
                    sort = state.threadSort,
                    onSort = onThreadSort,
                    showArchived = state.showArchived,
                    onToggleArchived = onToggleArchived,
                    onRefresh = onRetrySessions,
                    pendingDelete = state.pendingDelete,
                    rooms = state.rooms,
                    roomsAvailable = state.companionAvailability == app.hermes.companion.model.CompanionAvailability.AVAILABLE,
                    roomsLoading = state.roomsLoading,
                    roomsError = state.roomsError,
                    unreadRooms = state.unreadRooms,
                    onOpenRoom = { room -> changeConversation { onOpenRoom(room) } },
                    onNewRoom = { changeConversation(onNewRoom) },
                    onDeleteRoom = onDeleteRoom,
                    onOpen = { session ->
                        if (session.id == state.openSessionId && state.isVoiceStreamActive) onReturnToVoiceConversation()
                        else changeConversation { onOpenSession(session) }
                    },
                    onNew = { changeConversation(onNewThread) },
                    hasLocalDraft = state.hasLocalDraft,
                    onResumeLocalDraft = { changeConversation(onResumeLocalDraft) },
                    onRetry = onRetrySessions,
                    onDeleteRequest = onRequestDelete,
                    onConfirmDelete = onConfirmDelete,
                    onCancelDelete = onCancelDelete,
                    modifier = body,
                )
                MainTab.CONSOLE -> AgentConsoleScreen(
                    sessions = state.agentSessions,
                    tools = state.agentTools,
                    tmux = state.agentTmux,
                    defaultCwd = state.agentDefaultCwd,
                    sessionsLoading = state.agentSessionsLoading,
                    toolsLoading = state.agentToolsLoading,
                    starting = state.agentStarting,
                    newOpen = state.agentNewOpen,
                    openSession = state.openAgent,
                    pane = state.agentPane,
                    paneLoading = state.agentPaneLoading,
                    cols = state.agentCols,
                    input = state.agentInput,
                    error = state.agentError,
                    transcript = state.agentTranscript,
                    dirs = state.agentDirs,
                    dirsLoading = state.agentDirsLoading,
                    dirsError = state.agentDirsError,
                    onBrowse = onAgentBrowse,
                    onSubmit = onAgentSubmit,
                    onApprove = onAgentApprove,
                    onRefresh = onAgentRefresh,
                    onToggleNew = onAgentToggleNew,
                    onStart = onAgentStart,
                    onOpen = onAgentOpen,
                    onClose = onAgentClose,
                    onInput = onAgentInput,
                    onSendText = onAgentSendText,
                    onKey = onAgentKey,
                    onKill = onAgentKill,
                    onForget = onAgentForget,
                    onCols = onAgentCols,
                    quickShell = {
                        ConsoleScreen(
                            logs = state.terminalLogs,
                            isExecuting = state.terminalExecuting,
                            onExecute = onExecuteTerminal,
                            onClear = onClearTerminal,
                        )
                    },
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
                    onSelect = { id -> if (id != state.activeProfileId) changeConversation { onSelectProfile(id) } },
                    switching = state.sessionsLoading,
                    hostName = state.hostName,
                    modifier = body,
                )
                MainTab.GATEWAY -> GatewayScreen(
                    relayHealth = state.relayHealth, connectionModeBusy = state.connectionModeBusy,
                    connectionModeError = state.connectionModeError, onConnectionMode = onConnectionMode,
                    paired = state.pairingPhase == app.hermes.companion.model.PairingPhase.PAIRED,
                    status = state.status,
                    hud = state.hud,
                    nativeChatConnected = state.gatewayHello != null,
                    companionAvailability = state.companionAvailability,
                    notifyGranted = state.notifyGranted,
                    onEnableNotify = onEnableNotify,
                    phoneStreamEnabled = state.notifStreamEnabled,
                    onOpenChats = { onTab(MainTab.THREADS) },
                    onOpenDevice = { onTab(MainTab.DEVICE) },
                    nativeVoiceStatus = state.nativeVoiceStatus,
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
                    onSelectGateway = { host -> changeConversation { onSelectGateway(host) } },
                    onAddGateway = onAddGateway,
                    onCheckUpdate = onCheckUpdate,
                    onApplyUpdate = onApplyUpdate,
                    hostLoading = state.hostLoading,
                    modelLoading = state.modelLoading,
                    updateLoading = state.updateLoading,
                    switchingOrigin = if (state.loading) state.originInput else null,
                    peers = state.peers,
                    peersLoading = state.peersLoading,
                    peerLinking = state.peerLinking,
                    onLinkHost = onLinkHost,
                    onUnlinkPeer = onUnlinkPeer,
                    modifier = body,
                )
                MainTab.DEVICE -> DeviceScreen(
                    relayHealth = state.relayHealth, connectionModeBusy = state.connectionModeBusy,
                    connectionModeError = state.connectionModeError, onConnectionMode = onConnectionMode,
                    phase = state.pairingPhase,
                    code = state.pairingCode,
                    deviceId = state.deviceId,
                    deviceLabel = state.deviceLabel,
                    deviceProfileId = state.deviceProfileId,
                    error = state.error,
                    laneOpen = state.deviceLane,
                    a11yBound = state.a11yBound,
                    a11yListed = state.a11yListed,
                    a11yOffRequested = state.a11yOffRequested,
                    onDisableA11y = onDisableA11y,
                    overlayGranted = state.overlayGranted,
                    notifyGranted = state.notifyGranted,
                    foregroundApp = state.foregroundApp,
                    lastAudit = state.lastAudit,
                    arm = state.arm,
                    armPending = state.armPending,
                    automaticA11y = state.keepA11yAvailable,
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
        // The shell owns IME insets, keeping active voice controls above the keyboard.
        if (state.isVoiceStreamActive) VoiceDock(state, openVoice, onToggleVoiceMicrophone, onStopVoiceStream, compact = compactTyping)
        if (!imeVisible && !inAnyChat) {
            AppNavigationBar(state, inChat, onTab, { showMoreSheet = true }, openVoice, onDictate = {
                if (!state.inRoom && !state.loading && !state.creatingThread && state.openSessionId != null && state.activeProfileId != null) {
                    onReturnToVoiceConversation(); onVoiceClick()
                } else showVoiceStart = true
            })
        }
    }
}

/** Inline profile switcher dropped from the header glyph: one chip per profile, `ALL ▸` opens the tab. */
@Composable
private fun ProfilePicker(state: CompanionState, onSelect: (String) -> Unit, onOpenAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()

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
                    .heightIn(min = 48.dp).clickable { onSelect(profile.id) }
                    .padding(vertical = CompanionSpace.Xs),
            ) {
                ProfileGlyph(code = profile.glyph, selected = selected)
                Text(
                    text = profile.displayName,
                    style = CompanionType.Caption.copy(
                        color = if (selected) CompanionColor.Signal else CompanionColor.TextDim,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ActionText(
            text = "ALL ▸",
            style = CompanionType.Caption.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("header.profile.all")
                .heightIn(min = 48.dp).clickable(onClick = onOpenAll)
                .padding(CompanionSpace.Xs),
        )
    }
}

/** External deep link wants to switch profile / open a session. Nothing moves until the user says so. */
@Composable
private fun DeepLinkStrip(req: DeepLinkRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val stripShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
            .clip(stripShape)
            .border(CompanionSpace.Hairline, CompanionColor.Warn.copy(alpha = 0.5f), stripShape)

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("deeplink.strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = if (req.origin.isBlank()) "open ${req.sessionId} · ${req.profileId}?"
            else "open ${req.sessionId} · ${req.profileId} @ ${req.origin.substringAfter("://")}?",
            style = CompanionType.Caption.copy(color = CompanionColor.Warn),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActionText(
            text = "OPEN",
            style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag("deeplink.open").heightIn(min = 48.dp).clickable(onClick = onConfirm).padding(CompanionSpace.Xs),
        )
        ActionText(
            text = "DISMISS",
            style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
            modifier = Modifier.testTag("deeplink.dismiss").heightIn(min = 48.dp).clickable(onClick = onDismiss).padding(CompanionSpace.Xs),
        )
    }
}

/** External pair link: pairing gives that host a device-control lane, so the user must confirm who it is. */
@Composable
private fun PairLinkStrip(
    req: PairDeepLink,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stripShape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
            .clip(stripShape)
            .border(CompanionSpace.Hairline, CompanionColor.Warn.copy(alpha = 0.5f), stripShape)

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("pairlink.strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = "pair this phone with ${req.origin.substringAfter("://")} · ${req.profile}?",
            style = CompanionType.Caption.copy(color = CompanionColor.Warn),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActionText(
            text = "PAIR",
            style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag("pairlink.pair").heightIn(min = 48.dp).clickable(onClick = onConfirm).padding(CompanionSpace.Xs),
        )
        ActionText(
            text = "DISMISS",
            style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
            modifier = Modifier.testTag("pairlink.dismiss").heightIn(min = 48.dp).clickable(onClick = onDismiss).padding(CompanionSpace.Xs),
        )
    }
}

@Composable
private fun Header(
    state: CompanionState, inChat: Boolean, onCloseChat: () -> Unit,
    onOpenSettings: () -> Unit = {}, onSessionActions: () -> Unit = {},
    onNewRoom: () -> Unit = {}, onRefreshConversations: () -> Unit = {}, onNewChat: () -> Unit = {},
    onHost: () -> Unit = {}, onAgent: () -> Unit = {},
) {
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(inChat, state.tab) { options = false }
    val title = when {
        inChat -> state.openRoom?.title ?: state.openSession?.let(state::chatTitle) ?: "Chat"
        state.tab == MainTab.THREADS -> "Chats"
        state.tab == MainTab.CONSOLE -> state.openAgent?.let { it.title.ifBlank { it.id } } ?: "Coding agents"
        state.tab == MainTab.REVIEW -> "Code review"
        state.tab == MainTab.REMINDERS -> "Schedules"
        state.tab == MainTab.PROFILES -> "Agent profiles"
        state.tab == MainTab.GATEWAY -> "Gateway"
        else -> "Device"
    }
    val host = state.hostName.ifBlank { state.origin.orEmpty().substringAfter("://") }
    val profile = state.chatAgentName
    val subtitle = if (inChat && state.openSession?.let(DraftThread::isDraft) == true) "Draft · saved on first send" else "$host · $profile"
    app.hermes.companion.design.PageToolbar(title, subtitle,
        titleModifier = Modifier.testTag(if (inChat) "header.session.actions" else "context.host"),
        onTitleClick = if (!inChat) onHost else if (!state.inRoom && app.hermes.companion.domain.DraftThread.isPersisted(state.openSessionId)) onSessionActions else null,
        leading = if (!inChat) null else ({
            androidx.compose.material3.IconButton(onClick = onCloseChat, modifier = Modifier.testTag("chat.back").semantics { contentDescription = "Back to chats" }) {
                app.hermes.companion.design.ControlIcon(app.hermes.companion.design.ControlGlyph.BACK)
            }
        }), actions = {
            Box {
                androidx.compose.material3.IconButton(onClick = { options = true }, modifier = Modifier.testTag("header.options").semantics { contentDescription = "Page options" }) {
                    app.hermes.companion.design.ControlIcon(app.hermes.companion.design.ControlGlyph.MORE)
                }
                androidx.compose.material3.DropdownMenu(options, { options = false }, containerColor = CompanionColor.VoidElevated) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("New chat") }, onClick = { options = false; onNewChat() },
                        enabled = !state.loading && !state.creatingThread && state.activeProfileId != null, modifier = Modifier.testTag("header.newchat"))
                    if (state.tab == MainTab.THREADS && !inChat) {
                        if (state.companionAvailability == app.hermes.companion.model.CompanionAvailability.AVAILABLE)
                            androidx.compose.material3.DropdownMenuItem(text = { Text("New agent room") }, onClick = { options = false; onNewRoom() }, modifier = Modifier.testTag("threads.newroom"))
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Refresh conversations") }, onClick = { options = false; onRefreshConversations() }, modifier = Modifier.testTag("threads.refresh"))
                    }
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Switch host") }, onClick = { options = false; onHost() }, modifier = Modifier.testTag(if (inChat) "context.host" else "header.host"))
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Switch agent") }, onClick = { options = false; onAgent() }, modifier = Modifier.testTag("context.agent"))
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Settings") }, onClick = { options = false; onOpenSettings() }, modifier = Modifier.testTag("header.settings"))
                }
            }
        })
}

/** Profiles (local and on linked peers) that are not yet in the open room, as (participant id, label). */
internal fun roomCandidates(state: CompanionState): List<Pair<String, String>> {
    val room = state.openRoom ?: return emptyList()
    val present = room.participants.map { it.id }.toSet()
    val local = state.profiles.map { it.id to it.displayName.ifBlank { it.id } }
    val remote = state.peerProfiles.flatMap { (peer, profiles) -> profiles.map { "${it.id}@$peer" to "${it.displayName.ifBlank { it.id }} · $peer" } }
    return (local + remote).filter { it.first !in present }
}

/**
 * One mono line under the chat title: when the thread started, how much is in it, where it came
 * from, whether it has ended. Drafts say so — a new thread is not on the host until the first send.
 */
internal fun threadMeta(session: SessionRef?, nowMs: Long = System.currentTimeMillis()): String {
    if (session == null) return ""
    if (DraftThread.isDraft(session)) return "draft · saved on first send"
    val parts = mutableListOf<String>()
    val created = SessionLists.createdKey(session)
    if (created > 0L) parts += "started ${ThreadTime.relative(created, nowMs)}"
    if (session.messageCount > 0) parts += "${session.messageCount} msgs"
    if (session.source.isNotBlank()) parts += session.source.lowercase()
    if (session.archived) parts += "ARCHIVED" else if (session.ended) parts += "ENDED"
    return parts.joinToString(" · ")
}

/**
 * Operator-link state next to the HUD: `SYNC` (blinking) while the host or the profile's thread
 * list is being rebuilt, `LINK ↓` (warn) when the gateway socket is down and reconnecting, nothing when live.
 */
@Composable
private fun LinkPill(state: CompanionState) {
    val syncing = state.loading || state.sessionsLoading
    val down = !syncing && state.gatewayHello == null
    if (!syncing && !down) return
    val pillShape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(pillShape)

            .border(CompanionSpace.Hairline, if (syncing) CompanionColor.Signal else CompanionColor.Warn, pillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(if (syncing) "header.sync" else "header.linkdown"),
    ) {
        if (syncing) LiveDot(size = 5.dp) else LiveDot(size = 5.dp, color = CompanionColor.Warn)
        Text(
            text = if (syncing) "SYNC" else "LINK",
            style = CompanionType.Caption.copy(color = if (syncing) CompanionColor.Signal else CompanionColor.Warn),
        )
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
