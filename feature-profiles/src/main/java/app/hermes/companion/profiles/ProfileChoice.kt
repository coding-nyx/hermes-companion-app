package app.hermes.companion.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.ProfileRef

/** Same host-scoped agent choice in the quick switcher and the full profile list. */
@Composable
internal fun ProfileChoice(
    profile: ProfileRef,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switching: Boolean = false,
) {
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (selected) CompanionColor.AccentDim else CompanionColor.VoidElevated)
            .border(1.dp, if (selected) CompanionColor.Accent else CompanionColor.LineStrong, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics { stateDescription = if (selected) "Current agent profile" else "Available agent profile" }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        ProfileGlyph(code = profile.glyph, selected = selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.displayName, style = CompanionType.RowTitle,
                color = if (selected) CompanionColor.Accent else CompanionColor.Text)
            Text(buildList {
                add(profile.id)
                if (profile.model.isNotBlank()) add(profile.model.substringAfterLast("/"))
                add("${profile.sessionCount} conversations")
            }.joinToString(" · "), style = CompanionType.Caption)
            if (selected) Text(if (switching) "Switching…" else "Current agent", style = CompanionType.Caption, color = CompanionColor.Accent)
        }
        if (selected) ControlIcon(ControlGlyph.CHECK, color = CompanionColor.Accent)
    }
}
