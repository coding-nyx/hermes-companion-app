package app.companion.backend.codync

import app.companion.backend.*
import java.net.URI
import java.net.URLDecoder

/** Public computer identity is retained; the one-use code lives only for this pairing attempt. */
data class CodyncPairing(val connection: AgentConnection, val code: String, val urls: List<String>) {
    override fun toString() = "CodyncPairing(redacted)"
    companion object {
        fun parse(input: String): CodyncPairing {
            require(input.length <= 16 * 1024) { "Pairing link is too long." }
            val uri = URI(input.trim())
            require(uri.scheme == "codync" && uri.host == "pair") { "Paste a Codync pairing link from your computer." }
            val fields = linkedMapOf<String,String>()
            uri.rawQuery.orEmpty().split('&').forEach {
                val key=URLDecoder.decode(it.substringBefore('='),"UTF-8")
                val value=URLDecoder.decode(it.substringAfter('=',""),"UTF-8")
                require(fields.put(key,value)==null) { "Pairing link repeats a field." }
            }
            require(fields["v"]=="3") { "Show a current Codync pairing link on your computer." }
            val key=requireNotNull(fields["sk"]).decodeBase64Url(32)
            val hostId=requireNotNull(fields["id"])
            require(RelayCrypto.computerId(key)==hostId) { "Pairing identity does not match this computer." }
            requireNotNull(fields["bk"]).decodeBase64Url(32)
            val code=requireNotNull(fields["code"]).also { it.decodeBase64Url(16) }
            val urls=fields["urls"].orEmpty().split(',').filter(String::isNotBlank).distinct().also { require(it.size<=16) }
            val cloud=fields["cloud"].orEmpty()
            urls.forEach { require(URI(it).scheme in setOf("http","https")) { "Codync direct addresses require HTTP or HTTPS." }; AgentConnection.create(AgentRuntime.CODYNC,it) }
            if(cloud.isNotBlank()) {
                require(URI(cloud).scheme=="https") { "Codync relay requires HTTPS." }
                AgentConnection.create(AgentRuntime.CODYNC,cloud)
            }
            val endpoint=urls.firstOrNull() ?: cloud.also { require(it.isNotBlank()) { "Pairing link has no host address." } }
            return CodyncPairing(AgentConnection(hostId,AgentRuntime.CODYNC,fields["name"].orEmpty().ifBlank { "Codync" },endpoint,
                hostId,fields.getValue("sk"),fields.getValue("bk"),cloud,urls),code,urls)
        }
    }
}
