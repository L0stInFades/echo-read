package app.echoread.core

import java.text.BreakIterator
import java.util.Locale

/** 句子切分与合成片段（纯偏移，不持有文本副本） */
object Segmenter {
    /** 句末标点二次切分：BreakIterator 在部分实现（如桌面 JVM）不认「；…」，用兜底正则补齐 */
    private val TAIL_SPLIT = Regex("[。！？!?；;…]+[\"'”’）)\\]]*\\s*")

    /** 句子切分：ICU 句子边界优先（Android 内置），再按中文句末标点细分。返回纯偏移区间。 */
    fun splitSentences(text: String): List<Range> {
        val out = ArrayList<Range>()
        val it = BreakIterator.getSentenceInstance(Locale.CHINESE)
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            splitTail(text, start, end, out)
            start = end
            end = it.next()
        }
        return out.filter { !TextOps.isBlankJs(text.subSequence(it.start, it.end)) }
    }

    private fun splitTail(text: String, start: Int, end: Int, out: MutableList<Range>) {
        var cur = start
        for (m in TAIL_SPLIT.findAll(text.substring(start, end))) {
            val cut = start + m.range.last + 1
            if (cut >= end) break
            if (cut > cur) out.add(Range(cur, cut))
            cur = cut
        }
        if (cur < end) out.add(Range(cur, end))
    }

    /**
     * 将章节文本切分为 TTS 合成片段：句子按顺序合并，尽量接近 maxChunkChars
     * （减少 API 调用次数，同时保留较细的朗读高亮粒度）。超长单句硬切。
     */
    fun segmentChapter(text: String, maxChunkCharsIn: Int): List<Range> {
        val maxChunkChars = if (maxChunkCharsIn >= 1) maxChunkCharsIn else 120
        val segments = ArrayList<Range>()
        var start = -1
        var end = -1
        fun flush() {
            if (start < 0) return
            if (!TextOps.isBlankJs(text.subSequence(start, end))) segments.add(Range(start, end))
            start = -1
        }
        for (s in splitSentences(text)) {
            val sLen = s.end - s.start
            if (sLen > maxChunkChars) {
                flush()
                var cur = s.start
                while (cur < s.end) {
                    var hardEnd = minOf(cur + maxChunkChars, s.end)
                    if (hardEnd < s.end) {
                        // 切点避开代理对；步长 1 时回退会原地踏步，改为多带一个码元包住整对
                        val aligned = TextOps.alignSurrogateCut(text, hardEnd)
                        hardEnd = if (aligned > cur) aligned else hardEnd + 1
                    }
                    if (!TextOps.isBlankJs(text.subSequence(cur, hardEnd))) segments.add(Range(cur, hardEnd))
                    cur = hardEnd
                }
                continue
            }
            if (start < 0) {
                start = s.start
                end = s.end
            } else if (end - start + sLen > maxChunkChars) {
                flush()
                start = s.start
                end = s.end
            } else {
                end = s.end
            }
        }
        flush()
        return segments
    }

    /** 二分查找：包含 offset 的片段索引；落在空隙时取下一片段；越界时取边界 */
    fun segmentIndexAt(segments: List<Range>, offset: Int): Int {
        if (segments.isEmpty()) return 0
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                offset < s.start -> hi = mid - 1
                offset >= s.end -> lo = mid + 1
                else -> return mid
            }
        }
        return lo.coerceIn(0, segments.size - 1)
    }

    /** 段落定位：offset 所在段落索引（段落区间有序，二分；落在分隔符上取前一段） */
    fun paraIndexAt(paras: List<Range>, offset: Int): Int {
        if (paras.isEmpty()) return 0
        var lo = 0
        var hi = paras.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = paras[mid]
            when {
                offset < p.start -> hi = mid - 1
                offset > p.end -> lo = mid + 1
                else -> return mid
            }
        }
        return lo.coerceIn(0, paras.size - 1)
    }
}
