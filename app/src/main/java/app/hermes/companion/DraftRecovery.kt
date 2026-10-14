package app.hermes.companion

import app.hermes.companion.data.local.ComposerDraft
import app.hermes.companion.data.local.ComposerDraftStore
import app.hermes.companion.data.local.DraftOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RecoveredDraft(val contents: ComposerDraft, val error: String? = null) {
    val text get() = contents.text
    val attachments get() = contents.attachments
}

/** Checkpoint before navigation as StateFlow may conflate consecutive UI transitions. */
internal class DraftRecovery(
    private val store: ComposerDraftStore,
    private val state: MutableStateFlow<CompanionState>,
    scope: CoroutineScope,
) {
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { state.collect { checkpoint(it) } } }

    fun checkpoint(snapshot: CompanionState = state.value) {
        val owner = owner(snapshot) ?: return
        if (owner(state.value) != owner || contents(state.value) != contents(snapshot)) return
        runCatching { store.save(owner, contents(snapshot)) }.onFailure {
            state.update { if (owner(it) != owner) it else it.copy(error = "Could not save this draft. Your current text is still in the composer.") }
        }
    }

    fun restore(origin: String, profile: String, session: String): RecoveredDraft =
        runCatching { RecoveredDraft(store.load(DraftOwner(origin, profile, session))) }.getOrElse {
            RecoveredDraft(ComposerDraft(), "Could not recover the saved draft.")
        }

    fun warning(draft: RecoveredDraft?): String? = draft?.error ?: if (draft?.attachments.orEmpty().any {
        !java.io.File(it.localUri).isFile
    }) "A saved attachment is unavailable. Remove it and pick the file again." else null

    fun promote(snapshot: CompanionState, session: String) {
        val from = owner(snapshot) ?: return
        store.promote(from, from.copy(session = session), contents(snapshot))
    }

    fun consume(snapshot: CompanionState, session: String) {
        val from = owner(snapshot) ?: return
        store.consume(from.copy(session = session), contents(snapshot))
    }

    private fun owner(st: CompanionState): DraftOwner? {
        if (st.inRoom) return null
        return DraftOwner(st.origin ?: return null, st.activeProfileId ?: return null, st.openSessionId ?: return null)
    }
    private fun contents(st: CompanionState) = ComposerDraft(st.draft, st.pendingAttachments)
}
