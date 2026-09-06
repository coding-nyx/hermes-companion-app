package app.hermes.companion.device

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
)

/**
 * Process-wide bus from [CompanionNotificationListener] → DeviceNodeCoordinator.
 * DROP_OLDEST if the lane is slow — never block the system notification callback.
 * Config is pushed from the app module (StickyStore) so feature-device stays free of data-local.
 */
object NotifStreamBus {
    private val _events = MutableSharedFlow<NotifStreamEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NotifStreamEvent> = _events.asSharedFlow()

    @Volatile
    var listenerBound: Boolean = false

    @Volatile
    var streamEnabled: Boolean = false

    @Volatile
    var extraProtected: Set<String> = emptySet()

    @Volatile
    var selfPackage: String = "app.hermes.companion"

    fun emit(event: NotifStreamEvent) {
        _events.tryEmit(event)
    }

    fun configure(enabled: Boolean, protectedPackages: Set<String>, selfPackageName: String) {
        streamEnabled = enabled
        extraProtected = protectedPackages
        selfPackage = selfPackageName
    }
}
