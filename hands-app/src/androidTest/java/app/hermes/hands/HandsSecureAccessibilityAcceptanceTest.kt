package app.hermes.hands

import android.content.ComponentName
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.device.AccessibilitySettingsToggle
import app.hermes.companion.device.CompanionAccessibilityService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in fixture changes only Hands' component and restores its original state. */
@RunWith(AndroidJUnit4::class)
class HandsSecureAccessibilityAcceptanceTest : HandsAcceptanceSession() {
    override fun enabled(): Boolean {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("hands_secure_settings_acceptance") != "true") return false
        val app = instrumentation.targetContext.applicationContext as HandsApplication
        return !app.runtime.notifications.state.value.enabled
    }

    @Test fun explicitEnableStopAndArmedRecoveryPreserveOtherServices() {
        assumeTrue("Grant Hands WRITE_SECURE_SETTINGS locally before this opt-in fixture",
            CompanionAccessibilityService.canWriteSecureSettings(context))
        val resolver = context.contentResolver
        val global = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        assumeTrue("Global Accessibility must already be on; this test never turns it off", global == 1)
        val own = ComponentName(context, CompanionAccessibilityService::class.java)
        fun entries() = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter(String::isNotBlank)
        fun others() = entries().filter { ComponentName.unflattenFromString(it) != own }.toSet()
        val originalOthers = others()
        val originalListed = CompanionAccessibilityService.listed(context) == true
        val originalOff = CompanionAccessibilityService.explicitlyOff(context)
        assumeFalse("An inconsistent existing explicit-OFF/listed state cannot be restored safely", originalOff && originalListed)
        val toggle = AccessibilitySettingsToggle.forContext(context)
        try {
            instrumentation.runOnMainSync { runtime.configure(backend = "accessibility", target = "primary"); runtime.approve() }
            requireOk(command("device.arm", JSONObject().put("duration_seconds", 300).put("direct_only", true)))
            requireOk(command("device.accessibility.disable"))
            assertTrue(CompanionAccessibilityService.explicitlyOff(context))
            await("Hands accessibility to turn off", 8_000) { CompanionAccessibilityService.listed(context) == false }
            instrumentation.runOnMainSync { runtime.approve() }
            val direct = requireOk(command("device.arm", JSONObject().put("duration_seconds", 300).put("direct_only", true))).getString("lease_id")
            val enabled = requireOk(command("device.accessibility.enable", holder = direct))
            assertTrue(enabled.getBoolean("enabled_automatically"))
            assertTrue(enabled.getBoolean("can_write_secure_settings"))
            assertFalse(enabled.getBoolean("explicitly_off"))
            assertFalse(enabled.getBoolean("settings_opened"))
            await("Hands accessibility to bind", 8_000) { CompanionAccessibilityService.bound }
            assertEquals(originalOthers, others())

            requireOk(command("device.accessibility.disable"))
            await("Hands accessibility to stop", 8_000) { !CompanionAccessibilityService.bound && CompanionAccessibilityService.listed(context) == false }
            instrumentation.runOnMainSync { runtime.approve() }
            val offArm = requireOk(command("device.arm", JSONObject().put("duration_seconds", 300)))
            assertEquals("l0", offArm.getString("lanes"))
            assertTrue(CompanionAccessibilityService.explicitlyOff(context))
            assertEquals(false, CompanionAccessibilityService.listed(context))
            requireOk(command("device.disarm", holder = offArm.getString("lease_id")))

            // This represents the operator explicitly clearing the local OFF preference.
            CompanionAccessibilityService.setExplicitlyOff(context, false)
            instrumentation.runOnMainSync { runtime.approve() }
            val recovered = requireOk(command("device.arm", JSONObject().put("duration_seconds", 300)))
            assertEquals("l0+l1", recovered.getString("lanes"))
            assertTrue(CompanionAccessibilityService.bound)
            assertEquals(originalOthers, others())
            requireOk(command("device.disarm", holder = recovered.getString("lease_id")))
        } finally {
            runtime.disarm()
            // Keep the prior global switch on and never change another service component.
            CompanionAccessibilityService.setExplicitlyOff(context, false)
            assertTrue("Restore only the original Hands component", toggle.setEnabled(originalListed))
            CompanionAccessibilityService.setExplicitlyOff(context, originalOff)
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != global) {
                assertEquals("Do not alter a concurrently changed service list", originalOthers, others())
                assertTrue(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, global))
            }
            assertEquals(originalOthers, others())
            assertEquals(global, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
        }
    }
}
