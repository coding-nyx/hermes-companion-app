package app.hermes.hands

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Real AndroidKeyStore/Conscrypt interoperability; no phone input or application control. */
@RunWith(AndroidJUnit4::class)
class HandsTlsIdentityDeviceTest {
    @Test fun persistentNonexportableIdentityCompletesTls12And13Handshakes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = TlsIdentity(context)
        assertEquals(identity.fingerprint, TlsIdentity(context).fingerprint)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keys.getKey("hermes.hands.remote.tls.v1", null) as PrivateKey
        assertNull("TLS private key bytes must remain in AndroidKeyStore", key.encoded)
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        assertTrue(info.digests.toSet().containsAll(setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)))

        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not a client identity")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val actual = chain?.firstOrNull()?.encoded?.let { encoded -> MessageDigest.getInstance("SHA-256").digest(encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 255) } }
                if (actual != identity.fingerprint) throw CertificateException("TLS certificate fingerprint mismatch")
            }
        }
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        HandsHttpServer({ if (it == "device-test-token") ClientTokens.Client("test", "Device TLS test") else null },
            { ok(JSONObject().put("fixture", true)) }, { _, _ -> error("forbidden", "Control is not exposed by this test") },
            0, remoteIdentity = identity).use { server ->
            server.start()
            for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
                (client.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.enabledProtocols = arrayOf(protocol)
                    socket.startHandshake()
                    assertEquals(protocol, socket.session.protocol)
                    socket.getOutputStream().write(("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\n" +
                        "Authorization: Bearer device-test-token\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                    val response = socket.getInputStream().bufferedReader().readText()
                    assertTrue(response, response.startsWith("HTTP/1.1 200"))
                    assertTrue(JSONObject(response.substringAfter("\r\n\r\n")).getJSONObject("result").getBoolean("fixture"))
                }
            }
        }
    }
}
