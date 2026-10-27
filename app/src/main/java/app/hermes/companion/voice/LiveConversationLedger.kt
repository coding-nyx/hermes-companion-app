package app.hermes.companion.voice

import app.hermes.companion.model.NativeLiveEvent
import app.hermes.companion.model.VoiceCaption

/** A caption is context, never an instruction to execute. Only a delegation admits work. */
internal class LiveConversationLedger {
    val captions = mutableListOf<VoiceCaption>()
    private val events = mutableSetOf<String>()
    private val delegated = mutableSetOf<String>()
    fun caption(event: NativeLiveEvent.Caption): Boolean {
        if (event.eventId.isNotEmpty() && !events.add(event.eventId)) return false
        require(event.delta.length <= 65536 && captions.size < 10000) { "Live transcript is too large" }
        captions += VoiceCaption(event.role, event.delta, event.startMs, event.endMs, event.eventId)
        return true
    }
    fun request(event: NativeLiveEvent.Delegation): Pair<String, String>? {
        if (event.id in delegated) return null
        // Transport delivery order can differ from spoken order. Stable sorting
        // retains delta order for fragments with the same timestamp.
        val context = captions.filter { it.endMs <= event.offsetMs }.sortedBy { it.startMs }
        val turns = mutableListOf<Pair<String, String>>()
        for (fragment in context) {
            if (turns.lastOrNull()?.first == fragment.role) {
                val last = turns.removeAt(turns.lastIndex)
                turns += last.first to (last.second + fragment.text)
            } else turns += fragment.role to fragment.text
        }
        val prompt = turns.lastOrNull { it.first == "user" }?.second?.trim().orEmpty()
        if (prompt.isBlank()) return null
        delegated += event.id
        return prompt to turns.takeLast(20).joinToString("\n") { (role, text) -> "$role: $text" }.takeLast(6000)
    }
}
