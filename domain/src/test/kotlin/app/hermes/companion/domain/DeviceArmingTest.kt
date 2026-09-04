package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceArmingTest {
    @Test
    fun cannotArmWithoutAccessibility() {
        assertEquals(DeviceArm.DISARMED, DeviceArming.arm(DeviceArm.DISARMED, a11yBound = false))
    }

    @Test
    fun overlayDoesNotGateArm() {
        assertEquals(DeviceArm.ARMED, DeviceArming.arm(DeviceArm.DISARMED, a11yBound = true))
    }

    @Test
    fun armThenExecThenDisarm() {
        val armed = DeviceArming.arm(DeviceArm.DISARMED, a11yBound = true)
        assertEquals(DeviceArm.ARMED, armed)
        val exec = DeviceArming.beginExec(armed)
        assertEquals(DeviceArm.EXECUTING, exec)
        assertEquals(DeviceArm.ARMED, DeviceArming.endExec(exec))
        assertEquals(DeviceArm.DISARMED, DeviceArming.disarm())
    }
}
