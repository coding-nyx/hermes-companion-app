package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.model.DeviceCred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HostScopedStoresTest {
    @Test fun proxyPathsKeepIndependentCredentialsProfilesAndGatewayNames() {
        val first = "https://host.example/one"; val second = "https://host.example/two"
        val operators = OperatorCredStore(prefs("op.paths"))
        operators.save(OperatorCred(first, sessionToken = "one")); operators.save(OperatorCred(second, sessionToken = "two"))
        assertEquals("one", operators.load(first)!!.sessionToken); assertEquals("two", operators.load(second)!!.sessionToken)
        assertNull(operators.load("https://host.example"))
        val devices = DeviceCredStore(prefs("device.paths"))
        devices.save(DeviceCred("d-one", "ash", "c-one", first)); devices.save(DeviceCred("d-two", "research", "c-two", second))
        assertEquals("c-one", devices.load(first)!!.credential); assertEquals("c-two", devices.load(second)!!.credential)
        assertNull(devices.load("https://host.example"))
        prefs("companion.sticky")
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        sticky.setProfile(first, "ash"); sticky.setProfile(second, "research")
        assertEquals("ash", sticky.profileFor(first)); assertEquals("research", sticky.profileFor(second))
    }

    @Test fun legacyAuthorityCredentialsCannotBeAdoptedByAProxyPath() {
        val operators = OperatorCredStore(prefs("op.legacy.paths"))
        operators.save(OperatorCred("https://host.example", sessionToken = "legacy-root"))
        assertNull(operators.load("https://host.example/one"))
        assertEquals("legacy-root", operators.load("https://host.example")!!.sessionToken)
    }

    private fun prefs(name: String) = RuntimeEnvironment.getApplication()
        .getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun hostKeysNormalise() {
        assertEquals("http://100.85.151.99:9120", HostKeys.of("http://100.85.151.99:9120/"))
        assertEquals("http://100.85.151.99:9120", HostKeys.of("HTTP://100.85.151.99:9120/api/status"))
        assertEquals("https://hermes.ts.net", HostKeys.of("https://hermes.ts.net:443"))
        assertEquals("http://[fd7a::1]:9120", HostKeys.of("http://[FD7A::1]:9120"))
        assertEquals("100.85.151.99", HostKeys.hostOf("http://100.85.151.99:9120"))
        assertTrue(HostKeys.same("http://a:9120", "http://A:9120/"))
        assertEquals("", HostKeys.of(""))
    }

    @Test
    fun operatorCredsAreIsolatedPerHost() {
        val store = OperatorCredStore(prefs("op.test"))
        store.save(OperatorCred(origin = "http://lab:9120", sessionToken = "lab-token", authMode = "token"))
        store.save(OperatorCred(origin = "http://hub:9120", username = "nyx", password = "pw", authMode = "password"))
        assertEquals("lab-token", store.load("http://lab:9120/")!!.sessionToken)
        assertEquals("", store.load("http://hub:9120")!!.sessionToken)
        assertEquals("password", store.load("http://hub:9120")!!.authMode)
        assertNull(store.load("http://other:9120"))
        assertEquals(2, store.loadAll().size)
        // legacy no-arg load = most recently saved
        assertEquals("http://hub:9120", store.load()!!.origin)
        store.clear("http://hub:9120")
        assertNull(store.load("http://hub:9120"))
        assertEquals("lab-token", store.load()!!.sessionToken)
    }

    @Test
    fun operatorLegacyRecordMigrates() {
        val p = prefs("op.legacy")
        p.edit().putString("origin", "http://lab:9120").putString("username", "u").putString("password", "p")
            .putString("session_token", "t").putString("auth_mode", "password").commit()
        val store = OperatorCredStore(p)
        val migrated = store.load("http://lab:9120")!!
        assertEquals("u", migrated.username)
        assertEquals("t", migrated.sessionToken)
        assertNull(p.getString("password", null))
    }

    @Test
    fun hostNameFallsBackToBareHost() {
        val store = OperatorCredStore(prefs("op.names"))
        assertEquals("100.88.4.63", store.hostName("http://100.88.4.63:9120"))
        store.saveGateways(listOf(app.hermes.companion.model.SavedGateway("g", "hub-11", "http://100.88.4.63:9120")))
        assertEquals("hub-11", store.hostName("http://100.88.4.63:9120/"))
        assertEquals("hermes", store.loadGateways().first().kind)
    }

    @Test
    fun deviceCredsPerHostAndLegacyAdoption() {
        val p = prefs("dev.test")
        // legacy record without origin (the lab pairing)
        p.edit().putString("device_id", "dev_1").putString("profile_id", "knight").putString("credential", "c1").commit()
        val store = DeviceCredStore(p)
        assertNull(store.load("http://lab:9120"))
        val adopted = store.adoptLegacy("http://lab:9120")!!
        assertEquals("dev_1", adopted.deviceId)
        assertEquals("http://lab:9120", store.load("http://lab:9120")!!.origin)
        assertNull(store.load("http://hub:9120"))
        store.save(DeviceCred(deviceId = "dev_2", profileId = "default", credential = "c2", origin = "http://hub:9120/"))
        assertEquals(2, store.loadAll().size)
        assertEquals("dev_2", store.load("http://hub:9120")!!.deviceId)
        assertEquals("dev_1", store.load("http://lab:9120")!!.deviceId)
        store.clear("http://lab:9120")
        assertNull(store.load("http://lab:9120"))
        assertEquals("dev_2", store.load()!!.deviceId)
    }

    @Test
    fun stickyPerHostProfileAndTopic() {
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        sticky.setProfile("http://lab:9120", "knight")
        sticky.setProfile("http://hub:9120", "default")
        assertEquals("knight", sticky.profileFor("http://lab:9120/"))
        assertEquals("default", sticky.profileFor("http://hub:9120"))
        // unknown host falls back to the global (most recent) choice
        assertEquals("default", sticky.profileFor("http://new:9120"))
        sticky.setNtfyTopic("http://lab:9120", "https://ntfy.sh/lab")
        sticky.setNtfyTopic("http://hub:9120", " ")
        assertEquals("https://ntfy.sh/lab", sticky.ntfyTopicFor("http://lab:9120"))
        assertEquals(mapOf("http://lab:9120" to "https://ntfy.sh/lab"), sticky.ntfyHosts())
        sticky.lastGoodOrigin = "http://lab:9120"
        assertEquals("http://lab:9120", sticky.lastGoodOrigin)
    }

    @Test
    fun notificationTopicNeverFallsBackToAnotherHostsOrLegacyGlobalTopic() {
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        sticky.setNtfyTopic("https://configured.example", "https://ntfy.example/configured")
        assertEquals("https://ntfy.example/configured", sticky.ntfyTopicFor("https://configured.example"))
        assertNull(sticky.ntfyTopicFor("https://never-configured.example"))
        sticky.ntfyTopic = "https://ntfy.example/legacy"
        assertNull(sticky.ntfyTopicFor("https://never-configured.example"))
        sticky.setNtfyTopic("https://configured.example", null)
        assertNull(sticky.ntfyTopicFor("https://configured.example"))
    }

    @Test
    fun notificationTopicsAreIsolatedBetweenProxyPathsOnTheSameAuthority() {
        prefs("companion.sticky")
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        val first = "https://gateway.example/assistant-a"
        val second = "https://gateway.example/assistant-b"
        sticky.setNtfyTopic(first, "https://ntfy.example/a")
        assertNull(sticky.ntfyTopicFor(second))
        assertNull(sticky.ntfyTopicFor("https://gateway.example"))
        sticky.setNtfyTopic(second, "https://ntfy.example/b")
        assertEquals("https://ntfy.example/a", sticky.ntfyTopicFor("https://GATEWAY.example:443/assistant-a/"))
        assertEquals("https://ntfy.example/b", sticky.ntfyTopicFor(second))
        assertEquals(mapOf(first to "https://ntfy.example/a", second to "https://ntfy.example/b"), sticky.ntfyHosts())
        sticky.setNtfyTopic(second, null)
        assertNull(sticky.ntfyTopicFor(second))
        assertEquals(mapOf(first to "https://ntfy.example/a"), sticky.ntfyHosts())
    }

    @Test
    fun legacyAuthorityTopicsAreRetainedButNeverAdoptedWithoutAnExplicitSave() {
        val p = prefs("companion.sticky")
        p.edit().putString("ntfy_topic", "https://ntfy.example/global")
            .putString("ntfy_topic.https://gateway.example", "https://ntfy.example/legacy").commit()
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        listOf("https://gateway.example", "https://gateway.example/a", "https://gateway.example/b").forEach {
            assertNull(sticky.ntfyTopicFor(it))
        }
        assertTrue(sticky.ntfyHosts().isEmpty())
        sticky.setNtfyTopic("https://gateway.example/a", "https://ntfy.example/confirmed")
        assertNull(sticky.ntfyTopicFor("https://gateway.example/b"))
        assertEquals(mapOf("https://gateway.example/a" to "https://ntfy.example/confirmed"), sticky.ntfyHosts())
        assertEquals("https://ntfy.example/legacy", p.getString("ntfy_topic.https://gateway.example", null))
        assertEquals("https://ntfy.example/global", sticky.ntfyTopic)
    }

    @Test
    fun invalidNotificationOwnersCannotCreateSubscriptions() {
        prefs("companion.sticky")
        val sticky = StickyStore(RuntimeEnvironment.getApplication())
        listOf("", "https://user:password@gateway.example/a", "https://gateway.example/a?token=secret").forEach {
            sticky.setNtfyTopic(it, "https://ntfy.example/topic")
            assertNull(sticky.ntfyTopicFor(it))
        }
        assertTrue(sticky.ntfyHosts().isEmpty())
    }
}
