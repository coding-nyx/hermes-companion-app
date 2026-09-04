package app.hermes.companion.domain

import kotlin.random.Random

/** Phone-issued short codes. Alphabet matches hermes-plugin/pairing.py (no I/O/0/1). */
object PairingPolicy {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LEN = 6
    const val TTL_MS = 10 * 60 * 1000L
    const val POLL_MS = 2_000L

    fun normalize(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "")

    fun isValid(code: String): Boolean {
        val n = normalize(code)
        return n.length == CODE_LEN && n.all { it in ALPHABET }
    }

    fun expired(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - createdAtMs >= TTL_MS

    fun issue(random: Random = Random.Default): String = buildString {
        repeat(CODE_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
