package app.hermes.companion

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.hands.client.EncryptedHandsConnectionStore
import app.hermes.hands.client.HandsClient
import app.hermes.hands.client.HandsConnection
import app.hermes.hands.client.HandsConnectionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Installation metadata only: no ARM, provider read, permission request or host command. */
@RunWith(AndroidJUnit4::class)
class HandsClientInstallationDeviceTest {
    @Test fun pairedClientReadsAuthenticatedHandsStatusWithoutOwningMobilePermissions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encrypted = EncryptedHandsConnectionStore(context)
        assumeTrue("Connect Companion to Hands locally before this installation smoke", encrypted.isSelected())
        val connection = encrypted.load()
        assertNotNull("The selected Hands credential must be readable", connection)
        val approved = requireNotNull(connection)
        val methods = mutableListOf<String>()
        val readOnly = object : HandsConnectionStore {
            override fun isSelected() = encrypted.isSelected()
            override fun load() = encrypted.load()
            override fun save(connection: HandsConnection) = error("Installation smoke must not alter pairing")
            override fun clear() = error("Installation smoke must not remove pairing")
        }
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "GET" && request.url.encodedPath == "/v1/status") {
                    "Installation smoke permits only Hands status metadata"
                }
                check(request.header("Authorization") == "Bearer ${approved.token}") {
                    "Status must use the existing locally paired credential"
                }
                synchronized(methods) { methods += "GET /v1/status" }
                chain.proceed(request)
            }.build()
        try {
            val state = HandsClient(readOnly, http = http).refresh()
            assertTrue("Existing Hands connection must remain selected", state.configured)
            assertTrue("Start the approved Hands service before this smoke", state.reachable)
            assertNull("Authenticated Hands status must succeed", state.error)
            val expected = setOf("device.sms.list", "device.sms.send", "device.files", "device.files.access",
                "device.notifications.list", "device.notifications.ack", "device.notifications.clear", "device.secure_entry")
            assertTrue("Hands must advertise migrated mobile capabilities", state.capabilities.containsAll(expected))
            assertEquals(listOf("GET /v1/status"), synchronized(methods) { methods.toList() })
            val info = context.packageManager.getPackageInfo(context.packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            val mobile = setOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.PACKAGE_USAGE_STATS, Manifest.permission.ACCESS_NOTIFICATION_POLICY,
                Manifest.permission.WRITE_SETTINGS, Manifest.permission.WRITE_SECURE_SETTINGS)
            assertTrue("Companion must request no mobile-data grants", info.requestedPermissions.orEmpty().none { it in mobile })
            val retired = setOf("app.hermes.companion.device.CompanionAccessibilityService",
                "app.hermes.companion.device.CompanionNotificationListener", "app.hermes.companion.device.HandsService")
            assertTrue("Companion must expose no Android control service", info.services.orEmpty().none { it.name in retired })
            assertTrue("Companion must expose no retired debug executor", info.receivers.orEmpty().none {
                it.name == "app.hermes.companion.DirectToolboxTestReceiver"
            })
            assertTrue("Pairing must remain selected", encrypted.isSelected())
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
