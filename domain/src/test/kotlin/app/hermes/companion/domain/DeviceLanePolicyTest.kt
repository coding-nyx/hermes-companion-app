package app.hermes.companion.domain

import app.hermes.companion.model.DeviceArm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLanePolicyTest {
    @Test
    fun ticketNeverInWsUrl() {
        val url = DeviceLanePolicy.wsUrl("http://host:9119")
        assertEquals("ws://host:9119/companion/device/ws", url)
        assertFalse(url.contains("ticket="))
        assertFalse(url.contains("token="))
        assertFalse(url.contains("?"))
    }

    @Test
    fun subprotocolCarriesTicket() {
        val header = DeviceLanePolicy.protocolHeader("tick-9")
        assertTrue(DeviceLanePolicy.hasV1(header))
        assertEquals("tick-9", DeviceLanePolicy.parseTicket(header))
        assertTrue(header.contains(DeviceLanePolicy.PROTOCOL))
        assertTrue(header.contains("hermes-mobile-control-ticket.tick-9"))
    }

    @Test
    fun stripsUnknownCapabilities() {
        val filtered = DeviceLanePolicy.filterCapabilities(listOf("device.snapshot", "device.root", "device.click"))
        assertEquals(listOf("device.snapshot", "device.click"), filtered)
    }

    @Test
    fun hostMayArmAndDisarm() {
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = true, action = "device.arm"))
        assertEquals(
            "a11y_unavailable",
            DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = false, action = "device.arm"),
        )
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = false, action = "device.disarm"))
        assertNull(DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = true, action = "device.disarm"))
    }

    @Test
    fun failClosedDisarmedAndA11y() {
        assertEquals(
            "disarmed",
            DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = true, action = "device.snapshot"),
        )
        assertEquals(
            "a11y_unavailable",
            DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = "device.snapshot"),
        )
        assertEquals(
            "stale_ref",
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.click",
                ref = "e9",
                lastRefs = setOf("e1"),
            ),
        )
        assertNull(
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.click",
                ref = "e1",
                lastRefs = setOf("e1"),
            ),
        )
    }

    @Test
    fun minimalBuiltInDenylistProtectsSettingsAuthAndPasswordManagers() {
        for (pkg in listOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.keychain",
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.onepassword.android",
            "com.x8bit.bitwarden",
            "com.kunzisoft.keepass.free",
            "com.beemdevelopment.aegis",
            "com.samsung.android.settings.foo",
        )) {
            assertTrue(pkg, DeviceLanePolicy.isProtected(pkg))
            assertEquals(
                pkg,
                "protected_package",
                DeviceLanePolicy.reject(
                    DeviceArm.ARMED,
                    a11yBound = true,
                    action = "device.open_app",
                    foregroundApp = "com.example.fixture",
                    targetPackage = pkg,
                ),
            )
        }
        for (pkg in listOf(
            "com.example.fixture",
            "org.telegram.messenger",
            "com.android.vending",
            "com.phonepe.app",
            "com.chase.sig.android",
            "com.samsung.android.spay",
        )) {
            assertFalse(pkg, DeviceLanePolicy.isProtected(pkg))
            assertNull(
                pkg,
                DeviceLanePolicy.reject(
                    DeviceArm.ARMED,
                    a11yBound = true,
                    action = "device.snapshot",
                    foregroundApp = pkg,
                ),
            )
        }
    }

    @Test
    fun customDenylistFailsClosedAndNormalizes() {
        val custom = setOf("com.mybank.app", "com.corp.*")
        assertEquals(
            "protected_package",
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.snapshot",
                foregroundApp = "com.mybank.app",
                extraProtected = custom,
            ),
        )
        assertEquals(
            "protected_package",
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.open_app",
                foregroundApp = "com.example.fixture",
                targetPackage = "com.corp.mail",
                extraProtected = custom,
            ),
        )
        assertNull(
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.snapshot",
                foregroundApp = "com.mybank.app",
            ),
        )
        assertEquals("com.mybank.app", DeviceLanePolicy.normalizePackage("  Com.MyBank.App "))
        assertEquals("com.corp.*", DeviceLanePolicy.normalizePackage("com.corp.*"))
        assertEquals("com.corp.*", DeviceLanePolicy.normalizePackage("com.corp*"))
        assertNull(DeviceLanePolicy.normalizePackage("not a package"))
        assertNull(DeviceLanePolicy.normalizePackage("nodots"))
        assertNull(DeviceLanePolicy.normalizePackage("*"))
        assertNull(DeviceLanePolicy.normalizePackage(""))
        assertTrue(DeviceLanePolicy.matchesRule("com.corp", "com.corp.*"))
        assertFalse(DeviceLanePolicy.matchesRule("com.corporate.x", "com.corp.*"))
    }

    @Test
    fun a11yEnabledParsesComponent() {
        assertTrue(DeviceLanePolicy.a11yEnabled("app.hermes.companion/.device.CompanionAccessibilityService"))
        assertFalse(DeviceLanePolicy.a11yEnabled(""))
    }
}
