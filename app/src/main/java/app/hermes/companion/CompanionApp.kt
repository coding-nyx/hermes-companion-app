package app.hermes.companion

import android.app.Application
import app.hermes.companion.data.local.CompanionDatabase
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.OperatorCredStore
import app.hermes.companion.data.local.OutboxStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.local.TranscriptCache
import app.hermes.companion.data.remote.DashboardClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

class CompanionApp : Application() {
    lateinit var dashboard: DashboardClient
        private set
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
    var wakeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dashboard = DashboardClient()
        sticky = StickyStore(this)
        val db = CompanionDatabase.create(this)
        cache = TranscriptCache(db)
        outbox = OutboxStore(db)
        deviceCreds = DeviceCredStore.encrypted(this)
        operatorCreds = OperatorCredStore.encrypted(this)
        deviceNode = DeviceNodeCoordinator(this, dashboard, deviceCreds, sticky, appScope)
    }
}
