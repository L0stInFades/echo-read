package app.echoread.core

/**
 * 一枚书签（0.7.0）：书里的一个**内容位置**，不是页码。
 *
 * 位置用「章号 + 章内字符偏移」表示，与阅读进度、朗读片段、高亮是同一把尺（见 [Range]）。
 * 换字号、换行距、转屏都会让页码整个重排，而偏移量指着的那个字不会动 —— 书签落在哪一页，
 * 是拿它的偏移量去问当前排版现算出来的。
 *
 * 动手之前读过几款开源阅读器的做法（Legado、Readium、KOReader、Readest），共同点正是这一条：
 * 锚点都是内容位置，页码只是派生或缓存。其余取舍见 [Bookmarks]。
 */
data class Bookmark(
    val id: Long,
    val bookId: String,
    val chapter: Int,
    /** 章内字符偏移（章节规范文本的 UTF-16 码元偏移） */
    val offset: Int,
    /** 从锚点起的一小段原文，列表里认书签靠它 */
    val excerpt: String,
    val createdAt: Long
)

/** 书签的纯逻辑：摘录怎么取、哪些书签算在「这一页」上、列表滚到哪。不碰数据库，也不碰界面 */
object Bookmarks {
    /**
     * 摘录的长度上限。列表里一行最多显示两行，375dp 宽的手机上中文两行约 40 字、英文约 90 个字符；
     * 多存的部分永远看不到。（Readest 存 128 个字符，Legado 把整页都存下来。）
     */
    const val EXCERPT_CHARS = 96

    /**
     * 从 [offset] 起取一段摘录：跳过开头的空白，段落之间的换行并成一个空格，装不下时以省略号收尾。
     * 截断点避开代理对的中间（否则那半个字符会显示成 U+FFFD）。
     */
    fun excerpt(text: String, offset: Int, max: Int = EXCERPT_CHARS): String {
        if (text.isEmpty() || max <= 0) return ""
        var i = offset.coerceIn(0, text.length)
        // 锚点落在代理对后半时退一格，摘录不以半个字符开头
        if (i in 1 until text.length && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()) i--
        while (i < text.length && text[i].isWhitespace()) i++
        val out = StringBuilder(max + 1)
        var pendingSpace = false
        var truncated = false
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
                i++
                continue
            }
            val need = (if (pendingSpace) 1 else 0) + (if (ch.isHighSurrogate() && i + 1 < text.length) 2 else 1)
            if (out.length + need > max) {
                truncated = true
                break
            }
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.append(ch)
            if (ch.isHighSurrogate() && i + 1 < text.length) out.append(text[++i])
            i++
        }
        return if (truncated) out.toString().trimEnd() + "…" else out.toString()
    }

    /**
     * 落在某一页上的书签：同一章，且锚点在这一页的偏移区间 `[start, end)` 之内。
     *
     * 「这一页有没有书签」按**区间**判，不按「锚点恰好等于页首」判（Readium 的做法，换一次字号
     * 同一段就能再加一枚重复的）。KOReader 与 Readest 都是区间规则：重排之后页首变了，
     * 原来的书签仍然亮在它所在的那一页上。
     */
    fun onPage(all: List<Bookmark>, chapter: Int, start: Int, end: Int): List<Bookmark> =
        all.filter { it.chapter == chapter && it.offset >= start && it.offset < end }

    /**
     * 按位置排好的列表里，离当前位置最近、且不在它后面的那一枚的下标（全都在后面时返回 -1）。
     * 打开书签列表时滚到这里：读到书的后半段，不必从第一枚书签往下翻。
     */
    fun nearestBefore(sorted: List<Bookmark>, chapter: Int, offset: Int): Int =
        sorted.indexOfLast { it.chapter < chapter || (it.chapter == chapter && it.offset <= offset) }
}
