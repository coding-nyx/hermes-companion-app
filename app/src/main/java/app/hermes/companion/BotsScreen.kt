package app.hermes.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotsScreen(
    state: BotModeState, hostName: String, onRefresh: () -> Unit,
    onOpenBot: (NativeBot) -> Unit, onSaveBot: (BotEdit, NativeBot?) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit, onOpenGroup: (NativeGroup) -> Unit,
    onCloseGroup: () -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit,
    onReply: (String?) -> Unit, onMention: (String) -> Unit,
    onRename: (String) -> Unit, onStop: () -> Unit, onDisband: () -> Unit,
    onApprove: (NativeGroupAction, String) -> Unit, onRetry: (NativeGroupAction) -> Unit,
    onRecover: (NativeBotOperation) -> Unit, onSchedules: () -> Unit, onGateways: () -> Unit,
    modifier: Modifier = Modifier,
    onHostSwitch: () -> Unit = onGateways, onSettings: () -> Unit = {},
    onChats: () -> Unit = {},
) {
    var editing by remember(state.owner) { mutableStateOf<NativeBot?>(null) }
    var createBot by remember(state.owner) { mutableStateOf(false) }
    var createGroup by remember(state.owner) { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var options by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var rename by remember(state.selected?.group?.id) { mutableStateOf(false) }
    var disband by remember(state.selected?.group?.id) { mutableStateOf(false) }
    var retry by remember(state.selected?.group?.id) { mutableStateOf<NativeGroupAction?>(null) }
    val busy = state.busy.isNotEmpty()
    LaunchedEffect(state.savedBotVersion) { if (state.savedBotVersion > 0) { createBot = false; editing = null } }

    Column(modifier.fillMaxSize().background(CompanionColor.Void).testTag("bots.screen")) {
        PageToolbar(state.selected?.group?.name ?: "Bots",
            if (state.selected == null) "$hostName ▾" else if (!state.groupVerified) "Syncing…" else if (state.selected.working) "Working" else if (state.selected.blocked) "Needs attention" else "${state.selected.group.members.size} members · $hostName",
            titleModifier = Modifier.testTag(if (state.selected == null) "context.host" else "bots.group.title"),
            onTitleClick = if (state.selected == null) onHostSwitch else null,
            leading = if (state.selected == null) null else ({ IconButton(onClick = onCloseGroup, modifier = Modifier.testTag("bots.group.back").semantics { contentDescription = "All bots" }) { ControlIcon(ControlGlyph.BACK) } }),
            actions = {
                if (state.selected == null && !state.unsupported) Box {
                    IconButton(onClick = { newMenu = true }, modifier = Modifier.testTag("bots.new").semantics { contentDescription = "Create bot or group" }) { ControlIcon(ControlGlyph.PLUS) }
                    DropdownMenu(newMenu, { newMenu = false }, containerColor = CompanionColor.VoidElevated) {
                        DropdownMenuItem(text = { Text("New bot") }, onClick = { newMenu = false; createBot = true }, enabled = !busy && !state.unsupported, modifier = Modifier.testTag("bots.create"))
                        DropdownMenuItem(text = { Text("New group chat") }, onClick = { newMenu = false; createGroup = true }, enabled = !busy && state.capabilities?.canCreate == true, modifier = Modifier.testTag("bots.group.create"))
                    }
                }
                Box {
                    IconButton(onClick = { options = true }, modifier = Modifier.testTag("bots.options").semantics { contentDescription = if (state.selected == null) "Bot options" else "Group options" }) { ControlIcon(ControlGlyph.MORE) }
                    DropdownMenu(options, { options = false }, containerColor = CompanionColor.VoidElevated) {
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { options = false; onRefresh() })
                        if (state.selected == null) {
                            DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden bots" else "Show hidden bots") }, onClick = { options = false; showHidden = !showHidden })
                            DropdownMenuItem(text = { Text("Routines") }, onClick = { options = false; onSchedules() })
                            DropdownMenuItem(text = { Text("Manage gateways") }, onClick = { options = false; onGateways() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { options = false; onSettings() }, modifier = Modifier.testTag("header.settings"))
                        } else {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { options = false; rename = true }, enabled = !busy && "groups.rename" in state.capabilities?.methods.orEmpty())
                            DropdownMenuItem(text = { Text("Stop") }, onClick = { options = false; onStop() }, enabled = !busy && "groups.stop" in state.capabilities?.methods.orEmpty())
                            DropdownMenuItem(text = { Text("Disband") }, onClick = { options = false; disband = true }, enabled = !busy && "groups.disband" in state.capabilities?.methods.orEmpty())
                        }
                    }
                }
            })
        if (!state.unsupported) state.error?.let { Text(it, color = CompanionColor.Warn, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("bots.error")) }
        if (state.selected == null && state.unsupported) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("bots.unsupported"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bots unavailable on this gateway", style = CompanionType.Title)
                Text(state.error ?: "Connect to a native Hermes dashboard to use bots and group chats.", style = CompanionType.Body, color = CompanionColor.TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Open Chats", onChats, Modifier.testTag("bots.unsupported.chats"), kind = ActionKind.PRIMARY)
                    ActionButton("Switch gateway", onHostSwitch, Modifier.testTag("bots.unsupported.gateway"))
                    ActionButton("Retry", onRefresh, Modifier.testTag("bots.unsupported.retry"), enabled = !state.loading)
                }
            }
        } else if (state.selected == null) {
            SearchField(query, { query = it }, placeholder = "Search bots and groups", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("bots.search"))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                listOf("All", "Bots", "Groups").forEach { label ->
                    val count = when (label) { "Bots" -> state.bots.count { showHidden || !it.hidden }; "Groups" -> state.groups.size; else -> state.bots.count { showHidden || !it.hidden } + state.groups.size }
                    Column(Modifier.widthIn(min = 64.dp).height(if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 56.dp else 48.dp).testTag("bots.filter.${label.lowercase()}")
                        .drawBehind { if (filter == label) drawLine(CompanionColor.Signal, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx()) }
                        .clickable(role = Role.Tab) { filter = label }.semantics { this.selected = filter == label },
                        verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text("$label $count", style = CompanionType.Label, color = if (filter == label) CompanionColor.Text else CompanionColor.TextDim)
                        }
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("bots.loading"))
            HorizontalDivider(color = CompanionColor.Line)
            LazyColumn(Modifier.weight(1f).testTag("bots.list"), contentPadding = PaddingValues(bottom = 12.dp)) {
                state.groupError?.let { item { Text(it, style = CompanionType.Caption, color = CompanionColor.Warn, modifier = Modifier.padding(16.dp)) } }
                if (state.capabilities != null && !state.capabilities.driver) item { Text("Group worker unavailable. Existing conversations are still readable.", style = CompanionType.Caption, modifier = Modifier.padding(16.dp)) }
                items(state.pending.filter { it.kind == "create-group" }, key = { "pending:${it.id}" }) { op ->
                    Column(Modifier.padding(16.dp)) {
                        Text("Check group creation: ${op.text}")
                        TextButton(onClick = { onRecover(op) }, enabled = !busy) { Text("Check / recover") }
                    }
                }
                val groups = state.groups.filter { "${it.name} ${it.members.joinToString { m -> m.name }}".contains(query, true) }.sortedByDescending { it.updatedAt }
                if (filter != "Bots") {
                    if (groups.isNotEmpty() || filter == "Groups") item { BotSectionLabel("Group chats", groups.size) }
                    items(groups, key = { "group:${it.id}" }) { group ->
                        ConversationRow(group.name, group.members.joinToString { it.name }, group.id, true,
                            Modifier.testTag("bots.group.${group.id}"), enabled = !busy, onClick = { onOpenGroup(group) })
                    }
                    if (filter == "Groups" && groups.isEmpty()) item {
                        Text(if (query.isBlank()) "Create a group to bring 2–6 bots into one conversation." else "No matching groups", style = CompanionType.Caption, modifier = Modifier.padding(16.dp))
                    }
                }
                if (filter != "Groups") {
                    val bots = state.bots.filter { (showHidden || !it.hidden) && "${it.title} ${it.owner.profile} ${it.description} ${it.section}".contains(query, true) }
                        .sortedWith(compareByDescending<NativeBot> { it.pinned }.thenBy { it.section }.thenByDescending { it.updatedAt })
                    if (filter == "All" && groups.isNotEmpty()) item { BotSectionLabel("Bots", bots.size) }
                    items(bots, key = { "bot:${it.owner.profile}" }) { bot ->
                        var editMenu by remember { mutableStateOf(false) }
                        ConversationRow(bot.title, bot.preview.ifBlank { bot.description }.ifBlank { "@${if (bot.owner.profile == "default") "hermes" else bot.owner.profile} · ${bot.model.ifBlank { "Profile model" }}" },
                            bot.owner.profile, false, Modifier.testTag("bots.open.${bot.owner.profile}"),
                            enabled = !busy, onClick = { onOpenBot(bot) }, trailing = {
                                Box {
                                    IconButton(onClick = { editMenu = true }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Options for ${bot.title}" }) { ControlIcon(ControlGlyph.MORE) }
                                    DropdownMenu(editMenu, { editMenu = false }, containerColor = CompanionColor.VoidElevated) {
                                        DropdownMenuItem(text = { Text("Edit bot") }, onClick = { editMenu = false; editing = bot })
                                    }
                                }
                            })
                    }
                    if (bots.isEmpty() && !state.loading) item { Text(if (query.isBlank()) "Create your first bot with +" else "No matching bots", style = CompanionType.Caption, modifier = Modifier.padding(16.dp)) }
                }
            }
        } else {
            val snapshot = state.selected
            val messagesState = rememberLazyListState()
            LaunchedEffect(snapshot.group.id, state.events.size) {
                if (state.events.isNotEmpty() && (!messagesState.canScrollForward || messagesState.layoutInfo.totalItemsCount == 0))
                    messagesState.scrollToItem((state.events.count { it.kind in setOf("message.user", "message.member", "turn.failed", "turn.cancelled", "turn.deferred") } - 1).coerceAtLeast(0))
            }
            LazyColumn(Modifier.weight(1f).testTag("bots.group.messages"), state = messagesState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.events.filter { it.kind in setOf("message.user", "message.member", "turn.failed", "turn.cancelled", "turn.deferred") }, key = { it.id }) { event ->
                    Column(Modifier.fillMaxWidth().testTag("bots.event.${event.sequence}")) {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                            val names = app.hermes.companion.domain.ChatNames.profiles(state.owner?.origin, emptyList(), state.bots)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.hermes.companion.domain.ChatNames.groupSpeaker(event, snapshot.group, names),
                                style = CompanionType.Caption.copy(color = if (event.kind == "message.user") CompanionColor.TextDim else CompanionColor.Signal),
                                modifier = Modifier.weight(1f).padding(bottom = 4.dp).testTag("bots.event.speaker.${event.sequence}"))
                            if (event.thread.isNotBlank()) IconButton(onClick = { onReply(event.thread) },
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "Reply in thread" }) { ControlIcon(ControlGlyph.REPLY) }
                            }
                            if (event.kind in setOf("message.user", "message.member")) app.hermes.companion.chat.MarkdownBlocks(event.text)
                            else Text(event.text.ifBlank { when (event.kind) { "turn.failed" -> "This agent's turn failed"; "turn.cancelled" -> "Turn stopped"; "turn.deferred" -> "Turn deferred; check its status before retrying"; else -> "" } }, color = CompanionColor.Warn)
                        }
                    }
                }
                items(state.pending.filter { it.kind == "send" && it.target == snapshot.group.id }, key = { "pending:${it.id}" }) { op ->
                    GroupSurface(padded = true) { Column {
                        Text(op.text); Text("Delivery not confirmed", color = CompanionColor.Warn)
                        TextButton(onClick = { onRecover(op) }, enabled = !busy) { Text("Check / recover") }
                    } }
                }
            }
            snapshot.actions.forEach { action ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("${action.member}: ${action.command.ifBlank { if (action.kind == "approval") "Approval required" else "Turn needs review" }}", modifier = Modifier.weight(1f))
                    if (action.kind == "approval" && action.request.isNotBlank() && "groups.approve" in state.capabilities?.methods.orEmpty()) {
                        TextButton(onClick = { onApprove(action, "once") }, enabled = !busy) { Text("Allow once") }
                        TextButton(onClick = { onApprove(action, "deny") }, enabled = !busy) { Text("Deny") }
                    } else if (action.kind == "retry" && "groups.retry" in state.capabilities?.methods.orEmpty()) {
                        TextButton(onClick = { retry = action }, enabled = !busy) { Text("Review retry") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.group.members.forEach { member -> TextButton(onClick = { onMention(member.handle) }) { Text("@${member.handle}") } }
            }
            if (state.replyThread != null) Row(Modifier.padding(horizontal = 16.dp)) {
                Text("Replying in thread", modifier = Modifier.weight(1f)); TextButton(onClick = { onReply(null) }) { Text("New topic") }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HairlineField(state.draft, onDraft, placeholder = "Message the group", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp).testTag("bots.group.draft"))
                ActionButton(if ("send" in state.busy) "Sending…" else "Send", onSend,
                    enabled = state.draft.isNotBlank() && !busy && state.groupVerified && state.capabilities?.driver == true && "groups.send" in state.capabilities.methods,
                    kind = ActionKind.PRIMARY, modifier = Modifier.testTag("bots.group.send"))
            }
        }
    }
    if (createBot || editing != null) BotEditor(editing, busy, state.error, onDismiss = { createBot = false; editing = null }, onSave = { onSaveBot(it, editing) })
    if (createGroup) GroupEditor(state, onDismiss = { createGroup = false }, onCreate = { name, profiles -> createGroup = false; onCreateGroup(name, profiles) })
    if (rename) {
        var name by remember { mutableStateOf(state.selected?.group?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename group") }, text = { HairlineField(name, { name = it }, placeholder = "Group name", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text) },
            confirmButton = { TextButton(onClick = { rename = false; onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    }
    if (disband) AlertDialog(onDismissRequest = { disband = false }, title = { Text("Disband this group?") },
        text = { Text("This stops its work and permanently retires the group on the gateway.") },
        confirmButton = { TextButton(onClick = { disband = false; onDisband() }) { Text("Disband") } }, dismissButton = { TextButton(onClick = { disband = false }) { Text("Cancel") } })
    retry?.let { action -> AlertDialog(onDismissRequest = { retry = null }, title = { Text("Retry this agent's turn?") },
        text = { Text("The previous attempt has an uncertain outcome. Retrying may repeat work or external side effects. Check the conversation first.") },
        confirmButton = { TextButton(onClick = { retry = null; onRetry(action) }) { Text("Retry turn") } }, dismissButton = { TextButton(onClick = { retry = null }) { Text("Cancel") } }) }
}

@Composable
private fun BotSectionLabel(label: String, count: Int) {
    Text("$label · $count", style = CompanionType.Caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun ConversationRow(title: String, preview: String, identity: String, group: Boolean,
    modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val lines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 1
    Row(modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BotAvatar(identity, group)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CompanionType.RowTitle, maxLines = lines, overflow = TextOverflow.Ellipsis)
            Text(preview, style = CompanionType.Label.copy(color = CompanionColor.TextDim, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
    HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = CompanionColor.Line.copy(alpha = 0.5f))
}

/** Locally drawn avatars remain visible offline and do not expose a host token to image loaders. */
@Composable
internal fun BotAvatar(identity: String, group: Boolean, small: Boolean = false) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = listOf(CompanionColor.Signal, CompanionColor.Accent,
        if (dark) Color(0xFFE9AC74) else Color(0xFF9A4F12),
        if (dark) Color(0xFF79C6A5) else Color(0xFF247150))
    val color = if (group) CompanionColor.Accent else colors[(identity.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(Modifier.size(if (small) 26.dp else 40.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (small) 22.dp else 26.dp)) {
            val u = size.width / 24f
            val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*u, y*u), Offset(xx*u, yy*u), 1.7f*u, StrokeCap.Round)
            if (group) {
                drawCircle(color, 3f*u, Offset(9f*u, 8f*u), style = stroke)
                drawCircle(color, 2.6f*u, Offset(17f*u, 9f*u), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(2f*u, 14f*u), Size(14f*u, 12f*u), style = stroke)
                drawArc(color, 235f, 125f, false, Offset(12f*u, 15f*u), Size(10f*u, 10f*u), style = stroke)
            } else {
                drawRoundRect(color, Offset(4f*u, 7f*u), Size(16f*u, 13f*u), androidx.compose.ui.geometry.CornerRadius(4f*u), style = stroke)
                line(12f, 3f, 12f, 7f); drawCircle(color, 1.4f*u, Offset(12f*u, 3f*u))
                drawCircle(color, 1.4f*u, Offset(8.5f*u, 12f*u)); drawCircle(color, 1.4f*u, Offset(15.5f*u, 12f*u))
                line(9f, 16f, 15f, 16f)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotEditor(bot: NativeBot?, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (BotEdit) -> Unit) {
    var profile by rememberSaveable(bot?.owner) { mutableStateOf(bot?.owner?.profile.orEmpty()) }
    var title by rememberSaveable(bot?.owner) { mutableStateOf(bot?.title.orEmpty()) }
    var description by rememberSaveable(bot?.owner) { mutableStateOf(bot?.description.orEmpty()) }
    var section by rememberSaveable(bot?.owner) { mutableStateOf(bot?.section.orEmpty()) }
    var hidden by rememberSaveable(bot?.owner) { mutableStateOf(bot?.hidden ?: false) }
    var model by rememberSaveable(bot?.owner) { mutableStateOf("") }
    var provider by rememberSaveable(bot?.owner) { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = CompanionColor.VoidElevated,
        contentColor = CompanionColor.Text, dragHandle = null, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).testTag("bots.editor.sheet").navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeading(if (bot == null) "New bot" else "Edit bot", { if (!busy) onDismiss() })
            Text("Bots keep their own configuration and memory on this gateway.", style = CompanionType.Caption)
            if (bot == null) BotField("Profile name", profile, { profile = it }, "bots.editor.profile")
            BotField("Bot name", title, { title = it }, "bots.editor.title")
            BotField("Role / description", description, { description = it })
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("bots.editor.advanced")) { Text(if (advanced) "Advanced ▴" else "Advanced ▾") }
            if (advanced) {
                BotField("Section (optional)", section, { section = it })
                BotField("Model (optional)", model, { model = it })
                BotField("Provider (with model)", provider, { provider = it })
                PreferenceToggle("Hide from roster", hidden, { hidden = !hidden })
            }
            if (error != null) Text(error, color = CompanionColor.Warn)
            ActionButton("Save", { onSave(BotEdit(profile.trim(), title.trim(), description, model, provider, hidden, section)) },
                enabled = !busy && title.isNotBlank() && (bot != null || profile.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) && model.isBlank() == provider.isBlank(),
                kind = ActionKind.PRIMARY, busy = busy, busyLabel = "Saving…", modifier = Modifier.testTag("bots.editor.save"))
        }
    }
}

@Composable
private fun BotField(label: String, value: String, onChange: (String) -> Unit, tag: String = "") {
    HairlineField(value, onChange, label = label, keyboardType = androidx.compose.ui.text.input.KeyboardType.Text, modifier = Modifier.testTag(tag))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditor(state: BotModeState, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val allowed = state.capabilities?.seatableProfiles
    val eligible = state.bots.filter { allowed == null || it.owner.profile in allowed }
    val selected = eligible.filter { it.owner.profile in picked }
    val available = eligible.filter { "${it.title} ${it.owner.profile}".contains(query, true) }
    val defaultName = if (selected.isEmpty()) null else app.hermes.companion.domain.NativeGroupNames.resolve("",
        selected.map { it.title.ifBlank { it.owner.profile } }, state.groups.map { it.name }.toSet())
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.9f
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CompanionColor.VoidElevated,
        contentColor = CompanionColor.Text, dragHandle = null, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).background(CompanionColor.VoidElevated).testTag("bots.group.sheet").navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SheetHeading("New group chat", onDismiss) }
                item { HairlineField(name, { name = app.hermes.companion.domain.NativeGroupNames.limit(it) }, label = "Group name (optional)",
                    placeholder = defaultName, keyboardType = androidx.compose.ui.text.input.KeyboardType.Text, modifier = Modifier.testTag("bots.group.name")) }
                item { SearchField(query, { query = it }, placeholder = "Find bots", modifier = Modifier.testTag("bots.group.search")) }
                item { Text(if (selected.size < 2) "${selected.size} of 6 selected · Choose at least 2 bots" else "${selected.size} of 6 selected · Name is optional", style = CompanionType.Caption) }
                items(available, key = { it.owner.profile }) { bot ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BotAvatar(bot.owner.profile, false)
                        Column(Modifier.weight(1f)) {
                            Text(bot.title, style = CompanionType.RowTitle)
                            Text("@${bot.owner.profile}", style = CompanionType.Caption)
                        }
                        Checkbox(bot.owner.profile in picked, { on -> picked = if (on) (picked + bot.owner.profile).distinct() else picked - bot.owner.profile },
                            enabled = bot.owner.profile in picked || selected.size < 6, modifier = Modifier.testTag("bots.group.pick.${bot.owner.profile}"))
                    }
                }
            }
            HorizontalDivider(color = CompanionColor.Line)
            ActionButton("Create group", { onCreate(name, selected.map { it.owner.profile }) }, enabled = selected.size in 2..6 && state.capabilities?.canCreate == true && state.busy.isEmpty(),
                kind = ActionKind.PRIMARY, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("bots.group.confirm"))
        }
    }
}

/** One optional shell argument keeps existing chat/room callers source-compatible. */
data class BotModeActions(
    val refresh: () -> Unit = {}, val openBot: (NativeBot) -> Unit = {},
    val saveBot: (BotEdit, NativeBot?) -> Unit = { _, _ -> }, val createGroup: (String, List<String>) -> Unit = { _, _ -> },
    val openGroup: (NativeGroup) -> Unit = {}, val closeGroup: () -> Unit = {},
    val draft: (String) -> Unit = {}, val send: () -> Unit = {}, val reply: (String?) -> Unit = {},
    val mention: (String) -> Unit = {}, val rename: (String) -> Unit = {}, val stop: () -> Unit = {}, val disband: () -> Unit = {},
    val approve: (NativeGroupAction, String) -> Unit = { _, _ -> }, val retry: (NativeGroupAction) -> Unit = {},
    val recover: (NativeBotOperation) -> Unit = {},
)
