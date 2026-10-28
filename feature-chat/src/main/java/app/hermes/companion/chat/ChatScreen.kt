package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.FetchSkeleton
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.LiveDot
import app.hermes.companion.design.SignalCursor
import app.hermes.companion.design.WalkingEllipsis
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlock
import app.hermes.companion.model.RoomRef
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import app.hermes.companion.design.HairlineField
import androidx.compose.ui.text.input.KeyboardType
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.MessageDelivery
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomParticipant
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.domain.Rooms
import app.hermes.companion.model.ModelCatalog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class SlashCmd(
    val command: String,
    val description: String,
    val onTrigger: () -> Unit,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    draft: String,
    streaming: Boolean,
    error: String?,
    approval: ApprovalPrompt?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String) -> Unit,
    submitting: Boolean = false,
    creatingThread: Boolean = false,
    onRetryMessage: (String) -> Unit = {},
    onReadAloud: (ChatMessage) -> Unit = {},
    readAloudMessageId: String? = null,
    hasMoreOlder: Boolean = false,
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    rewindTargetId: String? = null,
    onRewind: (ChatMessage) -> Unit = {},
    onCancelRewind: () -> Unit = {},
    isListeningVoice: Boolean = false,
    onVoiceClick: () -> Unit = {},
    onVoiceLongClick: (() -> Unit)? = null,
    compactComposer: Boolean = false,
    loading: Boolean = false,
    historySource: String = "",
    onRetryHistory: () -> Unit = {},
    modelOverride: String = "",
    modelCatalog: ModelCatalog? = null,
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onOpenModelPicker: () -> Unit = {},
    pendingAttachments: List<ChatAttachment> = emptyList(),
    attachOpen: Boolean = false,
    onToggleAttach: () -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onPickCamera: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onOpenMedia: (ChatBlock) -> Unit = {},
    onFetchMedia: suspend (String) -> ByteArray? = { null },
    threadId: String? = null,
    /** Agent room (P21): who is in it. Non-empty switches the screen to room mode. */
    participants: List<RoomParticipant> = emptyList(),
    /** Profile taking a turn right now (room mode); labels the pending row. */
    pendingSpeaker: String? = null,
    onMention: (String) -> Unit = {},
    profiles: List<ProfileRef> = emptyList(),
    assistantName: String = "Agent",
    sendAvailable: Boolean = true,
    attachmentsAvailable: Boolean = true,
    modelsAvailable: Boolean = true,
    voiceAvailable: Boolean = true,
    readAloudAvailable: Boolean = true,
    commandsAvailable: Boolean = true,
    /** Room v2 (A22.10): floor state, participants, actions. Null outside a room. */
    room: RoomRef? = null,
    roomCandidates: List<Pair<String, String>> = emptyList(),
    onRoomPause: () -> Unit = {},
    onRoomContinue: (Int) -> Unit = {},
    onRoomSummarize: () -> Unit = {},
    onRoomAddParticipant: (String) -> Unit = {},
    onRoomRemoveParticipant: (String) -> Unit = {},
    onRoomRename: (String) -> Unit = {},
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
    modifier: Modifier = Modifier,
    nativeCommands: List<app.hermes.companion.model.NativeCommand> = emptyList(),
    approvalBusy: Boolean = false,
    onOpenReasoningPicker: (() -> Unit)? = null,
) {
    var composerActive by remember(threadId) { mutableStateOf(false) }
    var expandedDraft by rememberSaveable(threadId) { mutableStateOf(false) }
    val roomMode = participants.isNotEmpty()
    val transcript = remember(messages) { messages.distinctBy { it.id } }
    val listState = remember(threadId) { LazyListState() }
    val scope = rememberCoroutineScope()
    var landed by remember(threadId) { mutableStateOf(false) }
    var prevFirstId by remember(threadId) { mutableStateOf<String?>(null) }
    var prevLastId by remember(threadId) { mutableStateOf<String?>(null) }
    var prevCount by remember(threadId) { mutableStateOf(0) }
    val awayFromEnd by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex
            listState.firstVisibleItemScrollOffset
            listState.canScrollForward
        }
    }
    LaunchedEffect(threadId, messages.firstOrNull()?.id, messages.lastOrNull()?.id, messages.size) {
        val firstId = messages.firstOrNull()?.id
        val lastId = messages.lastOrNull()?.id
        val count = messages.size
        val added = count - prevCount
        val prepended = prevCount > 0 && added > 0 && lastId == prevLastId && firstId != prevFirstId
        if (prepended && listState.canScrollForward) {
            val idx = listState.firstVisibleItemIndex + added
            val off = listState.firstVisibleItemScrollOffset
            listState.scrollToItem(idx, off)
        }
        prevFirstId = firstId
        prevLastId = lastId
        prevCount = count
    }
    var lastSentId by remember(threadId) { mutableStateOf<String?>(null) }
    // Tail-follow: keep the newest row in view while text streams. Only an operator drag away from
    // the end turns it off; dragging back to the bottom or tapping the LIVE/[END] pill turns it on.
    // Checking "am I exactly at the end?" per frame is not enough — a growing row is taller than
    // the remaining space by the time the check runs, so the view silently stopped following.
    var followTail by remember(threadId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followTail = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (!listState.canScrollForward) followTail = true
            }
        }
    }
    LaunchedEffect(threadId, messages.lastOrNull()?.id, messages.lastOrNull()?.text, messages.size, streaming) {
        if (messages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        // A message the operator just sent always lands in view, even when the IME shrank the
        // viewport and left the list "away from end".
        val last = messages.last()
        val justSent = last.role == MessageRole.USER && last.id != lastSentId
        if (justSent) {
            lastSentId = last.id
            followTail = true
        }
        if (!landed || followTail || !listState.canScrollForward) {
            listState.scrollToEnd()
            landed = true
        }
    }
    LaunchedEffect(listState, hasMoreOlder, loadingOlder) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index <= 1 && hasMoreOlder && !loadingOlder) onLoadOlder()
            }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .imePadding()
            .testTag("chat.surface"),
    ) {
        if (roomMode && room != null) {
            RoomBar(
                room = room,
                streaming = streaming,
                profiles = profiles,
                candidates = roomCandidates,
                onMention = onMention,
                onPause = onRoomPause,
                onStop = onInterrupt,
                onContinue = onRoomContinue,
                onSummarize = onRoomSummarize,
                onAdd = onRoomAddParticipant,
                onRemove = onRoomRemoveParticipant,
                onRename = onRoomRename,
            )
            Hairline()
        }
        if (!loading && messages.isEmpty()) {
            val draft = DraftThread.isDraft(threadId)
            FetchPane(
                label = if (!error.isNullOrBlank()) "Could not load this conversation" else "Start a conversation",
                hint = when {
                    !error.isNullOrBlank() -> error
                    draft -> "Write your first message to create this conversation."
                    historySource.isNotBlank() -> "Write a message below."
                    else -> "Write a message below."
                },
                scanning = false,
                retryLabel = if (!error.isNullOrBlank()) "RETRY" else null,
                onRetry = if (!error.isNullOrBlank()) onRetryHistory else null,
                modifier = Modifier
                    .weight(1f)
                    .testTag(if (!error.isNullOrBlank()) "chat.history.failed" else "chat.empty"),
            )
        } else {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CompanionSpace.Lg)
                .testTag("chat.list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (loading) {
                item(key = "history.loading") {
                    FetchRow(
                        label = if (historySource.isBlank()) "loading transcript" else "loading transcript · $historySource",
                        padded = false,
                        modifier = Modifier.testTag("chat.loading"),
                    )
                }
                if (messages.isEmpty()) {
                    item(key = "history.skeleton") { FetchSkeleton(lines = 4, padded = false) }
                }
            }
            // Duplicate keys crash LazyColumn outright; a reducer race must never take the app down.
            itemsIndexed(transcript, key = { _, message -> message.id }) { index, message ->
                Column(Modifier.padding(top = transcriptGap(transcript.getOrNull(index - 1), message))) {
                    when (message.role) {
                        MessageRole.USER -> UserRow(message, if (roomMode) ({}) else onRewind, onFetchMedia, onOpenMedia, onRetryMessage)
                        MessageRole.TOOL -> Box(Modifier.padding(start = AgentRailInset)) {
                            ToolRow(message, onFetchMedia, onOpenMedia)
                        }
                        MessageRole.ASSISTANT -> if (message.reasoning) ReasoningRow(message) else AssistantRow(message, onFetchMedia, onOpenMedia, participants, profiles, assistantName, onReadAloud, readAloudMessageId, readAloudAvailable)
                    }
                }
            }
            val last = messages.lastOrNull()
            val awaitingText = streaming && !(last?.role == MessageRole.ASSISTANT && last.streaming)
            if (awaitingText) {
                item(key = "stream.pending") {
                    val who = if (roomMode) pendingSpeaker else null
                    PendingRow(
                        label = when {
                            last?.role == MessageRole.TOOL && last.toolRunning ->
                                "running · ${last.toolName?.ifBlank { null } ?: "tool"}"
                            roomMode && who == null -> "waiting for the room"
                            else -> "thinking"
                        },
                        speaker = who,
                        railIndex = Rooms.speakerIndex(who, participants),
                        participants = participants,
                        profiles = profiles,
                        assistantName = assistantName,
                        modifier = Modifier.padding(top = transcriptGap(last, MessageRole.ASSISTANT, who)),
                    )
                }
            }
        }
        ChatScrollTrack(
            listState = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp, top = CompanionSpace.Md, bottom = CompanionSpace.Md),
        )
        if (awayFromEnd) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = CompanionSpace.Md, bottom = CompanionSpace.Md)
                    .background(CompanionColor.VoidElevated, androidx.compose.foundation.shape.CutCornerShape(8.dp))
                    .border(1.dp, CompanionColor.LineStrong, androidx.compose.foundation.shape.CutCornerShape(8.dp))
                    .heightIn(min = 48.dp)
                    .clickable {
                        followTail = true
                        scope.launch {
                            listState.scrollToEnd()
                            landed = true
                        }
                    }
                    .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs)
                    .testTag(if (streaming) "chat.live" else "chat.end"),
            ) {
                if (streaming) LiveDot()
                Text(
                    text = if (streaming) "LIVE" else "[END]",
                    style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                )
            }
        }
        }
        }
        if (!error.isNullOrBlank() && (loading || messages.isNotEmpty())) {
            Text(
                text = error,
                style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            )
        }
        if (approval != null) {
            ApprovalStrip(approval, onApproval, approvalBusy)
        }
        if (roomMode && room != null && !streaming) {
            RoomFloorStrip(room, onRoomContinue)
        }
        if (rewindTargetId != null) {
            RewindStrip(onCancelRewind)
        }
        Hairline()
        var slashOpen by rememberSaveable(threadId) { mutableStateOf(false) }
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("chat.attach.pending"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                pendingAttachments.forEach { item ->
                    val attachShape = RoundedCornerShape(8.dp)
                    Text(
                        text = "${item.kind.name} · ${item.name}  ×",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .clip(attachShape)

                            .clickable { onRemoveAttachment(item.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        if (attachOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .horizontalScroll(rememberScrollState())
                    .testTag("chat.attach.menu"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                listOf(
                    "PHOTO" to onPickPhoto,
                    "CAMERA" to onPickCamera,
                    "VIDEO" to onPickVideo,
                    "FILE" to onPickFile,
                ).forEach { (label, action) ->
                    val menuShape = RoundedCornerShape(8.dp)
                    Text(
                        text = label,
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("chat.attach.${label.lowercase()}")
                            .clip(menuShape)

                            .clickable(onClick = action)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (roomMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("chat.mentions"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "TO", style = CompanionType.Caption)
                participants.forEach { p ->
                    val mentionShape = RoundedCornerShape(6.dp)
                    Text(
                        text = "@${p.glyph}",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("chat.mention.${p.glyph}")
                            .clip(mentionShape)

                            .clickable { onMention(p.glyph) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val slashCommands = remember(onArm, onDisarm, onOpenModelPicker, onVoiceClick, onDraftChange, onSend, nativeCommands) {
            listOf(
                SlashCmd("/arm", "arm hands") { onArm(); onDraftChange("") },
                SlashCmd("/disarm", "disarm hands") { onDisarm(); onDraftChange("") },
                SlashCmd("/model", "pick model") { onOpenModelPicker(); onDraftChange("") },
                SlashCmd("/voice", "toggle voice") { onVoiceClick(); onDraftChange("") },
                SlashCmd("/clear", "clear draft") { onDraftChange("") },

            ) + nativeCommands.filter { it.name !in setOf("arm", "disarm", "model", "voice", "clear") }.map { command ->
                SlashCmd("/${command.name}", command.description) { onDraftChange("/${command.name} ") }
            }
        }
        val filteredSlash = if (draft.startsWith("/")) {
            slashCommands.filter { it.command.startsWith(draft.trim(), ignoreCase = true) }
        } else if (slashOpen) {
            slashCommands
        } else {
            emptyList()
        }

        if (filteredSlash.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("chat.slash.bar"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "CMD", style = CompanionType.Caption.copy(color = CompanionColor.Signal))
                filteredSlash.forEach { cmd ->
                    val cmdShape = RoundedCornerShape(8.dp)
                    Row(
                        modifier = Modifier
                            .clip(cmdShape)

                            .clickable {
                                slashOpen = false
                                cmd.onTrigger()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = cmd.command,
                            style = CompanionType.Caption.copy(
                                color = CompanionColor.Signal,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            text = cmd.description,
                            style = CompanionType.Caption.copy(color = CompanionColor.TextDim, fontSize = 13.sp),
                        )
                    }
                }
            }
        }

        MarkdownComposer(
            value = draft,
            placeholder = "Message $assistantName…",
            onValueChange = onDraftChange,
            onSend = { if (!submitting && !creatingThread && sendAvailable) onSend() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = if (compactComposer) 4.dp else 8.dp),
            onOpenModelSettings = if (!roomMode && modelsAvailable) onOpenModelPicker else null,
            onOpenReasoningSettings = if (!roomMode && modelsAvailable) (onOpenReasoningPicker ?: onOpenModelPicker) else null,
            modelSettingsEnabled = !creatingThread && app.hermes.companion.domain.DraftThread.isPersisted(threadId),
            compactLayout = compactComposer,
            onEditingChange = { composerActive = it },
            onExpand = { expandedDraft = true },
            leadingControls = {
            if (!roomMode && (attachmentsAvailable || commandsAvailable)) {
                Row {
                    if (attachmentsAvailable) ComposerButton("Add attachment", "chat.attach", onToggleAttach, foreground = CompanionColor.TextDim, border = Color.Transparent) { tint ->
                        ComposerActionIcon(ComposerIcon.ADD, tint)
                    }
                    val commandsActive = slashOpen || draft.startsWith("/")
                    if (commandsAvailable) ComposerButton(
                        if (slashOpen) "Hide commands" else "Show commands",
                        "chat.slash", { slashOpen = !slashOpen },
                        foreground = if (commandsActive) CompanionColor.Warn else CompanionColor.TextDim,
                        background = if (commandsActive) CompanionColor.Warn.copy(alpha = 0.2f) else Color.Transparent,
                        border = Color.Transparent,
                    ) { tint -> ComposerActionIcon(ComposerIcon.COMMANDS, tint) }
                }
            }
            },
            trailingControls = {
            Row(verticalAlignment = Alignment.Bottom) {
                if (!roomMode && composerActive && voiceAvailable) {
                    ComposerButton(
                        if (isListeningVoice) "Stop dictation" else "Dictate message",
                        "chat.voice", onVoiceClick,
                        onLongClick = onVoiceLongClick,
                        foreground = if (isListeningVoice) CompanionColor.Warn else CompanionColor.TextDim,
                        background = if (isListeningVoice) CompanionColor.Warn.copy(alpha = 0.2f) else Color.Transparent,
                        border = Color.Transparent,
                    ) { tint -> ComposerActionIcon(if (isListeningVoice) ComposerIcon.STOP else ComposerIcon.MICROPHONE, tint) }
                }
                val interrupting = streaming && !roomMode && draft.isBlank()
                val busy = submitting || creatingThread
                val label = when {
                    creatingThread -> "Creating thread"
                    submitting -> "Sending message"
                    interrupting -> "Interrupt reply"
                    streaming && !roomMode -> "Steer reply"
                    else -> "Send message"
                }
                val icon = when {
                    interrupting -> ComposerIcon.STOP
                    streaming && !roomMode -> ComposerIcon.STEER
                    else -> ComposerIcon.SEND
                }
                ComposerButton(
                    label, "chat.send", if (interrupting) onInterrupt else onSend,
                    enabled = !busy && (sendAvailable || interrupting),
                    foreground = if (interrupting) CompanionColor.Warn else CompanionColor.Void,
                    background = if (interrupting) CompanionColor.Warn.copy(alpha = 0.2f) else CompanionColor.Signal,
                    border = if (interrupting) CompanionColor.Warn else CompanionColor.Signal,
                ) { tint -> ComposerActionIcon(icon, tint, busy) }
            }            },
        )
    }
    if (expandedDraft) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { expandedDraft = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("chat.expanded"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            app.hermes.companion.design.SheetHeading("Edit draft", { expandedDraft = false },
                closeModifier = Modifier.testTag("chat.expanded.close"))
            MarkdownComposer(draft, onDraftChange, expanded = true, tagPrefix = "chat.expanded", placeholder = "Message $assistantName…")
            app.hermes.companion.design.ActionButton("Done editing", { expandedDraft = false },
                kind = app.hermes.companion.design.ActionKind.PRIMARY, modifier = Modifier.fillMaxWidth().testTag("chat.expanded.done"))
        }
    }

}

/** Tool rows and the agent rail share this inset so a turn reads as one column. */
private val AgentRailInset = 10.dp

/** Operator turn: right-shifted panel, hairline frame, `YOU` label. Tap = rewind target. */
@Composable
private fun UserRow(
    message: ChatMessage,
    onRewind: (ChatMessage) -> Unit,
    onFetchMedia: suspend (String) -> ByteArray?,
    onOpenMedia: (ChatBlock) -> Unit,
    onRetryMessage: (String) -> Unit,
) {
    val bubbleShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat.user"),
    ) {
        Spacer(Modifier.width(CompanionSpace.Xl))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
            ) {
                val delivery = when {
                    message.delivery != MessageDelivery.NONE -> message.delivery
                    message.queued -> MessageDelivery.QUEUED
                    else -> MessageDelivery.SENT
                }
                val retryable = delivery == MessageDelivery.FAILED || delivery == MessageDelivery.UNCERTAIN
                if (delivery != MessageDelivery.NONE) {
                    Text(
                        text = when (delivery) {
                            MessageDelivery.UNCERTAIN -> "checking delivery"
                            else -> delivery.name.lowercase()
                        },
                        style = CompanionType.Caption.copy(color = if (delivery == MessageDelivery.SENT) CompanionColor.TextMute else CompanionColor.Warn),
                        modifier = Modifier.testTag(if (message.queued) "chat.queued" else "chat.delivery.${delivery.name.lowercase()}"),
                    )
                }
                if (retryable) {
                    Text(if (delivery == MessageDelivery.UNCERTAIN) "CHECK" else "RETRY", style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier.testTag("chat.retry.${message.id}").heightIn(min = 48.dp).clickable { onRetryMessage(message.id) }.padding(4.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))

                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "You",
                        style = CompanionType.Caption.copy(
                            color = CompanionColor.TextMute,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(CompanionColor.BubbleUser)
                    .heightIn(min = 48.dp)
                    .clickable { onRewind(message) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MessageBlocks(message, onFetchMedia, onOpenMedia)
            }
        }
    }
}

@Composable
private fun ReasoningRow(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = AgentRailInset).testTag("chat.reasoning")) {
        Text(if (expanded) "▾ REASONING" else "▸ REASONING",
            style = CompanionType.Caption.copy(color = CompanionColor.TextDim),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }.padding(vertical = 12.dp))
        if (expanded) Text(message.text, style = CompanionType.Body.copy(color = CompanionColor.TextDim))
    }
}

/**
 * Left rail for agent rows. One accent only: participants differ by glyph and rail texture —
 * index 0 solid, 1 dashed, 2+ dotted (tokens.yaml: "do not invent a second green").
 */
@Composable
private fun AgentRail(live: Boolean, railIndex: Int, modifier: Modifier = Modifier) {
    val color = if (live) CompanionColor.Signal else CompanionColor.SignalDim
    val dash = when (railIndex) {
        0 -> null
        1 -> floatArrayOf(10f, 6f)
        else -> floatArrayOf(3f, 5f)
    }
    Box(
        modifier
            .width(2.dp)
            .fillMaxHeight()
            .drawBehind {
                if (dash == null) {
                    drawRect(color)
                } else {
                    drawLine(
                        color = color,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = size.width,
                        pathEffect = PathEffect.dashPathEffect(dash, 0f),
                    )
                }
            },
    )
}

@Composable
private fun AssistantRow(
    message: ChatMessage,
    onFetchMedia: suspend (String) -> ByteArray?,
    onOpenMedia: (ChatBlock) -> Unit,
    participants: List<RoomParticipant> = emptyList(),
    profiles: List<ProfileRef> = emptyList(),
    assistantName: String,
    onReadAloud: (ChatMessage) -> Unit,
    readAloudMessageId: String?,
    readAloudAvailable: Boolean,
) {
    val label = Rooms.speakerLabel(message.speaker, participants, profiles, assistantName)
    val clipboard = LocalClipboardManager.current
    val railIndex = Rooms.speakerIndex(message.speaker, participants)
    val error = message.toolDetail.orEmpty().takeIf { message.speaker != null && message.text.isBlank() && !message.passed }.orEmpty()
    val bubbleShape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag(if (message.passed) "chat.passed" else "chat.assistant"),
    ) {
        if (message.speaker != null) {
            AgentRail(live = message.streaming, railIndex = railIndex)
            Spacer(Modifier.width(AgentRailInset - 2.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(label, style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.testTag("chat.speaker.${message.id}"))
                    if (message.passed) Text("passed", style = CompanionType.Caption)
                    if (message.summary) Text("summary", style = CompanionType.Caption.copy(color = CompanionColor.Signal), modifier = Modifier.testTag("chat.summary"))
                    if (message.speaker != null && Rooms.mentionsOperator(message.text))
                        Text("@YOU", style = CompanionType.Caption.copy(color = CompanionColor.Warn), modifier = Modifier.testTag("chat.mention.you"))
                    if (error.isNotBlank()) Text(Rooms.turnErrorLabel(error), style = CompanionType.Caption.copy(color = CompanionColor.Warn), modifier = Modifier.testTag("chat.turn.error"))
                    if (message.streaming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LiveDot()
                        Text("streaming", style = CompanionType.Caption, modifier = Modifier.testTag("chat.streaming"))
                    }
                }
                if (!message.streaming && message.text.isNotBlank() && !message.passed) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) },
                        modifier = Modifier.size(48.dp).testTag("chat.copy.${message.id}").semantics { contentDescription = "Copy answer" }) {
                        ComposerActionIcon(ComposerIcon.COPY, CompanionColor.TextDim)
                    }
                    if (readAloudAvailable) IconButton(onClick = { onReadAloud(message) },
                        modifier = Modifier.size(48.dp).testTag("chat.read.${message.id}").semantics {
                            contentDescription = if (readAloudMessageId == message.id) "Stop reading aloud" else "Read answer aloud"
                        }) {
                        ComposerActionIcon(if (readAloudMessageId == message.id) ComposerIcon.STOP else ComposerIcon.SPEAKER, CompanionColor.TextDim)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(bubbleShape)

                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    MessageBlocks(
                        message = message,
                        onFetchMedia = onFetchMedia,
                        onOpenMedia = onOpenMedia,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (message.streaming) {
                        Spacer(Modifier.width(4.dp))
                        SignalCursor(
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .testTag("chat.cursor"),
                        )
                    }
                }
            }

        }
    }
}

/** Shown between send and the first agent token, or while a tool runs with no text yet. */
@Composable
private fun PendingRow(
    label: String,
    speaker: String? = null,
    railIndex: Int = 0,
    participants: List<RoomParticipant> = emptyList(),
    profiles: List<ProfileRef> = emptyList(),
    assistantName: String,
    modifier: Modifier = Modifier,
) {
    val speakerText = when {
        speaker != null -> Rooms.displayName(speaker, participants, profiles)
        else -> Rooms.speakerLabel(null, participants, profiles, assistantName)
    }
    val pillShape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("chat.pending"),
    ) {
        AgentRail(live = true, railIndex = railIndex)
        Spacer(Modifier.width(AgentRailInset - 2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(pillShape)

                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = speakerText,
                style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontWeight = FontWeight.Bold),
            )
            LiveDot()
            Text(
                text = label,
                style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
            )
            WalkingEllipsis()
        }
    }
}

@Composable
private fun RewindStrip(onCancel: () -> Unit) {
    val stripShape = RoundedCornerShape(12.dp)
    val btnShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
            .clip(stripShape)
            .border(CompanionSpace.Hairline, CompanionColor.Warn.copy(alpha = 0.4f), stripShape)

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("chat.rewind"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "REWIND · this turn and after",
            style = CompanionType.Caption.copy(color = CompanionColor.Warn),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "CANCEL",
            style = CompanionType.Caption.copy(color = CompanionColor.Danger, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .testTag("chat.rewind.cancel")
                .clip(btnShape)

                .clickable(onClick = onCancel)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalStrip(prompt: ApprovalPrompt, onApproval: (String) -> Unit, busy: Boolean) {
    val kind = prompt.kind.uppercase()
    val stripShape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
            .clip(stripShape)
            .border(CompanionSpace.Hairline, CompanionColor.Warn, stripShape)

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("chat.approval"),
    ) {
        val who = prompt.speaker?.let { Rooms.glyph(it) + " wants " } ?: ""
        Text(
            text = "$who$kind · ${prompt.command}",
            style = CompanionType.Caption.copy(color = CompanionColor.Warn),

        )
        FlowRow(
            modifier = Modifier.padding(top = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
        ) {
            prompt.choices.forEach { choice ->
                val deny = choice.equals("deny", ignoreCase = true) ||
                    choice.equals("reject", ignoreCase = true)
                val label = prompt.choiceLabels[choice] ?: when {
                    deny -> "DENY"
                    choice.equals("once", ignoreCase = true) ||
                        choice.equals("allow", ignoreCase = true) ||
                        choice.equals("submit", ignoreCase = true) -> "ALLOW"
                    else -> choice.uppercase()
                }
                val tag = if (deny) "chat.approval.deny" else "chat.approval.${choice.lowercase()}"
                app.hermes.companion.design.ActionButton(
                    label = label,
                    onClick = { onApproval(choice) },
                    enabled = !busy,
                    kind = if (deny) app.hermes.companion.design.ActionKind.DANGER else app.hermes.companion.design.ActionKind.GHOST,
                    modifier = Modifier.testTag(if (deny) "chat.approval.deny" else if (label == "ALLOW") "chat.approval.allow" else tag),
                )
            }
        }
        if (busy) Text("Submitting response…", style = CompanionType.Caption.copy(color = CompanionColor.TextDim),
            modifier = Modifier.padding(top = CompanionSpace.Sm).testTag("chat.approval.submitting"))
    }
}

@Composable
private fun ToolRow(
    message: ChatMessage,
    onFetchMedia: suspend (String) -> ByteArray?,
    onOpenMedia: (ChatBlock) -> Unit,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val name = message.toolName?.ifBlank { null } ?: "tool"
    val media = message.blocks.filter { it.kind != app.hermes.companion.model.ChatBlockKind.TEXT }
    val detail = message.toolDetail.orEmpty().trim()
    val body = message.text.trim()
    val summary = detail.ifBlank {
        if (body.isNotBlank() && body != name) body.take(80).replace('\n', ' ') else "completed"
    }
    val running = message.toolRunning
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var inputCopied by remember { mutableStateOf(false) }
    var outputCopied by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(10.dp)
    val codeShape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)

            .heightIn(min = 48.dp)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .testTag(if (running) "chat.tool.running" else "chat.tool"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (running) {
                    LiveDot()
                } else {
                    Text(
                        text = "✓",
                        style = CompanionType.Caption.copy(
                            color = CompanionColor.Signal,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Text(
                    text = name,
                    style = CompanionType.Caption.copy(
                        color = CompanionColor.Signal,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "·",
                    style = CompanionType.Caption.copy(color = CompanionColor.TextDim),
                )
                Text(
                    text = if (running) "running · $summary" else summary,
                    style = CompanionType.Caption.copy(
                        color = if (running) CompanionColor.TextDim else CompanionColor.TextMute,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "▴" else "▾",
                style = CompanionType.Caption.copy(color = CompanionColor.Signal),
            )
        }

        if (expanded) {
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "INPUT / ARGS",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontSize = 13.sp),
                    )
                    Text(
                        text = if (inputCopied) "COPIED ✓" else "COPY",
                        style = CompanionType.Caption.copy(
                            color = if (inputCopied) CompanionColor.Signal else CompanionColor.TextDim,
                            fontSize = 13.sp,
                        ),
                        modifier = Modifier
                            .clickable {
                                clipboardManager.setText(AnnotatedString(detail))
                                inputCopied = true
                                scope.launch {
                                    delay(1800)
                                    inputCopied = false
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(codeShape)

                        .background(CompanionColor.Void)
                        .padding(10.dp),
                ) {
                    Text(
                        text = detail,
                        style = CompanionType.Caption.copy(color = CompanionColor.Text),
                    )
                }
            }

            if (body.isNotBlank() && body != detail) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "OUTPUT",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal, fontSize = 13.sp),
                    )
                    Text(
                        text = if (outputCopied) "COPIED ✓" else "COPY",
                        style = CompanionType.Caption.copy(
                            color = if (outputCopied) CompanionColor.Signal else CompanionColor.TextDim,
                            fontSize = 13.sp,
                        ),
                        modifier = Modifier
                            .clickable {
                                clipboardManager.setText(AnnotatedString(body))
                                outputCopied = true
                                scope.launch {
                                    delay(1800)
                                    outputCopied = false
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(codeShape)

                        .background(CompanionColor.Void)
                        .padding(10.dp),
                ) {
                    Text(
                        text = body,
                        style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
                    )
                }
            }

            if (media.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MessageBlocks(message.copy(blocks = media), onFetchMedia, onOpenMedia)
            }
        } else if (media.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            MessageBlocks(message.copy(blocks = media), onFetchMedia, onOpenMedia)
        }
    }
}

private suspend fun LazyListState.scrollToEnd() {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val last = total - 1
    scrollToItem(last)
    withFrameNanos { }
    val last2 = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    val item = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val extra = item.size - viewport
    if (item.index >= last2 && extra > 0) {
        scrollToItem(last2, extra)
    }
}

@Composable
private fun ChatScrollTrack(listState: LazyListState, modifier: Modifier = Modifier) {
    val first = listState.firstVisibleItemIndex
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total <= 1 || visible.isEmpty() || (!listState.canScrollForward && !listState.canScrollBackward)) return
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val shown = visible.size.coerceAtLeast(1)
    val thumbPx = (viewportPx * (shown.toFloat() / total)).toInt().coerceIn(12, viewportPx)
    val maxFirst = (total - shown).coerceAtLeast(1)
    val y = ((viewportPx - thumbPx) * (first.toFloat() / maxFirst)).toInt().coerceIn(0, viewportPx - thumbPx)
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(2.dp)
            .testTag("chat.scrollbar"),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .background(Color.Transparent),
        )
        Box(
            Modifier
                .offset { IntOffset(0, y) }
                .height(with(density) { thumbPx.toDp() })
                .fillMaxWidth()
                .background(CompanionColor.TextMute.copy(alpha = 0.45f)),
        )
    }
}

/**
 * Room header (A22.10 / A22.3): participant chips (tap = mention, long-press = remove), `+` to add,
 * the floor pill (`R 7/12` · `QUIET` · `PAUSED · budget`), PAUSE / STOP while live, CONTINUE when not,
 * SUMMARIZE, a pinned summary, and rename by tapping the title.
 */
@Composable
private fun RoomBar(
    room: RoomRef,
    streaming: Boolean,
    profiles: List<ProfileRef>,
    candidates: List<Pair<String, String>>,
    onMention: (String) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onContinue: (Int) -> Unit,
    onSummarize: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    var adding by remember(room.id) { mutableStateOf(false) }
    var renaming by remember(room.id) { mutableStateOf(false) }
    var titleDraft by remember(room.id) { mutableStateOf(room.title) }
    var showSummary by remember(room.id) { mutableStateOf(false) }
    var removeTarget by remember(room.id) { mutableStateOf<String?>(null) }
    val live = streaming || room.state == "running"
    Column(
        modifier = Modifier
            .fillMaxWidth()

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("room.bar"),
        verticalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                room.participants.forEach { p ->
                    val speaking = room.speaking == p.id || room.speaking == p.profile
                    val hands = room.hands == p.id
                    val mod = room.moderator == p.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .testTag("room.participant.${p.id}")

                            .pointerInput(p.id) {
                                detectTapGestures(
                                    onTap = { onMention(p.glyph) },
                                    onLongPress = { removeTarget = p.id },
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        if (speaking) LiveDot(size = 5.dp)
                        Text(
                            text = p.glyph + (if (hands) " ✋" else "") + (if (mod) " ★" else ""),
                            style = CompanionType.Caption.copy(color = if (speaking) CompanionColor.Signal else CompanionColor.TextDim),
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (!p.host.isNullOrBlank()) {
                            Text(text = p.host!!, style = CompanionType.Caption.copy(color = CompanionColor.TextMute, fontSize = 13.sp), maxLines = 1)
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    Text(
                        text = "+",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("room.add")

                            .clickable { adding = !adding }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            FloorPill(room, live)
        }
        if (removeTarget != null) {
            val target = removeTarget!!
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md)) {
                Text(
                    text = "REMOVE ${Rooms.glyph(target)} from the room?",
                    style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "REMOVE",
                    style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                    modifier = Modifier.testTag("room.remove.confirm").clickable { onRemove(target); removeTarget = null }.padding(CompanionSpace.Xs),
                )
                Text(
                    text = "CANCEL",
                    style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
                    modifier = Modifier.clickable { removeTarget = null }.padding(CompanionSpace.Xs),
                )
            }
        }
        if (adding) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "ADD", style = CompanionType.Caption)
                candidates.forEach { (id, label) ->
                    Text(
                        text = "${Rooms.glyph(id)} $label",
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("room.add.$id")

                            .clickable { onAdd(id); adding = false }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            if (live) {
                BarAction("PAUSE", CompanionColor.TextDim, "room.pause", onPause)
                BarAction("STOP", CompanionColor.Warn, "room.stop", onStop)
            } else {
                BarAction("CONTINUE +6", CompanionColor.Signal, "room.continue") { onContinue(6) }
                BarAction("SUMMARIZE", CompanionColor.TextDim, "room.summarize", onSummarize)
            }
            if (room.summary.isNotBlank()) {
                BarAction(if (showSummary) "SUMMARY ▴" else "SUMMARY ▾", CompanionColor.TextDim, "room.summary.toggle") { showSummary = !showSummary }
            }
            Spacer(Modifier.weight(1f))
            BarAction(if (renaming) "CANCEL" else "RENAME", CompanionColor.TextMute, "room.rename") {
                renaming = !renaming
                titleDraft = room.title
            }
        }
        if (renaming) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                HairlineField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    keyboardType = KeyboardType.Text,
                    placeholder = "room title",
                    modifier = Modifier.weight(1f).testTag("room.rename.field"),
                    onDone = { onRename(titleDraft); renaming = false },
                )
                BarAction("SAVE", CompanionColor.Signal, "room.rename.save") { onRename(titleDraft); renaming = false }
            }
        }
        if (showSummary && room.summary.isNotBlank()) {
            Text(
                text = room.summary,
                style = CompanionType.Caption.copy(color = CompanionColor.Text),
                modifier = Modifier
                    .fillMaxWidth()

                    .padding(CompanionSpace.Sm)
                    .testTag("room.summary"),
            )
        }
    }
}

@Composable
private fun FloorPill(room: RoomRef, live: Boolean) {
    val (text, color) = when {
        live -> Rooms.budgetPill(room.turnsUsed, room.budget) to CompanionColor.Signal
        room.state == "quiet" -> "QUIET" to CompanionColor.TextMute
        room.state == "paused" -> "PAUSED · ${room.pauseReason.ifBlank { "" }}".trimEnd(' ', '·') to CompanionColor.Warn
        else -> room.mode.uppercase() to CompanionColor.TextMute
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier

            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("room.pill"),
    ) {
        if (live) LiveDot(size = 5.dp)
        Text(text = text, style = CompanionType.Caption.copy(color = color), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun BarAction(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = CompanionType.Caption.copy(color = color),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = CompanionSpace.Xs, vertical = CompanionSpace.Xs),
    )
}

/** Under the transcript when the floor is closed: why, and how to give it back. */
@Composable
private fun RoomFloorStrip(room: RoomRef, onContinue: (Int) -> Unit) {
    val label = Rooms.stateLabel(room.state, room.pauseReason, room.turnsUsed, room.budget)
    if (label.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()

            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("room.floor"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = label,
            style = CompanionType.Caption.copy(color = if (room.state == "paused") CompanionColor.Warn else CompanionColor.TextMute),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "CONTINUE +6",
            style = CompanionType.Caption.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("room.floor.continue")

                .clickable { onContinue(6) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
