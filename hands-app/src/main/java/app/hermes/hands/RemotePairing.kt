package app.hermes.hands

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class PendingPairing(val clientName: String, val peerAddress: String)

/** Phone-local presentation only. A credential is never published through this state. */
data class PairingState(
    val open: Boolean = false,
    val code: String = "",
    val expiresInSeconds: Long = 0,
    val pending: PendingPairing? = null,
    val message: String = "Pairing is closed.",
)

/**
 * A short, locally opened code window followed by a separate local approval.
 * Only the waiting HTTP request receives the independently issued client token.
 */
class RemotePairing(
    private val issue: (String) -> Pair<ClientTokens.Client, String>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val waitMillis: Long = 60_000,
) {
    init { require(waitMillis in 1..60_000) }

    private class Waiting(
        val clientName: String,
        val peerAddress: String,
        val deadline: Long,
        val latch: CountDownLatch = CountDownLatch(1),
        var response: JSONObject? = null,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val mutable = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = mutable
    private val attempts = ArrayDeque<Long>()
    private var expectedCode: ByteArray? = null
    private var lastCodeDigest: ByteArray? = null
    private var expiresAt = 0L
    private var wrongCodes = 0
    private var waiting: Waiting? = null

    fun openWindow() = synchronized(lock) {
        closeLocked("pairing_cancelled", "A new local pairing window replaced this request.")
        val now = clock()
        pruneAttempts(now)
        var code: String
        var digest: ByteArray
        do {
            code = random.nextInt(100_000_000).toString().padStart(8, '0')
            digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.US_ASCII))
        } while (lastCodeDigest?.let { MessageDigest.isEqual(it, digest) } == true)
        lastCodeDigest = digest
        expectedCode = code.toByteArray(Charsets.US_ASCII)
        expiresAt = now + WINDOW_MILLIS
        wrongCodes = 0
        mutable.value = PairingState(open = true, code = code, expiresInSeconds = 120,
            message = "Enter this code on the client, then approve its request on this phone.")
    }

    /** Runs on an HTTP worker, never on the Android main thread. */
    fun request(body: JSONObject, peerAddress: String): JSONObject {
        val pending = synchronized(lock) {
            val now = clock()
            refreshLocked(now)
            pruneAttempts(now)
            if (attempts.size >= REQUESTS_PER_MINUTE) return failure("pairing_rate_limited", "Too many pairing requests. Wait before trying again.")
            attempts.addLast(now)
            if (waiting != null) return failure("pairing_pending", "Another client is awaiting local approval.")
            if (!mutable.value.open || expectedCode == null) return failure("pairing_closed", "Open a pairing window on the phone first.")

            val label = clientName(body) ?: return failure("bad_args", "Use a client_name of 1 to 80 characters without control characters.")
            if (!safeText(peerAddress, 128) || peerAddress.isBlank()) return failure("bad_args", "The connection has no valid peer address.")
            val submitted = body.opt("code") as? String
            val matches = submitted != null && CODE_FORMAT.matches(submitted) &&
                MessageDigest.isEqual(expectedCode, submitted.toByteArray(Charsets.US_ASCII))
            if (!matches) {
                wrongCodes++
                if (wrongCodes >= WRONG_CODES_PER_WINDOW) closeLocked("invalid_pairing_code", "Pairing closed after five incorrect codes. Open a new window.")
                return failure("invalid_pairing_code", "The pairing code is invalid or no longer available.")
            }

            // Consume before exposing the approval prompt. Concurrent requests cannot reuse it.
            eraseCode()
            Waiting(label, peerAddress, minOf(expiresAt, now + waitMillis)).also {
                waiting = it
                publishWaiting(it, now)
            }
        }

        var interrupted = false
        try {
            val remaining = (pending.deadline - clock()).coerceIn(0, waitMillis)
            if (remaining > 0) pending.latch.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
        val result = synchronized(lock) {
            refreshLocked(clock())
            if (pending.response == null && waiting === pending) {
                closeLocked(if (interrupted) "pairing_cancelled" else "pairing_timeout",
                    if (interrupted) "The pending connection was interrupted." else "Local approval timed out. Open a new pairing window.")
            }
            // Exactly one worker owns this response; erase it from the pending object on delivery.
            (pending.response ?: failure("pairing_cancelled", "This pairing request is no longer active.")).also {
                pending.response = null
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result
    }

    /** Local UI only. Timeout/close/approval share one decision lock; issue is never retried. */
    fun approve() = synchronized(lock) {
        refreshLocked(clock())
        val pending = waiting ?: return@synchronized
        // Approval and its synchronous credential commit are one serialized decision. A request
        // already timed out/denied/closed can never reach issue, even through a queued UI tap.
        try {
            val (client, token) = issue(pending.clientName)
            completeLocked(pending, JSONObject().put("ok", true).put("result", JSONObject()
                .put("token", token).put("client_id", client.id).put("label", client.label)),
                "Client paired. Open a new pairing window to add another client.")
        } catch (_: Exception) {
            closeLocked("pairing_failed", "Could not save a client credential. Open a new pairing window to try again.")
        }
    }

    fun deny() = synchronized(lock) {
        closeLocked("pairing_denied", "The phone operator declined this pairing request.")
    }

    fun close() = synchronized(lock) {
        closeLocked("pairing_cancelled", "Pairing was closed on the phone.")
    }

    fun refresh() = synchronized(lock) { refreshLocked(clock()) }

    private fun refreshLocked(now: Long) {
        if (!mutable.value.open) return
        val pending = waiting
        if (now >= expiresAt) {
            closeLocked("pairing_expired", "The local pairing window expired.")
        } else if (pending != null && now >= pending.deadline) {
            closeLocked("pairing_timeout", "Local approval timed out. Open a new pairing window.")
        } else if (pending != null) {
            publishWaiting(pending, now)
        } else {
            mutable.value = mutable.value.copy(expiresInSeconds = secondsLeft(expiresAt, now))
        }
    }

    private fun publishWaiting(pending: Waiting, now: Long) {
        mutable.value = PairingState(open = true, expiresInSeconds = secondsLeft(pending.deadline, now),
            pending = PendingPairing(pending.clientName, pending.peerAddress),
            message = "Review the client name and connection address before approving.")
    }

    private fun closeLocked(code: String, message: String) {
        val pending = waiting
        if (pending != null) completeLocked(pending, failure(code, message), message)
        else {
            eraseCode()
            expiresAt = 0
            mutable.value = PairingState(message = message)
        }
    }

    private fun completeLocked(pending: Waiting, response: JSONObject, message: String) {
        if (waiting !== pending || pending.response != null) return
        eraseCode()
        expiresAt = 0
        waiting = null
        pending.response = response
        mutable.value = PairingState(message = message)
        pending.latch.countDown()
    }

    private fun eraseCode() { expectedCode?.fill(0); expectedCode = null }
    private fun pruneAttempts(now: Long) {
        while (true) {
            val oldest = attempts.peekFirst() ?: return
            if (now - oldest < RATE_WINDOW_MILLIS) return
            attempts.removeFirst()
        }
    }

    private fun clientName(body: JSONObject): String? {
        if (body.has("client_name") && body.has("label")) return null
        val value = (body.opt("client_name") ?: body.opt("label")) as? String ?: return null
        if (value.any(::unsafeCharacter)) return null
        return value.trim().takeIf { it.length in 1..80 }
    }

    private fun safeText(value: String, limit: Int): Boolean = value.length <= limit && value.none(::unsafeCharacter)
    private fun unsafeCharacter(value: Char): Boolean = value.isISOControl() ||
        Character.getType(value) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())

    private fun secondsLeft(deadline: Long, now: Long): Long = ((deadline - now).coerceAtLeast(0) + 999) / 1000
    private fun failure(code: String, message: String): JSONObject = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))

    private companion object {
        const val WINDOW_MILLIS = 120_000L
        const val RATE_WINDOW_MILLIS = 60_000L
        const val REQUESTS_PER_MINUTE = 10
        const val WRONG_CODES_PER_WINDOW = 5
        val CODE_FORMAT = Regex("[0-9]{8}")
    }
}
