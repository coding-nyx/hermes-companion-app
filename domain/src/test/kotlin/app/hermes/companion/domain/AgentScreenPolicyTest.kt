package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import org.junit.Assert.*
import org.junit.Test

class AgentScreenPolicyTest {
    @Test fun statusIsPassiveAndStartRequiresArmingWithoutPrimaryAccessibility() {
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, false, "device.agent_screen.status"))
        assertEquals("disarmed", DeviceLanePolicy.reject(DeviceArm.DISARMED, false, "device.agent_screen.start"))
        assertNull(DeviceLanePolicy.reject(DeviceArm.ARMED, false, "device.agent_screen.start",
            foregroundApp = "com.private.app", extraProtected = setOf("com.private.app")))
    }

    @Test fun stopRemainsAvailableDuringPrivacyAndDisarmedState() {
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, false, "device.agent_screen.stop", privateEntryActive = true))
    }
}
