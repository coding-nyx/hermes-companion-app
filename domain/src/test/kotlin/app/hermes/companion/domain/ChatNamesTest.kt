package app.hermes.companion.domain

import app.hermes.companion.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatNamesTest {
    private val owner = BotOwner("https://work/hermes", "research")
    private val profiles = listOf(ProfileScope.toRef("research", "Research agent"))
    private val bot = NativeBot(owner, "Research bot")
    private val group = NativeGroup("g", "Launch team", listOf(NativeGroupMember("seat", "research", "research", "Old name")), "host", 1)

    @Test fun botTitlesOverrideGenericProfileNamesOnlyOnTheirOwnGateway() {
        val local = ChatNames.profiles(owner.origin + "/", profiles, listOf(bot))
        assertEquals("Research bot", ChatNames.agent("research", local))
        assertEquals("Research agent", ChatNames.agent("research", ChatNames.profiles("https://other/hermes", profiles, listOf(bot))))
        assertEquals("research", ChatNames.agent("research", emptyList()))
    }
    @Test fun aBotMissingFromTheOrdinaryProfileResponseStillHasItsName() {
        assertEquals("Research bot", ChatNames.agent("research", ChatNames.profiles(owner.origin, emptyList(), listOf(bot))))
    }
    @Test fun canonicalThreadPresentationDoesNotRenameItsDatabaseIdentity() {
        val session = SessionRef("s", "research", "Bot Chat", 0)
        assertEquals("Research agent", ChatNames.session(session, profiles))
        assertEquals("Bot Chat", session.title)
        assertEquals("Release plan", ChatNames.session(session.copy(title = "Release plan"), profiles))
    }
    @Test fun structuredGroupIdentityOverridesAGenericHermesLabel() {
        val event = NativeGroupEvent("e", 1, "message.member", "Hermes", actorId = "seat")
        assertEquals("Research agent", ChatNames.groupSpeaker(event, group, profiles))
        assertEquals("Research agent", ChatNames.groupSpeaker(event.copy(actorId = "", actorProfile = "research"), group, profiles))
        assertEquals("You", ChatNames.groupSpeaker(event.copy(kind = "message.user"), group, profiles))
    }
    @Test fun legacyGroupActorsResolveWithoutGuessingAnUnknownSpeaker() {
        val event = NativeGroupEvent("e", 1, "message.member", "seat")
        assertEquals("Research agent", ChatNames.groupSpeaker(event, group, profiles))
        assertEquals("Guest", ChatNames.groupSpeaker(event.copy(actor = "Guest"), group, profiles))
    }
    @Test fun aPeerMemberWithTheSameProfileIdKeepsItsOwnName() {
        val remote = group.copy(members = group.members.map { it.copy(name = "Lab researcher", peer = true) })
        assertEquals("Lab researcher", ChatNames.groupSpeaker(NativeGroupEvent("e", 1, "message.member", "Hermes", actorId = "seat"), remote, profiles))
    }
    @Test fun remoteRoomNamesNeverBorrowALocalProfilesTitle() {
        val participants = listOf(RoomParticipant("research", "RES", host = "Lab"))
        assertEquals("research · Lab", Rooms.speakerLabel(null, participants, profiles, "Launch team"))
        assertEquals("Launch team", Rooms.speakerLabel(null, participants + RoomParticipant("ops", "OPS"), profiles, "Launch team"))
        assertEquals(1, Rooms.speakerIndex("research@Lab", listOf(RoomParticipant("ops", "OPS")) + participants))
    }
}
