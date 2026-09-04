package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.HudState
import app.hermes.companion.model.PlatformStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayHudMapTest {
    @Test
    fun nullStatusIsAllOff() {
        val hud = GatewayHudMap.from(null)
        assertEquals(HudState.OFF, hud.gateway)
        assertEquals(HudState.OFF, hud.telegram)
        assertEquals(HudState.OFF, hud.discord)
        assertEquals(HudState.OFF, hud.api)
    }

    @Test
    fun runningGatewayLightsGwAndApi() {
        val hud = GatewayHudMap.from(
            DashboardStatus(authRequired = false, gatewayRunning = true, gatewayState = "running"),
        )
        assertEquals(HudState.ON, hud.gateway)
        assertEquals(HudState.ON, hud.api)
        assertEquals(HudState.OFF, hud.telegram)
        assertEquals(HudState.OFF, hud.discord)
    }

    @Test
    fun startingGatewayIsDegraded() {
        val hud = GatewayHudMap.from(
            DashboardStatus(authRequired = false, gatewayRunning = false, gatewayState = "starting"),
        )
        assertEquals(HudState.DEGRADED, hud.gateway)
    }

    @Test
    fun platformsDriveTgAndDc() {
        val hud = GatewayHudMap.from(
            DashboardStatus(
                authRequired = false,
                gatewayRunning = true,
                platforms = listOf(
                    PlatformStatus("coder:telegram", "connected"),
                    PlatformStatus("discord", "disconnected", error = "no token"),
                ),
            ),
        )
        assertEquals(HudState.ON, hud.telegram)
        assertEquals(HudState.DEGRADED, hud.discord)
    }

    @Test
    fun memoryPressureDegradesApi() {
        val hud = GatewayHudMap.from(
            DashboardStatus(authRequired = false, gatewayRunning = true, memoryPressure = "critical"),
        )
        assertEquals(HudState.DEGRADED, hud.api)
        assertEquals(HudState.ON, hud.gateway)
    }
}
