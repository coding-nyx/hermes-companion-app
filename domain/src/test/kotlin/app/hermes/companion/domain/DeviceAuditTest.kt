package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuditTest {
    @Test
    fun typeNeverKeepsText() {
        val row = DeviceAudit.row(
            action = "device.type",
            app = "com.example.fixture",
            ok = true,
            detail = "hunter2 secret",
        )
        assertEquals("device.type", row.action)
        assertFalse(row.code.contains("hunter2"))
        assertEquals("", DeviceAudit.redact("device.type", "password123"))
    }

    @Test
    fun hintOnlyWhenArmed() {
        assertNull(DeviceAudit.hint(paired = true, armed = false))
        assertNull(DeviceAudit.hint(paired = false, armed = true))
        assertTrue(DeviceAudit.hint(paired = true, armed = true)!!.contains("ARMED"))
    }

    @Test
    fun ringKeepsLast() {
        val rows = (1..60).fold(emptyList<DeviceAuditRow>()) { acc, i ->
            DeviceAudit.append(acc, DeviceAudit.row("device.noop", "app", true, atMs = i.toLong()))
        }
        assertEquals(DeviceAudit.KEEP, rows.size)
        assertEquals(11L, rows.first().atMs)
        assertEquals(60L, rows.last().atMs)
    }
}
