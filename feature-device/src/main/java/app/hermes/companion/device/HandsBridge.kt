package app.hermes.companion.device

object HandsBridge {
    @Volatile
    var onDisarm: (() -> Unit)? = null
}
