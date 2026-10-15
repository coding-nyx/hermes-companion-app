package app.hermes.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.HudState

internal enum class NavigationDestination(val label: String, val tab: MainTab?) {
    CHATS("Chats", MainTab.THREADS), AGENTS("Agents", MainTab.CONSOLE),
    DEVICE("Device", MainTab.DEVICE), MORE("More", null),
}

internal fun navigationDestination(tab: MainTab, inChat: Boolean): NavigationDestination =
    if (inChat) NavigationDestination.CHATS else
        NavigationDestination.entries.firstOrNull { it.tab == tab } ?: NavigationDestination.MORE

@Composable
internal fun AppNavigationBar(state: CompanionState, inChat: Boolean, onTab: (MainTab) -> Unit,
    onMore: () -> Unit, onVoice: () -> Unit = {}, onDictate: () -> Unit = {}) {
    val selected = navigationDestination(state.tab, inChat)
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().background(CompanionColor.Void).navigationBarsPadding().testTag("navigation.bar")) {
        HorizontalDivider(color = CompanionColor.Line)
        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.Bottom) {
            // Keep Voice centered. At large text sizes the selected destination keeps its
            // readable label; the others retain their accessible names and 48dp targets.
            Row(Modifier.weight(2f), verticalAlignment = Alignment.Bottom) {
                NavigationDestination.entries.take(2).forEach { destination ->
                    NavigationItem(state, destination, selected, onTab, onMore,
                        Modifier.weight(if (largeText && destination == selected) 2f else 1f),
                        showLabel = !largeText || destination == selected)
                }
            }
            VoiceNavigationAction(state.isVoiceStreamActive, onVoice, Modifier.weight(1f), onDictate = onDictate, showLabel = !largeText)
            Row(Modifier.weight(2f), verticalAlignment = Alignment.Bottom) {
                NavigationDestination.entries.drop(2).forEach { destination ->
                    NavigationItem(state, destination, selected, onTab, onMore,
                        Modifier.weight(if (largeText && destination == selected) 2f else 1f),
                        showLabel = !largeText || destination == selected)
                }
            }
        }
        }
    }
}

@Composable
private fun NavigationItem(state: CompanionState, destination: NavigationDestination, selected: NavigationDestination,
    onTab: (MainTab) -> Unit, onMore: () -> Unit, modifier: Modifier, horizontal: Boolean = false, showLabel: Boolean = true) {
    val active = destination == selected
    val color = if (active) CompanionColor.Signal else CompanionColor.TextDim
    val status = when (destination) {
        NavigationDestination.CHATS -> if (state.visibleSessions.any { it.unread } || state.unreadRooms.isNotEmpty()) "Unread conversations" else null
        NavigationDestination.DEVICE -> if (state.arm != DeviceArm.DISARMED) "Hands armed" else null
        NavigationDestination.MORE -> if (state.hud.gateway == HudState.OFF) "Check host status" else null
        else -> null
    }
    val itemModifier = modifier.heightIn(min = if (horizontal) 56.dp else 64.dp)
        .testTag(destination.tab?.let { "nav.${it.name.lowercase()}" } ?: "nav.more")
        .clip(RoundedCornerShape(16.dp))
        .selectable(active, role = Role.Tab, onClick = { destination.tab?.let(onTab) ?: onMore() })
        .semantics { contentDescription = destination.label; if (status != null) stateDescription = status }
    val content: @Composable () -> Unit = {
        Box(Modifier.size(if (horizontal) 40.dp else 46.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            , contentAlignment = Alignment.Center) {
            NavigationIcon(destination, color)
            if (status != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                .background(if (destination == NavigationDestination.CHATS) CompanionColor.Signal else CompanionColor.Warn, RoundedCornerShape(3.dp)))
        }
        if (showLabel) Text(destination.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp, lineHeight = 18.sp), color = color,
            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (horizontal) Row(itemModifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) { content() }
    else Column(itemModifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceNavigationAction(active: Boolean, onClick: () -> Unit, modifier: Modifier, expanded: Boolean = false, showLabel: Boolean = true, onDictate: () -> Unit = {}) {
    val action = modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp))
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onDictate, onLongClickLabel = "Dictate message").testTag("nav.voice")
        .semantics { contentDescription = if (active) "Open voice controls" else "Start voice conversation" }
    val icon: @Composable () -> Unit = {
        Box(Modifier.size(54.dp).clip(CircleShape).background(CompanionColor.Signal), contentAlignment = Alignment.Center) {
            VoiceControlIcon(VoiceControlGlyph.WAVE, CompanionColor.Void)
        }
    }
    if (expanded) Row(action.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) { icon(); Text("Voice", color = CompanionColor.Signal) }
    else Column(action, verticalArrangement = Arrangement.spacedBy(4.dp, if (showLabel) Alignment.Bottom else Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(); if (showLabel) Text("Voice", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = CompanionColor.Signal)
    }
}

@Composable
private fun NavigationIcon(destination: NavigationDestination, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            when (destination) {
                NavigationDestination.CHATS -> {
                    val bubble = Path().apply {
                        moveTo(4f, 3f); lineTo(20f, 3f); quadraticTo(22f, 3f, 22f, 5f)
                        lineTo(22f, 16f); quadraticTo(22f, 18f, 20f, 18f)
                        lineTo(9f, 18f); lineTo(3f, 22f); lineTo(3f, 18f)
                        quadraticTo(2f, 18f, 2f, 16f); lineTo(2f, 5f)
                        quadraticTo(2f, 3f, 4f, 3f); close()
                    }
                    drawPath(bubble, color, style = stroke)
                    drawLine(color, Offset(7f, 8f), Offset(17f, 8f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(7f, 13f), Offset(14f, 13f), 1.8f, StrokeCap.Round)
                }
                NavigationDestination.AGENTS -> {
                    drawRoundRect(color, Offset(2f, 4f), Size(20f, 16f), CornerRadius(3f), style = stroke)
                    val prompt = Path().apply { moveTo(6f, 9f); lineTo(9f, 12f); lineTo(6f, 15f) }
                    drawPath(prompt, color, style = stroke)
                    drawLine(color, Offset(12f, 15f), Offset(17f, 15f), 1.8f, StrokeCap.Round)
                }
                NavigationDestination.DEVICE -> {
                    drawRoundRect(color, Offset(6f, 2f), Size(12f, 20f), CornerRadius(3f), style = stroke)
                    drawLine(color, Offset(10f, 18f), Offset(14f, 18f), 1.8f, StrokeCap.Round)
                }
                NavigationDestination.MORE -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.7f, Offset(it, 12f)) }
            }
        }
    }
}

internal data class MoreDestination(val tab: MainTab, val label: String, val description: String)
internal val moreDestinations = listOf(
    MoreDestination(MainTab.REVIEW, "Code review", "Changes, files and commits"),
    MoreDestination(MainTab.REMINDERS, "Schedules", "Scheduled jobs and reminders"),
    MoreDestination(MainTab.GATEWAY, "Hosts", "Connections, health and models"),
    MoreDestination(MainTab.PROFILES, "Profiles", "Choose a Hermes profile"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationMoreSheet(state: CompanionState, onTab: (MainTab) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text,
        scrimColor = CompanionColor.Void.copy(alpha = 0.72f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp).testTag("navigation.more")) {
            app.hermes.companion.design.SheetHeading("More", onDismiss, modifier = Modifier.padding(horizontal = 8.dp))
            Text(state.hostName.ifBlank { "Connected host" }, color = CompanionColor.TextDim,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            moreDestinations.forEach { destination ->
                val selected = state.tab == destination.tab && state.openSessionId == null && !state.inRoom
                MoreRow(destination.label, destination.description, "nav.${destination.tab.name.lowercase()}", selected) {
                    onDismiss(); onTab(destination.tab)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = CompanionColor.LineStrong)
            MoreRow("Settings", "App preferences and speech", "nav.settings", false) { onDismiss(); onSettings() }
        }
    }
}

@Composable
private fun MoreRow(label: String, description: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    app.hermes.companion.design.PreferenceRow(label, description, modifier = Modifier.testTag(tag),
        value = if (selected) "Current" else null, selected = selected, onClick = onClick)
}
