package me.kafuuneko.rpclient.libs.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {

    @Test
    fun sentenceEnd_waitsForWhitespaceAfterPunctuation() {
        // 流式中 "3." 后续可能是 "5"，不能提前读出。
        assertEquals(0, lastCompleteSentenceEnd("The price is 3."))
        assertEquals("Hello there. ".length, lastCompleteSentenceEnd("Hello there. How are"))
    }

    @Test
    fun sentenceEnd_returnsLastBoundaryAndHandlesNewlinesAndQuotes() {
        val text = "\"Hi!\" she said. Then\nmore"
        assertEquals(text.indexOf("more"), lastCompleteSentenceEnd(text))
        assertEquals(0, lastCompleteSentenceEnd("no boundary yet"))
    }

    @Test
    fun cleanMarkdown_stripsFormattingButKeepsLinkText() {
        val cleaned = cleanMarkdown("**Bold** _it_ [link](https://x.y) ![img](a.png) <b>tag</b> `code`")
        assertEquals("Bold it link tag code", cleaned)
    }

    @Test
    fun splitForTts_respectsMaxAndKeepsAllText() {
        val text = (1..200).joinToString(" ") { "word$it" }
        val parts = splitForTts(text, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.dropLast(1).all { it.endsWith(" ") })
    }

    @Test
    fun speechError_mapping() {
        // 用户主动停止后的 ERROR_CLIENT / NO_MATCH 不提示。
        assertEquals(null, SpeechError.from(5, gotReady = true, userStopped = true))
        assertEquals(null, SpeechError.from(7, gotReady = true, userStopped = true))
        // 服务从未就绪就 ERROR_CLIENT：回退系统语音输入界面。
        val unavailable = SpeechError.from(5, gotReady = false, userStopped = false)
        assertEquals(SpeechError.ServiceUnavailable, unavailable)
        assertTrue(unavailable!!.fallbackToSystemUi)
        assertEquals(SpeechError.NoMatch, SpeechError.from(7, gotReady = true, userStopped = false))
        assertEquals(SpeechError.Language, SpeechError.from(13, gotReady = false, userStopped = false))
        assertEquals(SpeechError.Other(99), SpeechError.from(99, gotReady = true, userStopped = false))
    }
}
