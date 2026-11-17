package app.hermes.companion.device

import android.content.Context
import app.hermes.companion.domain.DeviceLanePolicy

/** An independent, bounded owner of the visible screen-hold window. Never wakes/unlocks a phone. */
class HandsKeepAwake(context: Context, tag: String = "hands") {
    private val owner = KeepAwakeWindow.Owner()
    private val window = KeepAwakeWindow.forContext(context)
    private val label = tag
    val isHeld: Boolean get() = window.isHeld(owner)

    /** Explicit fresh consent/standalone enable, never a heartbeat or status read. */
    fun beginSession() = owner.beginSession()

    fun renew(timeoutMs: Long = DeviceLanePolicy.IDLE_DISARM_MS) {
        require(timeoutMs > 0 && timeoutMs <= DeviceLanePolicy.MAX_HOLD_SECONDS * 1_000)
        window.acquire(owner, label, timeoutMs)
    }

    fun release() = window.release(owner)
}
