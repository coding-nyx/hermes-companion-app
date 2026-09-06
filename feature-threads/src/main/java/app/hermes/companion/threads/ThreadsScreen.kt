package app.hermes.companion.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.FetchSkeleton
import app.hermes.companion.design.Hairline
import app.hermes.companion.model.SessionRef

@Composable
fun ThreadsScreen(
    sessions: List<SessionRef>,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    pendingDelete: SessionRef? = null,
    onOpen: (SessionRef) -> Unit = {},
    onNew: () -> Unit = {},
    onDeleteRequest: (SessionRef) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("threads.list"),
    ) {
        Text(
            text = "NEW",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("threads.new")
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
                .clickable(onClick = onNew),
        )
        Hairline()
        pendingDelete?.let { target ->
            DeleteStrip(target, onConfirmDelete, onCancelDelete)
            Hairline()
        }
        if (loading) {
            FetchRow(label = "LOADING THREADS", modifier = Modifier.testTag("threads.loading"))
        }
        if (sessions.isEmpty() && !loading) {
            FetchPane(
                label = "NO SESSIONS",
                hint = "// idle",
                modifier = Modifier
                    .weight(1f)
                    .testTag("threads.empty"),
            )
            return
        }
        if (sessions.isEmpty() && loading) {
            FetchSkeleton()
            return
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
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
                            .height(18.dp)
                            .background(if (session.unread) CompanionColor.Signal else CompanionColor.Void),
                    )
                    Text(
                        text = session.title,
                        style = CompanionType.Body.copy(
                            color = if (session.unread) CompanionColor.Text else CompanionColor.TextDim,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.ended) {
                        Text(
                            text = "ENDED",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            softWrap = false,
                            modifier = Modifier
                                .padding(end = CompanionSpace.Sm)
                                .testTag("threads.ended.${session.id}"),
                        )
                    }
                    Text(
                        text = session.profileId,
                        style = CompanionType.MonoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                    )
                }
                Hairline()
            }
        }
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
