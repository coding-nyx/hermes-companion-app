package app.hermes.hands

import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Loopback by default. A separate, explicitly enabled instance serves pinned TLS remotely. */
class HandsHttpServer(
    private val authenticate: (String) -> ClientTokens.Client?,
    private val status: () -> JSONObject,
    private val command: (ClientTokens.Client, JSONObject) -> JSONObject,
    private val port: Int = 8765,
    private val remoteIdentity: TlsIdentity? = null,
    private val pair: ((JSONObject, String) -> JSONObject)? = null,
) : AutoCloseable {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var closed = false
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val connectionRate = RequestRate(600, 1200)
    private val pairingRate = RequestRate(5, 30)
    val localPort: Int get() = socket?.localPort ?: -1

    @Synchronized fun start() {
        check(socket == null && !closed)
        val listener = remoteIdentity?.createServerSocket(port) ?: ServerSocket().apply {
            try {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            } catch (error: Exception) { close(); throw error }
        }
        socket = listener
        Thread({
            while (!closed && !listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                val peer = client.inetAddress.hostAddress.orEmpty()
                if (remoteIdentity != null && !connectionRate.allow(peer)) { runCatching { client.close() }; continue }
                clients.add(client)
                try { workers.execute {
                    try { client.use { handle(it, listener.localPort, peer) } }
                    finally { clients.remove(client) }
                } } catch (_: java.util.concurrent.RejectedExecutionException) {
                    clients.remove(client); runCatching { client.close() }
                }
            }
        }, if (remoteIdentity == null) "hands-http" else "hands-https").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket, serverPort: Int, peer: String) {
        var tlsEstablished = remoteIdentity == null
        // Absolute deadline also stops peers that drip bytes just before each socket timeout.
        var readDeadline: java.util.concurrent.ScheduledFuture<*>? = null
        var responseDeadline: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            readDeadline = deadlines.schedule({ runCatching { client.close() } }, 15, TimeUnit.SECONDS)
            client.soTimeout = 5_000
            if (remoteIdentity != null) {
                check(client is SSLSocket)
                client.useClientMode = false
                client.startHandshake()
                tlsEstablished = true
            }
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            var bytesRead = 0
            fun readHeaderByte(): Int {
                val byte = input.read()
                require(byte >= 0 && ++bytesRead <= 8192) { "Invalid request headers" }
                return byte
            }
            fun line(): String {
                val out = StringBuilder()
                while (true) {
                    val byte = readHeaderByte()
                    if (byte == 13) { require(readHeaderByte() == 10); break }
                    require(byte in 32..126) { "Headers require ASCII and CRLF" }
                    out.append(byte.toChar())
                }
                return out.toString()
            }
            val request = line().split(' ')
            require(request.size == 3 && request.all { it.isNotEmpty() } && request[2] == "HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val row = line()
                if (row.isEmpty()) break
                val key = row.substringBefore(':').lowercase(java.util.Locale.ROOT)
                require(':' in row && key.matches(Regex("[a-z0-9_-]+")) && key !in headers)
                headers[key] = row.substringAfter(':').trim()
            }
            if ("origin" in headers || "transfer-encoding" in headers) {
                respond(client, 403, error("forbidden", "Browser origins and chunked requests are not accepted")); return
            }
            if (!HostAuthority.accepts(headers["host"], serverPort, remoteIdentity != null)) {
                respond(client, 403, error("forbidden", "Use the selected endpoint authority and port")); return
            }
            require("expect" !in headers) { "Expect/continue is not supported" }
            val bodyLength = headers["content-length"]?.let {
                require(it.matches(Regex("[0-9]{1,6}")))
                it.toInt()
            } ?: 0
            require(bodyLength in 0..393216)
            val isPair = remoteIdentity != null && pair != null && request[0] == "POST" && request[1] == "/v1/pair"
            val identity = if (!isPair) {
                val auth = headers["authorization"].orEmpty()
                val found = if (auth.startsWith("Bearer ")) authenticate(auth.removePrefix("Bearer ")) else null
                if (found == null) { respond(client, 401, error("unauthorized", "A valid client token is required")); return }
                found
            } else null

            fun jsonBody(limit: Int): JSONObject {
                require(bodyLength in 1..limit)
                require(headers["content-type"]?.substringBefore(';')?.trim()?.equals("application/json", true) == true)
                val data = ByteArray(bodyLength)
                var count = 0
                while (count < data.size) { val read = input.read(data, count, data.size - count); require(read > 0); count += read }
                val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                val tokener = JSONTokener(decoded)
                val json = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("An object is required")
                require(tokener.nextClean() == 0.toChar()) { "Unexpected data after the JSON object" }
                return json
            }

            val body = when {
                isPair -> jsonBody(2048).also {
                    require(it.keys().asSequence().toSet() == setOf("code", "client_name"))
                    require(it.opt("code") is String && it.getString("code").matches(Regex("[0-9]{8}")))
                    val name = it.opt("client_name") as? String ?: throw IllegalArgumentException("Client name is required")
                    require(name.trim().isNotEmpty() && name.length <= 100 && name.none { char -> char.code < 32 || char.code == 127 })
                    it.put("client_name", name.trim())
                }
                request[0] == "POST" && request[1] == "/v1/commands" -> jsonBody(393216)
                request[0] == "GET" && request[1] == "/v1/status" && bodyLength == 0 -> null
                else -> { respond(client, 404, error("not_found", "Unknown API route")); return }
            }
            readDeadline.cancel(false)
            val responseSeconds = when {
                isPair -> 65L
                body?.optString("action") == "device.files" -> 125L
                body?.optString("action") == "device.voice.speak" ->
                    ((body.optJSONObject("arguments")?.optLong("wait_ms", 0) ?: 0).coerceIn(0, 120_000) / 1000 + 5).coerceAtLeast(30)
                else -> 30L
            }
            responseDeadline = deadlines.schedule({ runCatching { client.close() } }, responseSeconds, TimeUnit.SECONDS)
            val result = when {
                isPair -> {
                    if (!pairingRate.allow(peer)) { respond(client, 429, error("rate_limited", "Too many pairing attempts; try again later")); return }
                    checkNotNull(pair).invoke(checkNotNull(body), peer)
                }
                body != null -> command(checkNotNull(identity), body)
                else -> status()
            }
            respond(client, 200, result)
        } catch (_: Exception) {
            // A plaintext or failed TLS connection never receives a plaintext HTTP response.
            if (tlsEstablished) runCatching { respond(client, 400, error("bad_request", "Malformed or expired request")) }
        } finally {
            readDeadline?.cancel(false)
            responseDeadline?.cancel(false)
        }
    }

    private fun respond(client: Socket, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    @Synchronized override fun close() {
        closed = true
        val listener = socket
        socket = null
        try { listener?.close() }
        finally {
            clients.forEach { runCatching { it.close() } }; clients.clear()
            try { workers.shutdownNow() } finally { deadlines.shutdownNow() }
        }
    }
}

/** Syntax-only validation: never resolve a client-controlled Host through DNS. */
internal object HostAuthority {
    fun accepts(raw: String?, expectedPort: Int, remote: Boolean): Boolean {
        if (raw == null || raw.length > 320 || expectedPort !in 1..65535) return false
        val host: String
        val port: String
        if (raw.startsWith('[')) {
            val end = raw.indexOf(']')
            if (end <= 1 || raw.getOrNull(end + 1) != ':') return false
            host = raw.substring(1, end)
            port = raw.substring(end + 2)
            if (!remote || ':' !in host || !host.matches(Regex("[0-9a-fA-F:.]+"))) return false
            if (runCatching { InetAddress.getByName(host) }.isFailure) return false
        } else {
            val split = raw.lastIndexOf(':')
            if (split < 1 || split != raw.indexOf(':')) return false
            host = raw.substring(0, split)
            port = raw.substring(split + 1)
            if (!remote) {
                if (host.lowercase(java.util.Locale.ROOT) !in setOf("127.0.0.1", "localhost")) return false
            } else if (host.all { it.isDigit() || it == '.' }) {
                val parts = host.split('.')
                if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith('0')) || (it.toIntOrNull() ?: 256) !in 0..255 }) return false
            } else {
                val dns = host.removeSuffix(".")
                if (dns.length !in 1..253 || dns.split('.').any {
                    it.length !in 1..63 || !it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                }) return false
            }
        }
        val numericPort = port.toIntOrNull() ?: return false
        return port.matches(Regex("[1-9][0-9]{0,4}")) && numericPort in 1..65535 &&
            (!remote || numericPort == expectedPort)
    }
}

/** Per-address and global rolling windows stay bounded even under address churn. */
internal class RequestRate(private val perPeer: Int, private val globalLimit: Int, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val peers = linkedMapOf<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()
    @Synchronized fun allow(peer: String): Boolean {
        val time = now()
        while (global.firstOrNull()?.let { it <= time - 60_000 } == true) global.removeFirst()
        if (global.size >= globalLimit) return false
        val entries = peers.getOrPut(peer) {
            if (peers.size >= 128) peers.remove(peers.keys.first())
            ArrayDeque()
        }
        while (entries.firstOrNull()?.let { it <= time - 60_000 } == true) entries.removeFirst()
        if (entries.size >= perPeer) return false
        entries.addLast(time); global.addLast(time)
        return true
    }
}

internal fun ok(result: JSONObject = JSONObject()) = JSONObject().put("ok", true).put("result", result)
internal fun error(code: String, message: String) = JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))
