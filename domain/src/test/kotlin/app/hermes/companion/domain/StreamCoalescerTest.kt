package app.hermes.companion.domain

import app.hermes.companion.model.ChatEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCoalescerTest {
    private fun d(t: String) = ChatEvent.AssistantDelta(t)

    @Test
    fun mergeJoinsAdjacentDeltasAndKeepsOrder() {
        val merged = StreamCoalescer.merge(
            listOf(d("he"), d("llo"), ChatEvent.ToolStarted("terminal", "ls"), d(" wor"), d("ld"), ChatEvent.Completed),
        )
        assertEquals(
            listOf(d("hello"), ChatEvent.ToolStarted("terminal", "ls"), d(" world"), ChatEvent.Completed),
            merged,
        )
    }

    @Test
    fun mergeLeavesSingletonsAlone() {
        assertEquals(listOf(d("x")), StreamCoalescer.merge(listOf(d("x"))))
        assertEquals(emptyList<ChatEvent>(), StreamCoalescer.merge(emptyList()))
    }

    @Test
    fun coalesceNeverLosesTextAndPreservesEventOrder() = runBlocking {
        val tokens = (1..300).map { "t$it " }
        val src = flow {
            tokens.take(150).forEach { emit(d(it)) }
            emit(ChatEvent.ToolStarted("terminal", "echo"))
            tokens.drop(150).forEach { emit(d(it)) }
            emit(ChatEvent.Completed)
        }
        val out = src.coalesceDeltas(windowMs = 20).toList()
        // Far fewer emissions than tokens, but the concatenated text is identical.
        assertTrue("expected coalescing, got ${out.size}", out.size < tokens.size)
        val text = out.filterIsInstance<ChatEvent.AssistantDelta>().joinToString("") { it.text }
        assertEquals(tokens.joinToString(""), text)
        val toolIdx = out.indexOfFirst { it is ChatEvent.ToolStarted }
        val before = out.take(toolIdx).filterIsInstance<ChatEvent.AssistantDelta>().joinToString("") { it.text }
        assertEquals(tokens.take(150).joinToString(""), before)
        assertEquals(ChatEvent.Completed, out.last())
    }

    @Test
    fun coalesceFlushesOnQuietWindow() = runBlocking {
        val src = flow {
            emit(d("a"))
            emit(d("b"))
            delay(120)
            emit(d("c"))
        }
        val out = src.coalesceDeltas(windowMs = 30).toList()
        assertEquals(listOf(d("ab"), d("c")), out)
    }
}
