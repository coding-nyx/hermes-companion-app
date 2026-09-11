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
    fun l0PassiveRunsWhileDisarmedAndWithoutA11y() {
        // Read-only L0 is a health-check surface: it must work on a cold, unarmed phone
        // whose accessibility service was never granted.
        for (action in DeviceLanePolicy.L0_PASSIVE) {
            assertNull(
                "expected $action to be allowed while disarmed",
                DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = false, action = action),
            )
        }
    }

    @Test
    fun l0DestructiveStillNeedsArming() {
        val destructive = DeviceLanePolicy.L0_NO_A11Y - DeviceLanePolicy.L0_PASSIVE
        assertTrue("expected some destructive L0 actions", destructive.isNotEmpty())
        for (action in destructive) {
            assertEquals(
                "expected $action to require arming",
                "disarmed",
                DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = true, action = action),
            )
        }
    }

    @Test
    fun l0DoesNotNeedA11yWhenArmed() {
        for (action in DeviceLanePolicy.L0_NO_A11Y) {
            assertNull(
                "expected $action to bypass the a11y gate",
                DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = action),
            )
        }
        // The L1 lane must keep the gate.
        assertEquals(
            "a11y_unavailable",
            DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = "device.snapshot"),
        )
    }

    @Test
    fun l0IgnoresProtectedForegroundPackage() {
        // L0 targets a stream or a session handle, never the foreground app, so a protected
        // app on screen must not block a volume change.
        val protectedPkg = "com.example.banking"
        assertNull(
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.audio.volume.set",
                foregroundApp = protectedPkg,
                extraProtected = setOf(protectedPkg),
            ),
        )
        assertEquals(
            "protected_package",
            DeviceLanePolicy.reject(
                DeviceArm.ARMED,
                a11yBound = true,
                action = "device.snapshot",
                foregroundApp = protectedPkg,
                extraProtected = setOf(protectedPkg),
            ),
        )
    }

    @Test
    fun privateHistoryReadsRequireArming() {
        // sms/call-log/contacts are reads, but arming is the user's consent and one sms.list
        // discloses the whole message history — so they must not be passive.
        for (action in listOf("device.sms.list", "device.calls.log", "device.contacts.search")) {
            assertFalse("$action must not be passive", action in DeviceLanePolicy.L0_PASSIVE)
            assertEquals(
                "$action must require arming",
                "disarmed",
                DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = true, action = action),
            )
        }
    }

    @Test
    fun messagingToolsRunWithoutAccessibility() {
        for (action in listOf(
            "device.sms.list", "device.sms.send", "device.sms.compose",
            "device.calls.log", "device.calls.dial", "device.calls.place",
            "device.contacts.search",
        )) {
            assertTrue("$action should be L0", action in DeviceLanePolicy.L0_NO_A11Y)
            assertNull(
                "$action should run in an armed L0-only session",
                DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = action),
            )
        }
    }

    @Test
    fun l0SetsAreConsistent() {
        assertTrue(
            "L0_PASSIVE must be a subset of L0_NO_A11Y",
            DeviceLanePolicy.L0_NO_A11Y.containsAll(DeviceLanePolicy.L0_PASSIVE),
        )
        assertTrue(
            "every L0 action must be a granted capability",
            DeviceLanePolicy.CAPABILITIES.containsAll(DeviceLanePolicy.L0_NO_A11Y),
        )
    }

    @Test
    fun armingNeverRequiresAccessibility() {
        // The whole point of the L0 lane is to work without the accessibility grant; gating
        // `arm` on it meant no destructive L0 action could ever run on such a phone.
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = false, action = "device.arm"))
        for (action in DeviceLanePolicy.L0_NO_A11Y) {
            assertNull(
                "expected $action to run in an armed L0-only session",
                DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = action),
            )
        }
    }

    @Test
    fun l1StillRefusesInAnL0OnlySession() {
        // Capability is per action: arming does not conjure an accessibility service.
        for (action in listOf("device.snapshot", "device.click", "device.type", "device.swipe", "device.scroll", "device.press")) {
            assertEquals(
                "expected $action to still need a11y",
                "a11y_unavailable",
                DeviceLanePolicy.reject(DeviceArm.ARMED, a11yBound = false, action = action),
            )
        }
    }

    @Test
    fun hostMayArmAndDisarm() {
        assertNull(DeviceLanePolicy.reject(DeviceArm.DISARMED, a11yBound = true, action = "device.arm"))
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
    fun builtInDenylistIsEmptyFormerDefaultsUnprotectedWithoutCustom() {
        assertTrue(DeviceLanePolicy.PROTECTED_PACKAGES.isEmpty())
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
            "com.example.fixture",
            "org.telegram.messenger",
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
    fun customRulesProtectSettingsAndBitwarden() {
        val custom = setOf("com.android.settings", "com.x8bit.bitwarden", "com.samsung.android.settings.*")
        for (pkg in listOf(
            "com.android.settings",
            "com.x8bit.bitwarden",
            "com.samsung.android.settings.foo",
        )) {
            assertTrue(pkg, DeviceLanePolicy.isProtected(pkg, custom))
            assertEquals(
                pkg,
                "protected_package",
                DeviceLanePolicy.reject(
                    DeviceArm.ARMED,
                    a11yBound = true,
                    action = "device.open_app",
                    foregroundApp = "com.example.fixture",
                    targetPackage = pkg,
                    extraProtected = custom,
                ),
            )
        }
        assertFalse(DeviceLanePolicy.isProtected("org.telegram.messenger", custom))
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
