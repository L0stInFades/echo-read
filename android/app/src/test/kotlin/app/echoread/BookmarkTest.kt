package app.echoread

import app.echoread.core.Bookmark
import app.echoread.core.Bookmarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签的纯逻辑：摘录、「在不在这一页」、排序、列表定位 */
class BookmarkTest {
    private fun mark(id: Long, chapter: Int, offset: Int, at: Long = id) = Bookmark(id, "b", chapter, offset, "", at)

    @Test
    fun excerptStartsAtTheAnchorAndFlattensParagraphs() {
        val text = "第一段的话。\n第二段从这里开始，后面还有很多字。\n第三段。"
        assertEquals("第一段的话。 第二段从这里开始，后面还有很多字。 第三段。", Bookmarks.excerpt(text, 0))
        // 锚点在段落中间：从那个字起
        assertEquals("的话。 第二段从这里开始，后面还有很多字。 第三段。", Bookmarks.excerpt(text, 3))
        // 锚点正好落在换行上：跳过空白，不以空格开头
        assertEquals("第二段从这里开始，后面还有很多字。 第三段。", Bookmarks.excerpt(text, 6))
        assertEquals("", Bookmarks.excerpt(text, text.length))
        assertEquals("越界的偏移按章尾处理", "", Bookmarks.excerpt(text, 9999))
        assertEquals("", Bookmarks.excerpt("", 0))
        assertEquals("负偏移按章首处理", "第一段的话。 第二段", Bookmarks.excerpt(text, -5, max = 10).removeSuffix("…"))
    }

    @Test
    fun excerptIsCappedWithAnEllipsis() {
        val long = "It was a dark and stormy night; the rain fell in torrents, except at occasional intervals, when it was checked."
        val e = Bookmarks.excerpt(long, 0)
        assertTrue(e.endsWith("…"))
        assertTrue("上限 ${Bookmarks.EXCERPT_CHARS} 个字符（不含省略号）", e.length <= Bookmarks.EXCERPT_CHARS + 1)
        assertTrue(long.startsWith(e.removeSuffix("…")))
        assertFalse("省略号前不留空格", e.removeSuffix("…").endsWith(" "))
        // 装得下就不加省略号
        assertEquals("short", Bookmarks.excerpt("short", 0))
        assertEquals("刚好装满也不加", "abcde", Bookmarks.excerpt("abcde", 0, max = 5))
        assertEquals("abcd…", Bookmarks.excerpt("abcdef", 0, max = 4))
    }

    @Test
    fun excerptNeverSplitsASurrogatePair() {
        val smile = String(Character.toChars(0x1F600))
        val text = "ab$smile$smile"
        // 上限落在表情中间：整个表情不要，而不是留半个
        assertEquals("ab…", Bookmarks.excerpt(text, 0, max = 3))
        assertEquals("ab$smile…", Bookmarks.excerpt(text, 0, max = 4))
        // 锚点落在代理对的后半：退到这个字符的开头
        assertEquals(smile + smile, Bookmarks.excerpt(text, 3))
    }

    @Test
    fun pageRuleIsARangeNotEquality() {
        val all = listOf(mark(1, 0, 0), mark(2, 0, 120), mark(3, 0, 300), mark(4, 1, 120))
        // 第 0 章、偏移 [100, 300) 的那一页：只有 120 那一枚；300 属于下一页（右开区间）
        assertEquals(listOf(2L), Bookmarks.onPage(all, 0, 100, 300).map { it.id })
        assertEquals(listOf(3L), Bookmarks.onPage(all, 0, 300, 480).map { it.id })
        // 调大字号后页变短：原来同页的两枚分到两页；调小后又回到同一页
        assertEquals(listOf(1L, 2L), Bookmarks.onPage(all, 0, 0, 200).map { it.id })
        assertEquals(listOf(1L), Bookmarks.onPage(all, 0, 0, 100).map { it.id })
        assertEquals("别的章同样的偏移不算", listOf(4L), Bookmarks.onPage(all, 1, 0, 500).map { it.id })
        assertTrue(Bookmarks.onPage(all, 2, 0, 500).isEmpty())
    }

    @Test
    fun nearestBookmarkAtOrBeforeAPosition() {
        // 列表按位置排好（库里查出来就是这个顺序）
        val sorted = listOf(mark(2, 0, 50), mark(1, 0, 50), mark(3, 1, 0), mark(4, 1, 700), mark(5, 2, 10))
        assertEquals("都在当前位置之后", -1, Bookmarks.nearestBefore(sorted, 0, 10))
        assertEquals(1, Bookmarks.nearestBefore(sorted, 0, 50))
        assertEquals(2, Bookmarks.nearestBefore(sorted, 1, 699))
        assertEquals(3, Bookmarks.nearestBefore(sorted, 1, 700))
        assertEquals(4, Bookmarks.nearestBefore(sorted, 9, 0))
        assertEquals(-1, Bookmarks.nearestBefore(emptyList(), 3, 3))
    }

}
