package app.hermes.hands.engine

/**
 * Own one platform observation connection across snapshot/ref RPCs. Its identity is part of
 * the observation: disconnecting the last accessibility client makes Android reallocate
 * ViewRoot window IDs. Revisions also keep a dequeued old idle callback from closing a
 * freshly used connection, or a connection transferred into phone-local private entry.
 */
internal class ObservationConnection<T : AutoCloseable>(private val open: () -> T) {
    data class Lease<T>(val value: T, val revision: Long)
    private var current: T? = null
    private var revision = 0L

    @Synchronized fun acquire(): Lease<T> {
        val value = current ?: open().also { current = it }
        return Lease(value, ++revision)
    }

    /** Transfer the exact current connection; the receiver becomes its sole close owner. */
    @Synchronized fun transfer(lease: Lease<T>): T {
        check(current === lease.value && revision == lease.revision) { "observation_changed" }
        current = null
        revision++
        return lease.value
    }

    /** An idle callback may close only the exact revision it scheduled. */
    @Synchronized fun close(expectedRevision: Long? = null): Boolean {
        if (expectedRevision != null && expectedRevision != revision) return false
        val value = current
        current = null
        revision++
        value?.close()
        return value != null
    }
}
