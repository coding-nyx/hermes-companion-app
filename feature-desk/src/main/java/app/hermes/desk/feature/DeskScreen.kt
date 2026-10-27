@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.hermes.desk.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.VoiceStreamState
import app.hermes.companion.design.CompanionColor as C
import app.hermes.companion.model.*
import app.hermes.desk.feature.character.RobotScene

data class DeskActions(
    val connect: (String, String, String, String) -> Unit,
    val profile: (String) -> Unit,
    val thread: (SessionRef) -> Unit,
    val newThread: () -> Unit,
    val draft: (String) -> Unit,
    val send: () -> Unit,
    val talk: () -> Unit,
    val endVoice: () -> Unit,
    val interrupt: () -> Unit,
    val decision: (ApprovalPrompt, String) -> Unit,
    val refresh: () -> Unit,
    val dismissUncertain: () -> Unit,
    val ambient: (Boolean) -> Unit,
    val preferences: (Boolean, Boolean, Boolean) -> Unit,
    val disconnect: () -> Unit,
    val openChat: () -> Unit,
    val dismissError: () -> Unit,
    val mute: () -> Unit = endVoice,
    val compact: () -> Unit = {},
    val speechMode: (String) -> Unit = {},
    val stopVision: () -> Unit = {},
    val acknowledgeCompaction: () -> Unit = {},
    val loadModelSettings: () -> Unit = {},
    val setSetting: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val connections: () -> Unit = {},
    val voiceSetup: () -> Unit = {},
    val presets: () -> Unit = {},
)

/** The default surface is a companion. Transcript and typing are deliberately secondary. */
@Composable
fun DeskScreen(state: DeskState, actions: DeskActions, cameraContent: (@Composable () -> Unit)? = null) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var camera by rememberSaveable { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var acknowledgeUnknown by remember { mutableStateOf(false) }
    var contextNotice by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    Surface(Modifier.fillMaxSize(), color = C.Void, contentColor = C.Text) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.connected) Box {
                    OutlinedButton(onClick = { profileMenu = true }, enabled = !state.loading, modifier = Modifier.widthIn(max = 320.dp)) {
                        Text("${state.backend} · ${state.profiles.firstOrNull { it.id == state.profile }?.displayName ?: state.profile}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        ExposedDropdownMenuDefaults.TrailingIcon(profileMenu)
                    }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        state.profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.displayName) }, onClick = { profileMenu = false; actions.profile(profile.id) }) }
                    }
                } else Text("Desk Companion", fontSize = 22.sp, modifier = Modifier.padding(vertical = 10.dp))
                Text(state.status, color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 13.dp))
                Text(if (state.microphoneReady) "MIC ON" else "MIC OFF", color = if (state.microphoneReady) C.Signal else C.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = { settings = true; actions.loadModelSettings() }) { Text("Settings") }
                TextButton(onClick = actions.connections, enabled = !state.working && !state.loading && !state.connecting) { Text("Connections") }
            }
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().background(C.VoidSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = C.Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.dismissError) { Text("Dismiss") }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.connected) {
                    if (maxWidth >= 800.dp && maxHeight >= 420.dp) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        RobotScene(state, Modifier.weight(1f).fillMaxHeight())
                        ConnectionPane(state, actions, Modifier.weight(1f).widthIn(max = 480.dp))
                    } else ConnectionPane(state, actions, Modifier.fillMaxSize())
                } else {
                    val sideBySide = maxWidth >= 700.dp && maxHeight >= 340.dp
                    if (sideBySide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        RobotScene(state, Modifier.weight(1.05f).fillMaxHeight())
                        PerchCaption(state, actions, { history = true }, Modifier.weight(.95f))
                    } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RobotScene(state, Modifier.weight(1f).fillMaxWidth())
                        PerchCaption(state, actions, { history = true }, Modifier.fillMaxWidth().heightIn(max = 220.dp), centered = true)
                    }
                }
            }
            if (state.connected) {
                if (state.visionSharing) Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera shared with agent", color = C.Signal, fontSize = 12.sp)
                    TextButton(actions.stopVision) { Text("Stop sharing") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (state.voice) {
                        VoiceStreamState.IDLE -> FilledTonalButton(actions.talk, enabled = state.canSend) { Text("Start voice") }
                        VoiceStreamState.MUTED -> FilledTonalButton(actions.mute) { Text("Resume mic") }
                        else -> FilledTonalButton(actions.mute) { Text("Mute") }
                    }
                    OutlinedButton(onClick = { camera = true }, enabled = cameraContent != null && state.binding != null) { Text(if (state.visionSharing) "Camera on" else "Camera") }
                    OutlinedButton(onClick = { history = true }) { Text("History") }
                    TextButton(actions.endVoice, enabled = state.voice != VoiceStreamState.IDLE || state.visionSharing) { Text("End") }
                }
            }
        }
    }
    if (history && state.connected) ModalBottomSheet(onDismissRequest = { history = false }, sheetState = sheetState, containerColor = C.VoidElevated) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation", fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { history = false }) { Text("Close history") }
            }
            ConversationPane(state, actions, { acknowledgeUnknown = true }, Modifier.weight(1f))
            if (state.compactionUncertain) TextButton(onClick = { contextNotice = true }) { Text("Resolve context notice") }
        }
    }
    if (camera && cameraContent != null) ModalBottomSheet(onDismissRequest = { camera = false }, containerColor = C.VoidElevated) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent vision", fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { camera = false }) { Text("Close camera controls") }
            }
            cameraContent()
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Make yourself at home") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice follows the selected speech service. Host speech uses Hermes’ existing configuration; no provider key is entered on this tablet.", fontSize = 13.sp, color = C.TextDim)
            Text("Speech service", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.speechMode == "hermes", { actions.speechMode("hermes") }, label = { Text("Hermes") })
                FilterChip(state.speechMode == "system", { actions.speechMode("system") }, label = { Text("Device") })
            }
            TextButton(onClick = { settings = false; actions.voiceSetup() }) { Text("Voice setup and test") }
            TextButton(onClick = { settings = false; actions.presets() }) { Text("Device presets") }
            if (state.binding != null) {
                Text("Agent model · ${state.backend}", fontWeight = FontWeight.Medium)
                Box {
                    OutlinedButton(onClick = { models = true }, enabled = !state.settingsBusy && state.canSend && state.modelSettings != null) {
                        Text(state.modelSettings?.model ?: if (state.settingsBusy) "Loading models…" else "Models unavailable", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp)); ExposedDropdownMenuDefaults.TrailingIcon(models)
                    }
                    DropdownMenu(models, { models = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        state.modelSettings?.models.orEmpty().distinctBy { it.provider to it.id }.forEach { model ->
                            DropdownMenuItem(text = { Text("${model.name} · ${model.provider}", maxLines = 2) }, onClick = {
                                models = false
                                actions.setSetting("model", "${model.id} --provider ${model.provider} --session", false)
                            })
                        }
                    }
                }
                if (state.backend == "Hermes") Text("Thinking", fontWeight = FontWeight.Medium)
                if (state.backend == "Hermes") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("low" to "Fast", "medium" to "Balanced", "high" to "Deep").forEach { (value, label) ->
                        FilterChip(state.modelSettings?.reasoning == value,
                            { actions.setSetting("reasoning", value, false) }, enabled = !state.settingsBusy && state.canSend && state.modelSettings != null, label = { Text(label) })
                    }
                }
                state.settingNotice?.let { Text(it, color = C.TextDim, fontSize = 12.sp) }
                state.settingConfirmation?.let { confirm ->
                    Text(confirm.message, color = C.Warn, fontSize = 13.sp)
                    TextButton(onClick = { actions.setSetting(confirm.key, confirm.value, true) }, enabled = !state.settingsBusy) { Text("Confirm model change") }
                }
            }
            SettingSwitch("Keep screen on while charging", state.keepAwake) { actions.preferences(state.privateCaptions, it, state.reducedMotion) }
            SettingSwitch("Hide captions and conversation", state.privateCaptions) { actions.preferences(it, state.keepAwake, state.reducedMotion) }
            SettingSwitch("Reduce motion", state.reducedMotion) { actions.preferences(state.privateCaptions, state.keepAwake, it) }
            if (state.connected) {
                Text("${state.origin}\n${state.profile}", fontSize = 12.sp, color = C.TextDim)
                OutlinedButton(onClick = { settings = false; actions.disconnect() }) { Text("Disconnect / change host") }
            }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (acknowledgeUnknown) AlertDialog(onDismissRequest = { acknowledgeUnknown = false }, title = { Text("Have you checked host history?") },
        text = { Text("This message may already have been delivered. Clearing this notice does not resend it. Check the conversation before writing it again.") },
        confirmButton = { TextButton(onClick = { acknowledgeUnknown = false; actions.dismissUncertain() }) { Text("I checked history") } },
        dismissButton = { TextButton(onClick = { acknowledgeUnknown = false; actions.refresh() }) { Text("Refresh history") } })
    if (contextNotice) AlertDialog(onDismissRequest = { contextNotice = false }, title = { Text("Check context maintenance on the host") },
        text = { Text("Compaction may still be running. Check the host first. Clearing this notice never runs compaction again; it only allows conversation to continue.") },
        confirmButton = { TextButton(onClick = { contextNotice = false; actions.acknowledgeCompaction() }) { Text("I checked the host") } },
        dismissButton = { TextButton(onClick = { contextNotice = false }) { Text("Keep waiting") } })
}

@Composable
private fun PerchCaption(s: DeskState, a: DeskActions, history: () -> Unit, modifier: Modifier, centered: Boolean = false) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(s.caption, fontSize = 27.sp, lineHeight = 37.sp, fontWeight = FontWeight.Normal, color = C.Text,
            maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        if (s.session == null) TextButton(history) { Text("Choose a conversation") }
        if (s.working) {
            Text(if (s.tool.isBlank()) "Working on your request" else "Working · ${s.tool}", color = C.TextDim, fontSize = 13.sp)
            TextButton(a.interrupt) { Text("Stop task") }
        }
        if (s.compacting) Text("Maintaining conversation context", color = C.TextDim, fontSize = 13.sp)
        if (s.uncertain) TextButton(onClick = { a.refresh(); history() }) { Text("Check history") }
        if (s.pending != null && !s.privateCaptions) DecisionCard(s.pending, s.decisionSending, a.decision, a.openChat)
    }
}

@Composable
private fun ConnectionPane(s: DeskState, actions: DeskActions, modifier: Modifier) {
    var origin by remember(s.origin) { mutableStateOf(s.origin) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var passwordAuth by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your agent.\nA place beside you.", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
        Text("Connect to Hermes and settle into a conversation. Your threads and tools stay on your host.", color = C.TextDim, lineHeight = 23.sp)
        Surface(color = C.SignalDim, shape = MaterialTheme.shapes.small) { Text("HERMES CONNECTION", color = C.Signal, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(10.dp)) }
        OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Dashboard address") },
            placeholder = { Text("http://your-host:9119") }, singleLine = true, enabled = !s.connecting,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        SettingSwitch("Sign in with username and password", passwordAuth) { passwordAuth = it }
        if (passwordAuth) {
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !s.connecting)
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), enabled = !s.connecting)
        } else OutlinedTextField(token, { token = it }, label = { Text("Dashboard token · optional") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !s.connecting)
        Button(onClick = { actions.connect(origin, if (passwordAuth) user else "", if (passwordAuth) password else "", if (!passwordAuth) token else ""); password = ""; token = "" },
            enabled = origin.isNotBlank() && !s.connecting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (s.connecting) "Connecting…" else "Connect to Hermes")
        }
        Text("Use a reachable LAN, Tailscale or HTTPS dashboard. Saved credentials stay encrypted on this device. Connecting never turns on the microphone.", color = C.TextMute, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun ConversationPane(s: DeskState, a: DeskActions, dismissUnknown: () -> Unit, modifier: Modifier) {
    var profileMenu by remember { mutableStateOf(false) }
    var threadMenu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { profileMenu = true }, enabled = !s.loading) { Text(s.profiles.firstOrNull { it.id == s.profile }?.displayName ?: s.profile); Spacer(Modifier.width(8.dp)); ExposedDropdownMenuDefaults.TrailingIcon(profileMenu) }
                DropdownMenu(profileMenu, { profileMenu = false }) { s.profiles.forEach { p -> DropdownMenuItem(text = { Text(p.displayName) }, onClick = { profileMenu = false; a.profile(p.id) }) } }
            }
            Spacer(Modifier.weight(1f))
            Text(s.backend, color = C.TextMute, fontSize = 11.sp)
            TextButton(onClick = a.refresh, enabled = !s.loading) { Text("Refresh") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { threadMenu = true }, enabled = !s.loading) {
                    Text(s.session?.title?.ifBlank { "Untitled conversation" } ?: "Choose conversation", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.width(8.dp)); ExposedDropdownMenuDefaults.TrailingIcon(threadMenu)
                }
                DropdownMenu(threadMenu, { threadMenu = false }) {
                    s.sessions.forEach { thread -> DropdownMenuItem(text = { Text(thread.title.ifBlank { "Untitled conversation" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { threadMenu = false; a.thread(thread) }) }
                    if (s.sessions.isEmpty()) DropdownMenuItem(text = { Text("No conversations yet") }, onClick = {}, enabled = false)
                }
            }
            TextButton(onClick = a.newThread, enabled = !s.loading) { Text("New") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s.context?.percent?.let { "Context · ${it.toInt()}%" } ?: "Context · usage unavailable", color = C.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (s.capabilities.compaction) TextButton(onClick = a.compact, enabled = s.canSend && !s.compactionUncertain && s.voice == VoiceStreamState.IDLE) { Text(if (s.compacting) "Compacting…" else "Compact") }
            if (s.capabilities.companionChat) TextButton(onClick = a.openChat) { Text("Open in chat") }
        }
        HorizontalDivider(color = C.Line)
        if (s.uncertain) Surface(color = C.VoidSurface) {
            Column(Modifier.padding(12.dp)) {
                Text("Delivery needs checking", color = C.Warn)
                Text("Nothing will be resent automatically.", fontSize = 12.sp, color = C.TextDim)
                Row { TextButton(onClick = a.refresh) { Text("Check history") }; TextButton(onClick = dismissUnknown) { Text("Clear notice…") } }
            }
        }
        if (s.pending != null && !s.privateCaptions) DecisionCard(s.pending, s.decisionSending, a.decision, a.openChat)
        if (s.tool.isNotBlank()) Text("Working · ${s.tool}", color = C.Signal, fontSize = 12.sp)
        val messages = if (s.privateCaptions) emptyList() else s.messages.filter { it.role != MessageRole.TOOL && !it.reasoning }
        val list = rememberLazyListState()
        LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
            if (messages.isNotEmpty() && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.size - 3) list.scrollToItem(messages.lastIndex)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (s.privateCaptions) "Conversation hidden" else if (s.loading) "Opening your conversation…" else if (s.session == null) "Where shall we begin?" else "Make room for an idea.", fontSize = 23.sp)
                    Text(if (s.privateCaptions) "Change privacy in Settings to show captions." else if (s.session == null) "Choose a thread, or start a new desk conversation." else "Type a thought below, or tap Talk when you’re ready.", color = C.TextDim, fontSize = 14.sp)
                }
            }
            items(messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth().background(if (message.role == MessageRole.USER) C.VoidSurface else C.Void).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (message.role == MessageRole.USER) "YOU" else s.backend.uppercase(), fontSize = 10.sp, letterSpacing = 1.5.sp, color = if (message.role == MessageRole.USER) C.TextMute else C.Signal)
                    SelectionContainer { Text(message.text, fontSize = 16.sp, lineHeight = 25.sp) }
                    if (message.delivery == MessageDelivery.SENDING) Text(if (s.uncertain) "Check delivery" else "Sending…", fontSize = 10.sp, color = C.TextMute)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(s.draft, a.draft, modifier = Modifier.weight(1f), placeholder = { Text("Say what’s on your mind") },
                enabled = s.session != null && !s.loading, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (s.canSend && s.draft.isNotBlank()) a.send() }))
            Button(onClick = a.send, enabled = s.canSend && s.draft.isNotBlank(), modifier = Modifier.heightIn(min = 54.dp)) { Text("Send") }
        }
    }
}

@Composable
private fun DecisionCard(prompt: ApprovalPrompt, sending: Boolean, decide: (ApprovalPrompt, String) -> Unit, openChat: () -> Unit) {
    var answer by remember(prompt.requestId) { mutableStateOf("") }
    Surface(color = C.VoidSurface, modifier = Modifier.fillMaxWidth().border(1.dp, C.Warn.copy(alpha = .4f), MaterialTheme.shapes.medium), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your decision is needed", color = C.Warn, fontSize = 15.sp)
            Text(prompt.command.take(2000), fontSize = 13.sp, lineHeight = 20.sp)
            if (prompt.kind !in setOf("approval", "clarify")) {
                Text("Complete this protected request in Hermes Companion.", fontSize = 12.sp)
                TextButton(onClick = openChat) { Text("Open in chat") }
            } else {
                if (prompt.kind == "clarify") {
                    OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, enabled = !sending, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { decide(prompt, answer) }, enabled = !sending && answer.isNotBlank()) { Text("Reply") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.choices.forEach { choice -> OutlinedButton(onClick = { decide(prompt, choice) }, enabled = !sending) { Text(choice.replaceFirstChar { it.uppercase() }) } }
                }
                if (sending) Text("Submitting decision…", color = C.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked, change)
    }
}
