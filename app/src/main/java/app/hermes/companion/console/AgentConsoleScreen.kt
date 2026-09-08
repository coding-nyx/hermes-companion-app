package app.hermes.companion.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.AgentPaneWidths
import app.hermes.companion.design.ActionButton
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.FetchSkeleton
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.design.LiveDot
import app.hermes.companion.design.StatusStrip
import app.hermes.companion.chat.MarkdownBlocks
import app.hermes.companion.domain.AgentTranscriptState
import app.hermes.companion.domain.AnsiText
import app.hermes.companion.model.AgentDirListing
import app.hermes.companion.model.ApprovalPrompt
import androidx.compose.foundation.layout.heightIn
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import app.hermes.companion.domain.ThreadTime
import app.hermes.companion.model.AgentPane
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.AgentTool

/** Special keys the key row offers; values are the host's key names (`agents.SPECIAL_KEYS`). */
private val KeyRow = listOf(
    "ESC" to "esc", "TAB" to "tab", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right",
    "⏎" to "enter", "^C" to "c-c", "^D" to "c-d", "^L" to "c-l", "PgUp" to "pgup", "PgDn" to "pgdn",
)

/**
 * Agent console (P23 / A23.2): the `term` tab. `SESSIONS` lists tmux-backed coding-agent sessions on
 * the host and opens one as a live pane snapshot with a key row; `SHELL` keeps the quick exec console.
 */
@Composable
fun AgentConsoleScreen(
    sessions: List<AgentSession>,
    tools: List<AgentTool>,
    tmux: Boolean,
    defaultCwd: String,
    sessionsLoading: Boolean,
    toolsLoading: Boolean,
    starting: Boolean,
    newOpen: Boolean,
    openSession: AgentSession?,
    pane: AgentPane,
    paneLoading: Boolean,
    cols: Int,
    input: String,
    error: String?,
    onRefresh: () -> Unit,
    onToggleNew: (Boolean) -> Unit,
    onStart: (tool: String, cwd: String, prompt: String, mode: String) -> Unit,
    onOpen: (AgentSession) -> Unit,
    onClose: () -> Unit,
    onInput: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onKey: (String) -> Unit,
    onKill: (AgentSession?) -> Unit,
    onForget: (AgentSession) -> Unit,
    onCols: (Int) -> Unit,
    quickShell: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    transcript: AgentTranscriptState = AgentTranscriptState(),
    onSubmit: (String) -> Unit = { onSendText(it, true) },
    onApprove: (String) -> Unit = {},
    dirs: AgentDirListing? = null,
    dirsLoading: Boolean = false,
    dirsError: String? = null,
    onBrowse: (path: String, hidden: Boolean) -> Unit = { _, _ -> },
) {
    var segment by rememberSaveable { mutableStateOf("sessions") }
    Column(modifier = modifier.fillMaxSize().background(CompanionColor.Void).testTag("agents.screen")) {
        if (openSession != null && openSession.mode == "structured") {
            StructuredView(
                session = openSession, transcript = transcript, loading = paneLoading, input = input, error = error,
                onClose = onClose, onInput = onInput, onSubmit = onSubmit, onApprove = onApprove, onInterrupt = { onKey("c-c") }, onKill = { onKill(openSession) },
            )
            return
        }
        if (openSession != null) {
            PaneView(
                session = openSession, pane = pane, loading = paneLoading, cols = cols, input = input, error = error,
                onClose = onClose, onInput = onInput, onSendText = onSendText, onKey = onKey, onKill = { onKill(openSession) }, onCols = onCols,
            )
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
        ) {
            Segment("SESSIONS", segment == "sessions", "agents.segment.sessions") { segment = "sessions" }
            Segment("SHELL", segment == "shell", "agents.segment.shell") { segment = "shell" }
            Spacer(Modifier.weight(1f))
            if (segment == "sessions") {
                Text(text = sessions.count { it.status != "exited" }.toString(), style = CompanionType.MonoSmall, modifier = Modifier.testTag("agents.count"))
                Text(
                    text = "↻",
                    style = CompanionType.Mono.copy(color = if (sessionsLoading) CompanionColor.TextMute else CompanionColor.Signal),
                    modifier = Modifier.testTag("agents.refresh").clickable(enabled = !sessionsLoading, onClick = onRefresh).padding(horizontal = CompanionSpace.Sm),
                )
                Text(
                    text = if (newOpen) "CANCEL" else "NEW",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    modifier = Modifier.testTag("agents.new").clickable { onToggleNew(!newOpen) }.padding(horizontal = CompanionSpace.Sm),
                )
            }
        }
        Hairline()
        if (segment == "shell") {
            quickShell()
            return
        }
        if (!error.isNullOrBlank()) {
            StatusStrip(text = error, modifier = Modifier.testTag("agents.error"))
            Hairline()
        }
        if (newOpen) {
            NewSessionPanel(tools, tmux, defaultCwd, toolsLoading, starting, onStart, dirs, dirsLoading, dirsError, onBrowse)
            Hairline()
        }
        if (sessionsLoading && sessions.isEmpty()) {
            FetchRow(label = "LOADING SESSIONS", modifier = Modifier.testTag("agents.loading"))
            FetchSkeleton(lines = 3)
            return
        }
        if (sessions.isEmpty()) {
            FetchPane(
                label = if (tmux) "NO SESSIONS" else "NO TMUX ON HOST",
                hint = if (tmux) "// NEW starts Claude Code, Codex or a shell on the host" else "// chat sessions work without tmux · terminals need apt install tmux",
                modifier = Modifier.weight(1f).testTag("agents.empty"),
            )
            return
        }
        if (sessionsLoading) FetchRow(label = "REFRESHING", modifier = Modifier.testTag("agents.loading"))
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { s -> SessionRow(s, tools, nowMs, onOpen, onKill, onForget) }
        }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextDim),
        modifier = Modifier
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(vertical = CompanionSpace.Xs),
    )
}

@Composable
private fun NewSessionPanel(
    tools: List<AgentTool>,
    tmux: Boolean,
    defaultCwd: String,
    loading: Boolean,
    starting: Boolean,
    onStart: (String, String, String, String) -> Unit,
    dirs: AgentDirListing? = null,
    dirsLoading: Boolean = false,
    dirsError: String? = null,
    onBrowse: (String, Boolean) -> Unit = { _, _ -> },
) {
    var tool by rememberSaveable { mutableStateOf("") }
    var cwd by rememberSaveable(defaultCwd) { mutableStateOf(defaultCwd) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var wantChat by rememberSaveable { mutableStateOf(true) }
    val installed = tools.filter { it.installed }
    LaunchedEffect(installed.map { it.id }) {
        if (tool.isBlank() || installed.none { it.id == tool }) tool = installed.firstOrNull { it.id != "shell" }?.id ?: installed.firstOrNull()?.id.orEmpty()
    }
    val selected = tools.firstOrNull { it.id == tool }
    val chatAvailable = selected?.modes?.contains("structured") == true
    val mode = if (chatAvailable && wantChat) "structured" else "pty"
    Column(
        modifier = Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).padding(CompanionSpace.Lg).testTag("agents.new.panel"),
        verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        Text(text = "TOOL · installed on this host", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
        if (loading && tools.isEmpty()) FetchRow(label = "PROBING HOST", padded = false, modifier = Modifier.testTag("agents.tools.loading"))
        if (!tmux) Text(text = "tmux missing on host · terminal sessions need apt install tmux / brew install tmux", style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn), modifier = Modifier.testTag("agents.notmux"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            tools.forEach { t ->
                val on = t.id == tool
                val color = when { !t.installed -> CompanionColor.TextMute; on -> CompanionColor.Signal; else -> CompanionColor.TextDim }
                Column(
                    modifier = Modifier
                        .testTag("agents.tool.${t.id}")
                        .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.Line)
                        .background(if (on) CompanionColor.SignalDim else CompanionColor.Void)
                        .clickable(enabled = t.installed) { tool = t.id }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "${t.glyph} ${t.label}", style = CompanionType.MonoSmall.copy(color = color), maxLines = 1)
                    Text(
                        text = if (t.installed) t.version.ifBlank { "installed" } else t.installHint.ifBlank { "not installed" },
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute, fontSize = 9.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        selected?.loginHint?.takeIf { it.isNotBlank() }?.let {
            Text(text = "first run: $it", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
        }
        if (chatAvailable) {
            Text(text = "MODE", style = CompanionType.MonoSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                ModeChip("CHAT", "prompts · approvals on the phone", mode == "structured", "agents.mode.structured") { wantChat = true }
                ModeChip("TERMINAL", if (tmux) "full TUI in tmux" else "needs tmux on host", mode == "pty", "agents.mode.pty", enabled = tmux) { wantChat = false }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "DIRECTORY", style = CompanionType.MonoSmall, modifier = Modifier.weight(1f))
            Text(
                text = if (picking) "DONE" else "BROWSE",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier.testTag("agents.new.browse").clickable {
                    picking = !picking
                    if (picking) onBrowse(cwd.trim(), showHidden)
                }.padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs),
            )
        }
        HairlineField(value = cwd, onValueChange = { cwd = it }, keyboardType = KeyboardType.Uri, placeholder = defaultCwd.ifBlank { "~/Projects/…" }, modifier = Modifier.testTag("agents.new.cwd"))
        if (picking) {
            DirPicker(
                listing = dirs, loading = dirsLoading, error = dirsError, current = cwd.trim(), showHidden = showHidden,
                onNavigate = { onBrowse(it, showHidden) },
                onToggleHidden = { showHidden = it; onBrowse(dirs?.path ?: cwd.trim(), it) },
                onPick = { cwd = it; picking = false },
            )
        }
        Text(text = "PROMPT (optional)", style = CompanionType.MonoSmall)
        HairlineField(value = prompt, onValueChange = { prompt = it }, keyboardType = KeyboardType.Text, placeholder = "what should it start on?", modifier = Modifier.testTag("agents.new.prompt"))
        ActionButton(
            label = "START",
            kind = ActionKind.PRIMARY,
            enabled = tool.isNotBlank() && (tmux || mode == "structured") && !starting,
            busy = starting,
            busyLabel = "STARTING",
            onClick = { onStart(tool, cwd.trim(), prompt.trim(), mode) },
            modifier = Modifier.testTag("agents.new.start"),
        )
    }
}

@Composable
private fun SessionRow(
    s: AgentSession,
    tools: List<AgentTool>,
    nowMs: Long,
    onOpen: (AgentSession) -> Unit,
    onKill: (AgentSession?) -> Unit,
    onForget: (AgentSession) -> Unit,
) {
    var menu by remember(s.id) { mutableStateOf(false) }
    val glyph = tools.firstOrNull { it.id == s.tool }?.glyph ?: s.tool.take(2).uppercase()
    val live = s.status != "exited"
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(s.id) { detectTapGestures(onTap = { onOpen(s) }, onLongPress = { menu = !menu }) }
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
                .testTag("agents.row.${s.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
        ) {
            Box(
                modifier = Modifier.size(36.dp).border(CompanionSpace.Hairline, if (live) CompanionColor.Signal else CompanionColor.LineStrong),
                contentAlignment = Alignment.Center,
            ) { Text(text = glyph, style = CompanionType.Mono.copy(color = if (live) CompanionColor.Signal else CompanionColor.TextMute)) }
            Column(Modifier.weight(1f)) {
                Text(text = s.title.ifBlank { s.id }, style = CompanionType.Body.copy(color = if (live) CompanionColor.Text else CompanionColor.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = (if (s.mode == "structured") "chat · " else "") + s.cwd, style = CompanionType.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.lastLine.isNotBlank()) Text(text = s.lastLine, style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (live) LiveDot(size = 5.dp)
                    Text(
                        text = if (live) statusLabel(s.status) else "exited" + (s.exitCode?.let { " $it" } ?: ""),
                        style = CompanionType.MonoSmall.copy(color = if (live) statusColor(s.status) else CompanionColor.TextMute),
                    )
                }
                Text(text = ThreadTime.relative(s.updatedAtEpochMs, nowMs), style = CompanionType.MonoSmall)
            }
        }
        if (menu) {
            Row(
                modifier = Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = s.attach, style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (live) Text(text = "KILL", style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger), modifier = Modifier.testTag("agents.kill.${s.id}").clickable { onKill(s); menu = false }.padding(CompanionSpace.Xs))
                Text(text = "FORGET", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim), modifier = Modifier.testTag("agents.forget.${s.id}").clickable { onForget(s); menu = false }.padding(CompanionSpace.Xs))
            }
        }
        Hairline()
    }
}

@Composable
private fun ColumnScope.PaneView(
    session: AgentSession,
    pane: AgentPane,
    loading: Boolean,
    cols: Int,
    input: String,
    error: String?,
    onClose: () -> Unit,
    onInput: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onKey: (String) -> Unit,
    onKill: () -> Unit,
    onCols: (Int) -> Unit,
) {
    val live = session.status != "exited"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        Text(text = "◂", style = CompanionType.Mono.copy(color = CompanionColor.Signal), modifier = Modifier.testTag("agents.back").clickable(onClick = onClose).padding(end = CompanionSpace.Xs))
        Column(Modifier.weight(1f)) {
            Text(text = session.title.ifBlank { session.id }, style = CompanionType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = "${session.cwd} · ${session.attach}", style = CompanionType.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (live) LiveDot(size = 5.dp)
            Text(
                text = if (live) session.status.uppercase() else "EXITED" + (session.exitCode?.let { " $it" } ?: ""),
                style = CompanionType.MonoSmall.copy(color = if (live) CompanionColor.Signal else CompanionColor.TextMute),
                modifier = Modifier.testTag("agents.pane.status"),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg).padding(bottom = CompanionSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        Text(text = "COLS", style = CompanionType.MonoSmall)
        AgentPaneWidths.forEach { w ->
            Text(
                text = "$w",
                style = CompanionType.MonoSmall.copy(color = if (w == cols) CompanionColor.Signal else CompanionColor.TextDim),
                modifier = Modifier.testTag("agents.cols.$w").clickable { onCols(w) }.padding(horizontal = CompanionSpace.Xs),
            )
        }
        Spacer(Modifier.weight(1f))
        if (live) Text(text = "KILL", style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger), modifier = Modifier.testTag("agents.pane.kill").clickable(onClick = onKill).padding(CompanionSpace.Xs))
    }
    Hairline()
    if (!error.isNullOrBlank()) {
        StatusStrip(text = error, modifier = Modifier.testTag("agents.pane.error"))
        Hairline()
    }
    Box(Modifier.weight(1f).fillMaxWidth().background(CompanionColor.VoidElevated)) {
        if (loading && pane.ansi.isEmpty()) {
            FetchRow(label = "ATTACHING", modifier = Modifier.testTag("agents.pane.loading"))
        } else {
            PaneText(pane, live)
        }
    }
    Hairline()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs).testTag("agents.keys"),
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        KeyRow.forEach { (label, key) ->
            Text(
                text = label,
                style = CompanionType.MonoSmall.copy(color = if (key == "c-c") CompanionColor.Warn else CompanionColor.Signal),
                modifier = Modifier
                    .testTag("agents.key.$key")
                    .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                    .clickable(enabled = live) { onKey(key) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        listOf("y", "n").forEach { ch ->
            Text(
                text = ch,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                modifier = Modifier.testTag("agents.key.$ch").border(CompanionSpace.Hairline, CompanionColor.Line).clickable(enabled = live) { onSendText(ch, false) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        HairlineField(
            value = input,
            onValueChange = onInput,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Send,
            keepKeyboardOnDone = true,
            placeholder = if (live) "type, ⏎ sends with Enter" else "session has exited",
            onDone = { if (live) onSendText(input, true) },
            modifier = Modifier.weight(1f).testTag("agents.input"),
        )
        ActionButton(label = "SEND", kind = ActionKind.PRIMARY, enabled = live, onClick = { onSendText(input, true) }, modifier = Modifier.testTag("agents.send"))
    }
}

/** The pane as monospace lines with SGR colours; scrolls both ways when wider than the screen. */
@Composable
private fun PaneText(pane: AgentPane, live: Boolean) {
    val lines = remember(pane.ansi) { AnsiText.parse(pane.ansi) }
    val vertical = rememberScrollState()
    LaunchedEffect(pane.ansi) { vertical.scrollTo(vertical.maxValue) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vertical)
            .horizontalScroll(rememberScrollState())
            .padding(CompanionSpace.Sm)
            .testTag("agents.pane"),
    ) {
        lines.forEachIndexed { row, spans ->
            Text(
                text = annotate(spans, cursorAt = if (live && row == pane.cursorY) pane.cursorX else -1),
                style = CompanionType.Mono.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = CompanionColor.Text),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

private fun annotate(spans: List<AnsiText.Span>, cursorAt: Int): AnnotatedString = buildAnnotatedString {
    var col = 0
    var cursorDrawn = false
    spans.forEach { sp ->
        val style = SpanStyle(
            color = sp.fg?.let { Color(it.toULong().toLong() and 0xFFFFFFFF) }?.let { if (sp.dim) it.copy(alpha = 0.6f) else it }
                ?: if (sp.dim) CompanionColor.TextDim else CompanionColor.Text,
            background = sp.bg?.let { Color(it and 0xFFFFFFFF) } ?: if (sp.inverse) CompanionColor.LineStrong else Color.Unspecified,
            fontWeight = if (sp.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (sp.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (sp.underline) TextDecoration.Underline else TextDecoration.None,
        )
        val end = col + sp.text.length
        if (cursorAt in col until end) {
            val i = cursorAt - col
            withStyle(style) { append(sp.text.substring(0, i)) }
            withStyle(style.copy(background = CompanionColor.Signal, color = CompanionColor.Void)) { append(sp.text[i].toString()) }
            withStyle(style) { append(sp.text.substring(i + 1)) }
            cursorDrawn = true
        } else {
            withStyle(style) { append(sp.text) }
        }
        col = end
    }
    if (cursorAt >= 0 && !cursorDrawn) {
        repeat((cursorAt - col).coerceAtLeast(0)) { append(' ') }
        withStyle(SpanStyle(background = CompanionColor.Signal, color = CompanionColor.Void)) { append(' ') }
    }
}


private fun statusLabel(status: String): String = when (status) {
    "waiting_approval" -> "needs you"
    "waiting_input" -> "idle"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    "waiting_approval" -> CompanionColor.Warn
    "waiting_input" -> CompanionColor.TextDim
    else -> CompanionColor.Signal
}

@Composable
private fun ModeChip(label: String, hint: String, on: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = when { !enabled -> CompanionColor.TextMute; on -> CompanionColor.Signal; else -> CompanionColor.TextDim }
    Column(
        modifier = Modifier
            .testTag(tag)
            .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.Line)
            .background(if (on) CompanionColor.SignalDim else CompanionColor.Void)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = CompanionType.MonoSmall.copy(color = color), maxLines = 1)
        Text(text = hint, style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute, fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Structured (stream-json) session as a chat: operator prompts, streamed answers as markdown,
 * tool rows, and an approval strip that answers the agent's `can_use_tool` request (A23.3/A23.4).
 */
@Composable
private fun ColumnScope.StructuredView(
    session: AgentSession,
    transcript: AgentTranscriptState,
    loading: Boolean,
    input: String,
    error: String?,
    onClose: () -> Unit,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onApprove: (String) -> Unit,
    onInterrupt: () -> Unit,
    onKill: () -> Unit,
) {
    val live = session.status != "exited" && !transcript.exited
    val busy = live && transcript.turnActive
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        Text(text = "◂", style = CompanionType.Mono.copy(color = CompanionColor.Signal), modifier = Modifier.testTag("agents.back").clickable(onClick = onClose).padding(end = CompanionSpace.Xs))
        Column(Modifier.weight(1f)) {
            Text(text = session.title.ifBlank { session.id }, style = CompanionType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cost = if (transcript.costUsd > 0) " · $" + String.format(java.util.Locale.US, "%.2f", transcript.costUsd) else ""
            val model = transcript.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            Text(text = "${session.tool}$model · ${session.cwd}$cost", style = CompanionType.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (busy) LiveDot(size = 5.dp)
            Text(
                text = when {
                    !live -> "EXITED" + (transcript.exitCode?.let { " $it" } ?: "")
                    transcript.approval != null -> "NEEDS YOU"
                    busy -> "WORKING"
                    else -> "IDLE"
                },
                style = CompanionType.MonoSmall.copy(
                    color = when {
                        !live -> CompanionColor.TextMute
                        transcript.approval != null -> CompanionColor.Warn
                        busy -> CompanionColor.Signal
                        else -> CompanionColor.TextDim
                    },
                ),
                modifier = Modifier.testTag("agents.chat.status"),
            )
        }
        if (live) Text(text = "KILL", style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger), modifier = Modifier.testTag("agents.pane.kill").clickable(onClick = onKill).padding(CompanionSpace.Xs))
    }
    Hairline()
    val shownError = error?.takeIf { it.isNotBlank() } ?: transcript.lastError.takeIf { it.isNotBlank() }
    if (shownError != null) {
        StatusStrip(text = shownError, modifier = Modifier.testTag("agents.pane.error"))
        Hairline()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(transcript.messages.size, transcript.messages.lastOrNull()?.text?.length) {
        if (transcript.messages.isNotEmpty()) listState.animateScrollToItem(transcript.messages.lastIndex)
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (loading && transcript.messages.isEmpty()) {
            FetchRow(label = "ATTACHING", modifier = Modifier.testTag("agents.pane.loading"))
        } else if (transcript.messages.isEmpty()) {
            FetchPane(label = "NO TURNS YET", hint = "// type a prompt below · the agent runs on the host in ${session.cwd}", modifier = Modifier.fillMaxSize().testTag("agents.chat.empty"))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("agents.chat"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
                verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
            ) {
                items(transcript.messages, key = { it.id }) { m ->
                    when (m.role) {
                        MessageRole.USER -> TranscriptUserRow(m)
                        MessageRole.ASSISTANT -> TranscriptAssistantRow(m, session.tool)
                        MessageRole.TOOL -> TranscriptToolRow(m)
                    }
                }
            }
        }
    }
    transcript.approval?.let { prompt ->
        Hairline()
        TranscriptApproval(prompt, onApprove)
    }
    Hairline()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        HairlineField(
            value = input,
            onValueChange = onInput,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Send,
            keepKeyboardOnDone = true,
            placeholder = when {
                !live -> "session has exited"
                busy -> "working · STOP to interrupt"
                else -> "prompt the agent"
            },
            onDone = { if (live && !busy) onSubmit(input) },
            modifier = Modifier.weight(1f).testTag("agents.input"),
        )
        if (busy) {
            ActionButton(label = "STOP", kind = ActionKind.DANGER, onClick = onInterrupt, modifier = Modifier.testTag("agents.chat.stop"))
        } else {
            ActionButton(label = "SEND", kind = ActionKind.PRIMARY, enabled = live && input.isNotBlank(), onClick = { onSubmit(input) }, modifier = Modifier.testTag("agents.send"))
        }
    }
}

@Composable
private fun TranscriptUserRow(m: ChatMessage) {
    Row(Modifier.fillMaxWidth().testTag("agents.chat.user")) {
        Spacer(Modifier.width(CompanionSpace.Xxl))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(text = "YOU", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim), modifier = Modifier.padding(end = 2.dp, bottom = 3.dp))
            SelectionContainer {
                Text(
                    text = m.text,
                    style = CompanionType.Body,
                    modifier = Modifier.border(CompanionSpace.Hairline, CompanionColor.LineStrong).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscriptAssistantRow(m: ChatMessage, tool: String) {
    Column(Modifier.fillMaxWidth().testTag("agents.chat.assistant")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 3.dp)) {
            Text(text = tool.uppercase(), style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            if (m.streaming) LiveDot(size = 5.dp)
        }
        SelectionContainer { MarkdownBlocks(text = m.text) }
    }
}

@Composable
private fun TranscriptToolRow(m: ChatMessage) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val name = m.toolName?.ifBlank { null } ?: "tool"
    val running = m.toolRunning
    val failed = m.toolDetail.orEmpty().startsWith("failed")
    val summary = m.toolDetail.orEmpty().ifBlank { m.text.take(80).replace('\n', ' ') }.ifBlank { if (running) "running" else "completed" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(CompanionSpace.Hairline, when { failed -> CompanionColor.Danger; expanded || running -> CompanionColor.Signal; else -> CompanionColor.Line })
            .background(CompanionColor.VoidElevated)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(if (running) "agents.chat.tool.running" else "agents.chat.tool"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running) LiveDot()
            Text(text = name, style = CompanionType.MonoSmall.copy(color = if (failed) CompanionColor.Danger else CompanionColor.Signal, fontWeight = FontWeight.SemiBold))
            Text(text = "·", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim))
            Text(
                text = if (running) "running · $summary" else summary,
                style = CompanionType.MonoSmall.copy(color = if (running) CompanionColor.TextDim else CompanionColor.TextMute),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (expanded && m.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = m.text.trimEnd(),
                    style = CompanionType.Mono.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = CompanionColor.Text),
                    modifier = Modifier.padding(top = CompanionSpace.Sm).horizontalScroll(rememberScrollState()),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TranscriptApproval(prompt: ApprovalPrompt, onApprove: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(CompanionColor.VoidElevated).padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md).testTag("agents.approval"),
    ) {
        Text(
            text = "${prompt.speaker?.uppercase() ?: "TOOL"} wants · ${prompt.command}",
            style = CompanionType.Mono.copy(color = CompanionColor.Warn),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Row(modifier = Modifier.padding(top = CompanionSpace.Sm), horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            prompt.choices.forEach { choice ->
                val deny = choice.equals("deny", ignoreCase = true)
                val color = if (deny) CompanionColor.Danger else CompanionColor.Signal
                Text(
                    text = if (deny) "DENY" else "ALLOW",
                    style = CompanionType.MonoSmall.copy(color = color),
                    modifier = Modifier
                        .testTag(if (deny) "agents.approval.deny" else "agents.approval.allow")
                        .border(CompanionSpace.Hairline, color)
                        .clickable { onApprove(choice) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}


/**
 * Inline directory picker for the NEW panel: root chips, recents, `..`, and the subdirectories of
 * the current host path (git/project folders first). Tap a row to enter it, `USE` to pick it.
 */
@Composable
private fun DirPicker(
    listing: AgentDirListing?,
    loading: Boolean,
    error: String?,
    current: String,
    showHidden: Boolean,
    onNavigate: (String) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().border(CompanionSpace.Hairline, CompanionColor.Line).background(CompanionColor.Void).padding(CompanionSpace.Sm).testTag("agents.dirs"),
        verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
    ) {
        val roots = listing?.roots.orEmpty()
        val recent = listing?.recent.orEmpty().filter { r -> roots.none { it.path == r } }
        if (roots.isNotEmpty() || recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                roots.forEach { r ->
                    Text(
                        text = r.label,
                        style = CompanionType.MonoSmall.copy(color = if (listing?.path == r.path) CompanionColor.Signal else CompanionColor.TextDim),
                        modifier = Modifier.testTag("agents.dirs.root.${r.label}").border(CompanionSpace.Hairline, CompanionColor.LineStrong).clickable { onNavigate(r.path) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                recent.forEach { r ->
                    Text(
                        text = "↺ " + r.substringAfterLast('/').ifBlank { r },
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                        modifier = Modifier.border(CompanionSpace.Hairline, CompanionColor.Line).clickable { onNavigate(r) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            Text(
                text = "..",
                style = CompanionType.Mono.copy(color = if (listing?.parent != null) CompanionColor.Signal else CompanionColor.TextMute),
                modifier = Modifier.testTag("agents.dirs.up").clickable(enabled = listing?.parent != null) { listing?.parent?.let(onNavigate) }.padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs),
            )
            Text(
                text = (listing?.path ?: current).ifBlank { "…" } + if (listing?.git == true) "  ⎇" else "",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("agents.dirs.path"),
            )
            Text(
                text = if (showHidden) "•hidden" else "hidden",
                style = CompanionType.MonoSmall.copy(color = if (showHidden) CompanionColor.Signal else CompanionColor.TextMute),
                modifier = Modifier.testTag("agents.dirs.hidden").clickable { onToggleHidden(!showHidden) }.padding(horizontal = CompanionSpace.Xs),
            )
        }
        if (!error.isNullOrBlank()) Text(text = error, style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn), modifier = Modifier.testTag("agents.dirs.error"))
        if (loading && listing == null) {
            FetchRow(label = "LISTING", padded = false, modifier = Modifier.testTag("agents.dirs.loading"))
        } else if (listing != null) {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                if (listing.dirs.isEmpty()) {
                    Text(text = "// no subdirectories", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute), modifier = Modifier.padding(CompanionSpace.Xs))
                }
                listing.dirs.forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigate(d.path) }.padding(horizontal = CompanionSpace.Xs, vertical = 7.dp).testTag("agents.dirs.row.${d.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                    ) {
                        Text(text = "▸", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
                        Text(text = d.name, style = CompanionType.Mono.copy(color = if (d.git || d.project) CompanionColor.Text else CompanionColor.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (d.git) Text(text = "⎇", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
                        else if (d.project) Text(text = "proj", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim))
                    }
                }
                if (listing.truncated) Text(text = "// list truncated", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
            }
            if (loading) FetchRow(label = "LISTING", padded = false, modifier = Modifier.testTag("agents.dirs.loading"))
            ActionButton(
                label = "USE ${listing.path.substringAfterLast('/').ifBlank { listing.path }}",
                kind = ActionKind.PRIMARY,
                onClick = { onPick(listing.path) },
                modifier = Modifier.testTag("agents.dirs.use"),
            )
        }
    }
}
