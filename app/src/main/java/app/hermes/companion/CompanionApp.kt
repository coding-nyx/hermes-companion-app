package app.hermes.companion

import android.app.Application
import app.hermes.companion.data.local.CompanionDatabase
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.local.OutboxStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import app.hermes.companion.data.remote.HostClientPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class CompanionApp : Application() {
    /** One DashboardClient per host (A8.5): token, cookies and sockets never cross hosts. */
    val clients: HostClientPool = HostClientPool { DashboardClient() }
    fun clientFor(origin: String): DashboardClient = clients.forOrigin(origin)
    lateinit var sticky: StickyStore
        private set
    lateinit var cache: TranscriptCache
        private set
    lateinit var outbox: OutboxStore
        private set
    lateinit var deviceCreds: DeviceCredStore
        private set
    lateinit var operatorCreds: OperatorCredStore
        private set
    lateinit var deviceNode: DeviceNodeCoordinator
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Per-process secret stamped on PendingIntents this app creates (wake notification,
     * stay-connected notification). An intent carrying it is ours; anything else that reaches
     * MainActivity with a deep link or origin extra is external and needs user confirmation.
     */
    val launchNonce: String = java.util.UUID.randomUUID().toString()
    var watchJob: Job? = null
    var hudJob: Job? = null
    var fleetHealthJob: Job? = null
    /** When false, fleet health waits on this flow instead of spinning (A8.4). */
    val fleetHealthForeground = MutableStateFlow(true)
    /** ntfy wake subscriptions, one per host with a topic. */
    val wakeJobs: MutableMap<String, Job> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        sticky = StickyStore(this)
        val db = CompanionDatabase.create(this)
        cache = TranscriptCache(db)
        outbox = OutboxStore(db)
        deviceCreds = DeviceCredStore.encrypted(this)
        operatorCreds = OperatorCredStore.encrypted(this)
        deviceNode = DeviceNodeCoordinator(this, clients, deviceCreds, sticky, appScope)
    }

    /** Display name for a host: gateway-book name, else bare host. */
    fun hostName(origin: String?): String {
        if (origin.isNullOrBlank()) return ""
        return operatorCreds.hostName(origin)
    }
}
