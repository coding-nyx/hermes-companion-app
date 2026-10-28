package app.hermes.companion.backend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.companion.backend.*
import app.hermes.companion.design.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionSetup(state: BackendWorkspaceState,coordinator: BackendCoordinator,onExit: ()->Unit) {
    var signIn by remember(state.runtime) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(CompanionColor.Void).statusBarsPadding().navigationBarsPadding().imePadding().testTag("backend.setup")) {
        PageToolbar("Companion","YOUR AGENTS. YOUR HOSTS.",leading={ IconButton(onClick=onExit,modifier=Modifier.size(48.dp).semantics { contentDescription="Back" }) { ControlIcon(ControlGlyph.BACK) } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Connect an agent host",style=CompanionType.Title)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                AgentRuntime.entries.forEach { runtime ->
                    val selected=state.runtime==runtime
                    Row((if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min=56.dp).border(1.dp,if(selected) CompanionColor.Signal else CompanionColor.LineStrong,CutCornerShape(topEnd=8.dp,bottomStart=8.dp))
                        .background(if(selected) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                        .clickable(enabled=!state.connecting,role=Role.Tab) { coordinator.select(runtime) }.semantics { this.selected=selected }.testTag("backend.kind.${runtime.name}")
                        .padding(horizontal=6.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        RuntimeMark(runtime,Modifier.size(18.dp)); Text(runtime.label,style=CompanionType.Label.copy(fontSize=androidx.compose.ui.unit.TextUnit(13f,androidx.compose.ui.unit.TextUnitType.Sp)))
                    }
                }
            }
            Text(state.runtime.setup,style=CompanionType.Caption)
            if(state.runtime!=AgentRuntime.CODYNC) HairlineField(state.name,coordinator::name,label="Connection name (optional)",modifier=Modifier.testTag("backend.name"))
            HairlineField(state.endpoint,coordinator::endpoint,
                label=when(state.runtime) { AgentRuntime.HERMES->"Dashboard or relay address"; AgentRuntime.OPENCLAW->"Gateway address"; AgentRuntime.CODYNC->"Paste Codync pairing link" },
                modifier=Modifier.testTag("backend.endpoint"))
            when(state.runtime) {
                AgentRuntime.HERMES -> {
                    Text("Use your native dashboard or Companion relay. Existing Hermes connections stay available.",style=CompanionType.Caption)
                    TextButton(onClick={signIn=!signIn},modifier=Modifier.heightIn(min=48.dp)) { Text(if(signIn) "Hide sign-in fields" else "Sign in with a password") }
                    if(signIn) {
                        HairlineField(state.username,coordinator::username,label="Username",modifier=Modifier.testTag("backend.username"))
                        HairlineField(state.password,coordinator::password,label="Password",visualTransformation=PasswordVisualTransformation(),modifier=Modifier.testTag("backend.password"))
                    }
                }
                AgentRuntime.OPENCLAW -> {
                    if(signIn) HairlineField(state.password,coordinator::password,label="Gateway password",visualTransformation=PasswordVisualTransformation(),modifier=Modifier.testTag("backend.password"))
                    else HairlineField(state.token,coordinator::token,label="Gateway token",visualTransformation=PasswordVisualTransformation(),modifier=Modifier.testTag("backend.token"))
                    TextButton(onClick={signIn=!signIn},modifier=Modifier.heightIn(min=48.dp)) { Text(if(signIn) "Use a gateway token" else "Use a gateway password") }
                    Text("Approve Companion on the gateway if asked. Your device identity is saved securely for this connection.",style=CompanionType.Caption)
                }
                AgentRuntime.CODYNC -> Text("Show a fresh pairing link on your computer and paste it here. Companion verifies the computer and opens an encrypted connection.",style=CompanionType.Caption)
            }
            state.error?.let { StatusStrip(it,modifier=Modifier.testTag("backend.error")) }
            ActionButton(if(state.connecting) "Connecting…" else "Connect ${state.runtime.label}",coordinator::connect,kind=ActionKind.PRIMARY,enabled=!state.connecting,modifier=Modifier.fillMaxWidth().testTag("backend.connect"))
            ActionButton("Explore sample workspace",coordinator::sample,enabled=!state.connecting,modifier=Modifier.fillMaxWidth().testTag("backend.sample"))
            if(state.connections.isNotEmpty()) {
                Text("Saved connections",style=CompanionType.Label)
                SavedConnectionRows(state,coordinator)
            }
            Text("Companion prototype · live connections and sample data are kept separate.",style=CompanionType.Caption)
        }
    }
}

@Composable
fun SavedConnectionRows(state: BackendWorkspaceState,coordinator: BackendCoordinator) {
    var forget by remember { mutableStateOf<AgentConnection?>(null) }
    state.connections.forEach { connection ->
        GroupSurface(padded=false) {
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable { coordinator.openConnection(connection) }.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                RuntimeMark(connection.runtime,Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(connection.name,style=CompanionType.RowTitle)
                    Text("${connection.runtime.label} · ${connection.endpoint.substringAfter("://")}",style=CompanionType.Caption,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                IconButton(onClick={forget=connection},modifier=Modifier.size(48.dp).semantics { contentDescription="Forget ${connection.name}" }) { ControlIcon(ControlGlyph.CLOSE) }
            }
        }
    }
    forget?.let { connection -> AlertDialog(onDismissRequest={forget=null},containerColor=CompanionColor.VoidElevated,
        title={Text("Forget ${connection.name}?")},text={Text("Remove this connection’s saved credentials, cached messages and drafts from this app.")},
        confirmButton={TextButton(onClick={coordinator.forget(connection);forget=null}) {Text("Forget",color=CompanionColor.Danger)}},
        dismissButton={TextButton(onClick={forget=null}) {Text("Cancel")}}) }
}
