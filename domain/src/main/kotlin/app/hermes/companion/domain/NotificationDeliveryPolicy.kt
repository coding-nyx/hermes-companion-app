package app.hermes.companion.domain

/** Transport send success is not delivery. Only an explicit application ACK retires an item. */
object NotificationDeliveryPolicy {
    enum class Action { ACKNOWLEDGED, WAIT_FOR_STORAGE, WAIT_FOR_RETRY, SEND }
    fun next(persisted: Boolean, acknowledged: Boolean, lastAttemptMs: Long, now: Long): Action = when {
        !persisted -> Action.WAIT_FOR_STORAGE
        acknowledged -> Action.ACKNOWLEDGED
        lastAttemptMs != 0L && now - lastAttemptMs in 0 until 10_000L -> Action.WAIT_FOR_RETRY
        else -> Action.SEND
    }
    fun sameScope(origin: String, profile: String, device: String, credential: String,
                  expectedOrigin: String, expectedProfile: String, expectedDevice: String, expectedCredential: String): Boolean =
        WakePolicy.topicOwnerKey(origin) != null && WakePolicy.topicOwnerKey(origin) == WakePolicy.topicOwnerKey(expectedOrigin) &&
            profile == expectedProfile && device == expectedDevice && credential == expectedCredential
}
