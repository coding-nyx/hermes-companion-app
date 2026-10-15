package app.hermes.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor

internal enum class VoiceControlGlyph { WAVE, MIC, MUTED, END, SPEAKER, AUTO }

@Composable
internal fun VoiceControlIcon(glyph: VoiceControlGlyph, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), 1.8f*u, StrokeCap.Round)
        val stroke = Stroke(1.8f*u, cap = StrokeCap.Round)
        when (glyph) {
            VoiceControlGlyph.WAVE -> listOf(4f to 5f, 8f to 9f, 12f to 13f, 16f to 8f, 20f to 4f).forEach { (x,h) -> line(x,12f-h/2,x,12f+h/2) }
            VoiceControlGlyph.MIC, VoiceControlGlyph.MUTED -> {
                drawRoundRect(color, Offset(9*u,2*u), Size(6*u,12*u), androidx.compose.ui.geometry.CornerRadius(3*u), style = stroke)
                drawArc(color, 0f, 180f, false, Offset(6*u,6*u), Size(12*u,12*u), style = stroke)
                line(12f,18f,12f,22f); line(9f,22f,15f,22f)
                if (glyph == VoiceControlGlyph.MUTED) line(3f,3f,21f,21f)
            }
            VoiceControlGlyph.END -> { line(5f,5f,19f,19f); line(19f,5f,5f,19f) }
            VoiceControlGlyph.SPEAKER -> {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(3*u,9*u); lineTo(7*u,9*u); lineTo(12*u,4*u); lineTo(12*u,20*u); lineTo(7*u,15*u); lineTo(3*u,15*u); close() }
                drawPath(path,color,style=stroke); drawArc(color,-60f,120f,false,Offset(10*u,3*u),Size(12*u,18*u),style=stroke)
            }
            VoiceControlGlyph.AUTO -> { drawArc(color,180f,180f,false,Offset(4*u,3*u),Size(16*u,18*u),style=stroke); line(4f,12f,4f,20f); line(20f,12f,20f,20f) }
        }
    }
}

internal fun voiceStageLabel(state: CompanionState): String = if (state.approval != null) "Approval needed" else when (state.voiceStreamState) {
    VoiceStreamState.IDLE -> "Voice ended"
    VoiceStreamState.LISTENING -> if(state.voiceMicrophoneReady)"Listening" else "Preparing microphone…"
    VoiceStreamState.TRANSCRIBING -> "Understanding…"
    VoiceStreamState.THINKING -> "Waiting for Hermes"
    VoiceStreamState.SPEAKING -> "Speaking"
    VoiceStreamState.MUTED -> "Microphone muted"
}.let { if (state.voiceMicrophoneMuted && state.voiceStreamState != VoiceStreamState.MUTED) "$it · Mic muted" else it }

@Composable
internal fun VoiceDock(state: CompanionState, onOpen: () -> Unit, onMute: () -> Unit, onStop: () -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=if(compact)0.dp else 6.dp)
        .clip(RoundedCornerShape(18.dp)).background(CompanionColor.VoidElevated)
        .border(1.dp, CompanionColor.Line, RoundedCornerShape(18.dp)).padding(start=10.dp,end=4.dp)
        .testTag("voice.stage"), verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min=if(compact)48.dp else 56.dp).clickable(role=Role.Button,onClick=onOpen)
            .testTag("voice.stage.open").semantics { contentDescription="Open voice controls for ${state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "this conversation" }}, ${voiceStageLabel(state)}" },
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            VoiceControlIcon(if(state.voiceMicrophoneMuted) VoiceControlGlyph.MUTED else VoiceControlGlyph.WAVE,CompanionColor.Signal)
            Column(Modifier.weight(1f)) {
                Text(if(compact)voiceStageLabel(state) else state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "Voice conversation" },
                    style=MaterialTheme.typography.labelMedium, color=CompanionColor.Text, maxLines=1,overflow=TextOverflow.Ellipsis)
                if(!compact)Text(voiceStageLabel(state),color=CompanionColor.Signal,style=MaterialTheme.typography.labelSmall,
                    maxLines=2,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            }
        }
        VoiceDockButton(if(state.voiceMicrophoneMuted)"Resume microphone" else "Mute microphone", "voice.stage.mute",
            if(state.voiceMicrophoneMuted)VoiceControlGlyph.MUTED else VoiceControlGlyph.MIC,onMute)
        VoiceDockButton("End voice conversation","voice.stage.stop",VoiceControlGlyph.END,onStop,CompanionColor.Danger)
    }
}

@Composable
private fun VoiceDockButton(label:String,tag:String,glyph:VoiceControlGlyph,onClick:()->Unit,color:Color=CompanionColor.Signal) {
    IconButton(onClick=onClick,modifier=Modifier.size(48.dp).testTag(tag).semantics { contentDescription=label }) { VoiceControlIcon(glyph,color) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoiceControlsSheet(state:CompanionState,onDismiss:()->Unit,onMute:()->Unit,onSpeaker:()->Unit,
    onStop:()->Unit,onConversation:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=CompanionColor.VoidElevated,contentColor=CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp)
            .testTag("voice.controls"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Voice conversation",style=MaterialTheme.typography.titleLarge)
            Text("${state.hostName.ifBlank { state.origin.orEmpty() }} / ${state.activeProfile?.displayName ?: state.activeProfileId.orEmpty()}",
                color=CompanionColor.TextDim,style=MaterialTheme.typography.bodySmall)
            Text(state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "Voice conversation" },style=MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().padding(vertical=12.dp),contentAlignment=Alignment.Center) { VoiceControlIcon(VoiceControlGlyph.WAVE,CompanionColor.Signal) }
            Text(voiceStageLabel(state),style=MaterialTheme.typography.titleMedium,color=CompanionColor.Signal,
                modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            Text(when {
                state.voicePauseReason.isNotBlank() -> state.voicePauseReason
                state.voiceStreamState==VoiceStreamState.LISTENING && !state.voiceMicrophoneReady -> "Wait until Listening appears before speaking."
                state.voiceStreamState==VoiceStreamState.TRANSCRIBING -> "Turning your speech into text. The microphone is paused."
                state.approval != null -> "Open the conversation to review the exact pending request."
                state.voiceMicrophoneMuted -> "Listening resumes only when you unmute."
                state.voiceStreamState==VoiceStreamState.SPEAKING -> "The microphone pauses during speaker playback."
                state.voiceStreamState==VoiceStreamState.THINKING -> "Hermes is preparing its reply."
                else -> "Speak when you are ready."
            },color=CompanionColor.TextDim)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onMute,modifier=Modifier.testTag("voice.controls.mute")) {
                    VoiceControlIcon(if(state.voiceMicrophoneMuted)VoiceControlGlyph.MUTED else VoiceControlGlyph.MIC,CompanionColor.Signal)
                    Spacer(Modifier.width(6.dp)); Text(if(state.voiceMicrophoneMuted)"Resume mic" else "Mute mic")
                }
                OutlinedButton(onClick=onSpeaker,modifier=Modifier.testTag("voice.stage.speaker")) {
                    VoiceControlIcon(if(state.voiceSpeaker)VoiceControlGlyph.SPEAKER else VoiceControlGlyph.AUTO,CompanionColor.Signal)
                    Spacer(Modifier.width(6.dp));Text(if(state.voiceSpeaker)"Speaker" else "Auto route")
                }
                OutlinedButton(onClick=onStop,modifier=Modifier.testTag("voice.controls.stop")) { Text("End voice",color=CompanionColor.Danger) }
            }
            Text("Spoken replies are brief. Read the full answer in chat, or use Read aloud.",style=MaterialTheme.typography.bodySmall,color=CompanionColor.TextDim)
            OutlinedButton(onClick=onConversation,modifier=Modifier.fillMaxWidth().testTag("voice.controls.conversation")) { Text(if (state.approval != null) "Review pending approval" else "Open this conversation") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoiceStartSheet(state:CompanionState,onDismiss:()->Unit,onStart:()->Unit,onChoose:()->Unit) {
    val unavailable = when {
        state.loading -> "Wait for the host connection to finish."
        state.activeProfileId == null -> "Select a profile before starting voice."
        state.inRoom -> "Voice is available in individual chats. Choose a chat to continue."
        state.streaming || state.submitting || state.creatingThread -> "Wait for the current reply before starting voice."
        else -> null
    }
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=CompanionColor.VoidElevated,contentColor=CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("voice.start"),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Talk to Hermes",style=MaterialTheme.typography.titleLarge)
            Text("${state.hostName.ifBlank { state.origin.orEmpty() }} / ${state.activeProfile?.displayName ?: state.activeProfileId.orEmpty()}",color=CompanionColor.TextDim)
            Text(unavailable ?: "Choose the conversation before listening starts.")
            Button(onClick=onStart,enabled=unavailable==null,modifier=Modifier.fillMaxWidth().testTag("voice.start.confirm")) {
                Text(state.openSession?.let { "Continue ${it.title.ifBlank { "conversation" }}" } ?: "Start a new voice conversation")
            }
            OutlinedButton(onClick=onChoose,modifier=Modifier.fillMaxWidth().testTag("voice.start.choose")) { Text("Choose another conversation") }
            Text("The microphone starts only after you confirm. Voice ends when the app leaves the foreground.",
                style=MaterialTheme.typography.bodySmall,color=CompanionColor.TextDim)
        }
    }
}
