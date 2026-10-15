package app.hermes.companion.gateway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.HostHealth
import app.hermes.companion.model.*

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
    status: DashboardStatus?,
    hud: GatewayHud,
    nativeVoiceStatus: app.hermes.companion.model.NativeVoiceStatus? = null,
    hostMetrics: HostMetrics? = null,
    modelCatalog: ModelCatalog? = null,
    modelOverride: String = "",
    savedGateways: List<SavedGateway> = emptyList(),
    hostHealth: Map<String, HostHealth> = emptyMap(),
    updateStatus: HermesUpdateStatus? = null,
    ntfyTopic: String = "",
    stayConnected: Boolean = false,
    onNtfyTopicChange: (String) -> Unit = {},
    onSaveNtfy: () -> Unit = {},
    onToggleStay: () -> Unit = {},
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onOpenModelPicker: () -> Unit = {},
    onSelectGateway: (SavedGateway) -> Unit = {},
    onAddGateway: (String, String) -> Unit = { _, _ -> },
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {},
    hostLoading: Boolean = false,
    modelLoading: Boolean = false,
    updateLoading: Boolean = false,
    /** Origin a host switch is connecting to right now; that row shows SWITCHING and the others lock. */
    switchingOrigin: String? = null,
    /** Cross-host rooms (A22.11): relays this host can ask for agent turns. */
    peers: List<PeerLink> = emptyList(),
    peersLoading: Boolean = false,
    /** Saved-gateway origin a LINK HOSTS handshake is running against. */
    peerLinking: String? = null,
    onLinkHost: (SavedGateway) -> Unit = {},
    onUnlinkPeer: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var pendingUnlink by remember { mutableStateOf<String?>(null) }
    var confirmUpdate by remember { mutableStateOf(false) }
    val current = savedGateways.firstOrNull { it.isActive }
    val dismissKeyboard = rememberDismissKeyboard()
    fun save() {
        val address = origin.trim()
        val uri = runCatching { java.net.URI(address) }.getOrNull()
        if (uri?.scheme !in listOf("https", "http") || uri?.host.isNullOrBlank()) {
            formError = "Enter a dashboard address beginning with https:// or http://."
            return
        }
        onAddGateway(name.trim().ifBlank { uri!!.host }, address)
        name = ""; origin = ""; formError = null; detail = null; dismissKeyboard()
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("gateway.surface"),
        verticalArrangement = Arrangement.spacedBy(32.dp)) {
        GroupedSection("Current connection") {
            PreferenceRow(current?.name ?: "Hermes host", current?.origin ?: "Connected host", value = "Current", selected = true,
                onClick = { detail = "Connection" }, modifier = Modifier.testTag("gateway.current"))
        }
        GroupedSection("Saved hosts", trailing = { ActionButton("Add host", { detail = "Add host" }, modifier = Modifier.testTag("gateway.add")) }) {
            if (savedGateways.isEmpty()) PreferenceRow("No saved hosts", "Add a host to switch connections here", onClick = { detail = "Add host" }, modifier = Modifier.testTag("gateway.fleet.empty"))
            savedGateways.forEach { host ->
                val target = switchingOrigin != null && GatewayBook.key(host.origin) == GatewayBook.key(switchingOrigin)
                PreferenceRow(host.name, host.origin, value = if (target) "Connecting…" else if (host.isActive) "Current" else "Connect",
                    selected = host.isActive, enabled = switchingOrigin == null,
                    onClick = { if (host.isActive) detail = "Connection" else onSelectGateway(host) },
                    modifier = Modifier.testTag("gateway.fleet.${GatewayBook.chipId(host.origin)}"))
                if (target) Text("Connecting…", style = CompanionType.Caption, modifier = Modifier.padding(16.dp).testTag("gateway.switching"))
            }
        }
        GroupedSection("Host tools") {
            PreferenceRow("Model catalog", modelOverride.ifBlank { modelCatalog?.currentModel.orEmpty() }.ifBlank { "Host default" },
                onClick = onOpenModelPicker, modifier = Modifier.testTag("gateway.model.all"))
            PreferenceRow("Health & services", "Resources, connection and uptime", onClick = { detail = "Host health" }, modifier = Modifier.testTag("gateway.health.open"))
            PreferenceRow("Linked hosts", "${peers.size} linked · Room participants", onClick = { detail = "Linked hosts" }, modifier = Modifier.testTag("gateway.peers.open"))
            PreferenceRow("Updates", updateStatus?.let { if (it.updateAvailable) "Update available" else "Up to date" } ?: "Not checked yet",
                onClick = { detail = "Updates" }, modifier = Modifier.testTag("gateway.updates.open"))
            PreferenceRow("Background connection", if (stayConnected) "Enabled" else "Disabled", onClick = { detail = "Background connection" }, modifier = Modifier.testTag("gateway.background.open"))
        }
    }
    if (detail != null) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { detail = null },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(remember(detail) { androidx.compose.foundation.ScrollState(0) })
            .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("gateway.details"), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            SheetHeading(detail!!, { detail = null })
            when (detail) {
                "Add host" -> GroupedSection("Connection", padded = true) {
                    Text("Host name", style = CompanionType.RowTitle)
                    HairlineField(name, { name = it }, placeholder = "Work host", keyboardType = KeyboardType.Text, modifier = Modifier.testTag("gateway.add.name"))
                    Text("Dashboard address", style = CompanionType.RowTitle)
                    HairlineField(origin, { origin = it; formError = null }, placeholder = "https://your-host/hermes", keyboardType = KeyboardType.Uri,
                        onDone = { save() }, modifier = Modifier.testTag("gateway.add.origin"))
                    formError?.let { StatusStrip(it) }
                    ActionButton("Save and connect", { save() }, kind = ActionKind.PRIMARY, enabled = origin.isNotBlank(), modifier = Modifier.testTag("gateway.add.save"))
                }
                "Connection" -> {
                    GroupedSection("Host") {
                        PreferenceRow(current?.name ?: "Current host", current?.origin ?: "Connected")
                        PreferenceRow("Model catalog", modelCatalog?.currentModel ?: "Not loaded", onClick = { detail = null; onOpenModelPicker() })
                        PreferenceRow("Health & services", "Current connection diagnostics", onClick = { detail = "Host health" })
                    }
                    GroupedSection("Voice", padded = true) {
                        Text(when (nativeVoiceStatus?.available) { true -> "Host voice provider is available. Native calling remains gated."; false -> "Host voice provider is unavailable."; null -> "Host voice availability has not been confirmed." }, style = CompanionType.Body)
                        nativeVoiceStatus?.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = CompanionType.Caption) }
                        Text("Incoming agent calls are not available yet.", style = CompanionType.Caption)
                    }
                }
                "Host health" -> {
                    GroupedSection("Services") {
                        listOf(Triple("Gateway", "gw", hud.gateway), Triple("Telegram", "tg", hud.telegram), Triple("Discord", "dc", hud.discord), Triple("API", "api", hud.api)).forEach { (label, tag, value) ->
                            PreferenceRow(label, when(value) { HudState.ON -> "Connected"; HudState.OFF -> "Offline"; else -> "Needs attention" }, modifier = Modifier.testTag("gateway.$tag"))
                        }
                    }
                    GroupedSection("Resources") {
                        when {
                            hostMetrics != null -> {
                                val gib = 1024.0 * 1024 * 1024
                                PreferenceRow("CPU", "${String.format("%.1f", hostMetrics.cpu.percent)}% · ${hostMetrics.cpu.cores} cores")
                                PreferenceRow("Memory", "${String.format("%.1f", hostMetrics.memory.usedBytes / gib)} / ${String.format("%.1f", hostMetrics.memory.totalBytes / gib)} GB")
                                PreferenceRow("Disk", "${String.format("%.1f", hostMetrics.disk.usedBytes / gib)} / ${String.format("%.1f", hostMetrics.disk.totalBytes / gib)} GB")
                                PreferenceRow("Uptime", "${hostMetrics.system.uptimeSeconds / 3600} hours")
                                if (hostMetrics.system.platform.isNotBlank()) PreferenceRow("Platform", hostMetrics.system.platform)
                            }
                            status != null -> {
                                PreferenceRow("Gateway", status.gatewayState.ifBlank { if (status.gatewayRunning) "Running" else "Stopped" })
                                PreferenceRow("Version", status.version.ifBlank { "Not reported" })
                                if (status.exitReason.isNotBlank()) PreferenceRow("Last exit", status.exitReason)
                            }
                            hostLoading -> FetchRow("Reading host health…", modifier = Modifier.testTag("gateway.telemetry.loading"))
                            else -> PreferenceRow("Not reported", "Health will appear when the host connection is ready")
                        }
                    }
                }
                "Linked hosts" -> {
                    GroupedSection("Linked") {
                        if (peersLoading && peers.isEmpty()) FetchRow("Loading linked hosts…", modifier = Modifier.testTag("gateway.peers.loading"))
                        peers.forEach { peer -> PreferenceRow(peer.name, peer.origin, value = "Unlink", onClick = { pendingUnlink = peer.name }, modifier = Modifier.testTag("gateway.peer.${peer.name}")) }
                        if (peers.isEmpty() && !peersLoading) PreferenceRow("No linked hosts", "Link another saved host to include its profiles in rooms", modifier = Modifier.testTag("gateway.peers.empty"))
                    }
                    val linked = peers.map { GatewayBook.key(it.origin) }.toSet()
                    val candidates = savedGateways.filter { !it.isActive && GatewayBook.key(it.origin) !in linked }
                    if (candidates.isNotEmpty()) GroupedSection("Available hosts") {
                        candidates.forEach { host -> PreferenceRow(host.name, host.origin, value = if (peerLinking == host.origin) "Linking…" else "Link",
                            enabled = peerLinking == null, onClick = { onLinkHost(host) }, modifier = Modifier.testTag("gateway.peer.link.${GatewayBook.chipId(host.origin)}")) }
                    }
                    Text("Linking shares a pairing credential between these hosts.", style = CompanionType.Caption)
                }
                "Updates" -> GroupedSection("Hermes host", padded = true) {
                    when {
                        updateLoading -> FetchRow("Checking host…", padded = false, modifier = Modifier.testTag("gateway.update.loading"))
                        updateStatus == null -> Text("Check for an update on this host.", style = CompanionType.Body)
                        updateStatus.updateAvailable -> {
                            Text("${updateStatus.currentVersion} · ${updateStatus.behind} commits behind", style = CompanionType.Body)
                            if (updateStatus.summary.isNotBlank()) Text(updateStatus.summary, style = CompanionType.Caption)
                            ActionButton("Update Hermes", { confirmUpdate = true }, kind = ActionKind.PRIMARY, modifier = Modifier.testTag("gateway.update.apply"))
                        }
                        else -> Text("Hermes is up to date · ${updateStatus.currentVersion}", style = CompanionType.Body)
                    }
                    ActionButton("Check for updates", onCheckUpdate, enabled = !updateLoading, modifier = Modifier.testTag("gateway.update.check"))
                }
                "Background connection" -> {
                    GroupedSection("Connection") { PreferenceToggle("Stay connected", stayConnected, onToggleStay,
                        summary = "Keep the operator connection active in the background", modifier = Modifier.testTag("gateway.stay")) }
                    GroupedSection("Notification topic", padded = true) {
                        HairlineField(ntfyTopic, onNtfyTopicChange, placeholder = "Topic name", onDone = onSaveNtfy, modifier = Modifier.testTag("gateway.ntfy"))
                        ActionButton("Save topic", onSaveNtfy, modifier = Modifier.testTag("gateway.ntfy.save"))
                    }
                }
            }
        }
    }
    if (pendingUnlink != null) androidx.compose.material3.AlertDialog(onDismissRequest = { pendingUnlink = null },
        title = { Text("Unlink ${pendingUnlink}?") }, text = { Text("Existing room history is kept. New turns from this host will stop.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { val peer = pendingUnlink!!; pendingUnlink = null; onUnlinkPeer(peer) }) { Text("Unlink") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingUnlink = null }) { Text("Cancel") } })
    if (confirmUpdate) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmUpdate = false },
        title = { Text("Update this host?") }, text = { Text("The gateway will restart. Check request outcomes after reconnecting before retrying work.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmUpdate = false; onApplyUpdate() }) { Text("Update") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmUpdate = false }) { Text("Later") } })
}

@Composable
private fun HelpText(text: String, modifier: Modifier = Modifier) = Text(text, style = CompanionType.Caption, modifier = modifier)

@Composable
private fun ServiceStatus(name: String, tag: String, state: HudState) {
    val color = when (state) { HudState.ON -> CompanionColor.Signal; HudState.DEGRADED -> CompanionColor.Warn; HudState.OFF -> CompanionColor.TextMute }
    Row(Modifier.heightIn(min = 32.dp).testTag("gateway.$tag"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = color)
        Text("$name · ${state.name.lowercase()}", style = CompanionType.Caption, color = color)
    }
}

@Composable
private fun HostHealthLabel(origin: String, health: HostHealth) {
    if (health == HostHealth.UNKNOWN) return
    Text(health.name.lowercase().replaceFirstChar(Char::titlecase), style = CompanionType.Caption,
        color = if (health == HostHealth.ONLINE) CompanionColor.Signal else CompanionColor.TextDim,
        modifier = Modifier.testTag("gateway.health.${GatewayBook.chipId(origin)}"))
}
