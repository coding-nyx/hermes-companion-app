package app.hermes.companion.device

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow

/** One shade notification the phone may forward to the stream target. */
data class NotifStreamEvent(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: String,
    val ongoing: Boolean,
    val clearable: Boolean,
    val postTimeMs: Long,
    val consentVersion: Long = -1L,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val contentMode: String = "metadata",
)

/**
 * Process-wide bus from [CompanionNotificationListener] → DeviceNodeCoordinator.
 * DROP_OLDEST if the lane is slow — never block the system notification callback.
 * Config is pushed from the app module (StickyStore) so feature-device stays free of data-local.
 */
object NotifStreamBus {
    val droppedBeforeQueue = java.util.concurrent.atomic.AtomicLong(0)
    private val _events = Channel<NotifStreamEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { droppedBeforeQueue.incrementAndGet() })
    val events: Flow<NotifStreamEvent> = _events.receiveAsFlow()

    @Volatile var allowedApps: Set<String> = emptySet()
    @Volatile var contentMode: String = "metadata"
    @Volatile var includeCalls: Boolean = true
    @Volatile var consentVersion: Long = 0
        private set

    @Volatile
    var listenerBound: Boolean = false

    @Volatile
    var streamEnabled: Boolean = false

    @Volatile
    var extraProtected: Set<String> = emptySet()

    @Volatile
    var selfPackage: String = "app.hermes.companion"

    /**
     * Hashed stream key → the real `StatusBarNotification.key`.
     *
     * The stream only ever shows the agent a SHA-256 prefix (see
     * [CompanionNotificationListener.hashKey]), so acting on a notification needs a way back to
     * the platform key. Bounded and insertion-ordered: the oldest entry is dropped past
     * [KEY_CAP] so a chatty phone cannot grow this without limit.
     *
     * Only notifications eligible for the current stream are registered here, which means a
     * protected or denylisted app's notification is not addressable by any of the notification
     * action tools either.
     */
    private const val KEY_CAP = 256
    private val keyMap = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > KEY_CAP
    }

    /** The bound listener instance, or null. Needed for cancel/snooze/getActiveNotifications. */
    @Volatile
    var listener: CompanionNotificationListener? = null

    fun rememberKey(hashed: String, real: String) {
        synchronized(keyMap) { keyMap[hashed] = real }
    }

    /** Resolve a hashed stream key back to the platform key, or null if we never saw it. */
    fun realKey(hashed: String): String? = synchronized(keyMap) { keyMap[hashed] }

    fun forgetKey(real: String) {
        synchronized(keyMap) { keyMap.entries.removeIf { it.value == real } }
    }

    fun emit(event: NotifStreamEvent) {
        _events.trySend(event)
    }

    fun configure(enabled: Boolean, protectedPackages: Set<String>, selfPackageName: String, apps: Set<String> = emptySet(), content: String = "metadata", calls: Boolean = true, version: Long = consentVersion + 1) {
        consentVersion = version
        synchronized(keyMap) { keyMap.clear() }
        allowedApps = apps.toSet()
        contentMode = content
        includeCalls = calls
        streamEnabled = enabled
        extraProtected = protectedPackages
        selfPackage = selfPackageName
    }
}
