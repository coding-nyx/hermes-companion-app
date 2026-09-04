package app.hermes.companion.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal class MemoryCookieJar : CookieJar {
    private val lock = Any()
    private val stored = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach { incoming ->
                stored.removeAll { it.name == incoming.name && it.matches(url) }
                stored.add(incoming)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(lock) { stored.filter { it.matches(url) } }

    fun clear() {
        synchronized(lock) { stored.clear() }
    }
}
