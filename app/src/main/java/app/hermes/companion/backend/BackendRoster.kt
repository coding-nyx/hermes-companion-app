package app.hermes.companion.backend

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.companion.backend.*
import app.hermes.companion.design.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackendRoster(state: BackendWorkspaceState,onOpen:(AgentBot)->Unit,onRefresh:()->Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    Column(Modifier.fillMaxSize()) {
        SearchField(search,{search=it},"Search bots and group chats",Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("backend.search"))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("All","Bots","Groups").forEach { value -> TextButton(onClick={filter=value},modifier=Modifier.heightIn(min=48.dp).semantics { selected=filter==value }) {
                Text(value,style=CompanionType.Label.copy(color=if(filter==value) CompanionColor.Signal else CompanionColor.TextDim))
            } }
            Spacer(Modifier.weight(1f))
            IconButton(onClick=onRefresh,modifier=Modifier.size(48.dp).semantics { contentDescription="Refresh conversations" }) { ControlIcon(ControlGlyph.REFRESH) }
        }
        val visible=state.bots.filter { (filter!="Bots" || !it.group) && (filter!="Groups" || it.group) && "${it.name} ${it.description} ${it.preview}".contains(search.trim(),true) }
        LazyColumn(Modifier.weight(1f).testTag("backend.roster"),contentPadding=PaddingValues(horizontal=16.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(visible,key={it.id}) { bot ->
                val shape=CutCornerShape(topEnd=10.dp,bottomStart=10.dp)
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(CompanionColor.VoidElevated,shape).border(1.dp,CompanionColor.CardBorder,shape)
                    .clickable(role=Role.Button) {onOpen(bot)}.testTag("backend.bot.${bot.id}").padding(horizontal=12.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(if(bot.group) CompanionColor.AccentDim else CompanionColor.SignalDim,CutCornerShape(8.dp)),contentAlignment=Alignment.Center) {
                        if(bot.group) Text("#",style=CompanionType.Title.copy(color=CompanionColor.Accent))
                        else RuntimeMark(if(state.sample) when(bot.agent) {"OpenClaw"->AgentRuntime.OPENCLAW;"Codync"->AgentRuntime.CODYNC;else->AgentRuntime.HERMES} else state.runtime,Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(bot.name,style=CompanionType.RowTitle,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(bot.preview.ifBlank { bot.description }.ifBlank { if(bot.group) "${bot.members.size} members" else state.connection?.runtime?.label.orEmpty() },
                            style=CompanionType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            if(visible.isEmpty()) item { Text(if(search.isNotBlank()) "No matching conversations" else "No bots returned by this host. Refresh or create one where supported.",style=CompanionType.Caption,modifier=Modifier.padding(vertical=16.dp)) }
        }
    }
}

@Composable
fun BackendThreadRoster(state: BackendWorkspaceState,onOpen:(AgentThread)->Unit) {
    var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SearchField(search,{search=it},"Search chats",Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("backend.thread.search"))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(state.threads.filter { "${it.title} ${state.bots.find { bot->bot.id==it.botId }?.name}".contains(search,true) },key={it.id}) { thread ->
                GroupSurface(padded=false) {
                    Column(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(role=Role.Button) {onOpen(thread)}.padding(12.dp).testTag("backend.thread.${thread.id}")) {
                        Text(thread.title,style=CompanionType.RowTitle)
                        Text(state.bots.find {it.id==thread.botId}?.name ?: thread.botId,style=CompanionType.Caption)
                    }
                }
            }
            if(state.threads.isEmpty()) item { Text("Open a bot to start a conversation, then return here to browse its chats.",style=CompanionType.Caption) }
        }
    }
}
