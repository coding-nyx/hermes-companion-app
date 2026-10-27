package app.hermes.companion.domain

import org.junit.Assert.*
import org.junit.Test

class NotificationCapturePolicyTest {
    @Test fun explicitSelectionFailsClosed() {
        assertFalse(NotificationCapturePolicy.selected("mail", emptySet(), "msg", true))
        assertTrue(NotificationCapturePolicy.selected("mail", setOf("mail"), "msg", true))
        assertFalse(NotificationCapturePolicy.selected("other", setOf("mail"), "msg", true))
    }
    @Test fun callChoiceDoesNotFilterOrdinaryMessages() {
        assertFalse(NotificationCapturePolicy.selected("phone", setOf("phone"), "call", false))
        assertFalse(NotificationCapturePolicy.selected("phone", setOf("phone"), "missed_call", false))
        assertTrue(NotificationCapturePolicy.selected("phone", setOf("phone"), "msg", false))
    }
    @Test fun metadataAndUnknownModeNeverIncludeText() {
        assertEquals("" to "", NotificationCapturePolicy.content("Name", "body", "metadata"))
        assertEquals("" to "", NotificationCapturePolicy.content("Name", "body", "future"))
    }
    @Test fun titleModeOmitsBodyAndFullIsBounded() {
        assertEquals("Name" to "", NotificationCapturePolicy.content("Name", "body", "title"))
        val content = NotificationCapturePolicy.content("x".repeat(300), "y".repeat(600), "full")
        assertEquals(240, content.first.length); assertEquals(480, content.second.length)
    }
    @Test fun codeAndSecretCandidatesAreMetadataOnly() {
        for (text in listOf("Your OTP is 123456", "Verification requested", "Password reset", "Code: 7865")) {
            assertEquals(text, "" to "", NotificationCapturePolicy.content("Mail", text, "full"))
            assertEquals(text, "" to "", NotificationCapturePolicy.content("Mail", text, "title"))
        }
    }
    @Test fun queueLifetimeRejectsExpiryAndClockReversal() {
        assertTrue(NotificationCapturePolicy.fresh(1000, 121000))
        assertFalse(NotificationCapturePolicy.fresh(1000, 121001))
        assertFalse(NotificationCapturePolicy.fresh(1000, 999))
    }
}
