package app.hermes.companion.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.model.SessionRef

@Composable
fun ThreadsScreen(
    sessions: List<SessionRef>,
    modifier: Modifier = Modifier,
    onOpen: (SessionRef) -> Unit = {},
    onNew: () -> Unit = {},
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
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "NO SESSIONS // waiting", style = CompanionType.Mono)
            }
            return
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(session) }
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
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = session.profileId, style = CompanionType.MonoSmall)
                }
                Hairline()
            }
        }
    }
}
