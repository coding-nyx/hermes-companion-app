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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.hermes.companion.chat.ChatScreen
import app.hermes.companion.connect.ConnectScreen
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HudDot
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.model.SessionRef
import app.hermes.companion.profiles.ProfilesScreen
import app.hermes.companion.threads.ThreadsScreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionShell(
    state: CompanionState,
    onOriginChange: (String) -> Unit,
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
) {
    if (state.origin == null) {
        ConnectScreen(
            origin = state.originInput,
            loading = state.loading,
            error = state.error,
            onOriginChange = onOriginChange,
            onConnect = onConnect,
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
            .statusBarsPadding(),
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
                MainTab.GATEWAY, MainTab.DEVICE -> Placeholder(state.tab.name.lowercase(), body)
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
            modifier = Modifier.weight(1f),
        )
        HudDot(label = "GW", on = state.gatewayHello != null)
        HudDot(label = "TG", on = false)
        HudDot(label = "DC", on = false)
        HudDot(label = "API", on = false)
    }
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
        style = CompanionType.MonoSmall.copy(
            color = if (selected) CompanionColor.Signal else CompanionColor.TextMute,
        ),
        modifier = Modifier
            .testTag("nav.${value.name.lowercase()}")
            .clickable { onTab(value) }
            .padding(CompanionSpace.Sm),
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
