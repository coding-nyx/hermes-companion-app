package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HostHealthMapTest {
    @Test
    fun nullStatusIsOffline() {
        assertEquals(HostHealth.OFFLINE, HostHealthMap.classify(null))
    }

    @Test
    fun authRequiredIsGated() {
        assertEquals(
            HostHealth.GATED,
            HostHealthMap.classify(DashboardStatus(authRequired = true, gatewayRunning = true)),
        )
    }

    @Test
    fun reachableWithoutAuthIsOnline() {
        assertEquals(
            HostHealth.ONLINE,
            HostHealthMap.classify(DashboardStatus(authRequired = false, gatewayRunning = true)),
        )
    }
}
