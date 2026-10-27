package app.hermes.companion.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The host owns routing. Pairing authorizes changing it; Hands remains independent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionModePreference(mode: String, configurable: Boolean, paired: Boolean, busy: Boolean,
    error: String?, onApply: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(mode) { mutableStateOf(mode) }
    val label = when (mode) { "proxy" -> "Native Hermes"; "standalone" -> "Standalone"; else -> "Checking host…" }
    GroupSurface(modifier) {
        PreferenceRow("Connection mode", when (mode) {
            "proxy" -> "Uses the native Hermes backend"
            "standalone" -> "Basic chat backend · native Bots unavailable"
            else -> "Connect to a host to check its backend"
        }, value = label, onClick = { open = true }, modifier = Modifier.testTag("connection.mode.open"))
    }
    if (open) ModalBottomSheet(onDismissRequest = { if (!busy) open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("connection.mode.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeading("Connection mode", { if (!busy) open = false })
            Text("Changes this gateway for every connected device. Phone permissions and Hands are separate.", style = CompanionType.Caption)
            GroupSurface {
                PreferenceRow("Native Hermes", "Bots, group chats and native conversations", selected = selected == "proxy",
                    enabled = !busy, onClick = { selected = "proxy" }, modifier = Modifier.testTag("connection.mode.proxy"))
                PreferenceRow("Standalone", "Basic conversations and phone tools", selected = selected == "standalone",
                    enabled = !busy, onClick = { selected = "standalone" }, modifier = Modifier.testTag("connection.mode.standalone"))
            }
            if (!paired) Text("Pair this phone to apply your selection. Pairing alone does not change the connection mode.", style = CompanionType.Caption)
            if (!configurable) Text("This host needs the current Companion plugin to change its mode from the app.", style = CompanionType.Caption)
            if (selected == "proxy") Text("The host verifies its native backend before switching. If verification fails, the current mode is kept.", style = CompanionType.Caption)
            error?.let { Text(it, color = CompanionColor.Warn, style = CompanionType.Body, modifier = Modifier.testTag("connection.mode.error")) }
            ActionButton("Apply connection mode", { onApply(selected) }, kind = ActionKind.PRIMARY,
                enabled = paired && configurable && selected in setOf("proxy", "standalone") && selected != mode,
                busy = busy, busyLabel = "Checking connection…", modifier = Modifier.testTag("connection.mode.apply"))
        }
    }
}
