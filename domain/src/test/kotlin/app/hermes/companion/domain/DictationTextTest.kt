package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTextTest {
    @Test fun finalTextReplacesThePartialSpanWithoutRepeatingIt() {
        val original = "Already typed:"
        assertEquals("Already typed: hello", DictationText.combine(original, "hello"))
        assertEquals("Already typed: hello world", DictationText.combine(original, "hello world"))
    }
    @Test fun quietRecognitionDoesNotEraseTheOriginalDraft() {
        assertEquals("already typed", DictationText.combine("already typed", " "))
    }
    @Test fun typedLineBreaksArePreserved() {
        assertEquals("notes\nhello", DictationText.combine("notes\n", "hello"))
    }
}
