package app.hermes.companion.domain

/** Desktop-style optional names: member labels supply the default, with a fresh catalog name. */
object NativeGroupNames {
    const val MAX_LENGTH = 64

    fun limit(value: String, length: Int = MAX_LENGTH): String {
        val clipped = value.take(length)
        return if (clipped.isNotEmpty() && Character.isHighSurrogate(clipped.last())) clipped.dropLast(1) else clipped
    }

    fun resolve(requested: String, members: List<String>, taken: Set<String> = emptySet()): String {
        val base = limit(requested.trim().ifBlank { members.joinToString(", ") }).ifBlank { "Group chat" }
        if (base !in taken) return base
        var number = 2
        while (true) {
            val suffix = " $number"
            val candidate = limit(base, MAX_LENGTH - suffix.length) + suffix
            if (candidate !in taken) return candidate
            number++
        }
    }
}
