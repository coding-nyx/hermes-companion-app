package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm

/** Pure arming transitions. Phone is source of truth; Hermes cannot re-arm. */
object DeviceArming {
    fun arm(current: DeviceArm, a11yBound: Boolean): DeviceArm {
        if (!a11yBound) return DeviceArm.DISARMED
        return if (current == DeviceArm.DISARMED) DeviceArm.ARMED else current
    }

    fun disarm(): DeviceArm = DeviceArm.DISARMED

    fun beginExec(current: DeviceArm): DeviceArm =
        if (current == DeviceArm.ARMED) DeviceArm.EXECUTING else current

    fun endExec(current: DeviceArm): DeviceArm =
        if (current == DeviceArm.EXECUTING) DeviceArm.ARMED else current
}
