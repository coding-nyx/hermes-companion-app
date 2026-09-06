package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus

/** Live reachability of a saved gateway (A8.4). */
enum class HostHealth { UNKNOWN, ONLINE, OFFLINE, GATED }

object HostHealthMap {
    fun classify(status: DashboardStatus?): HostHealth = when {
        status == null -> HostHealth.OFFLINE
        status.authRequired -> HostHealth.GATED
        else -> HostHealth.ONLINE
    }
}
