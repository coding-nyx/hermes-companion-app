package app.hermes.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.SavedGateway

/** Switching context uses the existing owner-scoped callbacks; no credentials live in this UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostSwitchSheet(state: CompanionState, onSelect: (SavedGateway) -> Unit,
    onManage: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("context.host.sheet"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SheetHeading("Switch host", onDismiss)
            Text("Conversations and drafts stay with their host and agent profile.", style = CompanionType.Caption)
            GroupSurface {
                if (state.savedGateways.isEmpty()) PreferenceRow(state.hostName.ifBlank { "Current host" }, state.origin,
                    value = "Current", selected = true)
                state.savedGateways.forEach { host ->
                    PreferenceRow(host.name, host.origin, value = if (host.isActive) "Current" else "Connect", selected = host.isActive,
                        enabled = !state.loading, modifier = Modifier.testTag("context.host.${app.hermes.companion.domain.GatewayBook.chipId(host.origin)}"),
                        onClick = { if (host.isActive) onDismiss() else onSelect(host) })
                }
            }
            ActionButton("Manage hosts & gateway", onManage, modifier = Modifier.fillMaxWidth().testTag("context.host.manage"))
        }
    }
}
