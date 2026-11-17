package app.hermes.hands

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.device.DevicePrivacy
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in disposable-emulator permission dialog proof. Never reads a provider or sends SMS. */
@RunWith(AndroidJUnit4::class)
class HandsSmsPermissionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun explicitLocalReadRequestUsesAndroidConsentWithoutGrantingSendAccess() {
        assumeTrue("Permission dialog changes are opt-in", InstrumentationRegistry.getArguments().getString("hands_permission_dialogs") == "true")
        assumeTrue("Never alter permissions on an operator phone", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HandsApplication
        val runtime = app.runtime
        assumeFalse(DevicePrivacy.active || runtime.state.value.armed || runtime.state.value.consent)
        assumeFalse(app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assumeFalse("Do not compete with an active privileged observation", runtime.shizuku.status.value.bound)
        assumeTrue(ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
        assumeTrue(ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "pm clear-permission-flags ${app.packageName} android.permission.READ_SMS user-set user-fixed")).use { it.readBytes() }
        val controller = MainActivity::class.java.getDeclaredField("permissions").apply { isAccessible = true }.get(compose.activity) as HandsPermissionController
        try {
            instrumentation.runOnMainSync { controller.refresh(); controller.request("sms_read") }
            fun allowButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.viewIdResourceName?.endsWith(":id/permission_allow_button") == true) return node
                for (index in 0 until node.childCount) allowButton(node.getChild(index))?.let { return it }
                return null
            }
            var allow: AccessibilityNodeInfo? = null
            compose.waitUntil(8_000) { allow = allowButton(automation.rootInActiveWindow); allow != null }
            assertTrue(requireNotNull(allow).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(8_000) { ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED }
            assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS))
            instrumentation.runOnMainSync { controller.refresh(); assertTrue(controller.state.value.first { it.id == "sms_read" }.granted) }
        } finally { instrumentation.runOnMainSync { controller.close() } }
    }
}
