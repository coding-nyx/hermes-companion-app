package app.hermes.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
    BOTS("Bots", MainTab.BOTS), CHATS("Chats", MainTab.THREADS),
    DEVICE("Device", MainTab.DEVICE), MORE("More", null),
}

internal fun navigationDestination(tab: MainTab, inChat: Boolean): NavigationDestination =
    if (inChat) NavigationDestination.CHATS else
        NavigationDestination.entries.firstOrNull { it.tab == tab } ?: NavigationDestination.MORE

@Composable
internal fun AppNavigationBar(state: CompanionState, inChat: Boolean, onTab: (MainTab) -> Unit,
    onMore: () -> Unit, onVoice: () -> Unit = {}, onDictate: () -> Unit = {}) {
    if (inChat) return
    val selected = navigationDestination(state.tab, false)
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(CompanionColor.VoidElevated)
            .border(1.dp, CompanionColor.LineStrong, shape).padding(4.dp).selectableGroup().testTag("navigation.bar"),
            verticalAlignment = Alignment.CenterVertically) {
            NavigationDestination.entries.take(2).forEach { destination ->
                NavigationItem(state, destination, selected, onTab, onMore, Modifier.weight(1f))
            }
            VoiceNavigationAction(state.isVoiceStreamActive, onVoice, Modifier.weight(1f), onDictate = onDictate,
                resume = state.voiceCallPhase == app.hermes.companion.model.NativeCallPhase.FAILED && state.openSession != null)
            NavigationDestination.entries.drop(2).forEach { destination ->
                NavigationItem(state, destination, selected, onTab, onMore, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavigationItem(state: CompanionState, destination: NavigationDestination, selected: NavigationDestination,
    onTab: (MainTab) -> Unit, onMore: () -> Unit, modifier: Modifier, showLabel: Boolean = true) {
    val active = destination == selected
    val color = if (active) CompanionColor.Signal else CompanionColor.TextDim
    val status = when (destination) {
        NavigationDestination.CHATS -> if (state.visibleSessions.any { it.unread } || state.unreadRooms.isNotEmpty()) "Unread conversations" else null
        NavigationDestination.DEVICE -> if (state.arm != DeviceArm.DISARMED) "Hands armed" else null
        else -> null
    }
    Column(modifier.height(if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 60.dp else 48.dp).clip(androidx.compose.foundation.shape.CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp))
        .background(if (active) CompanionColor.SignalDim else Color.Transparent)
        .testTag(destination.tab?.let { "nav.${it.name.lowercase()}" } ?: "nav.more")
        .selectable(active, role = Role.Tab, onClick = { destination.tab?.let(onTab) ?: onMore() })
        .semantics { contentDescription = destination.label; if (status != null) stateDescription = status },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        NavigationIconSlot(active, status != null, if (destination == NavigationDestination.CHATS) CompanionColor.Signal else CompanionColor.Warn) { NavigationIcon(destination, color) }
        NavigationLabel(destination.label, color, showLabel)
    }
}

@Composable
private fun NavigationIconSlot(active: Boolean, dot: Boolean = false, dotColor: Color = CompanionColor.Signal, content: @Composable () -> Unit) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        content()
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 1.dp).size(4.dp).background(dotColor, CircleShape))
    }
}

@Composable
private fun NavigationLabel(label: String, color: Color, visible: Boolean) {
    // Reserving the same label slot keeps all five icon baselines aligned at large text.
    Box(Modifier.height(if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 28.dp else 16.dp), contentAlignment = Alignment.Center) {
        if (visible) Text(label, style = MaterialTheme.typography.labelMedium.copy(
            fontSize = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 10.sp else 10.sp,
            lineHeight = 14.sp, letterSpacing = 0.sp), color = color, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceNavigationAction(active: Boolean, onClick: () -> Unit, modifier: Modifier, showLabel: Boolean = true, onDictate: () -> Unit = {}, resume: Boolean = false) {
    val color = if (active) CompanionColor.Signal else CompanionColor.TextDim
    Column(modifier.height(if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 60.dp else 48.dp).clip(androidx.compose.foundation.shape.CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp))
        .background(if (active) CompanionColor.SignalDim else Color.Transparent)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onDictate, onLongClickLabel = "Dictate message")
        .testTag("nav.voice").semantics { contentDescription = if (active) "Open voice controls" else if (resume) "Resume voice conversation" else "Start voice conversation" },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        NavigationIconSlot(active, dot = active) { VoiceControlIcon(VoiceControlGlyph.WAVE, color) }
        NavigationLabel(if (resume) "Resume" else "Voice", color, showLabel)
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
                NavigationDestination.BOTS -> {
                    drawRoundRect(color, Offset(4f, 7f), Size(16f, 13f), CornerRadius(4f), style = stroke)
                    drawLine(color, Offset(12f, 3f), Offset(12f, 7f), 1.8f, StrokeCap.Round)
                    drawCircle(color, 1.4f, Offset(12f, 3f))
                    drawCircle(color, 1.4f, Offset(8.5f, 12f)); drawCircle(color, 1.4f, Offset(15.5f, 12f))
                    drawLine(color, Offset(9f, 16f), Offset(15f, 16f), 1.8f, StrokeCap.Round)
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
    MoreDestination(MainTab.GATEWAY, "Gateways", "Connections, models and host services"),
    MoreDestination(MainTab.REVIEW, "Code review", "Changes, files and commits"),
    MoreDestination(MainTab.REMINDERS, "Schedules", "Scheduled jobs and reminders"),
    MoreDestination(MainTab.CONSOLE, "Coding agents", "Agent terminals and coding sessions"),
    MoreDestination(MainTab.PROFILES, "Agent profiles", "Choose your Hermes assistant"),
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
