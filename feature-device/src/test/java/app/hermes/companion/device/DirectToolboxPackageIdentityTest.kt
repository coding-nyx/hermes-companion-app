package app.hermes.companion.device

import android.app.Application
import android.content.ContextWrapper
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Simulated Android settings only; does not grant Notification Access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DirectToolboxPackageIdentityTest {
    private val preview = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "app.hermes.companion.preview"
    }
    private val service = CompanionNotificationListener::class.java.name

    @After fun clearListenerSetting() {
        Settings.Secure.putString(preview.contentResolver, "enabled_notification_listeners", "")
    }

    @Test fun originalListenerGrantDoesNotMarkPreviewReady() {
        Settings.Secure.putString(preview.contentResolver, "enabled_notification_listeners", "app.hermes.companion/$service")
        val status = DirectToolbox.nlsStatus(preview)
        assertEquals(false, status["nls_bound"])
        assertEquals("app.hermes.companion.preview/$service", status["listener_component"])
    }

    @Test fun previewListenerKeepsTheActualClassNamespace() {
        Settings.Secure.putString(preview.contentResolver, "enabled_notification_listeners", "${preview.packageName}/$service")
        assertEquals(true, DirectToolbox.nlsStatus(preview)["nls_bound"])
    }

    @Test fun inventedPreviewClassNamespaceIsNotAccepted() {
        Settings.Secure.putString(preview.contentResolver, "enabled_notification_listeners",
            "${preview.packageName}/${preview.packageName}.device.CompanionNotificationListener")
        assertEquals(false, DirectToolbox.nlsStatus(preview)["nls_bound"])
    }
}
