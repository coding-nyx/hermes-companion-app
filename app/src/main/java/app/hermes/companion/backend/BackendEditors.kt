package app.hermes.companion.backend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.companion.backend.*
import app.hermes.companion.design.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendEditor(group: Boolean,state: BackendWorkspaceState,coordinator: BackendCoordinator,onDismiss:()->Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val members=state.bots.filter { it.id in selected && !it.group }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=CompanionColor.Void,contentColor=CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).navigationBarsPadding().imePadding().testTag("backend.editor")) {
            SheetHeading(if(group) "New group" else "New bot",onDismiss,modifier=Modifier.padding(horizontal=16.dp))
            LazyColumn(Modifier.weight(1f,fill=false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item { HairlineField(name,{name=it},label=if(group) "Group name (optional)" else "Bot name",modifier=Modifier.testTag("backend.editor.name")) }
                if(group) {
                    item { Text(if(members.isEmpty()) "Choose 2–6 bots. The name can be generated for you." else "${members.size} selected · ${name.ifBlank { members.joinToString { it.name } }}",style=CompanionType.Caption) }
                    items(state.bots.filterNot { it.group },key={it.id}) { bot ->
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=bot.id in selected || selected.size<6,role=Role.Checkbox) {
                            selected=if(bot.id in selected) selected-bot.id else selected+bot.id
                        }.semantics { this.selected=bot.id in selected }.testTag("backend.pick.${bot.id}"),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(bot.id in selected,null)
                            Column(Modifier.weight(1f)) { Text(bot.name,style=CompanionType.Body);Text(bot.description,style=CompanionType.Caption) }
                        }
                    }
                } else {
                    item { HairlineField(description,{description=it},label="Description (optional)",modifier=Modifier.testTag("backend.editor.description")) }
                    item { HairlineField(agent,{agent=it},label=when(state.connection?.runtime) {
                        AgentRuntime.OPENCLAW->"Workspace path on host"; AgentRuntime.CODYNC->"Agent command on host"; else->"Profile ID"
                    },modifier=Modifier.testTag("backend.editor.agent")) }
                    item { Text(if(state.sample) "Sample bots stay in this workspace and never start a real agent." else "The host owns this agent’s tools, model and workspace.",style=CompanionType.Caption) }
                }
                state.error?.let { item { StatusStrip(it,modifier=Modifier.testTag("backend.editor.error")) } }
            }
            ActionButton(if(state.busy) "Creating…" else if(group) "Create group" else "Create bot",{
                coordinator.create(name.trim(),description.trim(),agent.trim(),if(group) members else emptyList(),onDismiss)
            },enabled=!state.busy && !state.creationUncertain && (if(group) members.size in 2..6 else name.isNotBlank() && (state.sample || agent.isNotBlank())),kind=ActionKind.PRIMARY,
                modifier=Modifier.fillMaxWidth().padding(16.dp).testTag("backend.editor.create"))
        }
    }
}
