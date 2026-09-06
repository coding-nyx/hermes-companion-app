package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStreamPolicyTest {
    @Test
    fun forwardsOrdinaryPackages() {
        assertTrue(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.example.mail",
                extraProtected = emptySet(),
            ),
        )
    }

    @Test
    fun doesNotBlockFormerBuiltInsWithoutCustom() {
        assertTrue(NotificationStreamPolicy.shouldForward("com.bitwarden.mobile"))
        assertTrue(NotificationStreamPolicy.shouldForward("com.android.settings"))
    }

    @Test
    fun blocksBuiltinTelegramStreamSuppress() {
        for (pkg in NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES) {
            assertFalse(pkg, NotificationStreamPolicy.shouldForward(pkg))
            assertTrue(pkg, NotificationStreamPolicy.isStreamSuppressed(pkg))
        }
        assertFalse(NotificationStreamPolicy.shouldForward("org.telegram.messenger.web"))
        assertFalse(NotificationStreamPolicy.shouldForward("org.thunderdog.challegram"))
    }

    @Test
    fun telegramStreamSuppressDoesNotImplyHandsProtect() {
        for (pkg in NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES) {
            assertFalse(pkg, DeviceLanePolicy.isProtected(pkg))
        }
        assertTrue(DeviceLanePolicy.PROTECTED_PACKAGES.isEmpty())
    }

    @Test
    fun streamSuppressCountMatchesBuiltinSet() {
        assertEquals(4, NotificationStreamPolicy.STREAM_SUPPRESS_PACKAGES.size)
    }

    @Test
    fun blocksCustomProtectedPackages() {
        assertFalse(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.bitwarden.mobile",
                extraProtected = setOf("com.bitwarden.mobile"),
            ),
        )
        assertFalse(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.android.settings",
                extraProtected = setOf("com.android.settings"),
            ),
        )
    }

    @Test
    fun blocksCustomProtected() {
        assertFalse(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.bank.app",
                extraProtected = setOf("com.bank.app"),
            ),
        )
    }

    @Test
    fun blocksSelfPackageAndChannels() {
        assertFalse(
            NotificationStreamPolicy.shouldForward(
                packageName = "app.hermes.companion",
                channelId = "stay",
            ),
        )
        assertFalse(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.example.app",
                channelId = "hands",
            ),
        )
    }
}
