package app.hermes.companion.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.model.ProfileRef

@Composable
fun ProfilesScreen(
    profiles: List<ProfileRef>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = profiles.find { it.id == activeId }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .padding(CompanionSpace.Lg)
            .testTag("profiles.list"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
            profiles.forEach { profile ->
                ProfileGlyph(
                    code = profile.glyph,
                    selected = profile.id == activeId,
                    modifier = Modifier.testTag("profiles.glyph.${profile.id}"),
                    onClick = { onSelect(profile.id) },
                )
            }
        }
        Spacer(Modifier.height(CompanionSpace.Xl))
        if (active == null) {
            Text(text = "NO PROFILES", style = CompanionType.Mono)
        } else {
            Text(text = active.displayName, style = CompanionType.Body)
            Spacer(Modifier.height(CompanionSpace.Md))
            MonoLine("id", active.id)
            MonoLine("model", active.model.ifBlank { "—" })
            MonoLine("sessions", active.sessionCount.toString())
            MonoLine("gateway", active.gateway.ifBlank { "—" })
        }
    }
}

@Composable
private fun MonoLine(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = CompanionType.Mono,
        modifier = Modifier.padding(vertical = CompanionSpace.Xs),
    )
}
