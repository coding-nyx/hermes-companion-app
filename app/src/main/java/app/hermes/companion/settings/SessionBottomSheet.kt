package app.hermes.companion.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.HairlineField
import app.hermes.companion.model.SessionRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionBottomSheet(
    session: SessionRef, busy: Boolean, streaming: Boolean,
    onRename: (String) -> Unit, onPin: () -> Unit, onArchive: () -> Unit,
    onBranch: () -> Unit, onDismiss: () -> Unit, onContext: () -> Unit = {}, onControls: () -> Unit = {},
) {
    var title by remember(session.id) { mutableStateOf(session.title) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CompanionColor.VoidElevated) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("session.actions")) {
            Text("Thread", style = CompanionType.Body)
            TextButton(onClick = { onDismiss(); onControls() }) { Text("Session settings and commands") }
            TextButton(onClick = { onDismiss(); onContext() }) { Text("Context usage") }
            HairlineField(value = title, onValueChange = { title = it }, placeholder = "Thread name",
                modifier = Modifier.testTag("session.title"))
            TextButton(enabled = !busy && title.isNotBlank() && title != session.title,
                onClick = { onRename(title.trim()); onDismiss() }) { Text("Rename") }
            TextButton(enabled = !busy, onClick = { onPin(); onDismiss() }) {
                Text(if (session.pinned) "Unpin" else "Pin")
            }
            TextButton(enabled = !busy, onClick = { onArchive(); onDismiss() }) {
                Text(if (session.archived) "Unarchive" else "Archive")
            }
            TextButton(enabled = !busy && !streaming, onClick = { onBranch(); onDismiss() }) {
                Text("Branch conversation")
            }
        }
    }
}
