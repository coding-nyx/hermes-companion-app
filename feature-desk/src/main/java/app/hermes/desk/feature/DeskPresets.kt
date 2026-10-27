package app.hermes.desk.feature

/** Deliberate user phrases only. Model text, quoted instructions and partial matches never execute. */
object DeskPresets {
    enum class Action { OPEN_BROWSER, OPEN_SETTINGS }
    fun match(utterance: String): Action? = when (utterance.trim().lowercase(java.util.Locale.ROOT).removeSuffix(".")
        .replace(Regex("[ \\t]+"), " ")) {
        "open browser", "open the browser", "open browser on this tablet", "open the browser on this tablet" -> Action.OPEN_BROWSER
        "open tablet settings", "open settings on this tablet" -> Action.OPEN_SETTINGS
        else -> null
    }
}
data class DeskLocalAction(val action: DeskPresets.Action, val binding: DeskBinding?)
