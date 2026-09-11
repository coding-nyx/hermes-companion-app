package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm

/** Pure arming transitions. Phone is source of truth; Hermes cannot re-arm. */
object DeviceArming {
    /**
     * Arming is consent plus the kill switch; it says nothing about *which* lane can run.
     *
     * It used to refuse without an accessibility binding, which quietly made every L0 write
     * impossible on a phone where the user never granted accessibility — the exact dependency
     * the direct-API lane exists to remove. Capability is enforced per action instead:
     * [DeviceLanePolicy.reject] still answers `a11y_unavailable` for the L1 lane.
     */
    fun arm(current: DeviceArm): DeviceArm =
        if (current == DeviceArm.DISARMED) DeviceArm.ARMED else current

    fun disarm(): DeviceArm = DeviceArm.DISARMED

    fun beginExec(current: DeviceArm): DeviceArm =
        if (current == DeviceArm.ARMED) DeviceArm.EXECUTING else current

    fun endExec(current: DeviceArm): DeviceArm =
        if (current == DeviceArm.EXECUTING) DeviceArm.ARMED else current
}
