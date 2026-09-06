package app.hermes.companion.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStreamPolicyTest {
    @Test
    fun forwardsOrdinaryPackages() {
        assertTrue(
            NotificationStreamPolicy.shouldForward(
                packageName = "com.telegram.messenger",
                extraProtected = emptySet(),
            ),
        )
    }

    @Test
    fun blocksBuiltInProtected() {
        assertFalse(NotificationStreamPolicy.shouldForward("com.bitwarden.mobile"))
        assertFalse(NotificationStreamPolicy.shouldForward("com.android.settings"))
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
