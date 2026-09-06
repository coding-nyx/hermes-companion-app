package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomParticipant
import app.hermes.companion.domain.Rooms
import app.hermes.companion.model.ModelCatalog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    hasMoreOlder: Boolean = false,
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    rewindTargetId: String? = null,
    onRewind: (ChatMessage) -> Unit = {},
    onCancelRewind: () -> Unit = {},
    isListeningVoice: Boolean = false,
    onVoiceClick: () -> Unit = {},
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
    modifier: Modifier = Modifier,
) {
    val roomMode = participants.isNotEmpty()
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
        if (!loading && messages.isEmpty()) {
            FetchPane(
                label = if (!error.isNullOrBlank()) "history failed" else "no messages",
                hint = if (!error.isNullOrBlank()) error else if (historySource.isNotBlank()) "// $historySource" else "// idle",
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
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
                .testTag("chat.list"),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
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
            items(messages.distinctBy { it.id }, key = { it.id }) { message ->
                when (message.role) {
                    MessageRole.USER -> UserRow(message, if (roomMode) ({}) else onRewind, onFetchMedia, onOpenMedia)
                    MessageRole.TOOL -> Box(Modifier.padding(start = AgentRailInset)) {
                        ToolRow(message, onFetchMedia, onOpenMedia)
                    }
                    MessageRole.ASSISTANT -> AssistantRow(message, onFetchMedia, onOpenMedia, participants, profiles)
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
                        roomMode = roomMode,
                        participants = participants,
                        profiles = profiles,
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
                    .background(CompanionColor.VoidElevated)
                    .border(1.dp, CompanionColor.Signal)
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
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
            }
        }
        }
        }
        if (!error.isNullOrBlank() && (loading || messages.isNotEmpty())) {
            Text(
                text = error,
                style = CompanionType.Mono.copy(color = CompanionColor.Danger),
                modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            )
        }
        if (approval != null) {
            ApprovalStrip(approval, onApproval)
        }
        if (rewindTargetId != null) {
            RewindStrip(onCancelRewind)
        }
        Hairline()
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("chat.attach.pending"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                pendingAttachments.forEach { item ->
                    Text(
                        text = "${item.kind.name} · ${item.name}  ×",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .border(CompanionSpace.Hairline, CompanionColor.Signal)
                            .clickable { onRemoveAttachment(item.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (attachOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("chat.attach.menu"),
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                listOf(
                    "PHOTO" to onPickPhoto,
                    "CAMERA" to onPickCamera,
                    "VIDEO" to onPickVideo,
                    "FILE" to onPickFile,
                ).forEach { (label, action) ->
                    Text(
                        text = label,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("chat.attach.${label.lowercase()}")
                            .border(CompanionSpace.Hairline, CompanionColor.Signal)
                            .clickable(onClick = action)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
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
                Text(text = "TO", style = CompanionType.MonoSmall)
                participants.forEach { p ->
                    Text(
                        text = "@${p.glyph}",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        modifier = Modifier
                            .testTag("chat.mention.${p.glyph}")
                            .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                            .clickable { onMention(p.glyph) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val activeModel = modelOverride.ifBlank { modelCatalog?.currentModel.orEmpty() }
        if (activeModel.isNotBlank() && !roomMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .testTag("chat.model")
                        .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
                        .background(CompanionColor.VoidElevated)
                        .clickable { onOpenModelPicker() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "model · $activeModel",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "▾",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    )
                }

                // Quick alternative model chips (up to 2 options)
                val quickOptions = modelCatalog?.models
                    ?.filter { it.id != activeModel }
                    ?.take(2)
                    .orEmpty()

                if (quickOptions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (opt in quickOptions) {
                            Box(
                                modifier = Modifier
                                    .border(CompanionSpace.Hairline, CompanionColor.Line)
                                    .clickable { onSwitchModel(opt.id, opt.provider) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("chat.model.quick.${opt.id}"),
                            ) {
                                Text(
                                    text = opt.id.substringAfterLast("/").take(14),
                                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontSize = 10.sp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!roomMode) {
                Text(
                    text = "+",
                    style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                    modifier = Modifier
                        .testTag("chat.attach")
                        .border(CompanionSpace.Hairline, CompanionColor.Signal)
                        .clickable(onClick = onToggleAttach)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(CompanionSpace.Sm))
            }
            MarkdownComposer(
                value = draft,
                onValueChange = onDraftChange,
                onSend = { if (!streaming) onSend() },
                modifier = Modifier.weight(1f),
            )
            if (!roomMode) {
            Spacer(Modifier.width(CompanionSpace.Sm))
            Text(
                text = if (isListeningVoice) "..." else "MIC",
                style = CompanionType.MonoSmall.copy(
                    color = if (isListeningVoice) CompanionColor.Warn else CompanionColor.Signal,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .testTag("chat.voice")
                    .border(
                        CompanionSpace.Hairline,
                        if (isListeningVoice) CompanionColor.Warn else CompanionColor.LineStrong,
                    )
                    .clickable(onClick = onVoiceClick)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
            }
            Spacer(Modifier.width(CompanionSpace.Sm))
            val stopLabel = if (roomMode) "INTERRUPT ALL" else "INTERRUPT"
            val action = if (streaming) stopLabel to onInterrupt else "SEND" to onSend
            Text(
                text = action.first,
                style = CompanionType.MonoSmall.copy(
                    color = if (streaming) CompanionColor.Warn else CompanionColor.Signal,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .testTag("chat.send")
                    .border(
                        CompanionSpace.Hairline,
                        if (streaming) CompanionColor.Warn else CompanionColor.Signal,
                    )
                    .clickable(onClick = action.second)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat.user"),
    ) {
        Spacer(Modifier.width(CompanionSpace.Xxl))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 2.dp, bottom = 3.dp),
            ) {
                if (message.queued) {
                    Text(
                        text = "queued",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                        modifier = Modifier.testTag("chat.queued"),
                    )
                }
                Text(
                    text = "YOU",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                )
            }
            Column(
                modifier = Modifier
                    .clickable { onRewind(message) }
                    .background(CompanionColor.VoidElevated)
                    .border(
                        CompanionSpace.Hairline,
                        if (message.queued) CompanionColor.Warn else CompanionColor.LineStrong,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MessageBlocks(message, onFetchMedia, onOpenMedia)
            }
        }
    }
}

/** Agent turn: full width, 2dp signal rail, `HERMES` label, live cursor while streaming. */
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
) {
    val label = Rooms.speakerLabel(message.speaker, participants, profiles)
    val railIndex = Rooms.speakerIndex(message.speaker, participants)
    val error = message.toolDetail.orEmpty().takeIf { message.speaker != null && message.text.isBlank() && !message.passed }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag(if (message.passed) "chat.passed" else "chat.assistant"),
    ) {
        AgentRail(live = message.streaming, railIndex = railIndex)
        Spacer(Modifier.width(AgentRailInset - 2.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 3.dp),
            ) {
                Text(
                    text = label,
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                if (message.passed) {
                    Text(text = "passed", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
                }
                if (error.isNotBlank()) {
                    Text(
                        text = Rooms.turnErrorLabel(error),
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                        modifier = Modifier.testTag("chat.turn.error"),
                    )
                }
                if (message.streaming) {
                    LiveDot()
                    Text(
                        text = "streaming",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                        modifier = Modifier.testTag("chat.streaming"),
                    )
                }
            }
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

/** Shown between send and the first agent token, or while a tool runs with no text yet. */
@Composable
private fun PendingRow(
    label: String,
    speaker: String? = null,
    railIndex: Int = 0,
    roomMode: Boolean = false,
    participants: List<RoomParticipant> = emptyList(),
    profiles: List<ProfileRef> = emptyList(),
) {
    val speakerText = when {
        speaker != null -> Rooms.displayName(speaker, participants, profiles)
        participants.size == 1 -> Rooms.displayName(participants[0].profile, participants, profiles)
        roomMode -> "ROOM"
        else -> "HERMES"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("chat.pending"),
    ) {
        AgentRail(live = true, railIndex = railIndex)
        Spacer(Modifier.width(AgentRailInset - 2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = speakerText,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            )
            LiveDot()
            Text(
                text = label,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            )
            WalkingEllipsis()
        }
    }
}

@Composable
private fun RewindStrip(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("chat.rewind"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "REWIND · this turn and after",
            style = CompanionType.Mono.copy(color = CompanionColor.Warn),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "CANCEL",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
            modifier = Modifier
                .testTag("chat.rewind.cancel")
                .border(CompanionSpace.Hairline, CompanionColor.Danger)
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ApprovalStrip(prompt: ApprovalPrompt, onApproval: (String) -> Unit) {
    val kind = prompt.kind.uppercase()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("chat.approval"),
    ) {
        Text(
            text = "$kind · ${prompt.command}",
            style = CompanionType.Mono.copy(color = CompanionColor.Warn),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
        ) {
            prompt.choices.forEach { choice ->
                val deny = choice.equals("deny", ignoreCase = true) ||
                    choice.equals("reject", ignoreCase = true)
                val label = when {
                    deny -> "DENY"
                    choice.equals("once", ignoreCase = true) ||
                        choice.equals("allow", ignoreCase = true) ||
                        choice.equals("submit", ignoreCase = true) -> "ALLOW"
                    else -> choice.uppercase()
                }
                val tag = if (deny) "chat.approval.deny" else "chat.approval.${choice.lowercase()}"
                val color = if (deny) CompanionColor.Danger else CompanionColor.Signal
                Text(
                    text = label,
                    style = CompanionType.MonoSmall.copy(color = color),
                    modifier = Modifier
                        .testTag(if (deny) "chat.approval.deny" else if (label == "ALLOW") "chat.approval.allow" else tag)
                        .border(CompanionSpace.Hairline, color)
                        .clickable { onApproval(choice) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                CompanionSpace.Hairline,
                if (expanded || running) CompanionColor.Signal else CompanionColor.Line,
            )
            .background(CompanionColor.VoidElevated)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (running) LiveDot()
                Text(
                    text = name,
                    style = CompanionType.MonoSmall.copy(
                        color = CompanionColor.Signal,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "·",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                )
                Text(
                    text = if (running) "running · $summary" else summary,
                    style = CompanionType.MonoSmall.copy(
                        color = if (running) CompanionColor.TextDim else CompanionColor.TextMute,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "▴" else "▾",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            )
        }

        if (expanded) {
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "INPUT / ARGS",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 9.sp),
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .background(CompanionColor.Void)
                        .padding(8.dp),
                ) {
                    Text(
                        text = detail,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                    )
                }
            }

            if (body.isNotBlank() && body != detail) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "OUTPUT",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal, fontSize = 9.sp),
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .background(CompanionColor.Void)
                        .padding(8.dp),
                ) {
                    Text(
                        text = body,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
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
    if (total <= 1 || visible.isEmpty()) return
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
                .background(CompanionColor.Line),
        )
        Box(
            Modifier
                .offset { IntOffset(0, y) }
                .height(with(density) { thumbPx.toDp() })
                .fillMaxWidth()
                .background(CompanionColor.Signal),
        )
    }
}
