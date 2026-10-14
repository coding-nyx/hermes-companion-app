package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FcmTransportDeviceTest {
    @Test fun registerAnIsolatedTransportFixture() {
        assertTrue("Firebase configuration is required", BuildConfig.FCM_CONFIGURED)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = requireNotNull(InstrumentationRegistry.getArguments().getString("nonce"))
        val token = Tasks.await(FirebaseMessaging.getInstance().token, 45, TimeUnit.SECONDS)
        assertTrue(token.isNotBlank())
        File(ctx.filesDir, "fcm-device-test.private.txt").writeText(token)
        assertTrue(ctx.getSharedPreferences("companion.push-device-test", 0).edit()
            .putString("expected", nonce).remove("received").putLong("expires", System.currentTimeMillis() + 300_000).commit())
    }
    @Test fun verifyAndCleanTheDeliveredFixture() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = requireNotNull(InstrumentationRegistry.getArguments().getString("nonce"))
        val prefs = ctx.getSharedPreferences("companion.push-device-test", 0)
        assertEquals(nonce, prefs.getString("received", null))
        assertTrue(prefs.getLong("received_at", 0) > 0)
        assertTrue(prefs.edit().clear().commit()); File(ctx.filesDir, "fcm-device-test.private.txt").delete()
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(9821)
        assertFalse("Push receipt must not arm Hands", (ctx.applicationContext as CompanionApp).deviceNode.state.value.arm != app.hermes.companion.model.DeviceArm.DISARMED)
    }
}
