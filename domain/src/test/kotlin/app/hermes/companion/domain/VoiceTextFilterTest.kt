package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTextFilterTest {

    @Test
    fun cleanForSpeechStripsMarkdownElements() {
        val input = "Hello **world**, check this `git status` and [click here](https://hermes.ai) for details."
        val cleaned = VoiceTextFilter.cleanForSpeech(input)
        assertEquals("Hello world, check this  and click here for details.", cleaned)
    }

    @Test
    fun cleanForSpeechReplacesCodeBlocks() {
        val input = "Here is the plan:\n```kotlin\nval x = 1\n```\nAll done."
        val cleaned = VoiceTextFilter.cleanForSpeech(input)
        assertEquals("Here is the plan:\n code snippet \nAll done.", cleaned)
    }

    @Test
    fun extractNextClauseSplitsOnQuestionMark() {
        val text = "Are you ready? Let's proceed."
        val result = VoiceTextFilter.extractNextClause(text)
        assertEquals("Are you ready?", result?.first)
        assertEquals(" Let's proceed.", result?.second)
    }

    @Test
    fun extractNextClauseSplitsOnPeriodFollowedByWhitespace() {
        val text = "Task completed. Starting next job."
        val result = VoiceTextFilter.extractNextClause(text)
        assertEquals("Task completed.", result?.first)
        assertEquals(" Starting next job.", result?.second)
    }

    @Test
    fun extractNextClauseDoesNotSplitOnDecimalNumbers() {
        val text = "The version is 3.14159 and growing"
        val result = VoiceTextFilter.extractNextClause(text)
        assertNull(result)
    }

    @Test
    fun extractNextClauseSplitsOnExclamation() {
        val text = "Alert triggered! Please check console."
        val result = VoiceTextFilter.extractNextClause(text)
        assertEquals("Alert triggered!", result?.first)
        assertEquals(" Please check console.", result?.second)
    }

    @Test
    fun extractNextClauseSplitsOnNewline() {
        val text = "First line\nSecond line"
        val result = VoiceTextFilter.extractNextClause(text)
        assertEquals("First line", result?.first)
        assertEquals("Second line", result?.second)
    }
}
