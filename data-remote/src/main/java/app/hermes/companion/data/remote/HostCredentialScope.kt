package app.hermes.companion.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A client belongs to one dashboard origin and proxy prefix for its entire lifetime. */
internal class HostCredentialScope {
    @Volatile private var owner: HttpUrl? = null

    @Synchronized fun bind(origin: String) {
        val candidate = base(origin)
        val current = owner
        if (current != null && current != candidate)
            throw DashboardException("host_scope", "Use a separate connection for this host or proxy path")
        owner = candidate
    }

    fun permits(url: HttpUrl): Boolean {
        val current = owner ?: return false
        if (url.scheme != current.scheme || url.host != current.host || url.port != current.port) return false
        val prefix = current.encodedPath.trimEnd('/')
        return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
    }

    /** Only native dashboard reads may recover a rotated loopback bootstrap token. */
    fun nativeReadOrigin(url: HttpUrl): String? {
        val current = owner ?: return null
        if (!permits(url)) return null
        val path = url.encodedPath.removePrefix(current.encodedPath.trimEnd('/'))
        return current.toString().trimEnd('/').takeIf { path.startsWith("/api/") }
    }

    fun pluginRoute(url: HttpUrl): Boolean {
        val current = owner ?: return false
        return permits(url) && url.encodedPath.removePrefix(current.encodedPath.trimEnd('/')).startsWith("/companion/")
    }

    companion object {
        fun base(origin: String): HttpUrl {
            val input = origin.trim().let { if ("://" in it) it else "http://$it" }
            val url = input.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Host connection must not contain credentials, query parameters or a fragment"
            }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }
        fun key(origin: String): String = base(origin).toString().trimEnd('/')
    }
}
