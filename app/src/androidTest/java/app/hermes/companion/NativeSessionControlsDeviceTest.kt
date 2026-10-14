package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.model.SessionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native host, isolated empty session. No prompt, microphone, global config or operator preference write. */
@RunWith(AndroidJUnit4::class)
class NativeSessionControlsDeviceTest {
    @Test fun nativeSessionSettingsAndCommandsAgreeWithHostReadback() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val api = DashboardClient().apply { useLoopback() }
        assertNotNull(api.adoptLoopbackToken(origin))
        var created: SessionRef? = null
        try {
            val session = api.createSession(origin, profile, "Companion native settings S22 fixture", requireRpc = true)
            created = session
            val before = api.sessionSettings(origin, session)
            val changed = api.setSessionSetting(origin, session, "reasoning", "low")
            assertFalse(changed.confirmationRequired)
            assertEquals("low", api.sessionSettings(origin, session).reasoning)
            val normal = api.setSessionSetting(origin, session, "fast", "normal")
            assertFalse(normal.confirmationRequired)
            val after = api.sessionSettings(origin, session)
            assertEquals("normal", after.fast); assertEquals(before.model, after.model); assertEquals(before.provider, after.provider)
            val catalog = api.commandCatalog(origin, session)
            assertTrue(catalog.isNotEmpty()); assertTrue(catalog.any { it.name == "status" })
            val status = api.dispatchCommand(origin, session, "status", "")
            assertEquals("exec", status.type); assertTrue(status.output.isNotBlank())
            assertTrue(api.listMessages(origin, session.id, profile).none { it.role == app.hermes.companion.model.MessageRole.USER })
        } finally { created?.let { api.deleteSession(origin, it.id, profile) }; api.closeRpc() }
    }
}
