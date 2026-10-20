package app.hermes.companion.domain

import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatEvent
import org.junit.Assert.*
import org.junit.Test

class NativeNotificationPolicyTest {
    private fun NativeNotificationPolicy.event(
        event: ChatEvent,
        visible: Boolean = false,
        origin: String = "https://host/hermes",
        profile: String = "coder",
        session: String = "stored-1",
        submission: String? = null,
    ) = accept(origin, profile, session, event, visible, "Work chat", submission)

    @Test fun completionPairCreatesOneRoutableAlertWithoutTranscript() {
        val policy = NativeNotificationPolicy()
        val notice = policy.event(ChatEvent.Final("private response"))!!
        assertEquals(WakePing("run.completed", "stored-1", "coder", "https://host/hermes", title = "Work chat"), notice.ping)
        assertFalse(notice.dismiss)
        assertNull(policy.event(ChatEvent.Completed))
        assertNull(policy.event(ChatEvent.Final("private response")))
    }

    @Test fun payloadlessCompletionThenFinalIsAlsoOneAlert() {
        val policy = NativeNotificationPolicy()
        assertNotNull(policy.event(ChatEvent.Completed))
        assertNull(policy.event(ChatEvent.Final("authoritative answer")))
        assertNull(policy.event(ChatEvent.Completed))
    }

    @Test fun newActivityAndLocalSubmissionAllowAnotherIdenticalAnswer() {
        val policy = NativeNotificationPolicy()
        assertNotNull(policy.event(ChatEvent.Final("same answer")))
        assertNull(policy.event(ChatEvent.AssistantDelta("same ")))
        assertNotNull(policy.event(ChatEvent.Final("same answer")))
        assertNotNull(policy.event(ChatEvent.Final("same answer"), submission = "new-user-message"))
        assertNull(policy.event(ChatEvent.Final("same answer"), submission = "new-user-message"))
    }

    @Test fun differentFinalOnlyReplyIsNotLost() {
        val policy = NativeNotificationPolicy()
        assertNotNull(policy.event(ChatEvent.Final("one")))
        assertNotNull(policy.event(ChatEvent.Final("two")))
        assertNull(policy.event(ChatEvent.Completed))
    }

    @Test fun foregroundConversationSuppressesAlertsAndTheirReplays() {
        val policy = NativeNotificationPolicy()
        assertNull(policy.event(ChatEvent.Final("shown on screen"), visible = true))
        assertNull(policy.event(ChatEvent.Completed))
        assertNull(policy.event(ChatEvent.Final("shown on screen")))
    }

    @Test fun approvalsAreDedupedByRequestAndCarryNoCommandOrSecret() {
        val policy = NativeNotificationPolicy()
        val request = ChatEvent.Approval(ApprovalPrompt("request-1", "secret", "private secret request"))
        val notice = policy.event(request)!!
        assertEquals("approval.request", notice.ping.type)
        assertFalse(notice.ping.toString().contains("private secret request"))
        assertNull(policy.event(request))
        assertNotNull(policy.event(request.copy(prompt = request.prompt.copy(requestId = "request-2"))))
    }

    @Test fun visibleApprovalIsNotReannouncedWhenScreenIsLeft() {
        val policy = NativeNotificationPolicy()
        val request = ChatEvent.Approval(ApprovalPrompt("r", "approval", "command"))
        assertNull(policy.event(request, visible = true))
        assertNull(policy.event(request))
    }

    @Test fun clarificationUsesQuestionChannelAndOnlyMatchingExpiryDismissesIt() {
        val policy = NativeNotificationPolicy()
        val question = ChatEvent.Approval(ApprovalPrompt("question", "clarify", "choose"))
        assertEquals("clarify", policy.event(question)!!.ping.type)
        assertNull(policy.event(ChatEvent.PromptExpired("other")))
        assertTrue(policy.event(ChatEvent.PromptExpired("question"))!!.dismiss)
        assertNull(policy.event(ChatEvent.PromptExpired("question")))
    }

    @Test fun oldExpiryCannotClearANewerCompletionNotice() {
        val policy = NativeNotificationPolicy()
        policy.event(ChatEvent.Approval(ApprovalPrompt("old", command = "command")))
        assertNotNull(policy.event(ChatEvent.Final("done")))
        assertNull(policy.event(ChatEvent.PromptExpired("old")))
    }

    @Test fun ownersIncludeHostPrefixProfileAndSession() {
        val policy = NativeNotificationPolicy()
        val event = ChatEvent.Approval(ApprovalPrompt("same-request", command = "command"))
        assertNotNull(policy.event(event))
        assertNotNull(policy.event(event, origin = "https://host/other"))
        assertNotNull(policy.event(event, profile = "ops"))
        assertNotNull(policy.event(event, session = "stored-2"))
        assertNull(policy.event(event))
    }

    @Test fun errorsCanUpgradeCompletionButInterruptionsStayQuiet() {
        val policy = NativeNotificationPolicy()
        policy.event(ChatEvent.Completed)
        assertEquals("error", policy.event(ChatEvent.Final(status = "error", error = "private error"))!!.ping.type)
        assertNull(policy.event(ChatEvent.Final(status = "error", error = "private error")))
        policy.event(ChatEvent.AssistantDelta("new turn"))
        assertNull(policy.event(ChatEvent.Final(status = "interrupted")))
        assertNull(policy.event(ChatEvent.Completed))
    }

    @Test fun unknownOrDraftOwnersCannotBecomeNotificationLinks() {
        val policy = NativeNotificationPolicy()
        assertNull(policy.event(ChatEvent.Completed, origin = ""))
        assertNull(policy.event(ChatEvent.Completed, profile = ""))
        assertNull(policy.event(ChatEvent.Completed, session = "local:draft"))
    }
}
