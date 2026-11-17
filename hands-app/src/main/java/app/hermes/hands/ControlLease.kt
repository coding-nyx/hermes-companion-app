package app.hermes.hands

import java.util.UUID

/** A service-scoped authorization and bounded remote controller leases. */
class ControlLease(private val now: () -> Long) {
    data class Lease(val owner: String, val id: String, val deadline: Long, val epoch: Long)
    private var enabled = false
    private val leases = linkedMapOf<String, Lease>()
    private var epoch = 0L

    @Synchronized fun approve() {
        epoch++
        leases.clear()
        enabled = true
    }

    @Synchronized fun revoke() { epoch++; enabled = false; leases.clear() }

    @Synchronized fun acquire(owner: String, seconds: Int): Lease {
        require(owner.isNotBlank() && seconds in 30..7200)
        expire()
        check(enabled) { "local_consent_required" }
        check(leases.isEmpty() || leases.values.all { it.owner == owner }) { "controller_busy" }
        check(leases.size < 32) { "holder_limit" }
        // Each ARM owns one holder; cleanup cannot release a newer task's holder.
        return Lease(owner, UUID.randomUUID().toString(), now() + seconds * 1000L, epoch)
            .also { leases[it.id] = it }
    }

    @Synchronized fun require(owner: String, id: String): Lease {
        expire()
        check(enabled) { "disarmed" }
        return checkNotNull(leases[id]?.takeIf { it.owner == owner }) { "lease_mismatch" }
    }

    @Synchronized fun renew(owner: String, id: String, seconds: Int): Lease {
        require(seconds in 30..7200)
        val previous = require(owner, id)
        return previous.copy(deadline = now() + seconds * 1000L).also { leases[id] = it }
    }

    @Synchronized fun release(owner: String, id: String): Boolean {
        require(owner, id)
        leases.remove(id)
        if (leases.isEmpty()) revoke()
        return leases.isEmpty()
    }

    @Synchronized fun currentEpoch() = epoch
    @Synchronized fun approved(): Boolean { expire(); return enabled }
    @Synchronized fun active(): Boolean { expire(); return leases.isNotEmpty() }
    @Synchronized fun holders(): List<Lease> { expire(); return leases.values.toList() }
    @Synchronized fun remainingSeconds(): Long { expire(); return ((leases.values.maxOfOrNull { it.deadline } ?: now()) - now()).coerceAtLeast(0) / 1000 }

    private fun expire() {
        val hadHolders = leases.isNotEmpty()
        leases.entries.removeAll { it.value.deadline <= now() }
        if (hadHolders && leases.isEmpty()) revoke()
    }
}
