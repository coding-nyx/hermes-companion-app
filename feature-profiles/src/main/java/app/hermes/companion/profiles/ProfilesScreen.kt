package app.hermes.companion.profiles

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.LiveDot
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.model.ProfileRef

/**
 * Every agent on the host as a row: glyph, name, `id · model · sessions`, ACTIVE badge.
 * Tapping a row switches; explicit loading text communicates a pending switch without blinking.
 */
@Composable
fun ProfilesScreen(
    profiles: List<ProfileRef>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Thread list for the active profile in flight (profile switch). */
    switching: Boolean = false,
    hostName: String = "",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("profiles.list"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hostName.isBlank()) "Agent profiles" else "Agents on $hostName",
                style = CompanionType.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = profiles.size.toString(), style = CompanionType.Caption, modifier = Modifier.testTag("profiles.count"))
        }
        Hairline()
        if (switching) {
            FetchRow(label = "SWITCHING PROFILE", modifier = Modifier.testTag("profiles.switching"))
        }
        if (profiles.isEmpty()) {
            FetchPane(
                label = "No profiles available",
                hint = "This host has not reported any profiles.",
                modifier = Modifier
                    .weight(1f)
                    .testTag("profiles.empty"),
            )
            return
        }
        LazyColumn(Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            item {
                Text("Choose an agent profile for this host. Coding agent terminals are separate in More.", style = CompanionType.Caption)
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileChoice(profile = profile, selected = profile.id == activeId,
                    enabled = !switching, switching = switching,
                    onSelect = { onSelect(profile.id) },
                    modifier = Modifier.testTag("profiles.row.${profile.id}"))
            }
        }
    }
}
