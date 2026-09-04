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
}
