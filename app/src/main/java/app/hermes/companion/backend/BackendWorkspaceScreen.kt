package app.hermes.companion.backend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.companion.backend.*
import app.hermes.companion.*
import app.hermes.companion.chat.ChatScreen
import app.hermes.companion.model.*
import app.hermes.companion.design.*

@Composable
fun BackendWorkspaceScreen(state: BackendWorkspaceState,coordinator: BackendCoordinator,onExit:()->Unit,
    onHermesTools:(String)->Unit) {
    var editor by remember(state.connection?.owner) { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    BackHandler(enabled=state.thread!=null) {coordinator.back()}
    if(state.connection==null) { ConnectionSetup(state,coordinator,onExit); return }
    val connection=requireNotNull(state.connection)
    Column(Modifier.fillMaxSize().background(CompanionColor.Void).statusBarsPadding().navigationBarsPadding().imePadding().testTag("backend.workspace")) {
        PageToolbar(state.thread?.title ?: when(state.section) {"Bots"->"Bots";"Chats"->"Chats";else->state.section},
            if(state.sample) "SAMPLE WORKSPACE · LOCAL ONLY" else "${connection.name} · ${connection.runtime.label}",
            leading=if(state.thread==null) null else ({ IconButton(onClick=coordinator::back,modifier=Modifier.size(48.dp).testTag("backend.chat.back").semantics {contentDescription="Back to bots"}) {ControlIcon(ControlGlyph.BACK)} }),
            actions={
                if(state.thread!=null && state.busy && state.capabilities.interrupt) IconButton(onClick=coordinator::interrupt,
                    modifier=Modifier.size(48.dp).testTag("backend.chat.stop").semantics {contentDescription="Stop agent turn"}) {ControlIcon(ControlGlyph.CLOSE,CompanionColor.Warn)}
                if(state.thread==null && state.section in setOf("Bots","Chats") && (state.capabilities.createBot || state.capabilities.nativeGroups)) Box {
                    IconButton(onClick={options=true},modifier=Modifier.size(48.dp).testTag("backend.new").semantics {contentDescription="New conversation"}) {ControlIcon(ControlGlyph.PLUS)}
                    DropdownMenu(options,{options=false},containerColor=CompanionColor.VoidElevated) {
                        if(state.capabilities.createBot) DropdownMenuItem(text={Text("New bot")},onClick={options=false;editor="bot"},modifier=Modifier.testTag("backend.new.bot"))
                        if(state.capabilities.nativeGroups) DropdownMenuItem(text={Text("New group")},onClick={options=false;editor="group"},modifier=Modifier.testTag("backend.new.group"))
                        if(!state.capabilities.createBot && !state.capabilities.nativeGroups) DropdownMenuItem(text={Text("Create agents on this host, then refresh")},onClick={options=false})
                    }
                }
                IconButton(onClick={coordinator.section("Connections")},modifier=Modifier.size(48.dp).testTag("backend.connections").semantics {contentDescription="Connections"}) {ControlIcon(ControlGlyph.SETTINGS)}
            })
        if(state.sample) Text("Interactive sample · no requests reach your hosts",style=CompanionType.Caption.copy(color=CompanionColor.Accent),modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("backend.sample.banner"))
        if(state.thread!=null) {
            val thread=requireNotNull(state.thread)
            if(state.uncertain) Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                Text("Delivery is unconfirmed. Check history before sending another message.",style=CompanionType.Caption.copy(color=CompanionColor.Warn))
                Row { TextButton(onClick=coordinator::checkHistory) {Text("Check history")}; TextButton(onClick=coordinator::acknowledgeUncertain) {Text("I reviewed delivery")} }
            }
            ChatScreen(loading=state.loadingHistory,sendAvailable=state.capabilities.chat && !state.loadingHistory && !state.uncertain,messages=state.messages,draft=state.draft,streaming=state.busy,error=state.error,approval=state.approval,
                approvalBusy=state.approvalBusy,onRetryMessage=coordinator::retryMessage,onRetryHistory=coordinator::checkHistory,onDraftChange=coordinator::draft,onSend=coordinator::send,onInterrupt=coordinator::interrupt,onApproval=coordinator::approve,
                threadId="${connection.owner}:${thread.id}",assistantName=state.bots.find { it.id==thread.botId }?.name ?: thread.title,
                profiles=state.bots.map {ProfileRef(it.id,it.name,it.name.take(3).uppercase())}+state.bots.flatMap { it.memberLabels.map { (id,name)->ProfileRef(id,name,name.take(3).uppercase()) } },
                participants=if(thread.group) state.bots.find {it.id==thread.botId}?.members.orEmpty().map {RoomParticipant(it,it.take(3).uppercase())} else emptyList(),
                attachmentsAvailable=false,modelsAvailable=false,voiceAvailable=false,readAloudAvailable=false,commandsAvailable=false,
                modifier=Modifier.weight(1f))
        } else {
            state.error?.let { StatusStrip(it,modifier=Modifier.padding(horizontal=16.dp)) }
            Box(Modifier.weight(1f)) {
                when(state.section) {
                    "Bots" -> BackendRoster(state,coordinator::open,coordinator::refresh)
                    "Chats" -> BackendThreadRoster(state,coordinator::openThread)
                    "Connections" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Connected to ${connection.name}",style=CompanionType.Title)
                        Text(if(state.sample) "Explore the shared bot, chat and group flows with local sample data." else connection.endpoint,style=CompanionType.Caption)
                        GroupSurface(padded=false) {
                            Column(Modifier.padding(horizontal=12.dp,vertical=6.dp)) {
                                CapabilityRow("Chat",state.capabilities.chat)
                                CapabilityRow("Native groups",state.capabilities.nativeGroups)
                                CapabilityRow("Approvals",state.capabilities.approvals)
                                CapabilityRow("Voice",state.capabilities.voice)
                                CapabilityRow("Phone tools",state.capabilities.device)
                            }
                        }
                        if(connection.runtime==AgentRuntime.HERMES && !state.sample) ActionButton("Open full Hermes controls",{onHermesTools(connection.endpoint)},modifier=Modifier.fillMaxWidth())
                        ActionButton("Add or switch connection",coordinator::disconnect,modifier=Modifier.fillMaxWidth().testTag("backend.add"))
                        SavedConnectionRows(state,coordinator)
                        ActionButton("Return to previous workspace",onExit,modifier=Modifier.fillMaxWidth())
                        TextButton(onClick={notices=true},modifier=Modifier.heightIn(min=48.dp)) { Text("Open-source notices") }
                    }
                    "Voice","Device" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(if(state.section=="Voice") "Voice" else "Phone controls",style=CompanionType.Title)
                        Text(if(state.sample) "Sample mode does not access the microphone or control the phone." else
                            if(connection.runtime==AgentRuntime.HERMES) "Use Hermes’s existing voice and Device controls for this connection." else
                                "${connection.runtime.label} ${if(state.section=="Voice") "voice" else "phone control"} is not enabled by this prototype adapter. Chat access does not arm the phone.",style=CompanionType.Body)
                        if(connection.runtime==AgentRuntime.HERMES && !state.sample) ActionButton("Open Hermes controls",{onHermesTools(connection.endpoint)},modifier=Modifier.fillMaxWidth())
                        Text("Your phone’s ARM switch, permissions and protected-app rules remain in control.",style=CompanionType.Caption)
                    }
                }
            }
            AppNavigationBar(CompanionState(tab=when(state.section) {"Bots"->MainTab.BOTS;"Chats"->MainTab.THREADS;"Device"->MainTab.DEVICE;else->MainTab.GATEWAY}),false,
                onTab={coordinator.section(when(it) {MainTab.BOTS->"Bots";MainTab.THREADS->"Chats";MainTab.DEVICE->"Device";else->"Connections"})},
                onMore={coordinator.section("Connections")},onVoice={coordinator.section("Voice")})
        }
    }
    if(notices) {
        val context=androidx.compose.ui.platform.LocalContext.current
        val text=remember { context.assets.open("companion-notices.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest={notices=false},containerColor=CompanionColor.VoidElevated,
            title={Text("Open-source notices")},text={Text(text,style=CompanionType.Caption,modifier=Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))},
            confirmButton={TextButton(onClick={notices=false}) {Text("Close")}})
    }
    editor?.let { BackendEditor(it=="group",state,coordinator,{editor=null}) }
}

@Composable private fun CapabilityRow(label:String,available:Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min=32.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,style=CompanionType.Body,modifier=Modifier.weight(1f))
        Text(if(available) "Available" else "Not enabled",style=CompanionType.Caption.copy(color=if(available) CompanionColor.Signal else CompanionColor.TextDim))
    }
}
