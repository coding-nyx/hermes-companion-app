package app.hermes.companion

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.DashboardException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only diagnosis and token-rotation gate. No UI, prompts or preference writes. */
@RunWith(AndroidJUnit4::class)
class NativeAuthenticationDeviceTest {
    @Test fun savedCredentialDiagnosis() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val saved = OperatorCredStore.encrypted(instrumentation.targetContext).load(origin)
        require(saved?.authMode != "password") { "This diagnostic is for loopback-token hosts only" }
        val api = DashboardClient().apply { useLoopback() }
        val fresh = api.adoptLoopbackToken(origin)
        assertFalse(fresh.isNullOrBlank())
        api.sessionToken = saved?.sessionToken
        val oldRead = runCatching { api.listSessions(origin, profile) }
        val error = (oldRead.exceptionOrNull() as? DashboardException)?.code ?: if (oldRead.isSuccess) "ok" else "transport_failure"
        instrumentation.sendStatus(0, Bundle().apply {
            putString("saved_token_present", (!saved?.sessionToken.isNullOrBlank()).toString())
            putString("saved_token_matches_current", (saved?.sessionToken == fresh).toString())
            putString("saved_token_read", error)
        })
        api.sessionToken = fresh
        api.listSessions(origin, profile) // Fresh native read must succeed; content is never printed.
    }

    @Test fun rotatedTokenRecoversWithoutSendingChat() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val profile = requireNotNull(args.getString("profile"))
        val api = DashboardClient().apply { useLoopback(); sessionToken = "companion-expired-token-fixture" }
        api.listSessions(origin, profile)
        assertNotEquals("companion-expired-token-fixture", api.sessionToken)
        assertFalse(api.sessionToken.isNullOrBlank())
    }
}
