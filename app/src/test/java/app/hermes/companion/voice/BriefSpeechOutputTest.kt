package app.hermes.companion.voice

import org.junit.Assert.*
import org.junit.Test

class BriefSpeechOutputTest {
    @Test fun aLongStreamSpeaksTwoSentencesAndOffersTheFullWrittenReplyOnce() {
        val sink=Output();val speech=BriefSpeechOutput(sink)
        val full="The build passed. Two checks remain. Here is a very long explanation with more details."
        full.chunked(3).forEach(speech::feedToken)
        speech.reconcileFinal(full);speech.finishFeed();speech.finishFeed()
        assertEquals("The build passed. Two checks remain. The rest is in the chat.",sink.text.toString())
        assertEquals(1,sink.finishes)
    }
    @Test fun aShortAnswerIsNotClippedOrRepeatedAndDecimalTokensStayIntact() {
        val sink=Output();val speech=BriefSpeechOutput(sink)
        listOf("It costs 3.","14 dollars."," That is all.").forEach(speech::feedToken)
        speech.finishFeed()
        assertEquals("It costs 3.14 dollars. That is all.",sink.text.toString())
    }
    @Test fun longPunctuationFreeRepliesAreWordBoundedAndCancelResetsTheNextTurn() {
        val sink=Output();val speech=BriefSpeechOutput(sink)
        speech.feedToken((1..100).joinToString(" ") { "word$it" });speech.finishFeed()
        assertTrue(sink.text.startsWith("word1 "));assertFalse(sink.text.contains("word46"))
        speech.stop();sink.text.clear();speech.feedToken("Next turn.");speech.finishFeed()
        assertEquals("Next turn.",sink.text.toString())
    }
    @Test fun finalOnlyAnswersAreSpokenAndBufferedProvidersReceiveTheBoundedFinal() {
        val sink=Output();val speech=BriefSpeechOutput(sink)
        speech.reconcileFinal("First. Second. Third.");speech.finishFeed()
        assertEquals("First. Second. The rest is in the chat.",sink.text.toString())
        assertEquals(sink.text.toString(),sink.finalText)
    }
    private class Output:SpeechOutput {
        val text=StringBuilder();var finishes=0;var finalText=""
        override fun feedToken(delta:String) {text.append(delta)}
        override fun reconcileFinal(text:String) {finalText=text}
        override fun finishFeed(){finishes++}
        override fun stop()=Unit
        override fun shutdown()=Unit
    }
}
