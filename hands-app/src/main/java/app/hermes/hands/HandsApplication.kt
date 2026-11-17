package app.hermes.hands

import android.app.Application
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.device.HandsBridge
import app.hermes.hands.engine.ShizukuController

class HandsApplication : Application() {
    lateinit var runtime: HandsRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        DevicePrivacy.initialize(this)
        runtime = HandsRuntime(this, ShizukuController(this))
        HandsBridge.onDisarm = { runtime.disarm() }
        app.hermes.companion.device.StopStreamReceiver.onStopStream = { runtime.notifications.close() }
    }
}
