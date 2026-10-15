package app.hermes.companion.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import app.hermes.companion.design.*
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CompanionColor.VoidElevated) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("session.actions"),
            verticalArrangement = Arrangement.spacedBy(32.dp)) {
            SheetHeading("Conversation", onDismiss)
            GroupedSection("Session") {
                PreferenceRow("Model, reasoning & commands", "Settings for this conversation", onClick = { onDismiss(); onControls() })
                PreferenceRow("Context usage", "Breakdown and available context", onClick = { onDismiss(); onContext() })
            }
            GroupedSection("Title", padded = true) {
                HairlineField(title, { title = it }, placeholder = "Conversation name", modifier = Modifier.testTag("session.title"))
                ActionButton("Rename", { onRename(title.trim()); onDismiss() }, enabled = !busy && title.isNotBlank() && title != session.title)
            }
            GroupedSection("Organization") {
                PreferenceRow(if (session.pinned) "Unpin" else "Pin", "Manage your pinned conversations", enabled = !busy, onClick = { onPin(); onDismiss() })
                PreferenceRow(if (session.archived) "Unarchive" else "Archive", "Keep the conversation and its history", enabled = !busy, onClick = { onArchive(); onDismiss() })
                PreferenceRow("Branch conversation", "Continue from this point in a separate chat", enabled = !busy && !streaming, onClick = { onBranch(); onDismiss() })
            }
        }
    }
}
