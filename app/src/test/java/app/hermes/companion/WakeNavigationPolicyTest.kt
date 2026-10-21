package app.hermes.companion

import org.junit.Assert.*
import org.junit.Test
import app.hermes.companion.model.SessionRef

class WakeNavigationPolicyTest {
    private val active = CompanionState(
        origin = "https://fixture.invalid/hermes",
        activeProfileId = "developer",
        openSessionId = "session-one",
        voiceStreamState = VoiceStreamState.MUTED,
    )
    private val current = DeepLinkRequest("developer", "session-one", "https://fixture.invalid/hermes")

    @Test fun sameConversationReturnsToVoiceWithoutStoppingIt() {
        assertTrue(WakeNavigationPolicy.isCurrentConversation(active.copy(tab = MainTab.DEVICE, chatMinimized = true), current))
        assertFalse(WakeNavigationPolicy.requiresVoiceConfirmation(active, current))
    }

    @Test fun eachConversationOwnerDimensionRequiresVoiceConfirmation() {
        listOf(
            current.copy(origin = "https://other.invalid/hermes"),
            current.copy(origin = "https://fixture.invalid/other-proxy"),
            current.copy(profileId = "research"),
            current.copy(sessionId = "session-two"),
            current.copy(roomId = "session-one"),
        ).forEach { request ->
            assertTrue("Must confirm $request", WakeNavigationPolicy.requiresVoiceConfirmation(active, request))
        }
    }

    @Test fun legacyBlankOriginResolvesOnlyToTheCurrentHost() {
        assertFalse(WakeNavigationPolicy.requiresVoiceConfirmation(active, current.copy(origin = "")))
        assertFalse(WakeNavigationPolicy.isCurrentConversation(active.copy(origin = null), current.copy(origin = "")))
    }

    @Test fun normalizedHostIdentityPreservesProxyPrefixesAndRejectsEmbeddedCredentials() {
        assertTrue(WakeNavigationPolicy.sameHost("https://FIXTURE.invalid:443/hermes/", current.origin))
        assertFalse(WakeNavigationPolicy.sameHost("https://fixture.invalid/another", current.origin))
        assertFalse(WakeNavigationPolicy.sameHost("https://user:password@fixture.invalid/hermes", current.origin))
        assertFalse(WakeNavigationPolicy.sameHost("https://fixture.invalid/hermes?token=secret", current.origin))
        assertFalse(WakeNavigationPolicy.sameHost("https://", "http://https"))
        assertFalse(WakeNavigationPolicy.sameHost("http://", "http://http"))
    }

    @Test fun idleVoiceDoesNotAddAnUnnecessaryConfirmation() {
        assertFalse(WakeNavigationPolicy.requiresVoiceConfirmation(
            active.copy(voiceStreamState = VoiceStreamState.IDLE), current.copy(sessionId = "other")))
    }

    @Test fun roomIdentityIsHostScopedAndNotConfusedWithAChat() {
        val roomState = active.copy(openRoomId = "room-one", openSessionId = null)
        val room = current.copy(roomId = "room-one")
        assertTrue(WakeNavigationPolicy.isCurrentConversation(roomState, room.copy(profileId = "other")))
        assertFalse(WakeNavigationPolicy.isCurrentConversation(active, current.copy(roomId = "session-one")))
        assertFalse(WakeNavigationPolicy.isCurrentConversation(roomState, room.copy(origin = "https://other.invalid")))
    }

    @Test fun pendingTapCannotBeConsumedByAnUnrelatedHost() {
        val pending = PendingWakeNavigation()
        pending.enqueue(current)
        assertNull(pending.takeForHost("https://other.invalid"))
        assertEquals(current, pending.takeForHost(current.origin))
        assertNull(pending.takeForHost(current.origin))
    }

    @Test fun choosingAnotherHostDiscardsTheOldPendingTap() {
        val pending = PendingWakeNavigation()
        pending.enqueue(current)
        pending.connectingTo("https://other.invalid")
        assertNull(pending.takeForHost(current.origin))
    }

    @Test fun retryingSameHostPreservesTapButNewerTapReplacesIt() {
        val pending = PendingWakeNavigation()
        pending.enqueue(current)
        pending.connectingTo("${current.origin}/")
        val newer = current.copy(sessionId = "newer")
        pending.enqueue(newer)
        assertEquals(newer, pending.takeForHost(current.origin))
    }

    @Test fun explicitNavigationOrDisconnectCancelsThePendingTap() {
        val pending = PendingWakeNavigation()
        pending.enqueue(current)
        pending.clear()
        assertNull(pending.takeForHost(current.origin))
    }

    @Test fun connectionCatchUpKeepsNewerTabModelDraftAndOpenConversation() {
        val selected = active.copy(tab = MainTab.DEVICE, chatMinimized = true, draft = "keep",
            modelOverride = "new-model", sessionsLoading = true)
        val rows = listOf(SessionRef("other", "developer", "Other", 0))
        assertEquals(selected.copy(sessionsLoading = false, sessions = rows),
            selected.finishConnectionRoster(current.origin, current.profileId, rows))
    }

    @Test fun connectionCatchUpCannotOverwriteANewerProfileOrHost() {
        val rows = listOf(SessionRef("old", "developer", "Old", 0))
        listOf(active.copy(activeProfileId = "research"), active.copy(origin = "https://other.invalid")).forEach {
            assertSame(it, it.finishConnectionRoster(current.origin, current.profileId, rows))
        }
    }
}
