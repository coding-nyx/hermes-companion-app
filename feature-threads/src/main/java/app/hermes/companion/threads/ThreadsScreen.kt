package app.hermes.companion.threads

import app.hermes.companion.design.ActionText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.ActionButton
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.ControlGlyph
import app.hermes.companion.design.ControlIcon
import app.hermes.companion.design.GroupSurface
import app.hermes.companion.design.StatusStrip
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.FetchSkeleton
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.design.LiveDot
import app.hermes.companion.domain.Rooms
import androidx.compose.foundation.layout.size
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.ThreadSort
import app.hermes.companion.domain.ThreadTime
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.SessionRef

/**
 * Thread rail (A18.9). [sessions] arrive already scoped and ordered by the caller
 * (`CompanionState.visibleSessions`); this screen only groups, filters and paints them.
 * Sort is host state (sticky), the filter is screen-local: it never survives a tab switch.
 */
@Composable
fun ThreadsScreen(
    sessions: List<SessionRef>,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    activeProfileId: String? = null,
    sort: ThreadSort = ThreadSort.DEFAULT,
    onSort: (ThreadSort) -> Unit = {},
    /** ARCHIVED toggle (A18.12): host state, the caller refetches with archived rows included. */
    showArchived: Boolean = false,
    onToggleArchived: (Boolean) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    pendingDelete: SessionRef? = null,
    /** Agent rooms (P21) listed above the threads. */
    rooms: List<RoomRef> = emptyList(),
    roomsLoading: Boolean = false,
    roomsError: String? = null,
    unreadRooms: Set<String> = emptySet(),
    onOpenRoom: (RoomRef) -> Unit = {},
    onNewRoom: () -> Unit = {},
    roomsAvailable: Boolean = true,
    onDeleteRoom: (RoomRef) -> Unit = {},
    onOpen: (SessionRef) -> Unit = {},
    onNew: () -> Unit = {},
    hasLocalDraft: Boolean = false,
    onResumeLocalDraft: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onDeleteRequest: (SessionRef) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    /** Injected clock so renders (and tests) are deterministic. */
    nowMs: Long = System.currentTimeMillis(),
    showToolbar: Boolean = true,
) {
    val density = LocalDensity.current
    var createHeight by remember { mutableStateOf(56.dp) }
    var query by rememberSaveable { mutableStateOf("") }
    // TELEGRAM is a pure view filter (screen-local, like the text filter); ARCHIVED changes the host query.
    var telegramOnly by rememberSaveable { mutableStateOf(false) }
    val filtered = SessionLists.filter(
        if (telegramOnly) SessionLists.bySource(sessions, SessionLists.SOURCE_TELEGRAM) else sessions,
        query,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("threads.list"),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showToolbar) Toolbar(
                total = sessions.size,
                shown = filtered.size,
                filtering = query.isNotBlank() || telegramOnly,
                loading = loading,
                activeProfileId = activeProfileId,
                onNewRoom = onNewRoom,
                roomsAvailable = roomsAvailable,
                onRefresh = onRefresh,
            )
            if (hasLocalDraft) {
                GroupSurface(Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("threads.resumeDraft")
                        .clickable(role = Role.Button, onClick = onResumeLocalDraft)
                        .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Continue unsent draft", style = CompanionType.RowTitle, color = CompanionColor.Text)
                            Text("Saved for this host and profile", style = CompanionType.Caption, color = CompanionColor.TextDim)
                        }
                        ControlIcon(ControlGlyph.CHEVRON, color = CompanionColor.Signal)
                    }
                }
            }
            Row(Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm), verticalAlignment = Alignment.CenterVertically) {
                HairlineField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search conversations",
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                    keepKeyboardOnDone = false,
                    modifier = Modifier.weight(1f).testTag("threads.filter"),
                )
                if (query.isNotEmpty()) ActionButton("Clear", { query = "" },
                    modifier = Modifier.testTag("threads.filter.clear").semantics { contentDescription = "Clear conversation search" })
            }
            SortRow(
                sort = sort,
                onSort = onSort,
                showArchived = showArchived,
                onToggleArchived = onToggleArchived,
                telegramOnly = telegramOnly,
                onToggleTelegram = { telegramOnly = it },
            )
            val showRooms = rooms.isNotEmpty() || !roomsError.isNullOrBlank() || (roomsLoading && sessions.isNotEmpty())
            pendingDelete?.let { target ->
                DeleteStrip(target, onConfirmDelete, onCancelDelete)
                Hairline()
            }
            if (sessions.isNotEmpty() && !error.isNullOrBlank()) {
                StatusStrip("Couldn’t refresh: $error", modifier = Modifier.testTag("threads.refresh.error"),
                    actionLabel = if (onRetry != null) "Retry" else null, onAction = onRetry)
            }
            if (loading) {
                FetchRow(label = "LOADING THREADS", modifier = Modifier.testTag("threads.loading"))
            }
            if (sessions.isEmpty() && !loading && !showRooms) {
                val failed = !error.isNullOrBlank()
                FetchPane(
                    label = if (failed) "Couldn’t load conversations" else "No conversations yet",
                    hint = if (failed) error else activeProfileId?.takeIf { it.isNotBlank() }?.let { "Start a new chat with $it." } ?: "Start a new chat to begin.",
                    retryLabel = if (failed) "RETRY" else null,
                    onRetry = if (failed) onRetry else null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(if (failed) "threads.failed" else "threads.empty"),
                )
                return@Column
            }
            if (sessions.isEmpty() && loading && !showRooms) {
                FetchSkeleton()
                return@Column
            }
            if (filtered.isEmpty() && !showRooms) {
                FetchPane(
                    label = "No matching conversations",
                    hint = "// " + listOfNotNull(
                        if (telegramOnly) "telegram" else null,
                        query.trim().ifBlank { null },
                    ).joinToString(" · "),
                    scanning = false,
                    retryLabel = "Clear filters",
                    onRetry = { query = ""; telegramOnly = false },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("threads.nomatch"),
                )
                return@Column
            }
            val rail = railItems(filtered, sort, nowMs)
            // Reserve scrollable space for the floating action, not a full-width footer.
            LazyColumn(modifier = Modifier.weight(1f).testTag("threads.rail"),
                contentPadding = PaddingValues(bottom = createHeight + 32.dp)) {
                if (showRooms) item(key = "room-list") {
                    RoomsSection(rooms, roomsLoading, roomsError, unreadRooms, nowMs, onOpenRoom, onDeleteRoom)
                }
                if (filtered.isEmpty() && !loading) item(key = "empty-chats") {
                    if (sessions.isEmpty() && !error.isNullOrBlank()) {
                        Column(Modifier.fillMaxWidth().padding(24.dp).testTag("threads.failed"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Couldn’t load conversations", style = CompanionType.Body)
                            Text(error, style = CompanionType.Caption)
                            onRetry?.let { retry -> app.hermes.companion.design.ActionButton("Retry", retry, modifier = Modifier.testTag("fetch.retry")) }
                        }
                    } else Text(if (sessions.isEmpty()) "Start a new chat to begin." else "No matching conversations",
                        style = CompanionType.Caption, modifier = Modifier.padding(24.dp).testTag(if (sessions.isEmpty()) "threads.emptyChats" else "threads.nomatch"))
                }
                rail.forEach { item ->
                    when (item) {
                        is RailItem.Group -> item(key = "group:${item.label}") {
                            GroupHeader(item.label)
                        }
                        is RailItem.Thread -> item(key = item.session.id) {
                            ThreadRow(
                                session = item.session,
                                stamp = item.stamp,
                                onOpen = onOpen,
                                onDeleteRequest = onDeleteRequest,
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("threads.new")
                .onSizeChanged { createHeight = with(density) { it.height.toDp() } }
                .semantics { contentDescription = "New chat" },
            containerColor = CompanionColor.Signal,
            contentColor = CompanionColor.Void,
            shape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp),
        ) {
            ControlIcon(ControlGlyph.PLUS, color = CompanionColor.Void)
            Spacer(Modifier.width(12.dp))
            Text("New chat", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = CompanionColor.Void)
        }
    }
}

/** What the LazyColumn paints, in order. Group headers only exist for time sorts. */
internal sealed class RailItem {
    data class Group(val label: String) : RailItem()
    data class Thread(val session: SessionRef, val stamp: String) : RailItem()
}

/**
 * Time shown per row follows the sort: creation for CREATED / A–Z, last activity for ACTIVE.
 * Group headers (TODAY / YESTERDAY / THIS WEEK / month / year) are emitted when the bucket changes,
 * so they are only ever contiguous because the input is already sorted on that same key.
 */
internal fun railItems(sessions: List<SessionRef>, sort: ThreadSort, nowMs: Long): List<RailItem> {
    val out = ArrayList<RailItem>(sessions.size + 8)
    var lastGroup: String? = null
    for (session in sessions) {
        val key = if (sort == ThreadSort.ACTIVE) SessionLists.activeKey(session) else SessionLists.createdKey(session)
        if (sort != ThreadSort.TITLE || session.pinned) {
            val group = if (session.pinned) "PINNED" else ThreadTime.dayGroup(key, nowMs)
            if (group != lastGroup) {
                out += RailItem.Group(group)
                lastGroup = group
            }
        }
        out += RailItem.Thread(session, ThreadTime.relative(key, nowMs))
    }
    return out
}

@Composable
private fun Toolbar(
    total: Int,
    shown: Int,
    filtering: Boolean,
    loading: Boolean,
    activeProfileId: String?,
    onNewRoom: () -> Unit,
    roomsAvailable: Boolean,
    onRefresh: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CompanionSpace.Lg),
    ) {
        Column(Modifier.weight(1f).padding(vertical = CompanionSpace.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                Text("Chats", style = CompanionType.Display, color = CompanionColor.Text)
                Text(
                    text = if (filtering) "$shown/$total" else total.toString(),
                    style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                    modifier = Modifier.testTag("threads.count"),
                )
            }
            activeProfileId?.takeIf { it.isNotBlank() }?.let {
                Text("Agent · $it", style = CompanionType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("threads.profile"))
            }
        }
        if (onRefresh != null) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .heightIn(min = 48.dp).clickable(enabled = !loading, role = Role.Button, onClick = onRefresh)
                    .semantics { contentDescription = "Refresh conversations" }
                    .testTag("threads.refresh"),
                contentAlignment = Alignment.Center,
            ) {
                Text("↻", style = CompanionType.Caption.copy(
                    color = if (loading) CompanionColor.TextMute else CompanionColor.Signal))
            }
        }
        if (roomsAvailable) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                ActionButton("Rooms +", { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Agent room options" }.testTag("threads.options"))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    containerColor = CompanionColor.VoidElevated) {
                    DropdownMenuItem(
                        text = { Text("New agent room", color = CompanionColor.Text) },
                        modifier = Modifier.testTag("threads.newroom"),
                        onClick = { menuOpen = false; onNewRoom() },
                    )
                }
            }
        }
    }
}

/** Sort and source controls share one baseline; menus keep the conversation list in view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortRow(
    sort: ThreadSort, onSort: (ThreadSort) -> Unit,
    showArchived: Boolean, onToggleArchived: (Boolean) -> Unit,
    telegramOnly: Boolean, onToggleTelegram: (Boolean) -> Unit,
) {
    var sorting by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("threads.sort"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            app.hermes.companion.design.ActionButton(
                when (sort) { ThreadSort.ACTIVE -> "Recent activity"; ThreadSort.TITLE -> "Alphabetical"; else -> "Recently created" } + " ▾",
                { sorting = true }, modifier = Modifier.testTag("threads.sort.menu"))
            DropdownMenu(sorting, { sorting = false }, containerColor = CompanionColor.VoidElevated) {
                ThreadSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(when (option) { ThreadSort.ACTIVE -> "Recent activity"; ThreadSort.TITLE -> "Alphabetical"; else -> "Recently created" }) },
                        leadingIcon = { Text(if (sort == option) "✓" else "") },
                        onClick = { sorting = false; onSort(option) }, modifier = Modifier.testTag("threads.sort.${option.name.lowercase()}"))
                }
            }
        }
        Box(Modifier.testTag("threads.show")) {
            app.hermes.companion.design.ActionButton(
                when {
                    telegramOnly && showArchived -> "Telegram + archived"
                    telegramOnly -> "Telegram only"
                    showArchived -> "Including archived"
                    else -> "All conversations"
                } + " ▾", { filtering = true },
                modifier = Modifier.testTag("threads.filter.menu").semantics {
                    stateDescription = listOfNotNull(if (telegramOnly) "Telegram only" else "All sources", if (showArchived) "Archived included" else null).joinToString(", ")
                })
            DropdownMenu(filtering, { filtering = false }, containerColor = CompanionColor.VoidElevated) {
                DropdownMenuItem(text = { Text("Telegram only") }, leadingIcon = { Text(if (telegramOnly) "✓" else "") },
                    onClick = { filtering = false; onToggleTelegram(!telegramOnly) }, modifier = Modifier.testTag("threads.filter.telegram"))
                DropdownMenuItem(text = { Text("Include archived") }, leadingIcon = { Text(if (showArchived) "✓" else "") },
                    onClick = { filtering = false; onToggleArchived(!showArchived) }, modifier = Modifier.testTag("threads.filter.archived"))
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        text = label.lowercase().replaceFirstChar(Char::titlecase),
        style = CompanionType.Title,
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.Void)
            .padding(start = CompanionSpace.Lg, end = CompanionSpace.Lg, top = CompanionSpace.Xl, bottom = CompanionSpace.Sm)
            .testTag("threads.group.$label").semantics { heading() },
    )
}

/**
 * Title + one meta line (`12 msgs · telegram · ENDED`), timestamp on the right.
 * The profile id is gone: the rail is profile-scoped and the header glyph already says which.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    session: SessionRef,
    stamp: String,
    onOpen: (SessionRef) -> Unit,
    onDeleteRequest: (SessionRef) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val meta = buildList {
        if (session.messageCount > 0) add("${session.messageCount} msgs")
        if (session.source.isNotBlank()) add(session.source.lowercase())
        if (session.pinned) add("pinned")
    }.joinToString(" · ")
    val cardShape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
    var optionsOpen by remember(session.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
            .clip(cardShape)
            .background(CompanionColor.VoidElevated)
            .border(1.dp, if (session.unread) CompanionColor.Signal.copy(alpha = .6f) else CompanionColor.CardBorder, cardShape)
            .combinedClickable(role = Role.Button, onClick = { onOpen(session) },
                onLongClickLabel = "Request conversation deletion", onLongClick = { onDeleteRequest(session) })
            .padding(start = CompanionSpace.Md, top = CompanionSpace.Md, bottom = CompanionSpace.Md)
            .testTag("threads.row.${session.id}")
            .semantics { stateDescription = listOfNotNull(if (session.unread) "Unread" else "Read", if (session.archived) "Archived" else if (session.ended) "Ended" else null).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = CompanionSpace.Sm)
                .width(3.dp)
                .height(if (meta.isNotBlank()) 32.dp else 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (session.unread) CompanionColor.Signal else CompanionColor.Line),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { session.id },
                style = CompanionType.Body.copy(
                    color = CompanionColor.Text,
                    fontWeight = if (session.unread) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = if (largeText) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.unread) Text("Unread", style = CompanionType.Caption, color = CompanionColor.Signal,
                modifier = Modifier.testTag("threads.unread.${session.id}"))
            if (meta.isNotBlank() || session.ended || session.archived) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = CompanionType.Caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("threads.meta.${session.id}"),
                        )
                    }
                    if (session.archived) {
                        Text(
                            text = if (meta.isNotBlank()) " · ARCHIVED" else "ARCHIVED",
                            style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("threads.archived.${session.id}"),
                        )
                    } else if (session.ended) {
                        Text(
                            text = if (meta.isNotBlank()) " · ENDED" else "ENDED",
                            style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("threads.ended.${session.id}"),
                        )
                    }
                }
            }
            if (largeText && stamp.isNotBlank()) Text(stamp, style = CompanionType.Caption,
                modifier = Modifier.testTag("threads.time.${session.id}"))
        }
        if (!largeText && stamp.isNotBlank()) {
            Text(
                text = stamp,
                style = CompanionType.Caption.copy(
                    color = if (session.unread) CompanionColor.Signal else CompanionColor.TextMute,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = CompanionSpace.Sm)
                    .testTag("threads.time.${session.id}"),
            )
        }
        Box {
            androidx.compose.material3.IconButton(onClick = { optionsOpen = true },
                modifier = Modifier.testTag("threads.actions.${session.id}").semantics {
                    contentDescription = "Actions for ${session.title.ifBlank { session.id }}"
                }) { ControlIcon(ControlGlyph.MORE) }
            DropdownMenu(optionsOpen, { optionsOpen = false }, containerColor = CompanionColor.VoidElevated) {
                DropdownMenuItem(text = { Text("Delete conversation", color = CompanionColor.Danger) },
                    onClick = { optionsOpen = false; onDeleteRequest(session) }, modifier = Modifier.testTag("threads.delete.request.${session.id}"))
            }
        }
    }
}

/** ROOMS rail: title, participant glyphs, live dot while a turn runs. Long-press deletes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoomsSection(
    rooms: List<RoomRef>,
    loading: Boolean,
    error: String?,
    unread: Set<String>,
    nowMs: Long,
    onOpen: (RoomRef) -> Unit,
    onDelete: (RoomRef) -> Unit,
) {
    Column(Modifier.testTag("threads.rooms")) {
        Text(
            text = "Rooms",
            style = CompanionType.Title,
            modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        )
        if (loading && rooms.isEmpty()) {
            FetchRow(label = "LOADING ROOMS", modifier = Modifier.testTag("threads.rooms.loading"))
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                modifier = Modifier
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("threads.rooms.error"),
            )
        }
        rooms.forEach { room ->
            val isUnread = room.id in unread
            val roomShape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .clip(roomShape)
                    .background(CompanionColor.VoidSurface)
                    .border(1.dp, CompanionColor.CardBorder, roomShape)
                    .semantics { stateDescription = listOfNotNull(if (isUnread) "Unread" else null, if (room.busy) "Agent speaking" else room.state).joinToString(", ") }
                    .combinedClickable(role = Role.Button, onClick = { onOpen(room) }, onLongClick = { onDelete(room) })
                    .padding(CompanionSpace.Md)
                    .testTag("threads.room.${room.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .padding(end = CompanionSpace.Sm)
                        .width(3.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (room.busy || isUnread) CompanionColor.Signal else CompanionColor.SignalDim),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                        Text(
                            text = room.title,
                            style = CompanionType.Body.copy(color = if (isUnread || room.busy) CompanionColor.Text else CompanionColor.TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isUnread) {
                            Box(Modifier.size(6.dp).background(CompanionColor.Signal).testTag("threads.room.unread.${room.id}"))
                        }
                    }
                    val preview = when {
                        room.busy -> "${room.speaking?.let { Rooms.glyph(it) } ?: "room"} speaking…"
                        room.lastText.isNotBlank() -> "${Rooms.glyph(room.lastSpeaker.ifBlank { null })}: ${room.lastText}"
                        else -> room.participants.joinToString(" ") { it.glyph }
                    }
                    Text(
                        text = preview,
                        style = CompanionType.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("threads.room.preview.${room.id}"),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = room.participants.joinToString(" ") { it.glyph },
                        style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (room.busy) LiveDot(size = 5.dp)
                        val state = when {
                            room.busy -> "live"
                            room.state == "paused" -> "paused"
                            room.state == "quiet" -> "quiet"
                            else -> ""
                        }
                        Text(
                            text = listOf(state, ThreadTime.relative(room.updatedAtEpochMs, nowMs)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = CompanionType.Caption.copy(color = if (room.state == "paused") CompanionColor.Warn else CompanionColor.TextMute),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        Text(
            text = "Conversations",
            style = CompanionType.Caption,
            modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeleteStrip(session: SessionRef, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val title = session.title.ifBlank { session.id }
    GroupSurface(Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm).testTag("threads.delete"), padded = true) {
        Text("Delete this conversation?", style = CompanionType.RowTitle, color = CompanionColor.Warn)
        Text(title, style = CompanionType.Body, color = CompanionColor.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            ActionButton("Cancel", onCancel, modifier = Modifier.testTag("threads.delete.cancel"))
            ActionButton("Delete conversation", onConfirm, kind = ActionKind.DANGER, modifier = Modifier.testTag("threads.delete.confirm"))
        }
    }
}
