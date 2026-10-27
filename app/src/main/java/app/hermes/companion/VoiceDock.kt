package app.hermes.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import app.hermes.companion.design.*

internal enum class VoiceControlGlyph { WAVE, MIC, MUTED, END, SPEAKER, AUTO }

@Composable
internal fun VoiceControlIcon(glyph: VoiceControlGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
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

internal fun voiceStageLabel(state: CompanionState): String = if (state.isVoiceStreamActive && state.voiceCallPhase == app.hermes.companion.model.NativeCallPhase.CONNECTING) "Connecting voice…" else if (state.approval != null) "Approval needed" else when (state.voiceStreamState) {
    VoiceStreamState.IDLE -> "Voice ended"
    VoiceStreamState.LISTENING -> if(state.voiceMicrophoneReady)"Listening" else "Preparing microphone…"
    VoiceStreamState.TRANSCRIBING -> "Understanding…"
    VoiceStreamState.THINKING -> "Waiting for Hermes"
    VoiceStreamState.SPEAKING -> "Speaking"
    VoiceStreamState.MUTED -> "Microphone muted"
}.let { if (state.voiceMicrophoneMuted && state.voiceStreamState != VoiceStreamState.MUTED) "$it · Mic muted" else it }

@Composable
internal fun VoiceDock(state: CompanionState, onOpen: () -> Unit, onMute: () -> Unit, onStop: () -> Unit, compact: Boolean = false) {
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp)
    val stageColor = when {
        state.approval != null -> CompanionColor.Warn
        state.voiceMicrophoneMuted -> CompanionColor.TextDim
        else -> CompanionColor.Signal
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp)
        .clip(shape).background(CompanionColor.VoidElevated).border(1.dp, CompanionColor.LineStrong, shape)
        .padding(start = 12.dp, end = 4.dp).testTag("voice.stage"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = if (compact) 48.dp else 64.dp)
            .clickable(role = Role.Button, onClick = onOpen).testTag("voice.stage.open")
            .semantics(mergeDescendants = true) {
                contentDescription = "Open voice controls for ${state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "this conversation" }}, ${voiceStageLabel(state)}"
                liveRegion = LiveRegionMode.Polite
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceControlIcon(if (state.voiceMicrophoneMuted) VoiceControlGlyph.MUTED else VoiceControlGlyph.WAVE, stageColor)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (!compact) Text(state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "Voice conversation" },
                    style = CompanionType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(voiceStageLabel(state).replace("Preparing microphone…", "Preparing mic…")
                    .replace("Microphone muted", "Mic muted"), color = stageColor, style = CompanionType.Label,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onMute, modifier = Modifier.size(48.dp).testTag("voice.stage.mute")
            .semantics { contentDescription = if (state.voiceMicrophoneMuted) "Resume microphone" else "Mute microphone" }) {
            VoiceControlIcon(if (state.voiceMicrophoneMuted) VoiceControlGlyph.MUTED else VoiceControlGlyph.MIC, CompanionColor.Signal)
        }
        TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("voice.stage.stop")
                .semantics { contentDescription = "End voice conversation" }) {
            Text("End", style = CompanionType.Label, color = CompanionColor.Danger, maxLines = 1)
        }
    }
}

@Composable
private fun VoiceIdentity(state: CompanionState) {
    GroupSurface(padded = true) {
        ProtocolStatus("Host", state.hostName.ifBlank { state.origin.orEmpty().ifBlank { "Not selected" } })
        ProtocolStatus("Agent", state.activeProfile?.displayName ?: state.activeProfileId ?: "Not selected", tone = CompanionColor.Accent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoiceControlsSheet(state: CompanionState, onDismiss: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
    onStop: () -> Unit, onConversation: () -> Unit, onInterrupt: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).testTag("voice.controls"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeading("Voice conversation", onDismiss)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("voice.controls.details"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VoiceIdentity(state)
                ProtocolStatus("Voice mode", state.voiceModeLabel)
                ProtocolStatus("Speech", state.voiceDescription.ifBlank { "Checking host speech…" })
                Text(state.voiceSessionTitle.ifBlank { state.openSession?.title ?: "Voice conversation" }, style = CompanionType.Title)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
                    Box(Modifier.size(64.dp).clip(shape).background(CompanionColor.SignalDim)
                        .border(1.dp, CompanionColor.Signal, shape), contentAlignment = Alignment.Center) {
                        VoiceControlIcon(if (state.voiceMicrophoneMuted) VoiceControlGlyph.MUTED else VoiceControlGlyph.WAVE,
                            CompanionColor.Signal, Modifier.size(32.dp))
                    }
                    Text(voiceStageLabel(state), style = CompanionType.Title,
                        color = if (state.approval != null) CompanionColor.Warn else CompanionColor.Signal,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Text(when {
                    state.voicePauseReason.isNotBlank() -> state.voicePauseReason
                    state.voiceStreamState == VoiceStreamState.LISTENING && !state.voiceMicrophoneReady -> "Wait until Listening appears before speaking."
                    state.voiceStreamState == VoiceStreamState.TRANSCRIBING -> if (state.speechBackend == app.hermes.companion.model.SpeechBackend.SYSTEM)
                        "Turning your speech into text. The microphone is paused." else "Turning your speech into text. Listening continues."
                    state.approval != null -> "Open the conversation to review the exact pending request."
                    state.voiceMicrophoneMuted -> "Listening resumes only when you unmute."
                    state.voiceStreamState == VoiceStreamState.SPEAKING -> if (state.speechBackend == app.hermes.companion.model.SpeechBackend.SYSTEM) "Android compatibility speech pauses listening during playback." else "Speak to interrupt. Listening continues during playback."
                    state.voiceStreamState == VoiceStreamState.THINKING -> "Hermes is preparing its reply. You can keep speaking."
                    else -> "Speak when you are ready."
                }, style = CompanionType.Body, color = CompanionColor.TextDim)
                Text("Complete conversational replies are spoken. Code and formatting remain in chat.",
                    style = CompanionType.Caption)
                if (state.voiceTranscript.isNotEmpty()) GroupedSection("Spoken conversation", padded = true) {
                    state.voiceTranscript.takeLast(6).forEach { caption ->
                        Text("${if (caption.role == "user") "You" else "Hermes"}: ${caption.text}", style = CompanionType.Body)
                    }
                }
                ActionButton(if (state.approval != null) "Review pending approval" else "Open this conversation",
                    onConversation, modifier = Modifier.fillMaxWidth().testTag("voice.controls.conversation"))
            }
            // These controls are outside the scrolling details so End voice remains reachable.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(if (state.voiceMicrophoneMuted) "Resume mic" else "Mute mic", onMute,
                    modifier = Modifier.testTag("voice.controls.mute"))
                ActionButton("Stop speaking", onInterrupt, modifier = Modifier.testTag("voice.controls.interrupt"))
                ActionButton(if (state.voiceSpeaker) "Speaker" else "Auto route", onSpeaker,
                    modifier = Modifier.testTag("voice.stage.speaker")
                        .semantics { contentDescription = "Audio output: ${if (state.voiceSpeaker) "Speaker" else "Auto route"}. Change audio output" })
            }
            ActionButton("End voice", onStop, kind = ActionKind.DANGER,
                modifier = Modifier.fillMaxWidth().testTag("voice.controls.stop"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceStartSheet(state: CompanionState, onDismiss: () -> Unit, onStart: () -> Unit, onChoose: () -> Unit) {
    val unavailable = when {
        state.loading -> "Wait for the host connection to finish."
        state.activeProfileId == null -> "Select a profile before starting voice."
        state.inRoom -> "Voice is available in individual chats. Choose a chat to continue."
        state.streaming || state.submitting || state.creatingThread -> "Wait for the current reply before starting voice."
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CompanionColor.VoidElevated, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("voice.start"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeading("Talk to Hermes", onDismiss)
            VoiceIdentity(state)
            ProtocolStatus("Conversation", state.openSession?.title?.ifBlank { "Untitled conversation" } ?: "New conversation")
            if (unavailable != null) StatusStrip(unavailable, tone = CompanionColor.Warn)
            else Text("Choose the conversation before listening starts.", style = CompanionType.Body)
            ActionButton(if (state.openSession != null) "Start voice here" else "Start a new voice conversation", onStart,
                enabled = unavailable == null, kind = ActionKind.PRIMARY,
                modifier = Modifier.fillMaxWidth().testTag("voice.start.confirm"))
            ActionButton("Choose another conversation", onChoose,
                modifier = Modifier.fillMaxWidth().testTag("voice.start.choose"))
            Text("The microphone starts only after you confirm. Voice ends when the app leaves the foreground.",
                style = CompanionType.Caption)
        }
    }
}
