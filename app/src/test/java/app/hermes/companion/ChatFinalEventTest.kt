package app.hermes.companion

import app.hermes.companion.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatFinalEventTest {
    @Test fun aFinalReplyWithoutDeltasIsDisplayed() {
        val result = applyEvent(CompanionState(streaming = true), ChatEvent.Final("answer"), "a")
        assertEquals("answer", result.messages.single().text)
        assertFalse(result.streaming)
    }

    @Test fun theAuthoritativeFinalReplacesThePartialResponse() {
        val partial = applyEvent(CompanionState(), ChatEvent.AssistantDelta("wrong partial"), "a")
        val result = applyEvent(partial, ChatEvent.Final("correct final"), "a")
        assertEquals(listOf("correct final"), result.messages.map { it.text })
    }

    @Test fun aGenerationFailureIsNotASendFailure() {
        val sent = ChatMessage("u", MessageRole.USER, "question", delivery = MessageDelivery.SENT)
        val result = applyEvent(CompanionState(messages = listOf(sent)), ChatEvent.Final(status = "error", error = "provider unavailable"), "a")
        assertEquals(MessageDelivery.SENT, result.messages.first().delivery)
        assertEquals("provider unavailable", result.error)
    }

    @Test fun reasonedTextIsKeptSeparateFromTheFinalAnswer() {
        val thinking = applyEvent(CompanionState(), ChatEvent.ReasoningDelta("thinking"), "a")
        val result = applyEvent(thinking, ChatEvent.Final("answer"), "a")
        assertTrue(result.messages.first().reasoning)
        assertFalse(result.messages.last().reasoning)
        assertEquals("answer", result.messages.last().text)
    }

    @Test fun aReusedResponseIsNotAppendedAgain() {
        val partial = applyEvent(CompanionState(), ChatEvent.AssistantDelta("already delivered"), "a")
        val result = applyEvent(partial, ChatEvent.Final("already delivered", reused = true), "a")
        assertEquals(1, result.messages.size)
    }

    @Test fun conversationIdentityIncludesHostAndProfile() {
        val state = CompanionState(origin = "lab", activeProfileId = "coder", openSessionId = "s")
        assertTrue(state.shows("lab", "coder", "s"))
        assertFalse(state.shows("raj", "coder", "s"))
        assertFalse(state.shows("lab", "ops", "s"))
    }

    @Test fun streamedAnswersNeverAppendToTheReasoningRow() {
        val thinking = applyEvent(CompanionState(), ChatEvent.ReasoningDelta("reason"), "a")
        val answer = applyEvent(thinking, ChatEvent.AssistantDelta("answer"), "a")
        assertEquals("reason", answer.messages.first().text)
        assertEquals("answer", answer.messages.last().text)
        assertFalse(answer.messages.last().reasoning)
    }

    @Test fun anInterimResponseDoesNotFinishTheAgentTurn() {
        val state = applyEvent(CompanionState(), ChatEvent.Interim("working on it"), "a")
        assertTrue(state.streaming)
        val next = applyEvent(state, ChatEvent.Final("working on it", reused = true), "a")
        assertEquals(1, next.messages.size)
        assertFalse(next.streaming)
    }
}
