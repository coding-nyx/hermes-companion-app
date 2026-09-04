package app.hermes.companion.domain

data class SwipeSpec(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long = 250)

object DeviceGestures {
    const val SCREENSHOT_MAX_PX = 1080
    const val WAIT_MAX_MS = 5_000L
    const val RATE_PER_SEC = 10

    fun fit(width: Int, height: Int, maxEdge: Int = SCREENSHOT_MAX_PX): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val edge = maxOf(w, h)
        if (edge <= maxEdge) return w to h
        val scale = maxEdge.toFloat() / edge
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    fun waitMs(raw: Long): Long = raw.coerceIn(0L, WAIT_MAX_MS)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 250): SwipeSpec? {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
        if (x1 == x2 && y1 == y2) return null
        return SwipeSpec(x1, y1, x2, y2, durationMs.coerceIn(50L, 1_200L))
    }

    fun scroll(direction: String, cx: Float, cy: Float, distance: Float = 600f): SwipeSpec? {
        val d = distance.coerceIn(80f, 1600f)
        return when (direction.trim().lowercase()) {
            "up" -> swipe(cx, cy + d / 2, cx, cy - d / 2)
            "down" -> swipe(cx, cy - d / 2, cx, cy + d / 2)
            "left" -> swipe(cx + d / 2, cy, cx - d / 2, cy)
            "right" -> swipe(cx - d / 2, cy, cx + d / 2, cy)
            else -> null
        }
    }

    fun allowRate(nowMs: Long, hits: MutableList<Long>, perSec: Int = RATE_PER_SEC): Boolean {
        hits.removeAll { nowMs - it >= 1_000L }
        if (hits.size >= perSec) return false
        hits.add(nowMs)
        return true
    }
}
