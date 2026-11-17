package app.hermes.hands

import android.app.Application
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HandsTlsServerTest {
    private fun server(pair: ((JSONObject, String) -> JSONObject)? = null, block: (HandsHttpServer) -> Unit) {
        HandsHttpServer({ if (it == "test-token") ClientTokens.Client("one", "Test") else null },
            { ok(JSONObject().put("armed", false)) }, { client, request -> ok(JSONObject().put("client", client.id).put("action", request.getString("action"))) },
            0, remoteIdentity = identity, pair = pair).use { it.start(); block(it) }
    }

    private fun request(host: HandsHttpServer, method: String = "GET", path: String = "/v1/status",
        extra: String = "Authorization: Bearer test-token\r\n", body: String = "", authority: String = "100.100.1.2:${host.localPort}",
        protocol: String? = null): String =
        (client.socketFactory.createSocket("127.0.0.1", host.localPort) as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            if (protocol != null) socket.enabledProtocols = arrayOf(protocol)
            socket.startHandshake()
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: $authority\r\n$extra" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body").toByteArray(Charsets.UTF_8))
            socket.getInputStream().bufferedReader().readText()
        }

    @Test fun tls12And13SupportBearerAuthenticatedRequests() = server { host ->
        assertTrue(request(host, protocol = "TLSv1.2").startsWith("HTTP/1.1 200"))
        assertTrue(request(host, protocol = "TLSv1.3").startsWith("HTTP/1.1 200"))
        assertTrue(request(host, extra = "").startsWith("HTTP/1.1 401"))
        assertTrue(request(host, extra = "Authorization: Bearer wrong\r\n").startsWith("HTTP/1.1 401"))
    }

    @Test fun pairingUsesOnlyValidatedTlsBodiesAndReportsTheRealPeer() {
        val calls = AtomicInteger()
        server(pair = { body, peer ->
            calls.incrementAndGet()
            assertEquals("12345678", body.getString("code"))
            assertEquals("My agent", body.getString("client_name"))
            assertEquals("127.0.0.1", peer)
            ok(JSONObject().put("token", "test-token").put("client_id", "one").put("label", "My agent"))
        }) { host ->
            val reply = request(host, "POST", "/v1/pair", "Content-Type: application/json\r\n",
                "{\"code\":\"12345678\",\"client_name\":\"My agent\"}")
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertEquals("test-token", JSONObject(reply.substringAfter("\r\n\r\n")).getJSONObject("result").getString("token"))
            assertEquals(1, calls.get())
        }
    }

    @Test fun pairingRejectsMalformedCodeNamesAndExtraFieldsBeforeCallback() {
        val calls = AtomicInteger()
        server(pair = { _, _ -> calls.incrementAndGet(); ok() }) { host ->
            listOf("{\"code\":12345678,\"client_name\":\"Agent\"}", "{\"code\":\"123\",\"client_name\":\"Agent\"}",
                "{\"code\":\"12345678\",\"client_name\":\" \"}", "{\"code\":\"12345678\",\"client_name\":\"A\\nB\"}",
                "{\"code\":\"12345678\",\"client_name\":\"Agent\",\"approved\":true}").forEach { body ->
                assertTrue(body, request(host, "POST", "/v1/pair", "Content-Type: application/json\r\n", body).startsWith("HTTP/1.1 400"))
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun pairingRateIsBoundedEvenWhenEachRequestIsWellFormed() {
        val calls = AtomicInteger()
        server(pair = { _, _ -> calls.incrementAndGet(); error("pairing_denied", "No local approval") }) { host ->
            repeat(5) { assertTrue(request(host, "POST", "/v1/pair", "Content-Type: application/json\r\n",
                "{\"code\":\"12345678\",\"client_name\":\"Agent\"}").startsWith("HTTP/1.1 200")) }
            assertTrue(request(host, "POST", "/v1/pair", "Content-Type: application/json\r\n",
                "{\"code\":\"12345678\",\"client_name\":\"Agent\"}").startsWith("HTTP/1.1 429"))
            assertEquals(5, calls.get())
        }
    }

    @Test fun remoteHostOriginChunkedAndDuplicateHeadersRemainRestricted() = server { host ->
        assertTrue(request(host, authority = "user@phone.local:${host.localPort}").startsWith("HTTP/1.1 403"))
        assertTrue(request(host, authority = "phone.local:1").startsWith("HTTP/1.1 403"))
        assertTrue(request(host, extra = "Origin: https://attacker.test\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(request(host, extra = "Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(request(host, extra = "Authorization: Bearer test-token\r\nAUTHORIZATION: Bearer test-token\r\n").startsWith("HTTP/1.1 400"))
    }

    @Test fun plaintextCannotReachPairingOrStatusOnTheTlsPort() {
        val calls = AtomicInteger()
        server(pair = { _, _ -> calls.incrementAndGet(); ok() }) { host ->
            val response = Socket("127.0.0.1", host.localPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("GET /v1/status HTTP/1.1\r\nHost: localhost:${host.localPort}\r\n" +
                    "Authorization: Bearer test-token\r\n\r\n").toByteArray())
                runCatching { socket.getInputStream().bufferedReader().readText() }.getOrDefault("")
            }
            assertFalse(response.startsWith("HTTP/1.1"))
            assertEquals(0, calls.get())
        }
    }

    @Test fun fingerprintIsLowercaseSha256OfTheLeafDer() {
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, identity.fingerprint)
        assertTrue(identity.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        private lateinit var directory: Path
        private lateinit var identity: TlsIdentity
        private lateinit var certificate: X509Certificate
        private lateinit var client: SSLContext

        @JvmStatic @BeforeClass fun keys() {
            directory = Files.createTempDirectory("hands-tls-test-")
            val file = directory.resolve("identity.p12")
            val password = "test-only-password".toCharArray()
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "test", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=Hands TLS Test", "-validity", "1", "-storetype", "PKCS12",
                "-storepass", String(password), "-keypass", String(password), "-keystore", file.toString()).redirectErrorStream(true).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Timed out creating the ephemeral test certificate") }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, password) } }
            certificate = store.getCertificate("test") as X509Certificate
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
            identity = TlsIdentity.forTesting(ssl, certificate)
            val pinned = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client certificates are not used")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty() || !MessageDigest.isEqual(chain[0].encoded, certificate.encoded)) throw CertificateException("Unexpected test identity")
                }
            }
            client = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pinned), SecureRandom()) }
        }

        @JvmStatic @AfterClass fun cleanup() {
            if (::directory.isInitialized) {
                Files.deleteIfExists(directory.resolve("identity.p12"))
                Files.deleteIfExists(directory)
            }
        }
    }
}
