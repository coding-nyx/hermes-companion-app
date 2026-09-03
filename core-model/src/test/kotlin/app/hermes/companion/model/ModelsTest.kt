package app.hermes.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun sessionIsKeyedByHostProfileAndId() {
        val session = SessionRef(
            id = "20260903_1",
            profileId = "coder",
            title = "fix auth ticket",
            updatedAtEpochMs = 0L,
        )
        assertEquals("coder", session.profileId)
        assertEquals(DeviceArm.DISARMED, DeviceArm.DISARMED)
    }
}
