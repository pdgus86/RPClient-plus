package me.kafuuneko.rpclient.libs.voice

// 纯文本处理工具，不依赖 Android，便于单元测试。

// 句末标点后必须跟空白（或换行）才算完整句子；流式输出中 "3." 后面可能还会接 "5"，不能提前切开。
private val SENTENCE_END = Regex("[.!?。！？…]+[\"'”’)\\]]*\\s+|\\n+")

/** 返回 [region] 中最后一个完整句子的结束位置（不含），没有完整句子时返回 0。 */
internal fun lastCompleteSentenceEnd(region: String): Int =
    SENTENCE_END.findAll(region).lastOrNull()?.let { it.range.last + 1 } ?: 0

/** 去掉 Markdown 标记、图片、链接地址和 HTML 标签，只保留适合朗读的正文。 */
internal fun cleanMarkdown(text: String): String {
    var s = text
    // HTML 标签必须先于 Markdown 符号清理；否则 ">" 先被删掉，"<b" 之类的残片会被念出来。
    s = s.replace(Regex("<[^>]+>"), " ")
    s = s.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
    s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("[*_#`>~]+"), "")
    return s.replace(Regex("[ \\t]+"), " ").trim()
}

/** 按引擎单次输入上限切分文本，尽量在空白处断开，避免把单词从中间截断。 */
internal fun splitForTts(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) {
            val space = text.lastIndexOf(' ', end - 1)
            if (space > start) end = space + 1
        }
        out.add(text.substring(start, end))
        start = end
    }
    return out
}
