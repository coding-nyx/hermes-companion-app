package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Empty native session; no prompt, voice, global configuration or saved operator preference change. */
@RunWith(AndroidJUnit4::class)
class NativeCreationDeviceTest {
    @Test fun installedHostAcceptsGuardedCreationAndSyntheticSessionIsRemoved() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin")); val profile = requireNotNull(args.getString("profile"))
        val client = DashboardClient().apply { useLoopback() }
        assertNotNull(client.adoptLoopbackToken(origin))
        var created: SessionRef? = null
        try {
            val session = client.createSession(origin, profile, "Companion creation compatibility S22 fixture",
                idempotencyKey = java.util.UUID.randomUUID().toString(), requireRpc = true)
            created = session
            assertEquals(profile, session.profileId); assertTrue(session.id.isNotBlank())
            assertTrue(client.listMessages(origin, session.id, profile).isEmpty())
        } finally { created?.let { client.deleteSession(origin, it.id, profile) }; client.closeRpc() }
    }
}
