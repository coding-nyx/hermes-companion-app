package app.hermes.companion.voice

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Foreground classic voice ownership. Future Telecom calls need their own Telecom audio owner. */
class VoiceAudioSession(private val context: Context) : VoiceAudioSessionControl {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var receiver: BroadcastReceiver? = null
    private var ownsMode = false
    private var selectedRoute = false

    override fun acquire(onLost: () -> Unit): Boolean {
        if (request != null) return true
        // A telephone/VoIP call already owns audio. Do not override its mode or route.
        if (manager.mode != AudioManager.MODE_NORMAL) return false
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (request != null && change < 0) onLost()
            }, Handler(Looper.getMainLooper()))
            .build()
        if (manager.requestAudioFocus(next) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = next
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsMode = true
            val noisy = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && request != null) onLost()
                }
            }
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = noisy
            return true
        } catch (e: RuntimeException) {
            release()
            return false
        }
    }

    override fun useSpeaker(enabled: Boolean): Boolean {
        if (request == null) return false
        if (!enabled) {
            if (selectedRoute) manager.clearCommunicationDevice()
            selectedRoute = false
            return true
        }
        val speaker = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return false
        return manager.setCommunicationDevice(speaker).also { if (it) selectedRoute = true }
    }

    override fun release() {
        val old = request ?: return
        request = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (selectedRoute) runCatching { manager.clearCommunicationDevice() }
        selectedRoute = false
        // MODE_NORMAL releases this process's mode request; it does not restore another app's mode.
        if (ownsMode) runCatching { manager.mode = AudioManager.MODE_NORMAL }
        ownsMode = false
        manager.abandonAudioFocusRequest(old)
    }

    companion object {
        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}
