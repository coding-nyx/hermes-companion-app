package app.hermes.companion.profiles

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

@OptIn(ExperimentalMaterial3Api::class)
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
        shape = RectangleShape,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "SELECT PROFILE",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                )
                Text(
                    text = "ALL ▸",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    modifier = Modifier
                        .testTag("nav.profile.sheet.all")
                        .clickable {
                            onDismiss()
                            onOpenAll()
                        }
                        .padding(CompanionSpace.Xs),
                )
            }
            Hairline()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = CompanionSpace.Sm),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == activeProfileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav.profile.sheet.${profile.id}")
                            .clickable {
                                onSelect(profile.id)
                                onDismiss()
                            }
                            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
                    ) {
                        ProfileGlyph(
                            code = profile.glyph,
                            selected = isSelected,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = CompanionType.Body.copy(
                                    color = if (isSelected) CompanionColor.Signal else CompanionColor.Text,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = profile.id,
                                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                                )
                                if (profile.model.isNotBlank()) {
                                    Text(
                                        text = "·",
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                                    )
                                    Text(
                                        text = profile.model.substringAfterLast("/"),
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "·",
                                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                                )
                                Text(
                                    text = "${profile.sessionCount} sessions",
                                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                                )
                            }
                        }
                        if (isSelected) {
                            Text(
                                text = "ACTIVE",
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                                modifier = Modifier
                                    .border(CompanionSpace.Hairline, CompanionColor.Signal)
                                    .padding(horizontal = CompanionSpace.Sm, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
