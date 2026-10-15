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
 * Tapping a row switches; while the new profile's threads load the row blinks and the
 * header says so, so a slow host never looks like a dead tap.
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
                text = if (hostName.isBlank()) "Available profiles" else "Profiles on $hostName",
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
        LazyColumn(Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { profile ->
                val selected = profile.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()

                        .heightIn(min = 48.dp).clickable(enabled = !switching) { onSelect(profile.id) }
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .testTag("profiles.row.${profile.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
                ) {
                    ProfileGlyph(
                        code = profile.glyph,
                        selected = selected,
                        modifier = Modifier.testTag("profiles.glyph.${profile.id}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName,
                            style = CompanionType.Body.copy(color = if (selected) CompanionColor.Signal else CompanionColor.Text),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildList {
                            add(profile.id)
                            if (profile.model.isNotBlank()) add(profile.model.substringAfterLast("/"))
                            if (profile.sessionCount > 0) add("${profile.sessionCount} threads")
                            if (profile.gateway.isNotBlank()) add("gw ${profile.gateway}")
                        }.joinToString(" · ")
                        Text(text = meta, style = CompanionType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected && switching) {
                        LiveDot()
                        Spacer(Modifier.width(CompanionSpace.Xs))
                    }
                    if (selected) {
                        Text(
                            text = "✓",
                            style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                            modifier = Modifier

                                .padding(horizontal = CompanionSpace.Sm, vertical = 2.dp),
                        )
                    }
                }
                Hairline()
            }
        }
    }
}
