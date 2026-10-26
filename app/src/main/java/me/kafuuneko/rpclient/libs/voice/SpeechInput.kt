package me.kafuuneko.rpclient.libs.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper around Android SpeechRecognizer with live partial results. */
class SpeechInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val ctx = context
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        VoiceManager.stop()
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { onError(error); onEnd() }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onFinal(t); onEnd()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onPartial(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    fun stop() { recognizer?.stopListening() }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
