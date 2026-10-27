package app.hermes.companion.voice

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hermes.companion.CompanionApp
import app.hermes.companion.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/** Started by an operator gesture while the activity is visible; never sticky or boot-started. */
class VoiceCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var watching = false
    private var ownedCall = ""
    private var latestStartId = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val app = application as CompanionApp
        val voice = app.voiceController
        if (intent == null || voice == null || intent.getStringExtra("nonce") != app.launchNonce) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent.action) {
            START -> {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice conversations", NotificationManager.IMPORTANCE_LOW))
                try {
                    startForeground(ID, notification(app), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } catch (e: SecurityException) {
                    app.conversationState?.update { it.copy(error = "Open Hermes and allow microphone access before starting voice.") }
                    stopSelf(startId); return START_NOT_STICKY
                }
                app.stopService(Intent(app, WakeWordService::class.java))
                app.conversationState?.update { it.copy(awakeOnVoice = false) }
                voice.start()
                ownedCall = voice.callId
                if (!watching) {
                    watching = true
                    wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice").also { it.setReferenceCounted(false) }
                    scope.launch {
                        while (isActive) { wake?.acquire(15 * 60 * 1000L); delay(10 * 60 * 1000L) }
                    }
                    scope.launch {
                        requireNotNull(app.conversationState).collectLatest {
                            if (!voice.isActive) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStartId) }
                            else manager.notify(ID, notification(app))
                        }
                    }
                }
            }
            MUTE, END -> if (intent.getStringExtra("call") == voice.callId) {
                if (intent.action == MUTE) voice.setMicrophoneMuted(!voice.isMicrophoneMuted) else { voice.stopStream(); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(app: CompanionApp): Notification {
        val voice = app.voiceController
        val muted = voice?.isMicrophoneMuted == true
        fun action(name: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, VoiceCallService::class.java).setAction(name).putExtra("nonce", app.launchNonce).putExtra("call", voice?.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 700, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hermes voice").setContentText(if (muted) "Microphone muted" else "Voice conversation active")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Hermes voice active").build())
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (muted) "Resume mic" else "Mute", action(MUTE, 701))
            .addAction(0, "End", action(END, 702)).build()
    }
    override fun onDestroy() {
        scope.cancel()
        (application as CompanionApp).voiceController?.let { if (it.callId == ownedCall) it.stopStream() }
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }
    companion object {
        const val START = "companion.voice.START"
        const val MUTE = "companion.voice.MUTE"
        const val END = "companion.voice.END"
        private const val ID = 709
        private const val CHANNEL = "companion.voice.call"
        fun start(app: CompanionApp) {
            ContextCompat.startForegroundService(app, Intent(app, VoiceCallService::class.java).setAction(START).putExtra("nonce", app.launchNonce))
        }
    }
}
