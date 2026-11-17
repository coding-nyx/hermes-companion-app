package app.hermes.hands.engine

import kotlin.random.Random

/** A cancellation is latched until the app explicitly begins a newly approved session. */
internal class EngineSessions {
    private var epoch = Random.nextLong(1, Long.MAX_VALUE / 2)
    private var active = false

    @Synchronized fun begin(cancelPrevious: () -> Unit = {}): Long {
        cancelPrevious()
        epoch++
        active = true
        return epoch
    }

    @Synchronized fun cancel(stopActive: () -> Unit = {}) {
        epoch++
        active = false
        stopActive()
    }

    @Synchronized fun requireActive(): Long {
        check(active) { "disarmed" }
        return epoch
    }

    @Synchronized fun require(expected: Long) {
        check(active && expected > 0 && expected == epoch) { "disarmed" }
    }

    /** Process creation and cancellation share the same lock, closing the check/start race. */
    @Synchronized fun <T> withPermit(expected: Long, create: () -> T): T {
        require(expected)
        return create()
    }
}
