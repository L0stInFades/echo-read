package app.echoread.core

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** TXT 导入：编码探测与分章 */
object TxtParser {
    data class Decoded(val text: String, val encoding: String)

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** BOM → 严格 UTF-8 → juniversalchardet 探测（GBK/GB18030/Big5 等）→ 候选逐一严格解码 */
    fun decodeText(bytes: ByteArray): Decoded {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "utf-8")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "utf-16le")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "utf-16be")
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return Decoded(it, "utf-8") }

        var detected = ""
        try {
            val det = UniversalDetector(null)
            det.handleData(bytes, 0, minOf(bytes.size, 65536))
            det.dataEnd()
            detected = det.detectedCharset?.lowercase() ?: ""
        } catch (_: Throwable) {
            /* 探测失败走默认候选 */
        }
        // 严格 UTF-8 已失败，必含非 ASCII 字节：单字节西文编码永不报错却会解出乱码，
        // 中文小说优先按 GB18030 / Big5 尝试（两者对非法序列会报错，可作为判据）
        val unreliable = detected.isEmpty() || detected == "ascii" || detected == "us-ascii" ||
            detected.startsWith("windows-125") || detected.startsWith("iso-8859") || detected == "macroman"
        val candidates = LinkedHashSet<String>()
        if (!unreliable) candidates.add(normalizeCharsetName(detected))
        candidates.add("GB18030")
        candidates.add("Big5")
        for (name in candidates) {
            val cs = try { Charset.forName(name) } catch (_: Throwable) { continue }
            strictDecode(bytes, cs)?.let { return Decoded(it, name.lowercase()) }
        }
        if (unreliable && detected.isNotEmpty() && detected != "ascii" && detected != "us-ascii") {
            try {
                val cs = Charset.forName(normalizeCharsetName(detected))
                return Decoded(String(bytes, cs), detected)
            } catch (_: Throwable) {
                /* 继续兜底 */
            }
        }
        return Decoded(String(bytes, Charsets.UTF_8), "utf-8")
    }

    private fun normalizeCharsetName(name: String): String = when (name.lowercase()) {
        "gbk", "gb2312", "gb18030", "euc-cn", "x-gbk" -> "GB18030"
        "big5", "big5-hkscs", "x-big5" -> "Big5"
        else -> name
    }

    /* ---------------- 章节切分 ---------------- */

    private class Pattern(val re: Regex, val min: Int)

    private const val TAIL = "[^\\n。！？!?；;…]{0,30}[ \\t]*$"

    /** 候选章节标题正则（取匹配数最多者）；标题行不允许以句末标点结尾；min 为采纳所需的最少匹配数 */
    private val CHAPTER_PATTERNS = listOf(
        Pattern(Regex("^[ \\t]*第[0-9零〇一二三四五六七八九十百千万两]+[章节卷回部集篇幕]$TAIL", RegexOption.MULTILINE), 1),
        Pattern(Regex("^[ \\t]*(?:Chapter|CHAPTER|chap)[ \\t]*[0-9零〇一二三四五六七八九十]+$TAIL", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)), 1),
        Pattern(Regex("^[ \\t]*(?:楔子|序言?|前言|序章|引子|终章|尾声|后记|番外篇?)$TAIL", RegexOption.MULTILINE), 1),
        Pattern(Regex("^[ \\t]*[0-9]{1,5}[、.．][ \\t]?[^\\n。！？!?；;…]{1,30}[ \\t]*$", RegexOption.MULTILINE), 3),
        Pattern(Regex("^[ \\t]*卷[0-9零〇一二三四五六七八九十]+$TAIL", RegexOption.MULTILINE), 1)
    )

    private class Match(val index: Int, val rawLen: Int, val title: String)

    private fun toParagraphs(text: String): List<String> =
        text.split(Regex("\\r?\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun splitChapters(fullText: String): List<ParsedChapter> {
        val content = fullText.replace(Regex("\\r\\n?"), "\n")

        var best: List<Match>? = null
        for (p in CHAPTER_PATTERNS) {
            val matches = p.re.findAll(content).map { Match(it.range.first, it.value.length, it.value.trim()) }.toList()
            if (matches.size >= p.min && (best == null || matches.size > best.size)) best = matches
        }

        val chapters = ArrayList<MutableChapter>()
        if (best != null) {
            val matches = best
            // 第一章之前的内容（封面/简介等），再短也不丢字
            val head = content.substring(0, matches[0].index).trim()
            if (head.isNotEmpty()) chapters.add(MutableChapter("开篇", toParagraphs(head).toMutableList()))
            for (i in matches.indices) {
                val start = matches[i].index + matches[i].rawLen
                val end = if (i + 1 < matches.size) matches[i + 1].index else content.length
                val paragraphs = toParagraphs(content.substring(start, end))
                when {
                    paragraphs.isNotEmpty() -> chapters.add(MutableChapter(matches[i].title, paragraphs.toMutableList()))
                    // 空体“标题”（编号正文等幻影匹配）：降级为上一章的正文行，内容不丢
                    chapters.isNotEmpty() -> chapters.last().paragraphs.add(matches[i].title)
                    else -> chapters.add(MutableChapter("开篇", mutableListOf(matches[i].title)))
                }
            }
        }

        // 无章节结构：按字数在段落边界切块
        if (chapters.isEmpty()) {
            var buf = ArrayList<String>()
            var len = 0
            var part = 1
            for (p in toParagraphs(content)) {
                if (len + p.length > TextOps.CHAPTER_MAX_CHARS && buf.isNotEmpty()) {
                    chapters.add(MutableChapter("第 $part 节", buf))
                    buf = ArrayList()
                    len = 0
                    part++
                }
                buf.add(p)
                len += p.length
            }
            if (buf.isNotEmpty()) chapters.add(MutableChapter("第 $part 节", buf))
        }
        return chapters.map { ParsedChapter(it.title, it.paragraphs) }
    }

    private class MutableChapter(val title: String, val paragraphs: MutableList<String>)

    /** 从 TXT 文件构建书籍 */
    fun parse(fileName: String, bytes: ByteArray): ParsedBook {
        val (text, _) = decodeText(bytes)
        val chapters = splitChapters(text).toMutableList()
        val title = fileName.replace(Regex("\\.(txt|text)$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { "未命名" }
        // TXT 常见约定：首行即书名，去掉以免重复出现在正文
        val first = chapters.firstOrNull()
        if (first != null && first.paragraphs.isNotEmpty() && first.paragraphs[0].trim() == title) {
            val rest = first.paragraphs.drop(1)
            if (rest.isEmpty()) chapters.removeAt(0) else chapters[0] = ParsedChapter(first.title, rest)
        }
        return ParsedBook(title = title, author = "", chapters = chapters)
    }
}
