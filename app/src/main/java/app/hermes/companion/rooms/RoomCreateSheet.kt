package app.hermes.companion.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.HairlineField
import app.hermes.companion.domain.Rooms
import app.hermes.companion.model.ProfileRef

/**
 * New room: title, which profiles sit in it, how many agent rounds one operator message may
 * trigger. Same hairline chrome as the other sheets; no bubbles, one accent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomCreateSheet(
    profiles: List<ProfileRef>,
    onCreate: (title: String, participants: List<String>, maxRounds: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Last failure from the host (e.g. plugin too old, unpaired); shown under the buttons. */
    error: String? = null,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var rounds by rememberSaveable { mutableStateOf(2) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CompanionColor.VoidElevated,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Lg)
                .testTag("room.create"),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
        ) {
            Text(text = "NEW ROOM", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            Text(
                text = "Pick the profiles that sit in this room. They keep their own memory, tools and model; " +
                    "the host runs their turns and streams them here.",
                style = CompanionType.Mono,
            )
            Text(text = "TITLE", style = CompanionType.MonoSmall)
            HairlineField(
                value = title,
                onValueChange = { title = it },
                keyboardType = KeyboardType.Text,
                placeholder = "ops + coder triage",
                modifier = Modifier.testTag("room.create.title"),
            )
            Text(text = "PARTICIPANTS", style = CompanionType.MonoSmall)
            if (profiles.isEmpty()) {
                Text(text = "no profiles loaded", style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                profiles.forEach { p ->
                    val on = p.id in picked
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .testTag("room.create.profile.${p.id}")
                            .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.LineStrong)
                            .background(if (on) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                            .clickable { picked = if (on) picked - p.id else picked + p.id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = Rooms.glyph(p.id),
                            style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextDim),
                        )
                        Text(
                            text = p.displayName.ifBlank { p.id },
                            style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Text else CompanionColor.TextMute),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                Text(text = "ROUNDS", style = CompanionType.MonoSmall)
                (1..4).forEach { n ->
                    val on = n == rounds
                    Text(
                        text = "$n",
                        style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextMute),
                        modifier = Modifier
                            .testTag("room.create.rounds.$n")
                            .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.Line)
                            .clickable { rounds = n }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "agent replies per message",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                )
            }
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                    modifier = Modifier.testTag("room.create.error"),
                )
            }
            Spacer(Modifier.height(CompanionSpace.Xs))
            Row(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                val ready = picked.isNotEmpty()
                Text(
                    text = "CREATE",
                    style = CompanionType.MonoSmall.copy(color = if (ready) CompanionColor.Signal else CompanionColor.TextMute),
                    modifier = Modifier
                        .testTag("room.create.submit")
                        .border(CompanionSpace.Hairline, if (ready) CompanionColor.Signal else CompanionColor.Line)
                        .clickable(enabled = ready) { onCreate(title.trim(), picked, rounds) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = "CANCEL",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                    modifier = Modifier
                        .testTag("room.create.cancel")
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
