package app.hermes.hands

import android.Manifest
import android.app.Application
import android.os.Environment
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HandsPermissionsTest.NoSharedFileGrant::class])
class HandsPermissionsTest {
    @Test fun smsReadingAndSendingAreSeparateLocallyRequestedGrantsAndStatusNeverRequestsEither() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        assertArrayEquals(arrayOf(Manifest.permission.READ_SMS), MobilePermissions.runtime.getValue("sms_read"))
        assertArrayEquals(arrayOf(Manifest.permission.SEND_SMS), MobilePermissions.runtime.getValue("sms_send"))
        assertFalse(MobilePermissions.json(context).getBoolean("sms_read"))
        assertFalse(MobilePermissions.json(context).getBoolean("sms_send"))
        shadowOf(context).grantPermissions(Manifest.permission.READ_SMS)
        assertTrue(MobilePermissions.json(context).getBoolean("sms_read"))
        assertFalse("Reading never implies permission to send", MobilePermissions.json(context).getBoolean("sms_send"))
        shadowOf(context).denyPermissions(Manifest.permission.READ_SMS)
        assertFalse(MobilePermissions.json(context).getBoolean("sms_read"))
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test fun specialPermissionRoutesOnlyOpenAndroidApprovalSettingsForThisApp() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, MobilePermissions.settings(context, "files").action)
        assertEquals("package:${context.packageName}", MobilePermissions.settings(context, "files").dataString)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, MobilePermissions.settings(context, "notifications").action)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, MobilePermissions.settings(context, "accessibility").action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, MobilePermissions.settings(context, "sms_read").action)
        assertEquals("package:${context.packageName}", MobilePermissions.settings(context, "sms_read").dataString)
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test fun sessionSignatureTracksSecureGrantButAllowsOwnAccessibilityStateToChange() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val before = MobilePermissions.signature(context)
        val component = android.content.ComponentName(context, app.hermes.companion.device.CompanionAccessibilityService::class.java)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component.flattenToString())
        app.hermes.companion.device.CompanionAccessibilityService.setExplicitlyOff(context, false)
        assertEquals(before, MobilePermissions.signature(context))
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertNotEquals(before, MobilePermissions.signature(context))
    }
    @Test fun armRecoveryRequiresExplicitScreenLaneAndNeverOverridesOffOrAgentDisplay() {
        fun recover(direct: Boolean = false, target: String = "primary", backend: String = "accessibility",
            shizuku: Boolean = false, bound: Boolean = false, off: Boolean = false, grant: Boolean = true) =
            AccessibilityArmPolicy.shouldRecover(direct, target, backend, shizuku, bound, off, grant)
        assertTrue(recover())
        assertTrue(recover(backend = "hybrid"))
        assertFalse(recover(direct = true))
        assertFalse(recover(target = "agent"))
        assertFalse(recover(backend = "shizuku"))
        assertFalse(recover(backend = "hybrid", shizuku = true))
        assertFalse(recover(bound = true))
        assertFalse(recover(off = true))
        assertFalse(recover(grant = false))
    }
    @Test fun accessibilityReadinessFailsClosedDuringOffOrUncertainTeardown() {
        assertTrue(AccessibilityReadiness.allows(bound = true, explicitlyOff = false, listed = true))
        assertFalse(AccessibilityReadiness.allows(bound = false, explicitlyOff = false, listed = true))
        assertFalse(AccessibilityReadiness.allows(bound = true, explicitlyOff = true, listed = true))
        assertFalse(AccessibilityReadiness.allows(bound = true, explicitlyOff = false, listed = false))
        assertFalse(AccessibilityReadiness.allows(bound = true, explicitlyOff = false, listed = null))
    }
    @Test fun cancellingLocalAccessibilitySettingsPreservesExplicitOff() {
        var off = true
        var listed: Boolean? = false
        var restores = 0
        val approval = LocalAccessibilityApproval({ off }, { listed }, { off = false }, { off = true; restores++ })
        approval.begin()
        assertFalse("OFF is only provisional while the settings screen can bind", off)
        approval.finish()
        assertTrue(off)
        assertEquals(1, restores)
        approval.finish()
        assertEquals("Closing twice must not alter a newer approval", 1, restores)

        approval.begin()
        listed = true
        approval.finish()
        assertFalse("An observed new local enable may clear OFF", off)
    }

    @Test fun localAccessibilityApprovalFailsClosedForUnknownOrPreviouslyListedState() {
        for (before in listOf(true, null)) {
            var off = true
            var listed: Boolean? = before
            val approval = LocalAccessibilityApproval({ off }, { listed }, { off = false }, { off = true })
            approval.begin()
            listed = true
            approval.finish()
            assertTrue("An unchanged or unreadable prior state is not proof of a local enable", off)
        }
    }

    @Test fun localAccessibilityApprovalDoesNotUndoAnotherExplicitStop() {
        var off = true
        var listed: Boolean? = false
        val approval = LocalAccessibilityApproval({ off }, { listed }, { off = false }, { off = true })
        approval.begin()
        listed = true
        off = true // A separate Stop occurred while settings were open.
        approval.finish()
        assertTrue(off)
    }
    // Robolectric has no Android user storage service; that independent special grant is
    // fixed OFF while testing the real Context permission checks for separate SMS grants.
    @Implements(Environment::class)
    class NoSharedFileGrant : org.robolectric.shadows.ShadowEnvironment() {
        companion object { @JvmStatic @Implementation fun isExternalStorageManager() = false }
    }
}
