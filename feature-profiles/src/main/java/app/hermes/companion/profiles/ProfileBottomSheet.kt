package app.hermes.companion.profiles

import app.hermes.companion.design.ActionText

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.ProfileGlyph
import app.hermes.companion.model.ProfileRef

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfileBottomSheet(
    profiles: List<ProfileRef>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onOpenAll: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = androidx.compose.foundation.shape.CutCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = CompanionColor.VoidElevated,
        contentColor = CompanionColor.Text,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = CompanionSpace.Sm)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .fillMaxWidth(0.12f)
                        .background(CompanionColor.LineStrong),
                )
            }
        },
        modifier = Modifier.testTag("nav.profile.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = CompanionSpace.Lg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
            ) {
                app.hermes.companion.design.SheetHeading("Agent profiles", onDismiss,
                    closeModifier = Modifier.testTag("nav.profile.sheet.close"))
                ActionText(
                    text = "All profiles",
                    style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                    modifier = Modifier
                        .testTag("nav.profile.sheet.all")
                        .heightIn(min = 48.dp).clickable {
                            onDismiss()
                            onOpenAll()
                        }
                        .padding(CompanionSpace.Xs),
                )
            }
            Hairline()
            Text("Choose the agent for this host. Each agent keeps its own conversations.",
                style = CompanionType.Caption,
                modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileChoice(profile = profile, selected = profile.id == activeProfileId,
                        onSelect = { onSelect(profile.id); onDismiss() },
                        modifier = Modifier.testTag("nav.profile.sheet.${profile.id}"))
                }
            }
        }
    }
}
