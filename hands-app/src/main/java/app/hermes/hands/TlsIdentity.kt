package app.hermes.hands

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Persistent app-private TLS identity. Clients authenticate the displayed certificate pin. */
class TlsIdentity private constructor(private val ssl: SSLContext, val fingerprint: String) {
    constructor(context: Context) : this(load(context.applicationContext))
    private constructor(identity: Material) : this(identity.context, identity.fingerprint)

    /** IPv4 listener; discovery advertises only addresses the runtime has actually observed. */
    fun createServerSocket(port: Int): ServerSocket {
        require(port in 0..65535)
        val socket = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            socket.reuseAddress = true
            socket.useClientMode = false
            socket.needClientAuth = false
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            check(socket.enabledProtocols.isNotEmpty()) { "TLS 1.2 or newer is required" }
            val allowed = setOf("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256")
            socket.enabledCipherSuites = socket.supportedCipherSuites.filter { it in allowed }.toTypedArray()
            check(socket.enabledCipherSuites.isNotEmpty()) { "A modern authenticated cipher suite is required" }
            socket.bind(InetSocketAddress("0.0.0.0", port), 8)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }

    private data class Material(val context: SSLContext, val fingerprint: String)

    companion object {
        private const val ALIAS = "hermes.hands.remote.tls.v1"
        private val creationLock = Any()

        private fun load(context: Context): Material = synchronized(creationLock) {
            // Scope is the application's AndroidKeyStore namespace; private key bytes are never
            // exported or copied into preferences, backup files, or a software keystore.
            check(context.packageName.isNotBlank())
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keys.containsAlias(ALIAS)) {
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Android's TLS stack signs precomputed handshake digests with NONEwithECDSA.
                    // See KeyGenParameterSpec.Builder.setDigests: TLS keys must authorize NONE.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Hands"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).setBit(0))
                    .setCertificateNotBefore(Date(now - TimeUnitDays.ONE))
                    .setCertificateNotAfter(Date(now + TimeUnitDays.TEN_YEARS))
                    .setUserAuthenticationRequired(false)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            }
            val entry = keys.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: error("The saved TLS identity is unavailable; do not silently replace its trusted fingerprint")
            val certificate = entry.certificate as? X509Certificate ?: error("The saved TLS certificate is unavailable")
            val chain = entry.certificateChain.map { it as X509Certificate }.toTypedArray()
            val manager = IdentityKeyManager(entry.privateKey, chain)
            val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, SecureRandom()) }
            Material(ssl, digest(certificate.encoded))
        }

        internal fun forTesting(context: SSLContext, certificate: X509Certificate): TlsIdentity =
            TlsIdentity(context, digest(certificate.encoded))

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Keep JSSE/Conscrypt on this one nonexportable signing identity. */
    private class IdentityKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (keyType?.startsWith("EC") == true) arrayOf(ALIAS) else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            getServerAliases(keyType, issuers)?.firstOrNull()
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            getServerAliases(keyType, issuers)?.firstOrNull()
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.clone() else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private object TimeUnitDays {
        const val ONE = 86_400_000L
        const val TEN_YEARS = 3650L * ONE
    }
}
