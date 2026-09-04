package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPolicyTest {
    @Test
    fun loopbackDoesNotNeedPassword() {
        assertFalse(AuthPolicy.needsPassword(DashboardStatus(authRequired = false)))
        assertTrue(AuthPolicy.needsPassword(DashboardStatus(authRequired = true)))
    }

    @Test
    fun emptyOrBasicProvidersArePassword() {
        assertEquals("basic", AuthPolicy.passwordProvider(emptyList()))
        assertEquals("basic", AuthPolicy.passwordProvider(listOf("basic")))
        assertEquals("password", AuthPolicy.passwordProvider(listOf("password")))
    }

    @Test
    fun oidcOnlyHasNoPasswordProvider() {
        assertNull(AuthPolicy.passwordProvider(listOf("nous")))
        assertNull(AuthPolicy.passwordProvider(listOf("oidc")))
    }

    @Test
    fun loginJsonEscapesCredentials() {
        val body = AuthPolicy.jsonLogin("basic", "nyx", """p"ass""")
        assertTrue(body.contains("\"provider\":\"basic\""))
        assertTrue(body.contains("\"username\":\"nyx\""))
        assertTrue(body.contains("\\\"ass"))
        assertFalse(body.contains("token"))
    }
}
