package app.hermes.hands

import android.app.Application
import android.content.Context
import app.hermes.hands.engine.ShizukuController
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    instrumentedPackages = ["app.hermes.hands"],
    shadows = [HandsRuntimeRemoteDefaultTest.NoCredentials::class]
)
class HandsRuntimeRemoteDefaultTest {
    private lateinit var context: Context
    private lateinit var shizuku: ShizukuController
    private lateinit var runtime: HandsRuntime

    @Implements(ClientTokens::class, isInAndroidSdk = false)
    class NoCredentials {
        @Implementation fun __constructor__(context: Context) = Unit
        @Implementation fun clients(): List<ClientTokens.Client> = emptyList()
        @Implementation fun authenticate(token: String): ClientTokens.Client? = null
        @Implementation fun issue(label: String): Pair<ClientTokens.Client, String> =
            ClientTokens.Client("test-client", label) to "test-token"
    }

    companion object {
        private lateinit var directory: Path
        private lateinit var testIdentity: TlsIdentity

        @JvmStatic @BeforeClass fun createTestTlsIdentity() {
            directory = Files.createTempDirectory("hands-runtime-tls-test-")
            val file = directory.resolve("identity.p12")
            val password = "test-only-password".toCharArray()
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "test", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=Hands Test", "-validity", "1", "-storetype", "PKCS12",
                "-storepass", String(password), "-keypass", String(password), "-keystore", file.toString()).redirectErrorStream(true).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Timed out creating test certificate") }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, password) } }
            val certificate = store.getCertificate("test") as X509Certificate
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
            testIdentity = TlsIdentity.forTesting(ssl, certificate)
        }

        @JvmStatic @AfterClass fun cleanupTlsIdentity() {
            if (::directory.isInitialized) {
                Files.deleteIfExists(directory.resolve("identity.p12"))
                Files.deleteIfExists(directory)
            }
        }
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hands.settings", Context.MODE_PRIVATE).edit().clear().commit()
        shizuku = ShizukuController(context)
        runtime = HandsRuntime(context, shizuku, tlsIdentityFactory = { testIdentity })
    }

    @After fun tearDown() {
        runtime.stopServer()
        shizuku.close()
    }

    @Test fun remoteAccessDefaultsToAutoStartTrue() {
        assertTrue(runtime.remoteAccess.value.autoStart)
        assertFalse(runtime.remoteAccess.value.enabled)
    }

    @Test fun startServerAutomaticallyLaunchesRemoteTlsByDefault() {
        runtime.startServer()
        assertTrue("Local server should be serving", runtime.state.value.serving)
        assertTrue("Remote access should auto-start on LAN/Tailscale by default", runtime.remoteAccess.value.enabled)
        assertTrue("TLS fingerprint should be populated", runtime.remoteAccess.value.fingerprint.isNotBlank())
    }

    @Test fun stopServerPreservesAutoStartPreference() {
        runtime.startServer()
        assertTrue(runtime.remoteAccess.value.enabled)

        runtime.stopServer()
        assertFalse("Server stopped", runtime.state.value.serving)
        assertFalse("Remote server stopped", runtime.remoteAccess.value.enabled)
        assertTrue("AutoStart preference preserved across service stops", runtime.remoteAccess.value.autoStart)

        runtime.startServer()
        assertTrue("Remote access restarts automatically on next service start", runtime.remoteAccess.value.enabled)
    }

    @Test fun explicitDisableRemotePersistsFalse() {
        runtime.startServer()
        assertTrue(runtime.remoteAccess.value.enabled)

        runtime.disableRemote(clearPreference = true)
        assertFalse(runtime.remoteAccess.value.enabled)
        assertFalse(runtime.remoteAccess.value.autoStart)

        // Stopping and restarting the service should now honor the user's explicit disable
        runtime.stopServer()
        runtime.startServer()
        assertFalse("Remote should remain off if user explicitly disabled it", runtime.remoteAccess.value.enabled)
    }
}
