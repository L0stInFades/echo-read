package app.echoread

import app.echoread.core.ParsedChapter
import app.echoread.core.Segmenter
import app.echoread.core.TextOps
import app.echoread.core.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** 解析与分段核心逻辑（移植自网页版 test/sanity.ts） */
class CoreTest {
    @Test
    fun utf8Decode() {
        val (text, enc) = TxtParser.decodeText("第一章 开始\n内容".toByteArray())
        assertEquals("utf-8", enc)
        assertTrue(text.contains("第一章"))
    }

    @Test
    fun gbkDecode() {
        val bytes = "第二章 风云\n正文内容".toByteArray(Charset.forName("GBK"))
        val (text, enc) = TxtParser.decodeText(bytes)
        assertTrue(enc, enc in setOf("gb18030", "gbk", "gb2312"))
        assertTrue(text, text.contains("风云"))
    }

    @Test
    fun asciiHeadThenGbk() {
        val head = "Project Gutenberg License\n".repeat(200)
        val bytes = (head + "\n第三章 归来\n中文正文。").toByteArray(Charset.forName("GBK"))
        val (text, _) = TxtParser.decodeText(bytes)
        assertTrue(text.take(60), text.contains("归来") && text.contains("中文正文"))
    }

    @Test
    fun big5Decode() {
        val bytes = "第一章 開始\n繁體內容。".toByteArray(Charset.forName("Big5"))
        val (text, _) = TxtParser.decodeText(bytes)
        assertTrue(text, text.contains("繁體內容"))
    }

    @Test
    fun splitChaptersNumeric() {
        val text = "简介：这是一本测试书，讲述了一段漫长而曲折的故事，供单元测试使用。\n\n第一章 起源\n这是第一章的正文。\n第二段。\n\n第二章 发展\n第二章正文。\n\n第三章 高潮\n第三章正文。"
        val chapters = TxtParser.splitChapters(text)
        assertEquals(chapters.map { it.title }.toString(), 4, chapters.size)
        assertEquals("开篇", chapters[0].title)
        assertEquals("第一章 起源", chapters[1].title)
        assertEquals("第三章 高潮", chapters[3].title)
        assertEquals(listOf("这是第一章的正文。", "第二段。"), chapters[1].paragraphs)
    }

    @Test
    fun splitChaptersChineseNumerals() {
        val chapters = TxtParser.splitChapters("第一章 雪夜\n正文一。\n第二章 黎明\n正文二。")
        assertEquals(2, chapters.size)
        assertEquals("第一章 雪夜", chapters[0].title)
    }

    @Test
    fun fallbackChunking() {
        val long = (0 until 600).joinToString("\n") { "第${it}段内容内容内容内容内容内容内容内容内容内容。" }
        val chapters = TxtParser.splitChapters(long)
        assertTrue("兜底切块数=${chapters.size}", chapters.size >= 2)
    }

    @Test
    fun phantomNumberingKeepsText() {
        val text = "1. 他来了\n2. 她走了\n3. 狗趴在门口\n这一段才是真正的正文，包含了完整的故事内容。"
        val all = TxtParser.splitChapters(text).flatMap { listOf(it.title) + it.paragraphs }.joinToString("\n")
        for (line in listOf("他来了", "她走了", "狗趴在门口", "这一段才是真正的正文")) assertTrue("内容丢失: $line", all.contains(line))
    }

    @Test
    fun singleTitleKeepsShortHead() {
        val chapters = TxtParser.splitChapters("题记：献给远方。\n第一章 唯一\n" + "正文内容。".repeat(50))
        assertTrue(chapters.map { it.title }.toString(), chapters.size >= 2)
        assertEquals("开篇", chapters[0].title)
        assertTrue(chapters[0].paragraphs[0].contains("题记"))
        assertEquals("第一章 唯一", chapters[1].title)
    }

    @Test
    fun titleLineNotChapterWhenEndsWithPunctuation() {
        val chapters = TxtParser.splitChapters("第一章 开始\n第二章正文。\n更多正文。\n第二章 继续\n正文。")
        assertEquals(chapters.map { it.title }.toString(), 2, chapters.size)
    }

    @Test
    fun parseStripsTitleLine() {
        val book = TxtParser.parse("我的书.txt", "我的书\n第一章 起\n正文。".toByteArray())
        assertEquals("我的书", book.title)
        assertEquals("第一章 起", book.chapters[0].title)
    }

    @Test
    fun paraRangesRoundTrip() {
        val paragraphs = listOf("小明走进了屋子。他环顾四周。", "信上写着：欢迎回来。")
        val text = TextOps.joinParagraphs(paragraphs)
        val paras = TextOps.paraRanges(text)
        assertEquals(2, paras.size)
        assertEquals(paragraphs[0], text.substring(paras[0].start, paras[0].end))
        assertEquals(paragraphs[1], text.substring(paras[1].start, paras[1].end))
    }

    @Test
    fun segmentAndLocate() {
        val text = "小明走进了屋子。他环顾四周，看到桌上有一封信。\n信上写着：欢迎回来。"
        val segs = Segmenter.segmentChapter(text, 20)
        assertTrue(segs.size >= 2)
        for (i in segs.indices) {
            assertTrue(segs[i].start < segs[i].end)
            if (i > 0) assertTrue(segs[i].start >= segs[i - 1].start)
        }
        val probe = text.indexOf("信上")
        val seg = segs[Segmenter.segmentIndexAt(segs, probe)]
        assertTrue("$probe 应落在 [${seg.start},${seg.end})", probe >= seg.start && probe < seg.end)
        assertTrue(text.substring(seg.start, seg.end).contains("信上写着"))
        assertEquals(0, Segmenter.segmentIndexAt(segs, 0))
        assertEquals(segs.size - 1, Segmenter.segmentIndexAt(segs, text.length + 100))
    }

    @Test
    fun sentenceSplitOnChinesePunctuation() {
        val text = "他说：\"走！\"她笑了。第二句；第三句…第四句"
        val s = Segmenter.splitSentences(text)
        assertTrue(s.toString(), s.size >= 4)
        assertEquals("他说：\"走！\"", text.substring(s[0].start, s[0].end))
    }

    @Test
    fun hardCutLongSentence() {
        val longSentence = "一".repeat(500) + "。"
        val segs = Segmenter.segmentChapter(longSentence, 100)
        assertEquals("硬切片段数=${segs.size}", 6, segs.size)
        val withBlanks = "甲。" + " ".repeat(300) + "乙。"
        val segs2 = Segmenter.segmentChapter(withBlanks, 50)
        assertTrue("不应有纯空白片段", segs2.all { withBlanks.substring(it.start, it.end).isNotBlank() })
    }

    @Test
    fun surrogateSafeHardCut() {
        val emoji = "😀".repeat(60)
        val segs = Segmenter.segmentChapter(emoji, 7)
        for (s in segs) {
            val part = emoji.substring(s.start, s.end)
            assertTrue(part, !part.first().isLowSurrogate() && !part.last().isHighSurrogate())
        }
    }

    @Test
    fun layoutBlocksRestoreParagraphs() {
        val text = TextOps.joinParagraphs(listOf("第一句。第二句。", "第三句。第四句。第五句。"))
        val paras = TextOps.paraRanges(text)
        val segs = Segmenter.segmentChapter(text, 12)
        val blocks = TextOps.layoutBlocks(paras, segs)
        assertEquals(paras.size, blocks.size)
        for (pi in paras.indices) {
            val joined = blocks[pi].joinToString("") { TextOps.fragText(text, it, paras[pi]) }
            assertEquals("段落 $pi 还原失败", text.substring(paras[pi].start, paras[pi].end), joined)
        }
        assertTrue(blocks.flatten().size >= segs.size)
    }

    @Test
    fun boundChapters() {
        val bigPara = "长".repeat(TextOps.CHAPTER_MAX_CHARS + 500)
        val chapters = TextOps.boundChapters(
            listOf(
                ParsedChapter("短章", listOf("很短。")),
                ParsedChapter("巨章", listOf(bigPara)),
                ParsedChapter("长章", List(100) { "内容内容内容内容内容内容内容内容内容。" })
            )
        )
        assertEquals("短章", chapters[0].title)
        assertTrue(chapters.all { it.text.length <= TextOps.CHAPTER_MAX_CHARS })
        val giant = chapters.filter { it.title.startsWith("巨章") }
        assertTrue(giant.map { it.title }.toString(), giant.size == 2 && giant[1].title == "巨章（2）")
        assertEquals(bigPara.length, giant.sumOf { it.text.length })
    }

    @Test
    fun paraIndexAt() {
        val text = "aa\nbbb\ncc"
        val paras = TextOps.paraRanges(text)
        assertEquals(0, Segmenter.paraIndexAt(paras, 0))
        assertEquals(1, Segmenter.paraIndexAt(paras, 3))
        assertEquals(1, Segmenter.paraIndexAt(paras, 5))
        assertEquals(2, Segmenter.paraIndexAt(paras, 8))
        assertEquals(2, Segmenter.paraIndexAt(paras, 99))
    }
}
