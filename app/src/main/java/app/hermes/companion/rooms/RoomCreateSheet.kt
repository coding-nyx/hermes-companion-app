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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.RoomCreateSpec
import app.hermes.companion.design.ActionButton
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.HairlineField
import app.hermes.companion.domain.Rooms
import app.hermes.companion.model.PeerLink
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomPolicySpec

/** Client-side presets: title, mode, turns and an opening line. Zero host work (A22.7). */
internal data class RoomTemplate(
    val id: String,
    val label: String,
    val title: String,
    val mode: String,
    val turns: Int,
    val opening: String,
)

internal val RoomTemplates = listOf(
    RoomTemplate("blank", "BLANK", "", "converse", 12, ""),
    RoomTemplate("triage", "TRIAGE", "triage", "converse", 12, "Something broke. Each of you: what do you see from your side, and who should own the fix?"),
    RoomTemplate("review", "REVIEW", "review", "converse", 16, "Review the latest change together. Point at concrete risks, disagree where you must, converge on a verdict."),
    RoomTemplate("plan", "PLAN", "plan", "moderated", 16, "We need a plan. Moderator: collect options from everyone, then pick one and say why."),
    RoomTemplate("standup", "STANDUP", "standup", "moderated", 8, "Standup. One line each: what you did, what is next, what blocks you."),
)

/**
 * New room: template, title, who sits in it (this host first, then linked peers), how the floor
 * works (converse / moderated / bounded), who holds the phone, and an opening line.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomCreateSheet(
    profiles: List<ProfileRef>,
    onCreate: (RoomCreateSpec) -> Unit,
    onDismiss: () -> Unit,
    /** Last failure from the host (e.g. plugin too old, unpaired); shown under the buttons. */
    error: String? = null,
    peers: List<PeerLink> = emptyList(),
    peerProfiles: Map<String, List<ProfileRef>> = emptyMap(),
    peersLoading: Boolean = false,
    hostName: String = "",
) {
    var template by rememberSaveable { mutableStateOf("blank") }
    var title by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var mode by rememberSaveable { mutableStateOf("converse") }
    var turns by rememberSaveable { mutableStateOf(12) }
    var rounds by rememberSaveable { mutableStateOf(2) }
    var moderator by rememberSaveable { mutableStateOf("") }
    var hands by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }

    fun applyTemplate(t: RoomTemplate) {
        template = t.id
        title = t.title
        mode = t.mode
        turns = t.turns
        opening = t.opening
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CompanionColor.VoidElevated,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Lg)
                .testTag("room.create"),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
        ) {
            Text(text = "NEW ROOM", style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal))
            Text(
                text = "Agents keep their own memory, tools and model; the host runs the floor and streams every turn here. " +
                    "They talk until nobody has anything to add.",
                style = CompanionType.Mono,
            )
            Text(text = "TEMPLATE", style = CompanionType.MonoSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                RoomTemplates.forEach { t ->
                    Chip(label = t.label, on = template == t.id, tag = "room.create.template.${t.id}") { applyTemplate(t) }
                }
            }
            Text(text = "TITLE", style = CompanionType.MonoSmall)
            HairlineField(
                value = title,
                onValueChange = { title = it },
                keyboardType = KeyboardType.Text,
                placeholder = "ops + coder triage",
                modifier = Modifier.testTag("room.create.title"),
            )
            Text(text = if (hostName.isBlank()) "PARTICIPANTS" else "PARTICIPANTS · ${hostName.uppercase()}", style = CompanionType.MonoSmall)
            if (profiles.isEmpty()) {
                Text(text = "no profiles loaded", style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn))
            }
            ParticipantRow(
                ids = profiles.map { it.id to (it.displayName.ifBlank { it.id }) },
                picked = picked,
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                tagPrefix = "room.create.profile.",
            )
            if (peersLoading && peers.isEmpty()) {
                FetchRow(label = "LOADING PEERS", padded = false, modifier = Modifier.testTag("room.create.peers.loading"))
            }
            peers.forEach { peer ->
                val remote = peerProfiles[peer.name].orEmpty()
                Text(text = "PARTICIPANTS · ${peer.name.uppercase()}", style = CompanionType.MonoSmall)
                if (remote.isEmpty()) {
                    Text(
                        text = "no profiles readable on ${peer.name} · connect to it once from this phone",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                    )
                } else {
                    ParticipantRow(
                        ids = remote.map { "${it.id}@${peer.name}" to (it.displayName.ifBlank { it.id }) },
                        picked = picked,
                        onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                        tagPrefix = "room.create.peer.",
                    )
                }
            }
            if (peers.isEmpty() && !peersLoading) {
                Text(
                    text = "other hosts: link them on the host tab (LINK HOSTS) to invite their agents",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                )
            }
            Text(text = "FLOOR", style = CompanionType.MonoSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                Chip("CONVERSE", mode == "converse", "room.create.mode.converse") { mode = "converse" }
                Chip("MODERATED", mode == "moderated", "room.create.mode.moderated") { mode = "moderated" }
                Chip("BOUNDED", mode == "bounded", "room.create.mode.bounded") { mode = "bounded" }
            }
            Text(
                text = when (mode) {
                    "moderated" -> "one agent chairs: it picks who speaks and closes the topic"
                    "bounded" -> "v1 relay race: mention-first, then round-robin, hard stop after N rounds"
                    else -> "agents talk until everyone passes; the budget only pauses the room"
                },
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            )
            if (mode == "bounded") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                    Text(text = "ROUNDS", style = CompanionType.MonoSmall)
                    (1..4).forEach { n -> Chip("$n", n == rounds, "room.create.rounds.$n") { rounds = n } }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                    Text(text = "BUDGET", style = CompanionType.MonoSmall)
                    listOf(6, 12, 24, 40).forEach { n -> Chip("$n", n == turns, "room.create.turns.$n") { turns = n } }
                    Spacer(Modifier.weight(1f))
                    Text(text = "turns before a pause", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
                }
            }
            if (mode == "moderated") {
                Text(text = "MODERATOR", style = CompanionType.MonoSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                    picked.forEach { id -> Chip(Rooms.glyph(id), moderator == id, "room.create.moderator.$id") { moderator = id } }
                    if (picked.isEmpty()) Text(text = "pick participants first", style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute))
                }
            }
            Text(text = "HANDS · who may drive this phone", style = CompanionType.MonoSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                Chip("NOBODY", hands.isBlank(), "room.create.hands.none") { hands = "" }
                picked.forEach { id -> Chip(Rooms.glyph(id), hands == id, "room.create.hands.$id") { hands = id } }
            }
            Text(text = "OPENING LINE (optional)", style = CompanionType.MonoSmall)
            HairlineField(
                value = opening,
                onValueChange = { opening = it },
                keyboardType = KeyboardType.Text,
                placeholder = "posted as you when the room opens",
                modifier = Modifier.testTag("room.create.opening"),
            )
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                    modifier = Modifier.testTag("room.create.error"),
                )
            }
            Spacer(Modifier.height(CompanionSpace.Xs))
            Row(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                val ready = picked.isNotEmpty() && (mode != "moderated" || moderator in picked || picked.size == 1)
                ActionButton(
                    label = "CREATE",
                    kind = ActionKind.PRIMARY,
                    enabled = ready,
                    onClick = {
                        onCreate(
                            RoomCreateSpec(
                                title = title.trim(),
                                participants = picked,
                                policy = RoomPolicySpec(
                                    mode = mode,
                                    maxTurns = turns,
                                    maxRounds = rounds,
                                    moderator = if (mode == "moderated") moderator.ifBlank { picked.first() } else "",
                                    hands = hands,
                                ),
                                openingPost = opening.trim(),
                            ),
                        )
                    },
                    modifier = Modifier.testTag("room.create.submit"),
                )
                ActionButton(label = "CANCEL", onClick = onDismiss, modifier = Modifier.testTag("room.create.cancel"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantRow(
    ids: List<Pair<String, String>>,
    picked: List<String>,
    onToggle: (String) -> Unit,
    tagPrefix: String,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
        ids.forEach { (id, name) ->
            val on = id in picked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .testTag(tagPrefix + id)
                    .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.LineStrong)
                    .background(if (on) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                    .clickable { onToggle(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = Rooms.glyph(id),
                    style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextDim),
                )
                Text(
                    text = name,
                    style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Text else CompanionColor.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextMute),
        modifier = Modifier
            .testTag(tag)
            .border(CompanionSpace.Hairline, if (on) CompanionColor.Signal else CompanionColor.Line)
            .background(if (on) CompanionColor.SignalDim else CompanionColor.VoidElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
