package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.GatewayHud
import app.hermes.companion.model.HudState
import app.hermes.companion.model.PlatformStatus

object GatewayHudMap {
    private val onStates = setOf("connected", "running", "online", "ready", "ok", "live")
    private val degradedStates = setOf(
        "starting", "connecting", "retry", "degraded", "error", "failed",
        "startup_failed", "timeout", "crashed",
    )

    fun from(status: DashboardStatus?): GatewayHud {
        if (status == null) return GatewayHud()
        return GatewayHud(
            gateway = gatewayDot(status),
            telegram = platformDot(status, "telegram", "tg"),
            discord = platformDot(status, "discord", "dc"),
            api = apiDot(status),
        )
    }

    private fun gatewayDot(status: DashboardStatus): HudState {
        val state = status.gatewayState.lowercase()
        if (state in degradedStates) return HudState.DEGRADED
        if (status.gatewayRunning || state in onStates) return HudState.ON
        return HudState.OFF
    }

    private fun platformDot(status: DashboardStatus, vararg names: String): HudState {
        val hits = status.platforms.filter { platform -> names.any { matches(platform.name, it) } }
        if (hits.isEmpty()) return HudState.OFF
        return fold(hits.map { platformState(it) })
    }

    private fun apiDot(status: DashboardStatus): HudState {
        val pressure = listOf(status.memoryPressure, status.diskPressure).map { it.lowercase() }
        if (pressure.any { it == "critical" || it == "elevated" }) return HudState.DEGRADED
        return HudState.ON
    }

    private fun platformState(platform: PlatformStatus): HudState {
        val state = platform.state.lowercase()
        if (state in degradedStates || platform.error.isNotBlank()) {
            return if (state in onStates && platform.error.isBlank()) HudState.ON else HudState.DEGRADED
        }
        if (state in onStates) return HudState.ON
        return HudState.OFF
    }

    private fun fold(states: List<HudState>): HudState = when {
        states.any { it == HudState.DEGRADED } -> HudState.DEGRADED
        states.any { it == HudState.ON } -> HudState.ON
        else -> HudState.OFF
    }

    private fun matches(platformName: String, needle: String): Boolean {
        val name = platformName.lowercase()
        val key = needle.lowercase()
        return name == key || name.endsWith(":$key")
    }
}
