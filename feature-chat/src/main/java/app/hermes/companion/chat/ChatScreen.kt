package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlock
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
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
    modifier: Modifier = Modifier,
) {
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
    LaunchedEffect(threadId, messages.lastOrNull()?.id, messages.lastOrNull()?.text, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (!landed || !listState.canScrollForward) {
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
            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    MessageRole.USER -> Column(
                        modifier = Modifier
                            .testTag("chat.user")
                            .clickable { onRewind(message) },
                    ) {
                        MessageBlocks(message, onFetchMedia, onOpenMedia)
                        if (message.queued) {
                            Text(
                                text = "queued",
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                                modifier = Modifier.testTag("chat.queued"),
                            )
                        }
                    }
                    MessageRole.TOOL -> ToolRow(message, onFetchMedia, onOpenMedia)
                    MessageRole.ASSISTANT -> Row(verticalAlignment = Alignment.Bottom) {
                        MessageBlocks(
                            message = message,
                            onFetchMedia = onFetchMedia,
                            onOpenMedia = onOpenMedia,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (message.streaming) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .width(7.dp)
                                    .height(13.dp)
                                    .background(CompanionColor.Signal)
                                    .testTag("chat.cursor"),
                            )
                        }
                    }
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
            Text(
                text = "[END]",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = CompanionSpace.Md, bottom = CompanionSpace.Md)
                    .background(CompanionColor.VoidElevated)
                    .border(1.dp, CompanionColor.Signal)
                    .clickable {
                        scope.launch {
                            listState.scrollToEnd()
                            landed = true
                        }
                    }
                    .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs)
                    .testTag("chat.end"),
            )
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
        val activeModel = modelOverride.ifBlank { modelCatalog?.currentModel.orEmpty() }
        if (activeModel.isNotBlank()) {
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
            MarkdownComposer(
                value = draft,
                onValueChange = onDraftChange,
                onSend = { if (!streaming) onSend() },
                modifier = Modifier.weight(1f),
            )
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
            Spacer(Modifier.width(CompanionSpace.Sm))
            val action = if (streaming) "INTERRUPT" to onInterrupt else "SEND" to onSend
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                CompanionSpace.Hairline,
                if (expanded) CompanionColor.Signal else CompanionColor.Line,
            )
            .background(CompanionColor.VoidElevated)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("chat.tool"),
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
                    text = summary,
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
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
