package app.hermes.companion.data.local

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NotificationStreamPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun legacyEnabledRequiresNewConsentAndKeepsOldChoice() {
        context.getSharedPreferences("companion.sticky", Context.MODE_PRIVATE).edit().putBoolean("notif_stream_enabled", true)
            .putString("notif_stream_origin", "https://old.invalid").commit()
        val s = StickyStore(context)
        assertFalse(s.notifStreamEnabled); assertEquals("https://old.invalid", s.notifStreamOrigin)
        assertTrue(s.notifStreamApps.isEmpty()); assertEquals("metadata", s.notifStreamContent)
    }
    @Test fun routeDoesNotFollowActiveHostOrProfile() {
        val s = StickyStore(context); s.origin = "https://active.invalid"; s.lastGoodOrigin = "https://other.invalid"
        s.profileId = "default"
        assertNull(s.notifStreamTargetOrigin()); assertNull(s.notifStreamProfileFor("https://active.invalid"))
    }
    @Test fun profilesArePinnedToExactRoute() {
        val s = StickyStore(context); s.setNotifStreamProfile("https://host.invalid/one", "alice")
        assertEquals("alice", s.notifStreamProfileFor("https://host.invalid/one/"))
        assertNull(s.notifStreamProfileFor("https://host.invalid/two"))
    }
    @Test fun appSelectionAndConsentSurviveRecreation() {
        val s = StickyStore(context); s.notifStreamApps = setOf("com.example.mail"); s.notifStreamEnabled = true
        s.notifStreamConsentVersion = 7
        assertEquals(setOf("com.example.mail"), StickyStore(context).notifStreamApps)
        assertTrue(StickyStore(context).notifStreamEnabled); assertEquals(7L, StickyStore(context).notifStreamConsentVersion)
    }
    @Test fun invalidContentModeFailsClosed() {
        val s = StickyStore(context); s.notifStreamContent = "invalid"; assertEquals("metadata", s.notifStreamContent)
    }
}
