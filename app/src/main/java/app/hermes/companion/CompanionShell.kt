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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import app.hermes.companion.chat.ChatScreen
import app.hermes.companion.connect.ConnectScreen
import app.hermes.companion.device.DeviceScreen
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HudDot
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.gateway.GatewayScreen
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.HudState
import app.hermes.companion.model.SessionRef
import app.hermes.companion.profiles.ProfilesScreen
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
        Header(state, inChat, onCloseChat)
        Hairline()
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
                MainTab.PROFILES -> ProfilesScreen(
                    profiles = state.profiles,
                    activeId = state.activeProfileId,
                    onSelect = onSelectProfile,
                    modifier = body,
                )
                MainTab.GATEWAY -> GatewayScreen(
                    status = state.status,
                    hud = state.hud,
                    ntfyTopic = state.ntfyTopic,
                    stayConnected = state.stayConnected,
                    onNtfyTopicChange = onNtfyTopicChange,
                    onSaveNtfy = onSaveNtfy,
                    onToggleStay = onToggleStay,
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
                    onPair = onPair,
                    onCancel = onCancelPair,
                    onRevoke = onRevokePair,
                    onArm = onArm,
                    onDisarm = onDisarm,
                    onEnableA11y = onEnableA11y,
                    onEnableOverlay = onEnableOverlay,
                    onEnableNotify = onEnableNotify,
                    modifier = body,
                )
            }
            Hairline()
            NavBar(state.tab, onTab)
        }
    }
}

@Composable
private fun Header(state: CompanionState, inChat: Boolean, onCloseChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        val active = state.activeProfile
        if (active != null) {
            ProfileGlyph(code = active.glyph, selected = true)
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
            .padding(vertical = CompanionSpace.Md),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem("THREADS", MainTab.THREADS, tab, onTab)
        NavItem("PROFILES", MainTab.PROFILES, tab, onTab)
        NavItem("GATEWAY", MainTab.GATEWAY, tab, onTab)
        NavItem("DEVICE", MainTab.DEVICE, tab, onTab)
    }
}

@Composable
private fun NavItem(label: String, value: MainTab, current: MainTab, onTab: (MainTab) -> Unit) {
    val selected = current == value
    Text(
        text = label,
        style = CompanionType.Mono.copy(
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

@Composable
private fun Placeholder(label: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(CompanionSpace.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = CompanionType.Mono)
        Spacer(Modifier)
        Text(text = "later slice", style = CompanionType.MonoSmall)
    }
}
