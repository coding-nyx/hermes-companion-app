package app.hermes.companion.device

object HandsBridge {
    val serviceEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile
    var onDisarm: (() -> Unit)? = null
}
