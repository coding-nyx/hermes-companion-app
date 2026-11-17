package app.hermes.companion.device

import app.hermes.companion.model.SnapshotNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeviceObservationException(
    val code: String,
    message: String,
    val androidErrorCode: Int? = null,
) : IllegalStateException(message)

data class ScreenObservation(
    val app: String,
    val nodes: List<SnapshotNode>,
    val windowId: Int,
    val capturedAtMs: Long,
    val generation: Long,
    val snapshotId: String,
)

data class ScreenshotObservation(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val windowId: Int,
    val capturedAtMs: Long,
    val generation: Long,
    val app: String,
    val bounds: List<Int>,
    val coordinateSpace: String,
)

/** Dispatch acceptance is not gesture completion. Keep cancellation and uncertain timeout explicit. */
internal class GestureCompletion {
    private val latch = CountDownLatch(1)
    private val succeeded = AtomicBoolean(false)

    fun complete() { succeeded.set(true); latch.countDown() }
    fun cancel() { latch.countDown() }

    fun await(timeoutMs: Long, checkAllowed: () -> Unit = {}) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                checkAllowed()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw DeviceObservationException(
                    "gesture_timeout", "Gesture completion is unknown; obtain a fresh view before deciding whether to retry",
                )
                if (latch.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DeviceObservationException("gesture_cancelled", "Gesture wait was cancelled; obtain a fresh view before continuing")
        }
        checkAllowed()
        if (!succeeded.get()) throw DeviceObservationException("gesture_cancelled", "Android cancelled the gesture")
    }
}

internal object ScreenCapturePolicy {
    fun failure(androidCode: Int): DeviceObservationException = when (androidCode) {
        2 -> DeviceObservationException("a11y_unavailable", "Android denied accessibility screenshot access", androidCode)
        3 -> DeviceObservationException("screenshot_rate_limited", "Android requires more time between screenshots", androidCode)
        4 -> DeviceObservationException("screenshot_invalid_display", "Android could not find the target display", androidCode)
        5 -> DeviceObservationException("screenshot_invalid_window", "The target window no longer exists", androidCode)
        6 -> DeviceObservationException("screenshot_secure_window", "Android blocked capture of a protected window", androidCode)
        else -> DeviceObservationException("screenshot_failed", "Android screenshot capture failed (code $androidCode)", androidCode)
    }

    /** Ignore edge handles/bars so a black protected app with a tiny system affordance stays unusable. */
    fun isBlank(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return true
        var nearBlack = 0
        var brightness = 0L
        val samples = 48
        for (row in 0 until samples) for (column in 0 until samples) {
            val x = ((0.05 + 0.9 * (column + 0.5) / samples) * width).toInt().coerceIn(0, width - 1)
            val y = ((0.05 + 0.9 * (row + 0.5) / samples) * height).toInt().coerceIn(0, height - 1)
            val color = pixel(x, y)
            val maximum = maxOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
            if (maximum <= 8) nearBlack++
            brightness += maximum
        }
        val count = samples * samples
        return nearBlack >= count * 0.998 && brightness < count
    }
}
