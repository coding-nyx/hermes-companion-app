package app.hermes.companion.domain

import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway

/** Deduped Connect-screen rail: saved book + paired origins + last successful origin. */
object GatewayBook {
    fun key(origin: String): String {
        val trimmed = origin.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        val rest = withScheme.substringAfter("://").substringBefore('/').substringAfterLast('@')
        return "$scheme://${rest.lowercase()}"
    }

    fun chipId(origin: String): String =
        origin.substringAfter("://", origin).substringBefore('/').replace(':', '-').lowercase()
            .ifBlank { "gw" }

    fun merge(
        saved: List<SavedGateway>,
        pairedOrigins: List<String>,
        lastGoodOrigin: String?,
    ): List<GatewayChoice> {
        data class Acc(
            var origin: String,
            var name: String,
            var paired: Boolean = false,
            var lastOk: Boolean = false,
            var fromBook: Boolean = false,
        )
        val map = linkedMapOf<String, Acc>()
        fun acc(origin: String): Acc? {
            val k = key(origin)
            if (k.isBlank()) return null
            return map.getOrPut(k) {
                Acc(origin = origin.trim().trimEnd('/'), name = OriginPolicy.host(origin))
            }
        }
        for (row in saved) {
            val a = acc(row.origin) ?: continue
            a.fromBook = true
            if (row.name.isNotBlank()) a.name = row.name
            a.origin = row.origin.trim().trimEnd('/')
        }
        for (origin in pairedOrigins) {
            val a = acc(origin) ?: continue
            a.paired = true
        }
        lastGoodOrigin?.takeIf { it.isNotBlank() }?.let { origin ->
            acc(origin)?.lastOk = true
        }
        return map.values
            .map { a ->
                val host = OriginPolicy.host(a.origin)
                GatewayChoice(
                    id = chipId(a.origin),
                    origin = a.origin,
                    name = a.name.ifBlank { host },
                    host = host,
                    paired = a.paired,
                    lastOk = a.lastOk,
                    forgettable = a.fromBook && !a.paired,
                )
            }
            .sortedByDescending { it.lastOk }
    }

    fun markHealth(choices: List<GatewayChoice>, upKeys: Set<String>, downKeys: Set<String>): List<GatewayChoice> =
        choices.map { choice ->
            val k = key(choice.origin)
            choice.copy(
                health = when {
                    k in downKeys -> "down"
                    k in upKeys -> "up"
                    else -> choice.health
                },
            )
        }
}
