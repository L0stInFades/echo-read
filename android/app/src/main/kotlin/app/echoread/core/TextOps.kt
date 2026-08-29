package app.echoread.core

/**
 * 一切皆偏移量：任一章节在内存中只有一份规范纯文本，段落/片段/高亮都是它的偏移区间。
 * 本文件与网页版 lib/text.ts 一一对应。
 */
object TextOps {
    /** 单章内存上限：章节是渲染/合成/缓存的统一工作单元，限长即限内存 */
    const val CHAPTER_MAX_CHARS = 8000

    /** 段落数组 → 规范章节文本（入库唯一形态） */
    fun joinParagraphs(paragraphs: List<String>): String = paragraphs.joinToString("\n")

    /** 硬切点校正：cut 恰落在代理对中间时回退到高代理之前（劈开的两半各自变成 U+FFFD） */
    fun alignSurrogateCut(text: String, cut: Int): Int {
        if (cut <= 0 || cut >= text.length) return cut
        val hi = text[cut - 1]
        val lo = text[cut]
        return if (hi.isHighSurrogate() && lo.isLowSurrogate()) cut - 1 else cut
    }

    /** 规范文本 → 段落区间序列（O(n) 单趟，零字符串副本） */
    fun paraRanges(text: String): List<Range> {
        val out = ArrayList<Range>()
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || text[i] == '\n') {
                out.add(Range(start, i))
                start = i + 1
            }
        }
        return out
    }

    /**
     * 渲染布局合并：段落区间 × 片段区间均按偏移有序，
     * 双指针一趟归并出每段重叠的片段列表 —— O(P+S)。
     */
    fun layoutBlocks(paras: List<Range>, segments: List<Range>): List<List<Range>> {
        val blocks = ArrayList<List<Range>>(paras.size)
        var s = 0
        for (p in paras) {
            val spans = ArrayList<Range>()
            while (s < segments.size && segments[s].end <= p.start) s++
            var j = s
            while (j < segments.size && segments[j].start < p.end) {
                spans.add(segments[j])
                j++
            }
            blocks.add(spans)
        }
        return blocks
    }

    /** 片段区间裁剪到段落内并取文本（渲染切片在用到时才发生） */
    fun fragText(text: String, seg: Range, para: Range): String =
        text.substring(maxOf(seg.start, para.start), minOf(seg.end, para.end))

    /**
     * 章节限长归一化：把任意解析产物切到 CHAPTER_MAX_CHARS 以内（段落边界处断开），
     * 超长章自动拆分并追加序号后缀。TXT 的兜底分章与 EPUB 的单文件大章共用此守卫。
     */
    fun boundChapters(chapters: List<ParsedChapter>): List<BoundChapter> {
        val out = ArrayList<BoundChapter>()
        for (c in chapters) {
            val parts = ArrayList<String>()
            var buf = ArrayList<String>()
            var len = 0
            fun flush() {
                if (buf.isNotEmpty()) parts.add(joinParagraphs(buf))
                buf = ArrayList()
                len = 0
            }
            for (p in c.paragraphs) {
                if (len + p.length > CHAPTER_MAX_CHARS) flush()
                if (p.length > CHAPTER_MAX_CHARS) {
                    // 无换行的巨型段落：硬切（切点避开代理对——劈开即畸形文本永久入库）
                    var i = 0
                    while (i < p.length) {
                        val end = if (i + CHAPTER_MAX_CHARS >= p.length) p.length
                        else alignSurrogateCut(p, i + CHAPTER_MAX_CHARS)
                        parts.add(p.substring(i, end))
                        i = end
                    }
                    continue
                }
                buf.add(p)
                len += p.length + 1
            }
            flush()
            for (i in parts.indices) {
                out.add(BoundChapter(if (parts.size > 1) "${c.title}（${i + 1}）" else c.title, parts[i]))
            }
        }
        return out
    }

    /** 与 JS String.prototype.trim 等价的空白判定（含 U+FEFF） */
    fun isBlankJs(s: CharSequence): Boolean = s.all { it.isWhitespace() || it == '\uFEFF' }
}
