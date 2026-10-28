package app.hermes.companion

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Merged-manifest identity checks only. No device permissions, pairing, or data migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class AppIdentityIsolationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun applicationIdentityAndLauncherLabelMatchTheSelectedBuild() {
        assertEquals(BuildConfig.APPLICATION_ID, app.packageName)
        val expected = if (BuildConfig.APPLICATION_ID.endsWith(".preview")) "Companion Prototype" else "Companion"
        assertEquals(expected, app.packageManager.getApplicationLabel(app.applicationInfo).toString())
    }

    @Test fun contentProvidersBelongOnlyToThisApplicationId() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
        val authorities = info.providers.orEmpty().flatMap { it.authority.split(';') }
        assertTrue("File sharing must use this sandbox", "${app.packageName}.files" in authorities)
        assertTrue(authorities.isNotEmpty())
        authorities.forEach { assertTrue("Unexpected shared provider: $it", it.startsWith("${app.packageName}.")) }
        assertEquals(app.packageName, app.packageManager.resolveContentProvider("${app.packageName}.files", 0)?.packageName)
        if (app.packageName.endsWith(".preview")) {
            assertFalse(authorities.any { it == "app.hermes.companion.files" })
        }
    }

    @Test fun debugToolboxIsPackageScopedAndStillProtectedByDumpPermission() {
        assertEquals("${app.packageName}.test.L0", DirectToolboxTestReceiver.ACTION)
        val receiver = app.packageManager.getReceiverInfo(ComponentName(app, DirectToolboxTestReceiver::class.java), 0)
        assertEquals(app.packageName, receiver.packageName)
        assertEquals("android.permission.DUMP", receiver.permission)
        assertTrue(receiver.exported)
    }

    @Test fun previewDoesNotChangeServiceClassNamespacesOrShareAUid() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SERVICES)
        assertNull(info.sharedUserId)
        val services = info.services.orEmpty()
        assertTrue(services.any { it.name == "app.hermes.companion.device.CompanionAccessibilityService" })
        assertTrue(services.any { it.name == "app.hermes.companion.device.CompanionNotificationListener" })
        services.forEach { assertEquals(app.packageName, it.packageName) }
    }
}
