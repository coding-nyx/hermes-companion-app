package app.hermes.companion.domain

import app.hermes.companion.model.ChatEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Batches assistant text deltas so the UI updates at a bounded rate instead of once per token.
 *
 * Rules:
 * - Consecutive [ChatEvent.AssistantDelta]s are merged into one delta per [windowMs] frame.
 * - Any other event flushes the pending text first, then passes through — order is preserved.
 * - Stream end flushes whatever is pending.
 *
 * Pure text merging lives in [StreamCoalescer.merge] so it can be unit-tested without a clock.
 */
object StreamCoalescer {
    const val DEFAULT_WINDOW_MS = 40L

    /** Merge a run of events: adjacent deltas become one delta, other events are untouched. */
    fun merge(events: List<ChatEvent>): List<ChatEvent> {
        if (events.size < 2) return events
        val out = ArrayList<ChatEvent>(events.size)
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                out += ChatEvent.AssistantDelta(pending.toString())
                pending.setLength(0)
            }
        }
        for (ev in events) {
            if (ev is ChatEvent.AssistantDelta) pending.append(ev.text) else {
                flush()
                out += ev
            }
        }
        flush()
        return out
    }
}

/**
 * Time-window delta coalescing. The upstream is consumed eagerly into an unbounded channel so a
 * slow collector never back-pressures the socket reader; text is merged per [windowMs] frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<ChatEvent>.coalesceDeltas(windowMs: Long = StreamCoalescer.DEFAULT_WINDOW_MS): Flow<ChatEvent> = flow {
    coroutineScope {
        val inbox = produce(capacity = Channel.UNLIMITED) { collect { send(it) } }
        val pending = StringBuilder()
        suspend fun flush() {
            if (pending.isNotEmpty()) {
                emit(ChatEvent.AssistantDelta(pending.toString()))
                pending.setLength(0)
            }
        }
        var open = true
        while (open) {
            val first = inbox.receiveCatching().getOrNull()
            if (first == null) break
            if (first !is ChatEvent.AssistantDelta) {
                emit(first)
                continue
            }
            pending.append(first.text)
            // Drain until the frame closes or a non-delta arrives.
            var frameOpen = true
            while (frameOpen) {
                val next: ChatEvent? = select {
                    inbox.onReceiveCatching { r ->
                        if (r.isClosed) { open = false; frameOpen = false; null } else r.getOrThrow()
                    }
                    onTimeout(windowMs) { frameOpen = false; null }
                }
                when (next) {
                    null -> Unit
                    is ChatEvent.AssistantDelta -> pending.append(next.text)
                    else -> {
                        flush()
                        emit(next)
                        frameOpen = false
                    }
                }
            }
            flush()
        }
        flush()
    }
}
