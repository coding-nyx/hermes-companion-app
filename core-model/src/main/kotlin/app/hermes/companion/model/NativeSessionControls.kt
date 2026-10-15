package app.hermes.companion.model

data class NativeSessionSettings(val model: String?, val provider: String?, val reasoning: String?, val fast: String?,
    val models: List<ModelOption> = emptyList())
data class NativeConfigResult(val key: String, val value: String?, val scope: String?,
    val deferred: Boolean, val confirmationRequired: Boolean, val confirmation: String, val warning: String)
data class NativeCommand(val name: String, val description: String)
data class NativeCommandResult(val type: String, val output: String, val message: String,
    val display: String, val target: String, val notice: String)
data class NativeRedirectResult(val status: String, val text: String)
