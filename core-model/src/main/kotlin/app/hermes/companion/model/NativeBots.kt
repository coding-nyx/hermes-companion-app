package app.hermes.companion.model

import kotlinx.serialization.Serializable

/** The dashboard base path is part of the owner, including reverse-proxy prefixes. */
@Serializable
data class BotOwner(val origin: String, val profile: String)

data class NativeBot(
    val owner: BotOwner,
    val title: String,
    val description: String = "",
    val model: String = "",
    val provider: String = "",
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val section: String = "",
    val avatar: Boolean = false,
    val canonicalId: String? = null,
    val resolvedId: String? = null,
    val preview: String = "",
    val updatedAt: Long = 0,
    /** Preserve the entire namespaced object when changing one presentation field. */
    val metadataJson: String = "{}",
    val metadataRevision: Int? = null,
)

data class BotRoster(val bots: List<NativeBot>, val installationId: String = "", val protocol: Boolean = false)

data class BotEdit(val profile: String, val title: String, val description: String = "",
    val model: String = "", val provider: String = "", val hidden: Boolean = false, val section: String = "")

data class NativeGroupCapabilities(
    val driver: Boolean = false,
    val methods: Set<String> = emptySet(),
    val features: Set<String> = emptySet(),
    /** Null means an older host; empty means this caller cannot seat any profile. */
    val seatableProfiles: Set<String>? = null,
) {
    val canCreate: Boolean get() = driver && "groups.create" in methods && "groups.send" in methods
}

@Serializable
data class NativeGroupMember(val id: String, val profile: String, val handle: String, val name: String,
    val peer: Boolean = false)

@Serializable
data class NativeGroup(val id: String, val name: String, val members: List<NativeGroupMember>,
    val authority: String, val epoch: Long, val latestSeq: Long = 0, val updatedAt: Long = 0)

data class NativeGroupEvent(val id: String, val sequence: Long, val kind: String, val actor: String,
    val text: String = "", val thread: String = "", val payloadJson: String = "{}",
    val actorId: String = "", val actorProfile: String = "")

data class NativeGroupPage(val events: List<NativeGroupEvent>, val cursor: Long, val latest: Long,
    val hasMore: Boolean, val authority: String, val epoch: Long)

/** All attempt coordinates are required before an approval/retry can be offered. */
data class NativeGroupAction(val kind: String, val member: String, val task: String, val generation: Long,
    val request: String = "", val command: String = "", val choices: List<String> = emptyList()) {
    val key: String get() = "$kind:$member:$task:$generation:$request"
}

data class NativeGroupSnapshot(val group: NativeGroup, val working: Boolean = false,
    val blocked: Boolean = false, val actions: List<NativeGroupAction> = emptyList())

/** Persist before transmission. Unknown outcomes retain the original event/room identity. */
@Serializable
data class NativeBotOperation(val id: String, val owner: BotOwner, val kind: String,
    val target: String, val text: String = "", val thread: String = "",
    val members: List<NativeGroupMember> = emptyList(), val createdAt: Long = System.currentTimeMillis())
