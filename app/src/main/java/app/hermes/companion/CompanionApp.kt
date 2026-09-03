package app.hermes.companion

import android.app.Application
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.DashboardClient

class CompanionApp : Application() {
    lateinit var dashboard: DashboardClient
        private set
    lateinit var sticky: StickyStore
        private set

    override fun onCreate() {
        super.onCreate()
        dashboard = DashboardClient()
        sticky = StickyStore(this)
    }
}
