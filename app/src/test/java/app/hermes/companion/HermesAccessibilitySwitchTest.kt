package app.hermes.companion

import app.hermes.companion.device.HermesAccessibilitySwitch
import org.junit.Assert.*
import org.junit.Test

class HermesAccessibilitySwitchTest {
    @Test fun connectedServiceDisablesItselfWithoutSecureSettings() {
        var selfCalls = 0
        var secureCalls = 0
        val control = HermesAccessibilitySwitch({ true }, { selfCalls++ }, { secureCalls++; false })
        assertTrue(control.requestOff())
        assertEquals(1, selfCalls)
        assertEquals(0, secureCalls)
    }

    @Test fun disconnectedServiceUsesSecureSettingsFallback() {
        var secureCalls = 0
        val control = HermesAccessibilitySwitch({ false }, { error("Not connected") }, { secureCalls++; true })
        assertTrue(control.requestOff())
        assertEquals(1, secureCalls)
    }

    @Test fun selfDisableFailureFallsBackWithoutClaimingSuccessOnDenial() {
        val control = HermesAccessibilitySwitch({ true }, { throw SecurityException("disconnected") }, { false })
        assertFalse(control.requestOff())
    }

    @Test fun secureSettingsRuntimeFailureRequiresManualFallback() {
        assertFalse(HermesAccessibilitySwitch({ false }, {}, { throw IllegalStateException("provider unavailable") }).requestOff())
    }

    @Test fun noConnectionAndNoPermissionRequiresManualSettings() {
        assertFalse(HermesAccessibilitySwitch({ false }, { error("Not connected") }, { false }).requestOff())
    }
}
