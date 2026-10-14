package app.hermes.companion.data.remote

import app.hermes.companion.model.*
import kotlinx.serialization.json.*

internal fun parseSessionContext(value: JsonObject): SessionContext = SessionContext(
    used = value.nonnegative("context_used"), maximum = value.nonnegative("context_max"),
    estimatedTotal = value.nonnegative("estimated_total"),
    estimated = (value["context_estimated"] as? JsonPrimitive)?.booleanOrNull,
    source = value.string("context_source"), model = value.string("model"),
    categories = (value["categories"] as? JsonArray).orEmpty().mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val tokens = item.nonnegative("tokens") ?: return@mapNotNull null
        val id = item.string("id")
        val label = item.string("label").ifBlank { id }
        if (label.isBlank()) null else ContextCategory(id, label, tokens)
    },
)

internal fun parseSessionCompression(value: JsonObject): SessionCompression {
    val summary = value["summary"] as? JsonObject
    val aborted = (summary?.get("aborted") as? JsonPrimitive)?.booleanOrNull == true
    val status = when {
        aborted || value.string("status") == "aborted" -> CompressionStatus.ABORTED
        value.string("status") == "compressed" -> CompressionStatus.COMPRESSED
        value.string("status") == "pending" -> CompressionStatus.PENDING
        else -> CompressionStatus.UNKNOWN
    }
    return SessionCompression(status,
        (value["lock_held"] as? JsonPrimitive)?.booleanOrNull == true,
        value.string("message"), summary?.string("headline").orEmpty(),
        summary?.string("token_line").orEmpty(), summary?.string("note").orEmpty(),
        value.nonnegative("before_tokens"), value.nonnegative("after_tokens"),
        if (value["messages"] is JsonArray) parseMessages(value.toString()) else null)
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.nonnegative(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
