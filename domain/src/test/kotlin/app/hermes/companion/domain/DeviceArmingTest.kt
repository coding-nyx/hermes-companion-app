package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceArmingTest {
    @Test
    fun armsWithoutAccessibilitySoTheL0LaneIsUsable() {
        // Arming is consent + kill switch, not a capability claim. Refusing here made every
        // L0 write impossible on a phone without the accessibility grant.
        assertEquals(DeviceArm.ARMED, DeviceArming.arm(DeviceArm.DISARMED))
    }

    @Test
    fun armIsIdempotent() {
        assertEquals(DeviceArm.ARMED, DeviceArming.arm(DeviceArm.ARMED))
        assertEquals(DeviceArm.EXECUTING, DeviceArming.arm(DeviceArm.EXECUTING))
    }

    @Test
    fun armThenExecThenDisarm() {
        val armed = DeviceArming.arm(DeviceArm.DISARMED)
        assertEquals(DeviceArm.ARMED, armed)
        val exec = DeviceArming.beginExec(armed)
        assertEquals(DeviceArm.EXECUTING, exec)
        assertEquals(DeviceArm.ARMED, DeviceArming.endExec(exec))
        assertEquals(DeviceArm.DISARMED, DeviceArming.disarm())
    }
}
