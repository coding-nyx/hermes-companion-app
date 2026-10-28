package app.companion.backend

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun ByteArray.b64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
internal fun String.unb64(size: Int? = null): ByteArray {
    require(all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) { "Invalid key encoding." }
    val raw = Base64.getUrlDecoder().decode(this)
    require(raw.b64() == this && (size == null || raw.size == size)) { "Invalid key size." }
    return raw
}
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun randomSeed() = ByteArray(32).also(SecureRandom()::nextBytes)

internal class DeviceIdentity(seed: ByteArray) {
    private val key = Ed25519PrivateKeyParameters(seed.also { require(it.size == 32) }, 0)
    val publicKey: ByteArray = key.generatePublicKey().encoded
    val id: String = sha256(publicKey).joinToString("") { "%02x".format(it) }
    fun sign(text: String): String = sign(text.toByteArray()).b64()
    fun sign(bytes: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key); update(bytes, 0, bytes.size); generateSignature()
    }
}
