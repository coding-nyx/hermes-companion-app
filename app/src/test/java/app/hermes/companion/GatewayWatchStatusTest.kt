package app.hermes.companion

import app.hermes.companion.model.GatewayHello
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GatewayWatchStatusTest {
    private val connected = CompanionState(
        origin = "https://gateway.example/assistant-a",
        activeProfileId = "developer",
        gatewayHello = GatewayHello(instanceId = "previous-handshake"),
    )

    @Test fun startingOrEndingAnOwnedWatchClearsThePreviousHandshake() {
        assertNull(connected.clearGatewayHello(connected.origin!!, "developer").gatewayHello)
    }

    @Test fun endingAnOldProfileWatchCannotClearTheNewProfilesHandshake() {
        assertSame(connected, connected.clearGatewayHello(connected.origin!!, "previous-profile"))
    }

    @Test fun endingAnOldHostWatchCannotClearTheNewProxyOwnersHandshake() {
        assertSame(connected, connected.clearGatewayHello("https://gateway.example/assistant-b", "developer"))
    }
}
