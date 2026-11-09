package me.kafuuneko.rpclient.libs.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Thin wrapper around Android SpeechRecognizer with live partial results. */
class SpeechInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (Int) -> Unit,
) {
    // 使用应用上下文创建识别器，避免服务绑定跟随 Activity 生命周期，页面销毁后不再持有失效的 Activity。
    private val ctx = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /**
     * 启动一次语音识别。
     *
     * 创建识别器与开始监听可能抛异常（例如设备无识别服务、绑定失败、权限缺失），
     * 此处统一捕获并返回失败，不让异常扩散导致页面崩溃。
     *
     * @return 是否成功开始监听。
     */
    fun start(): Boolean {
        VoiceManager.stop()
        destroy()
        return try {
            // 创建识别器并挂上回调。
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onError(error); onEnd()
                }

                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    onFinal(t); onEnd()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!t.isNullOrBlank()) onPartial(t)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            // 以自由输入模式开始监听并要求返回中间结果。
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            r.startListening(intent)
            true
        } catch (e: Exception) {
            // 启动失败时释放已创建的识别器并记录异常，调用方据返回值提示用户。
            Log.e("SpeechInput", "Failed to start speech recognition", e)
            destroy()
            false
        }
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
