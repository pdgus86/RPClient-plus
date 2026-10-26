package me.kafuuneko.rpclient.libs.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-wide voice output using the Android system TextToSpeech engine.
 * Settings are stored in SharedPreferences. Supports sentence-by-sentence
 * speaking of streaming replies.
 */
object VoiceManager {
    private const val PREFS = "voice_settings"
    const val KEY_ENGINE = "engine"
    const val KEY_VOICE = "voice"
    const val KEY_RATE = "rate"
    const val KEY_PITCH = "pitch"
    const val KEY_AUTO_READ = "auto_read"
    const val KEY_AUTO_SEND = "auto_send"
    const val KEY_SKIP_ACTIONS = "skip_actions"

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val utteranceCounter = AtomicInteger(0)
    private val outstanding = AtomicInteger(0)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady

    // Streaming state
    private var streamKey: Any? = null
    private var streamSpokenUpTo = 0

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (tts == null) createTts()
    }

    private fun prefs() = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(key: String): String? = appContext?.let { prefs().getString(key, null) }
    fun getFloat(key: String, def: Float): Float = appContext?.let { prefs().getFloat(key, def) } ?: def
    fun getBool(key: String, def: Boolean): Boolean = appContext?.let { prefs().getBoolean(key, def) } ?: def
    fun putString(key: String, value: String?) = prefs().edit().putString(key, value).apply()
    fun putFloat(key: String, value: Float) {
        prefs().edit().putFloat(key, value).apply(); applyParams()
    }
    fun putBool(key: String, value: Boolean) = prefs().edit().putBoolean(key, value).apply()

    val autoRead: Boolean get() = getBool(KEY_AUTO_READ, false)
    val autoSend: Boolean get() = getBool(KEY_AUTO_SEND, false)

    private fun createTts() {
        val ctx = appContext ?: return
        ready = false
        _ttsReady.value = false
        val engine = getString(KEY_ENGINE)?.takeIf { it.isNotBlank() }
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                _ttsReady.value = true
                applyParams()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = true }
                    override fun onDone(utteranceId: String?) { finishOne() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finishOne() }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { finishOne() }
                })
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                queued.forEach { speakChunk(it) }
            }
        }
        tts = if (engine != null) TextToSpeech(ctx, listener, engine) else TextToSpeech(ctx, listener)
    }

    private fun finishOne() {
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            _speaking.value = false
        }
    }

    /** Re-create the engine after the user picks a different one. */
    fun setEngine(engine: String?) {
        putString(KEY_ENGINE, engine)
        putString(KEY_VOICE, null)
        stop()
        tts?.shutdown()
        tts = null
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
    } catch (_: Exception) { emptyList() }

    private fun applyParams() {
        val t = tts ?: return
        if (!ready) return
        t.setSpeechRate(getFloat(KEY_RATE, 1.0f))
        t.setPitch(getFloat(KEY_PITCH, 1.0f))
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
        s = s.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("[*_#`>~]+"), "")
        s = s.replace(Regex("<[^>]+>"), " ")
        return s.replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun speakChunk(text: String) {
        val t = tts
        if (t == null || !ready) {
            synchronized(pending) { pending.add(text) }
            return
        }
        val chunks = splitLong(text)
        for (c in chunks) {
            outstanding.incrementAndGet()
            _speaking.value = true
            val id = "rp_" + utteranceCounter.incrementAndGet()
            val r = t.speak(c, TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (r != TextToSpeech.SUCCESS) finishOne()
        }
    }

    private fun splitLong(text: String): List<String> {
        val max = (TextToSpeech.getMaxSpeechInputLength() - 10).coerceAtLeast(200)
        if (text.length <= max) return listOf(text)
        return text.chunked(max)
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
        tts?.stop()
        outstanding.set(0)
        _speaking.value = false
    }

    // ---- Streaming: speak finished sentences as they arrive ----

    private val sentenceEnd = Regex("[.!?。！？…]+[\"'”’)\\]]*(\\s+|$)|\\n+")

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
        var lastEnd = -1
        sentenceEnd.findAll(region).forEach { m -> if (m.range.last + 1 < region.length || m.value.isNotEmpty()) lastEnd = m.range.last + 1 }
        if (lastEnd <= 0) return
        // Require a little content after the boundary unless it's a newline, to avoid "Mr." style splits mid-stream.
        val piece = region.substring(0, lastEnd)
        streamSpokenUpTo += lastEnd
        val c = clean(piece)
        if (c.isNotBlank() && c.any { it.isLetterOrDigit() }) speakChunk(c)
    }

    fun streamEnd(key: Any, rawContent: String) {
        if (streamKey !== key) return
        val rest = if (streamSpokenUpTo < rawContent.length) rawContent.substring(streamSpokenUpTo) else ""
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
