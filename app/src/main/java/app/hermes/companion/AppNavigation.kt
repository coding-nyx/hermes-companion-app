package app.hermes.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal fun AppNavigationBar(state: CompanionState, inChat: Boolean, onTab: (MainTab) -> Unit, onMore: () -> Unit) {
    val selected = navigationDestination(state.tab, inChat)
    Row(Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).navigationBarsPadding()
        .padding(horizontal = 8.dp, vertical = 8.dp).selectableGroup().testTag("navigation.bar")) {
        NavigationDestination.entries.forEach { destination ->
            val active = destination == selected
            val color = if (active) CompanionColor.Signal else CompanionColor.TextDim
            val status = when (destination) {
                NavigationDestination.CHATS -> if (state.visibleSessions.any { it.unread } || state.unreadRooms.isNotEmpty()) "Unread conversations" else null
                NavigationDestination.DEVICE -> if (state.arm != DeviceArm.DISARMED) "Hands armed" else null
                NavigationDestination.MORE -> if (state.hud.gateway == HudState.OFF) "Check host status" else null
                else -> null
            }
            Column(Modifier.weight(1f).heightIn(min = 64.dp)
                .testTag(destination.tab?.let { "nav.${it.name.lowercase()}" } ?: "nav.more")
                .clip(RoundedCornerShape(16.dp))
                .selectable(active, role = Role.Tab, onClick = { destination.tab?.let(onTab) ?: onMore() })
                .semantics { if (status != null) stateDescription = status }
                .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp, 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) CompanionColor.SignalSubtle else Color.Transparent), contentAlignment = Alignment.Center) {
                    NavigationIcon(destination, color)
                    if (status != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                        .background(if (destination == NavigationDestination.CHATS) CompanionColor.Signal else CompanionColor.Warn,
                            RoundedCornerShape(3.dp)))
                }
                Text(destination.label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, lineHeight = 18.sp), color = color,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
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
        Column(Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp).testTag("navigation.more")) {
            Text("More", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
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
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(tag).clip(RoundedCornerShape(12.dp))
        .background(if (selected) CompanionColor.SignalSubtle else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) CompanionColor.Signal else CompanionColor.Text)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = CompanionColor.TextDim)
        }
        Text(if (selected) "✓" else "›", color = CompanionColor.TextDim, modifier = Modifier.padding(start = 12.dp))
    }
}
