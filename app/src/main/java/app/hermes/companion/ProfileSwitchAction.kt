package app.hermes.companion

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.CompanionColor

/** Always visible, rather than hidden in page options. */
@Composable
internal fun ProfileSwitchAction(onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("context.agent")
            .semantics { contentDescription = "Switch agent profile" }) {
        Text("Profiles", style = CompanionType.Caption, color = if (enabled) CompanionColor.Signal else CompanionColor.TextMute)
    }
}
