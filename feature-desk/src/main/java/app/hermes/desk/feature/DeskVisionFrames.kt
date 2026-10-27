package app.hermes.desk.feature

/** Main-thread-owned memory. Spoken "this" keeps the frame from utterance end during STT. */
class DeskVisionFrames(private val now: () -> Long) {
    private data class Frame(val binding: DeskBinding, val captured: Long, val bytes: ByteArray)
    private var latest: Frame? = null
    private var utterance: Frame? = null
    fun update(binding: DeskBinding, jpeg: ByteArray) {
        latest?.bytes?.fill(0)
        latest = Frame(binding, now(), jpeg)
    }
    fun freeze(binding: DeskBinding) {
        utterance?.bytes?.fill(0)
        utterance = latest?.takeIf { it.binding == binding && now() - it.captured in 0..5_000 }
            ?.let { it.copy(bytes = it.bytes.copyOf()) }
    }
    fun take(binding: DeskBinding, spoken: Boolean = false): ByteArray? {
        if (spoken) {
            val snapshot = utterance
            utterance = null
            if (snapshot != null) {
                if (snapshot.binding == binding && now() - snapshot.captured in 0..30_000) return snapshot.bytes
                snapshot.bytes.fill(0)
                return null
            }
            return null
        }
        return latest?.takeIf { it.binding == binding && now() - it.captured in 0..5_000 }?.bytes?.copyOf()
    }
    fun clearUtterance() { utterance?.bytes?.fill(0); utterance = null }
    fun clear() { latest?.bytes?.fill(0); latest = null; clearUtterance() }
}
