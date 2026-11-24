package me.kafuuneko.rpclient.libs.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-wide voice output using the Android system TextToSpeech engine.
 * Settings are stored in SharedPreferences. Supports sentence-by-sentence
 * speaking of streaming replies.
 *
 * Third-party engines (Pocket TTS, Supertonic, Marmalade…) use their own configured
 * voice, so this class never calls setLanguage() and never treats a language status
 * code as fatal.
 */
object VoiceManager {
    private const val TAG = "VoiceManager"
    private const val PREFS = "voice_settings"
    const val KEY_ENGINE = "engine"
    const val KEY_VOICE = "voice"
    const val KEY_RATE = "rate"
    const val KEY_PITCH = "pitch"
    const val KEY_AUTO_READ = "auto_read"
    const val KEY_AUTO_SEND = "auto_send"
    const val KEY_SKIP_ACTIONS = "skip_actions"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false
    private var initFailed = false
    private var triedDefaultFallback = false
    private var rebindAttempted = false
    private val pending = ArrayList<String>()
    private val utteranceCounter = AtomicInteger(0)

    // 记录仍在队列或正在播放的 utterance，避免 stop() 后迟到的 onStop 回调把新一轮朗读状态误置为结束。
    private val activeUtterances: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady

    private var focusRequest: AudioFocusRequest? = null

    // Streaming state
    private var streamKey: Any? = null
    private var streamSpokenUpTo = 0

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (tts == null) createTts()
    }

    private fun prefs() = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(key: String): String? = appContext?.let { prefs().getString(key, null) }
    fun getFloat(key: String, def: Float): Float =
        appContext?.let { prefs().getFloat(key, def) } ?: def

    fun getBool(key: String, def: Boolean): Boolean =
        appContext?.let { prefs().getBoolean(key, def) } ?: def

    fun putString(key: String, value: String?) = prefs().edit().putString(key, value).apply()
    fun putFloat(key: String, value: Float) {
        prefs().edit().putFloat(key, value).apply(); applyParams()
    }

    fun putBool(key: String, value: Boolean) = prefs().edit().putBoolean(key, value).apply()

    // 默认开启：按下麦克风说完即发送，并自动朗读回复（不做免提循环监听）。
    val autoRead: Boolean get() = getBool(KEY_AUTO_READ, true)
    val autoSend: Boolean get() = getBool(KEY_AUTO_SEND, true)

    private fun createTts(forceDefault: Boolean = false) {
        val ctx = appContext ?: return
        ready = false
        initFailed = false
        _ttsReady.value = false
        val engine = if (forceDefault) null else getString(KEY_ENGINE)?.takeIf { it.isNotBlank() }
        val listener = TextToSpeech.OnInitListener { status -> onTtsInit(status, engine) }
        tts = try {
            if (engine != null) TextToSpeech(ctx, listener, engine) else TextToSpeech(ctx, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TextToSpeech", e)
            null
        }
        if (tts == null) onTtsInit(TextToSpeech.ERROR, engine)
    }

    /**
     * 处理引擎初始化结果。
     *
     * 第三方引擎可能因电池优化被系统杀掉或绑定失败；自选引擎失败时退回系统默认引擎再试一次，
     * 仍失败则清空待读队列并提示用户，避免静默无声。
     */
    private fun onTtsInit(status: Int, engine: String?) {
        // 初始化成功：应用参数、挂监听并播放初始化期间积压的文本。
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            triedDefaultFallback = false
            _ttsReady.value = true
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            applyParams()
            tts?.setOnUtteranceProgressListener(progressListener)
            val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
            queued.forEach { speakChunk(it) }
            return
        }
        Log.w(TAG, "TTS init failed (status=$status, engine=$engine)")
        // 自选引擎失败：退回系统默认引擎。
        if (engine != null && !triedDefaultFallback) {
            triedDefaultFallback = true
            runCatching { tts?.shutdown() }
            tts = null
            showToast("Speech engine $engine didn't start. Using the system default engine.")
            main.post { createTts(forceDefault = true) }
            return
        }
        // 默认引擎也失败：放弃积压内容并提示。
        initFailed = true
        synchronized(pending) { pending.clear() }
        setSpeaking(false)
        showToast("Text-to-speech couldn't start. Check Android's text-to-speech settings.")
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (utteranceId != null && utteranceId in activeUtterances) setSpeaking(true)
        }

        override fun onDone(utteranceId: String?) {
            finishOne(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            finishOne(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "Utterance $utteranceId failed: $errorCode")
            finishOne(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            finishOne(utteranceId)
        }
    }

    private fun finishOne(utteranceId: String?) {
        if (utteranceId != null) activeUtterances.remove(utteranceId)
        if (activeUtterances.isEmpty()) setSpeaking(false)
    }

    /** 朗读状态变化时同步请求/释放音频焦点，让音乐等其他声音在朗读期间压低音量。 */
    private fun setSpeaking(value: Boolean) {
        if (_speaking.value == value) return
        _speaking.value = value
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (value) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            } else {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        }
    }

    private fun showToast(message: String) {
        val ctx = appContext ?: return
        main.post { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }
    }

    /** Re-create the engine after the user picks a different one. */
    fun setEngine(engine: String?) {
        putString(KEY_ENGINE, engine)
        putString(KEY_VOICE, null)
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        triedDefaultFallback = false
        createTts()
    }

    fun setVoice(name: String?) {
        putString(KEY_VOICE, name)
        applyParams()
    }

    fun engines(): List<TextToSpeech.EngineInfo> = tts?.engines.orEmpty()
    fun currentEngine(): String? = tts?.defaultEngine?.let { getString(KEY_ENGINE) ?: it }

    fun voices(): List<Voice> = try {
        tts?.voices?.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name })).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun applyParams() {
        val t = tts ?: return
        if (!ready) return
        runCatching { t.setSpeechRate(getFloat(KEY_RATE, 1.0f)) }
        runCatching { t.setPitch(getFloat(KEY_PITCH, 1.0f)) }
        val voiceName = getString(KEY_VOICE)
        if (!voiceName.isNullOrBlank()) {
            voices().firstOrNull { it.name == voiceName }?.let { runCatching { t.voice = it } }
        }
    }

    /** Clean message text before speaking: drop think blocks, markdown symbols, optional *actions*. */
    fun clean(text: String): String {
        var s = text
        s = s.replace(Regex("(?s)<think>.*?(</think>|$)"), " ")
        s = s.replace(Regex("(?s)<thinking>.*?(</thinking>|$)"), " ")
        if (getBool(KEY_SKIP_ACTIONS, false)) s = s.replace(Regex("\\*[^*\\n]+\\*"), " ")
        return cleanMarkdown(s)
    }

    /**
     * 将一段文本送入引擎队列。
     *
     * 引擎未就绪时先排队；第三方引擎进程被系统回收后 speak() 会返回 ERROR，
     * 此时重建一次引擎并把文本放回队列，避免之后所有朗读都静默失败。
     */
    private fun speakChunk(text: String) {
        val t = tts
        // 引擎未就绪：排队等待 onInit。
        if (t == null || !ready) {
            synchronized(pending) { pending.add(text) }
            // 上次初始化彻底失败时，把这次朗读请求当作重试。
            if (initFailed) {
                triedDefaultFallback = false
                runCatching { t?.shutdown() }
                tts = null
                createTts()
            }
            return
        }
        // 引擎就绪：逐段入队，失败时重建引擎重试。
        val chunks = splitForTts(text, (TextToSpeech.getMaxSpeechInputLength() - 10).coerceAtLeast(200))
        for ((index, c) in chunks.withIndex()) {
            val id = "rp_" + utteranceCounter.incrementAndGet()
            activeUtterances.add(id)
            setSpeaking(true)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            val r = runCatching { t.speak(c, TextToSpeech.QUEUE_ADD, params, id) }
                .getOrDefault(TextToSpeech.ERROR)
            if (r == TextToSpeech.SUCCESS) {
                rebindAttempted = false
                continue
            }
            finishOne(id)
            if (rebindAttempted) {
                showToast("Text-to-speech engine isn't responding.")
                return
            }
            rebindAttempted = true
            Log.w(TAG, "speak() returned $r, rebinding the TTS engine")
            runCatching { t.shutdown() }
            tts = null
            ready = false
            synchronized(pending) { pending.addAll(chunks.drop(index)) }
            createTts()
            return
        }
    }

    /** Speak a full message now, replacing anything currently being spoken. */
    fun speakNow(text: String) {
        stop()
        val c = clean(text)
        if (c.isNotBlank()) speakChunk(c)
    }

    fun stop() {
        streamKey = null
        streamSpokenUpTo = 0
        synchronized(pending) { pending.clear() }
        activeUtterances.clear()
        runCatching { tts?.stop() }
        setSpeaking(false)
    }

    // ---- Streaming: speak finished sentences as they arrive ----

    fun streamStart(key: Any) {
        if (!autoRead) return
        stop()
        streamKey = key
        streamSpokenUpTo = 0
    }

    fun streamUpdate(key: Any, rawContent: String) {
        if (streamKey !== key) return
        val text = rawContent
        // Don't speak inside an unfinished think block.
        val lastOpen = text.lastIndexOf("<think")
        val lastClose = text.lastIndexOf("</think")
        val safeLimit = if (lastOpen > lastClose) lastOpen else text.length
        if (safeLimit <= streamSpokenUpTo) return
        val region = text.substring(streamSpokenUpTo, safeLimit)
        val lastEnd = lastCompleteSentenceEnd(region)
        if (lastEnd <= 0) return
        val piece = region.substring(0, lastEnd)
        streamSpokenUpTo += lastEnd
        val c = clean(piece)
        if (c.isNotBlank() && c.any { it.isLetterOrDigit() }) speakChunk(c)
    }

    fun streamEnd(key: Any, rawContent: String) {
        if (streamKey !== key) return
        val rest =
            if (streamSpokenUpTo < rawContent.length) rawContent.substring(streamSpokenUpTo) else ""
        streamKey = null
        streamSpokenUpTo = 0
        val c = clean(rest)
        if (c.isNotBlank() && c.any { it.isLetterOrDigit() }) speakChunk(c)
    }

    /** Non-streaming reply finished. */
    fun replyComplete(text: String) {
        if (!autoRead) return
        speakNow(text)
    }
}
