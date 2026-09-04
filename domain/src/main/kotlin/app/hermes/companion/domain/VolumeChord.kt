package app.hermes.companion.domain

/** Double volume-down within WINDOW_MS disarms. First press is not consumed. */
object VolumeChord {
    const val WINDOW_MS = 400L

    fun secondPress(nowMs: Long, lastMs: Long): Boolean =
        lastMs > 0L && nowMs - lastMs in 1 until WINDOW_MS
}
