package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import org.junit.Assert.*
import org.junit.Test

class SecureEntryPolicyTest {
    @Test fun privateEntryBlocksPhoneDataAndArmButPermitsReadinessMetadata() {
        DeviceLanePolicy.CAPABILITIES.filterNot {
            it in setOf("device.disarm", "device.accessibility.disable", "device.status", "device.agent_screen.stop", "device.agent_screen.status")
        }.forEach { action ->
            assertEquals(action, "privacy_active", DeviceLanePolicy.reject(
                DeviceArm.ARMED, true, action, "com.example", privateEntryActive = true,
                secureEntryAction = "request",
            ))
        }
    }

    @Test fun statusAndCancelStayAvailableWhenDisarmedOrAccessibilityIsOff() {
        listOf("status", "cancel").forEach { action ->
            assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, false, "device.secure_entry",
                "com.protected", privateEntryActive = true, secureEntryAction = action,
                extraProtected = setOf("com.protected")))
        }
        listOf("device.disarm", "device.accessibility.disable").forEach { action ->
            assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, false, action, "",
                privateEntryActive = true))
        }
    }

    @Test fun requestNeedsArmAccessibilityAndFreshReferenceAndRespectsProtectedApps() {
        fun reject(arm: DeviceArm = DeviceArm.ARMED, a11y: Boolean = true,
            ref: String = "e1", protected: Set<String> = emptySet()) = DeviceLanePolicy.reject(
            arm, a11y, "device.secure_entry", "com.example", ref, setOf("e1"), "com.example",
            protected, secureEntryAction = "request",
        )
        assertEquals("disarmed", reject(arm = DeviceArm.DISARMED))
        assertEquals("a11y_unavailable", reject(a11y = false))
        assertEquals("stale_ref", reject(ref = "e2"))
        assertEquals("protected_package", reject(protected = setOf("com.example")))
        assertNull(reject())
    }
}
