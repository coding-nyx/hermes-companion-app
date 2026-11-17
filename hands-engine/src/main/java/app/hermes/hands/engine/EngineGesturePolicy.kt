package app.hermes.hands.engine

/** Pixel geometry shared by legacy delta gestures and ref-scoped scrolling. */
internal object EngineGesturePolicy {
    fun delta(width: Int, height: Int, dx: Double, dy: Double): List<Double> {
        require(dx.isFinite() && dy.isFinite() && (dx != 0.0 || dy != 0.0)) { "invalid_swipe" }
        val x = width / 2.0
        val y = height / 2.0
        return listOf(x, y, x + dx, y + dy).also {
            EnginePolicy.coordinate(it[2], width)
            EnginePolicy.coordinate(it[3], height)
        }
    }

    fun scroll(width: Int, height: Int, direction: String, bounds: List<Int>? = null): List<Double> {
        val left = (bounds?.get(0) ?: 0).toDouble().coerceIn(0.0, width - 1.0)
        val top = (bounds?.get(1) ?: 0).toDouble().coerceIn(0.0, height - 1.0)
        val right = (bounds?.get(2) ?: width).toDouble().coerceIn(0.0, width - 1.0)
        val bottom = (bounds?.get(3) ?: height).toDouble().coerceIn(0.0, height - 1.0)
        require(right > left && bottom > top) { "invalid_scroll_bounds" }
        val x = (left + right) / 2
        val y = (top + bottom) / 2
        val dx = (right - left) * .3
        val dy = (bottom - top) * .3
        return when (direction) {
            "down" -> listOf(x, y + dy, x, y - dy)
            "up" -> listOf(x, y - dy, x, y + dy)
            "left" -> listOf(x - dx, y, x + dx, y)
            "right" -> listOf(x + dx, y, x - dx, y)
            else -> throw IllegalArgumentException("invalid_direction")
        }
    }
}
