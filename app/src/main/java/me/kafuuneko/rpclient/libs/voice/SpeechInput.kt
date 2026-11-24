package me.kafuuneko.rpclient.libs.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android SpeechRecognizer with live partial results.
 *
 * Must be created and used on the main thread (SpeechRecognizer requirement).
 */
class SpeechInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (SpeechError) -> Unit,
) {
    // 使用应用上下文创建识别器，避免服务绑定跟随 Activity 生命周期，页面销毁后不再持有失效的 Activity。
    private val ctx = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    // 用户主动停止后识别服务常回报 ERROR_CLIENT，此时不应提示错误。
    private var userStopped = false

    // 识别服务一次都没进入 onReadyForSpeech 就报错，通常是服务本身不可用，适合退回系统语音输入界面。
    private var gotReady = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** 构造识别请求；系统语音输入界面（回退方案）也复用同一组参数。 */
    fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // 部分三星/谷歌识别服务未指定语言时会回报语言不可用。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

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
        userStopped = false
        gotReady = false
        return try {
            // 创建识别器并挂上回调。
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    gotReady = true
                }

                override fun onBeginningOfSpeech() {
                    gotReady = true
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // 识别器已失效：先释放，再按错误类型通知调用方。
                    if (recognizer !== r) return
                    destroy()
                    val mapped = SpeechError.from(error, gotReady, userStopped)
                    // 显式调用构造参数回调；直接写 onError(error) 会解析为本方法自身，导致无限递归崩溃。
                    if (mapped != null) this@SpeechInput.onError(mapped)
                    onEnd()
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== r) return
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    destroy()
                    if (t.isBlank() && !userStopped) this@SpeechInput.onError(SpeechError.NoMatch)
                    onFinal(t); onEnd()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer !== r) return
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!t.isNullOrBlank()) onPartial(t)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            // 以自由输入模式开始监听并要求返回中间结果。
            r.startListening(recognizerIntent())
            true
        } catch (e: Exception) {
            // 启动失败时释放已创建的识别器并记录异常，调用方据返回值提示用户。
            Log.e("SpeechInput", "Failed to start speech recognition", e)
            destroy()
            false
        }
    }

    /** 用户点击停止：结束录音并等待最终结果。 */
    fun stop() {
        userStopped = true
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.destroy() }
    }
}

/** 识别失败类型，决定界面提示文案以及是否退回系统语音输入界面。 */
sealed class SpeechError(val message: String, val fallbackToSystemUi: Boolean = false) {
    data object NoMatch : SpeechError("Didn't catch that. Tap the mic and try again.")
    data object NoPermission : SpeechError("Microphone permission is off for this app.")
    data object Network : SpeechError("Speech recognition needs a network connection right now.")
    data object Busy : SpeechError("Speech recognizer is busy. Opening the system voice input…", true)
    data object ServiceUnavailable :
        SpeechError("Speech service didn't respond. Opening the system voice input…", true)

    data object Language :
        SpeechError("Speech language isn't downloaded. Opening the system voice input…", true)

    data object Audio : SpeechError("Couldn't record audio. Another app may be using the mic.")
    data class Other(val code: Int) : SpeechError("Speech recognition error ($code).")

    companion object {
        // Android 12+ 新增的错误码，按数值比较以兼容 minSdk 26。
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private const val ERROR_CANNOT_CHECK_SUPPORT = 14

        /**
         * 把 SpeechRecognizer 错误码映射为提示类型。
         *
         * @param gotReady 识别服务是否已进入监听状态
         * @param userStopped 是否由用户主动停止
         * @return null 表示无需提示（例如用户主动停止后的 ERROR_CLIENT）
         */
        fun from(code: Int, gotReady: Boolean, userStopped: Boolean): SpeechError? = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (userStopped) null else NoMatch

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NoPermission
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> Network

            SpeechRecognizer.ERROR_AUDIO -> Audio
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            ERROR_TOO_MANY_REQUESTS -> Busy

            ERROR_SERVER_DISCONNECTED -> ServiceUnavailable
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE,
            ERROR_CANNOT_CHECK_SUPPORT -> Language
            // ERROR_CLIENT：用户停止时忽略；服务从未就绪则视为服务不可用并回退。
            SpeechRecognizer.ERROR_CLIENT -> when {
                userStopped -> null
                !gotReady -> ServiceUnavailable
                else -> NoMatch
            }

            else -> Other(code)
        }
    }
}
