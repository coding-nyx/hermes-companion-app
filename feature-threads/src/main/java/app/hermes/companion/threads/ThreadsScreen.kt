package app.hermes.companion.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onDeleteRoom: (RoomRef) -> Unit = {},
    onOpen: (SessionRef) -> Unit = {},
    onNew: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onDeleteRequest: (SessionRef) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    /** Injected clock so renders (and tests) are deterministic. */
    nowMs: Long = System.currentTimeMillis(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    // TELEGRAM is a pure view filter (screen-local, like the text filter); ARCHIVED changes the host query.
    var telegramOnly by rememberSaveable { mutableStateOf(false) }
    val filtered = SessionLists.filter(
        if (telegramOnly) SessionLists.bySource(sessions, SessionLists.SOURCE_TELEGRAM) else sessions,
        query,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("threads.list"),
    ) {
        Toolbar(
            total = sessions.size,
            shown = filtered.size,
            filtering = query.isNotBlank() || telegramOnly,
            loading = loading,
            onNew = onNew,
            onNewRoom = onNewRoom,
            onRefresh = onRefresh,
        )
        Hairline()
        SortRow(
            sort = sort,
            onSort = onSort,
            showArchived = showArchived,
            onToggleArchived = onToggleArchived,
            telegramOnly = telegramOnly,
            onToggleTelegram = { telegramOnly = it },
        )
        HairlineField(
            value = query,
            onValueChange = { query = it },
            placeholder = "filter threads",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
            keepKeyboardOnDone = false,
            modifier = Modifier
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
                .testTag("threads.filter"),
        )
        Hairline()
        if (rooms.isNotEmpty() || !roomsError.isNullOrBlank() || (roomsLoading && sessions.isNotEmpty())) {
            RoomsSection(rooms, roomsLoading, roomsError, unreadRooms, nowMs, onOpenRoom, onDeleteRoom)
        }
        pendingDelete?.let { target ->
            DeleteStrip(target, onConfirmDelete, onCancelDelete)
            Hairline()
        }
        if (loading) {
            FetchRow(label = "LOADING THREADS", modifier = Modifier.testTag("threads.loading"))
        }
        if (sessions.isEmpty() && !loading) {
            val failed = !error.isNullOrBlank()
            val profileHint = activeProfileId?.takeIf { it.isNotBlank() }?.let { "profile=$it" } ?: "idle"
            FetchPane(
                label = if (failed) "SESSIONS FAILED" else "NO SESSIONS",
                hint = if (failed) error else "// $profileHint",
                retryLabel = if (failed) "RETRY" else null,
                onRetry = if (failed) onRetry else null,
                modifier = Modifier
                    .weight(1f)
                    .testTag(if (failed) "threads.failed" else "threads.empty"),
            )
            return
        }
        if (sessions.isEmpty() && loading) {
            FetchSkeleton()
            return
        }
        if (filtered.isEmpty()) {
            FetchPane(
                label = "NO MATCH",
                hint = "// " + listOfNotNull(
                    if (telegramOnly) "telegram" else null,
                    query.trim().ifBlank { null },
                ).joinToString(" · "),
                scanning = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("threads.nomatch"),
            )
            return
        }
        val rail = railItems(filtered, sort, nowMs)
        LazyColumn(modifier = Modifier.testTag("threads.rail")) {
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
                        Hairline()
                    }
                }
            }
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
        if (sort != ThreadSort.TITLE) {
            val group = ThreadTime.dayGroup(key, nowMs)
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
    onNew: () -> Unit,
    onNewRoom: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "NEW",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("threads.new")
                .clickable(onClick = onNew)
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
        )
        Text(
            text = "NEW ROOM",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("threads.newroom")
                .clickable(onClick = onNewRoom)
                .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Md),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (filtering) "$shown/$total" else total.toString(),
            style = CompanionType.MonoSmall,
            modifier = Modifier.testTag("threads.count"),
        )
        if (onRefresh != null) {
            Text(
                text = "↻",
                style = CompanionType.Mono.copy(
                    color = if (loading) CompanionColor.TextMute else CompanionColor.Signal,
                ),
                modifier = Modifier
                    .testTag("threads.refresh")
                    .clickable(enabled = !loading, onClick = onRefresh)
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            )
        } else {
            Spacer(Modifier.width(CompanionSpace.Lg))
        }
    }
}

/**
 * Two rows so nothing clips at 360 dp:
 * `SORT  CREATED ▾  ACTIVE  A–Z` orders; `SHOW  □ TELEGRAM  □ ARCHIVED` filters.
 * A filter chip reads `■ NAME` when on and `□ NAME` when off so state is legible without colour.
 */
@Composable
private fun SortRow(
    sort: ThreadSort,
    onSort: (ThreadSort) -> Unit,
    showArchived: Boolean,
    onToggleArchived: (Boolean) -> Unit,
    telegramOnly: Boolean,
    onToggleTelegram: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("threads.sort")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs),
        ) {
            Text(text = "SORT", style = CompanionType.MonoSmall)
            Spacer(Modifier.width(CompanionSpace.Md))
            ThreadSort.entries.forEach { option ->
                val selected = option == sort
                Text(
                    text = if (selected) "${option.label} ▾" else option.label,
                    style = CompanionType.MonoSmall.copy(
                        color = if (selected) CompanionColor.Signal else CompanionColor.TextDim,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .testTag("threads.sort.${option.name.lowercase()}")
                        .clickable { onSort(option) }
                        .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Sm),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg)
                .padding(bottom = CompanionSpace.Xs)
                .testTag("threads.show"),
        ) {
            Text(text = "SHOW", style = CompanionType.MonoSmall)
            Spacer(Modifier.width(CompanionSpace.Md))
            FilterChip(label = "TELEGRAM", on = telegramOnly, tag = "threads.filter.telegram", onToggle = onToggleTelegram)
            FilterChip(label = "ARCHIVED", on = showArchived, tag = "threads.filter.archived", onToggle = onToggleArchived)
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, tag: String, onToggle: (Boolean) -> Unit) {
    Text(
        text = (if (on) "■ " else "□ ") + label,
        style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextDim),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .testTag(tag)
            .clickable { onToggle(!on) }
            .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Sm),
    )
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        text = label,
        style = CompanionType.MonoSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.Void)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("threads.group.$label"),
    )
}

/**
 * Title + one meta line (`12 msgs · telegram · ENDED`), timestamp on the right.
 * The profile id is gone: the rail is profile-scoped and the header glyph already says which.
 */
@Composable
private fun ThreadRow(
    session: SessionRef,
    stamp: String,
    onOpen: (SessionRef) -> Unit,
    onDeleteRequest: (SessionRef) -> Unit,
) {
    val meta = buildList {
        if (session.messageCount > 0) add("${session.messageCount} msgs")
        if (session.source.isNotBlank()) add(session.source.lowercase())
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(session.id) {
                detectTapGestures(
                    onTap = { onOpen(session) },
                    onLongPress = { onDeleteRequest(session) },
                )
            }
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("threads.row.${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = CompanionSpace.Sm)
                .width(2.dp)
                .height(if (meta.isNotBlank()) 34.dp else 18.dp)
                .background(if (session.unread) CompanionColor.Signal else CompanionColor.Void),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { session.id },
                style = CompanionType.Body.copy(
                    color = if (session.unread) CompanionColor.Text else CompanionColor.TextDim,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank() || session.ended || session.archived) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = CompanionType.MonoSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("threads.meta.${session.id}"),
                        )
                    }
                    if (session.archived) {
                        Text(
                            text = if (meta.isNotBlank()) " · ARCHIVED" else "ARCHIVED",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("threads.archived.${session.id}"),
                        )
                    } else if (session.ended) {
                        Text(
                            text = if (meta.isNotBlank()) " · ENDED" else "ENDED",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("threads.ended.${session.id}"),
                        )
                    }
                }
            }
        }
        if (stamp.isNotBlank()) {
            Text(
                text = stamp,
                style = CompanionType.MonoSmall.copy(
                    color = if (session.unread) CompanionColor.Signal else CompanionColor.TextMute,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = CompanionSpace.Sm)
                    .testTag("threads.time.${session.id}"),
            )
        }
    }
}

/** ROOMS rail: title, participant glyphs, live dot while a turn runs. Long-press deletes. */
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
            text = "ROOMS",
            style = CompanionType.MonoSmall,
            modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        )
        if (loading && rooms.isEmpty()) {
            FetchRow(label = "LOADING ROOMS", modifier = Modifier.testTag("threads.rooms.loading"))
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                modifier = Modifier
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Xs)
                    .testTag("threads.rooms.error"),
            )
        }
        rooms.forEach { room ->
            val isUnread = room.id in unread
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(room.id) {
                        detectTapGestures(
                            onTap = { onOpen(room) },
                            onLongPress = { onDelete(room) },
                        )
                    }
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
                    .testTag("threads.room.${room.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .padding(end = CompanionSpace.Sm)
                        .width(2.dp)
                        .height(34.dp)
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
                        style = CompanionType.MonoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("threads.room.preview.${room.id}"),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = room.participants.joinToString(" ") { it.glyph },
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
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
                            style = CompanionType.MonoSmall.copy(color = if (room.state == "paused") CompanionColor.Warn else CompanionColor.TextMute),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Hairline()
        }
        Text(
            text = "THREADS",
            style = CompanionType.MonoSmall,
            modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        )
    }
}

@Composable
private fun DeleteStrip(session: SessionRef, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val title = session.title.ifBlank { session.id }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm)
            .testTag("threads.delete"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = "DELETE  $title?",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "DELETE",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
            modifier = Modifier
                .testTag("threads.delete.confirm")
                .clickable(onClick = onConfirm)
                .padding(CompanionSpace.Xs),
        )
        Text(
            text = "CANCEL",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            modifier = Modifier
                .testTag("threads.delete.cancel")
                .clickable(onClick = onCancel)
                .padding(CompanionSpace.Xs),
        )
    }
}
