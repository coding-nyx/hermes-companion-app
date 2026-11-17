package app.hermes.hands.engine

/** All command arguments stay argv entries, never shell syntax. */
internal object EnginePolicy {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    val keys = mapOf("back" to 4, "home" to 3, "recents" to 187,
        "enter" to 66, "delete" to 67, "tab" to 61, "escape" to 111)

    fun packageName(value: String): String {
        require(value.length <= 255 && packagePattern.matches(value)) { "invalid_package" }
        return value
    }

    fun coordinate(value: Double, extent: Int): String {
        require(value.isFinite() && value >= 0 && value < extent) { "invalid_coordinate" }
        return value.toFloat().toString()
    }

    fun duration(value: Int): Int {
        require(value in 1..5000) { "invalid_duration" }
        return value
    }

    fun text(value: String): String {
        require(value.length in 1..4000) { "invalid_text" }
        // Android's input text uses a virtual key map, not an IME. Reject unsupported input
        // instead of reporting success after silently dropping Unicode/newlines.
        require(value.all { it.code in 32..126 } && !value.contains("%s")) { "unsupported_text" }
        return value.replace(" ", "%s")
    }

    fun dimensions(width: Int, height: Int, density: Int) {
        require(width in 320..1600 && height in 320..2400 && width.toLong() * height <= 2_560_000) { "invalid_display_size" }
        require(density in 120..480) { "invalid_density" }
    }
}
