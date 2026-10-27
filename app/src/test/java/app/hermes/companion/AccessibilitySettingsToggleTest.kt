package app.hermes.companion

import android.app.Application
import org.robolectric.annotation.Config
import android.content.ComponentName
import app.hermes.companion.device.AccessibilitySettingsToggle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccessibilitySettingsToggleTest {
    private val hermes = ComponentName("app.hermes.companion", "app.hermes.companion.device.CompanionAccessibilityService")
    private val full = hermes.flattenToString()
    private val short = "app.hermes.companion/.device.CompanionAccessibilityService"
    private val other = "com.reader/.TalkBack"
    private var services = ""
    private var permission = true
    private var writes = 0
    private var globalEnables = 0
    private var writeOk = true
    private var globalOk = true
    private var denyWrite = false
    private val toggle get() = AccessibilitySettingsToggle(hermes, { permission }, { services }, {
        writes++
        if (denyWrite) throw SecurityException("revoked")
        if (writeOk) services = it
        writeOk
    }, { globalEnables++; globalOk })

    @Test fun actualStateReadNormalizesAndFailureIsUnknownNotOff() {
        services = "$other:$short"
        assertEquals(true, toggle.isListed())
        services = other
        assertEquals(false, toggle.isListed())
        val deniedRead = AccessibilitySettingsToggle(hermes, { false }, { throw SecurityException("denied") }, { false }, { false })
        assertNull(deniedRead.isListed())
    }

    @Test fun enablePreservesOtherServicesAndNormalizesHermesOnly() {
        services = "$other:$short:$full:invalid-entry"
        assertTrue(toggle.setEnabled(true))
        assertEquals("$other:invalid-entry:$full", services)
        assertEquals(1, globalEnables)
    }

    @Test fun disarmRemovesBothFormsWithoutChangingOtherServicesOrGlobalFlag() {
        services = "$other:$short:$full:invalid-entry"
        assertTrue(toggle.setEnabled(false))
        assertEquals("$other:invalid-entry", services)
        assertEquals(0, globalEnables)
    }

    @Test fun lastServiceRemovalNeverWritesGlobalOff() {
        services = short
        assertTrue(toggle.setEnabled(false))
        assertEquals("", services)
        assertEquals(0, globalEnables)
    }

    @Test fun deniedPermissionDoesNotReadOrWriteSettings() {
        permission = false
        services = other
        assertFalse(toggle.setEnabled(true))
        assertFalse(toggle.setEnabled(false))
        assertEquals(other, services)
        assertEquals(0, writes)
        assertEquals(0, globalEnables)
    }

    @Test fun failedWriteAndPermissionRevocationAreNotSuccess() {
        writeOk = false
        assertFalse(toggle.setEnabled(true))
        assertEquals(0, globalEnables)
        denyWrite = true
        assertFalse(toggle.setEnabled(true))
    }

    @Test fun globalEnableFailureIsNotSuccessEvenIfListWriteSucceeded() {
        globalOk = false
        assertFalse(toggle.setEnabled(true))
        assertEquals(full, services)
        assertTrue(toggle.setEnabled(false))
        assertEquals("", services)
    }

    @Test fun serviceAlreadyListedStillRepairsGlobalEnableAndDisableIsIdempotent() {
        services = full
        assertTrue(toggle.setEnabled(true))
        assertEquals(0, writes)
        assertEquals(1, globalEnables)
        assertTrue(toggle.setEnabled(false))
        val count = writes
        assertTrue(toggle.setEnabled(false))
        assertEquals(count, writes)
    }
}
