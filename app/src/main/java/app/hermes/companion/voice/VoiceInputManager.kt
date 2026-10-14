package app.hermes.companion.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceInputManager(private val context: Context) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L

    override fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onErrorCode: ((Int) -> Unit)?,
        onStateChange: (Boolean) -> Unit,
        onActivity: () -> Unit,
        onProcessing: () -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorCode?.invoke(-1)
            onError("Speech recognition not available on this device")
            return
        }

        stopListening()
        val current = generation

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        val googleService = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        )
        val hasGoogleService = runCatching {
            val queryIntent = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(googleService)
            context.packageManager.queryIntentServices(queryIntent, 0).isNotEmpty()
        }.getOrDefault(false)

        val speechRecognizer = when {
            hasGoogleService -> runCatching { SpeechRecognizer.createSpeechRecognizer(context, googleService) }.getOrNull()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            else -> null
        } ?: SpeechRecognizer.createSpeechRecognizer(context)

        recognizer = speechRecognizer.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (current == generation) onStateChange(true) }
                override fun onBeginningOfSpeech() { if (current == generation) onActivity() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (current == generation) { onStateChange(false); onProcessing() } }
                override fun onError(error: Int) {
                    if (current != generation) return
                    onStateChange(false)
                    onErrorCode?.invoke(error)
                    onError(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No speech recognized. Try again after listening starts."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone recording failed"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition network failed. Check your connection and try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
                        else -> "Speech recognition failed ($error)"
                    })
                }

                override fun onResults(results: Bundle?) {
                    if (current != generation) return
                    onStateChange(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (current != generation) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        onPartial(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(intent)
        }
    }

    override fun stopListening() {
        generation++
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
