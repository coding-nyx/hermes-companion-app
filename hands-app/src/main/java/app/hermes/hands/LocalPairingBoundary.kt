package app.hermes.hands

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Opens local pairing only after commands accepted before the boundary have finished.
 * Call [open] from a worker. Content-producing commands must also check [epoch] and
 * [isProtected] before publishing their result, including passive commands outside [commands].
 * The stop callback revokes/cancels control without calling [cancel] itself. A show callback
 * publishes its active state synchronously, before returning to display the local UI.
 */
class LocalPairingBoundary(
    private val commands: ReentrantLock,
    private val stop: () -> Unit,
    private val show: () -> Unit,
    private val ready: () -> Unit,
    private val active: () -> Boolean,
    private val waitMillis: Long = 5_000,
) {
    init { require(waitMillis >= 0) }

    private val opening = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val decision = Any()
    val epoch: Long get() = generation.get()

    /** A failed active-state lookup must not accidentally expose phone content. */
    val isProtected: Boolean get() = opening.get() || runCatching(active).getOrDefault(true)
    val `protected`: Boolean get() = isProtected

    /** Cleanup is limited to state changes; Android/command-lock work runs after this returns. */
    fun cancel(after: () -> Unit = {}) { cancelIf({ true }, after) }

    /** An old Activity may clear only its own authorization, atomically with the final show. */
    fun cancelIf(predicate: () -> Boolean, after: () -> Unit = {}): Boolean = synchronized(decision) {
        if (!predicate()) return@synchronized false
        generation.incrementAndGet()
        after()
        true
    }

    fun open(show: () -> Unit = this.show, ready: () -> Unit = this.ready) {
        val observedEpoch = generation.get()
        check(opening.compareAndSet(false, true)) { "pairing_opening" }
        var acquired = false
        try {
            val ticket = synchronized(decision) {
                // A Stop between claiming opening and obtaining this decision lock must
                // cancel this opening, rather than become an older epoch we overwrite.
                check(generation.get() == observedEpoch) { "pairing_cancelled" }
                generation.incrementAndGet()
            }
            // Set both guards before cancellation, so a fast old result cannot escape while
            // stop() revokes the lease and requests cancellation of Android input.
            stop()
            check(generation.get() == ticket) { "pairing_cancelled" }
            // A reentrant acquisition would not drain the caller's existing command.
            check(!commands.isHeldByCurrentThread) { "pairing_reentrant_open" }
            acquired = try {
                commands.tryLock(waitMillis, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("pairing_cancelled", interrupted)
            }
            check(generation.get() == ticket) { "pairing_cancelled" }
            check(acquired) { "pairing_drain_timeout" }
            synchronized(decision) {
                check(generation.get() == ticket) { "pairing_cancelled" }
                // Network availability, keyguard and privacy may have changed during draining.
                ready()
                check(generation.get() == ticket) { "pairing_cancelled" }
                show()
            }
        } finally {
            try { if (acquired) commands.unlock() }
            finally { opening.set(false) }
        }
    }
}
