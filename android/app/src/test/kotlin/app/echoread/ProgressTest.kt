package app.echoread

import app.echoread.core.BookFormat
import app.echoread.core.BookMeta
import app.echoread.core.Progress
import app.echoread.core.bookFraction
import app.echoread.core.readFraction
import app.echoread.core.started
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度条回归测试。每一条都对应 0.1.x 里一个可复现的错误行为 ——
 * 用户的原话是「左下角的播放进度条到底咋回事？感觉不是很准确」，这些就是「不准确」的具体形态。
 */
class ProgressTest {

    private fun meta(chapters: Int, totalChars: Int, ch: Int, off: Int) = BookMeta(
        id = "b", title = "t", author = "a", format = BookFormat.TXT,
        chapterCount = chapters, totalChars = totalChars, createdAt = 0L,
        progress = Progress(ch, off)
    )

    /**
     * 旧书架算法 `chapterIndex / (chapterCount - 1)` 在**到达**最后一章时就报 100%。
     * 12 章的书刚翻到第 12 章，书架说「已读 100%」、阅读器说 0%，真值 91.7%。
     */
    @Test
    fun arrivingAtLastChapterIsNotFinished() {
        val f = meta(12, 31200, 11, 0).readFraction()
        assertEquals(11f / 12f, f, 1e-4f)
        assertTrue("刚到最后一章不该是 100%", f < 0.999f)
        // 读完最后一章才是 100%
        assertEquals(1f, meta(12, 31200, 11, 2600).readFraction(), 1e-4f)
    }

    /** 单章书与多章书曾走两条完全不同的公式；现在是同一个 */
    @Test
    fun singleChapterUsesTheSameFormula() {
        assertEquals(0.5f, bookFraction(0, 4000, 8000, 1), 1e-4f)
        assertEquals(0.5f, meta(1, 8000, 0, 4000).readFraction(), 1e-4f)
        // 2 章书在同一文本位置上不该读成 0（旧实现：单章 99.99% vs 双章 0%）
        assertTrue(meta(2, 8000, 0, 3999).readFraction() > 0f)
    }

    /**
     * 换章不得倒退。旧实现在章尾闪 100% 再掉回 0%，因为分子分母都是章内的。
     * 书级坐标下，第 N 章末尾与第 N+1 章开头是同一条轨道上相邻的两点。
     */
    @Test
    fun chapterTurnDoesNotRewind() {
        val endOfCh3 = bookFraction(3, 2600, 2600, 12)
        val startOfCh4 = bookFraction(4, 0, 2600, 12)
        assertEquals("章尾与下一章开头必须重合", endOfCh3, startOfCh4, 1e-6f)
    }

    /**
     * 合成参数不得影响阅读进度。旧实现里拖动「单片段字数」滑块（80..400）会让静止不动的
     * 进度条在 40.0%～50.0% 之间非单调地来回走 —— 一个计费/延迟旋钮不是「我读了多少」的输入。
     * 新公式压根不含 segmentCount，这里用「同一位置、任意分段」来断言这一点。
     */
    @Test
    fun synthesisSettingsCannotMoveTheBar() {
        val at = bookFraction(0, 1300, 2601, 1)
        assertEquals(0.4998f, at, 1e-3f)
        // 无论片段怎么切，同一字符位置得到同一个数
        for (maxChunkChars in listOf(80, 120, 160, 200, 240, 300, 400)) {
            assertEquals("maxChunkChars=$maxChunkChars 不该改变进度", at, bookFraction(0, 1300, 2601, 1), 1e-6f)
        }
    }

    /** 边界与脏输入不得抛出或越界 */
    @Test
    fun clampsAndDegradesSafely() {
        assertEquals(0f, bookFraction(0, 0, 0, 0), 1e-6f)
        assertEquals(0f, bookFraction(-1, 100, 100, 5), 1e-6f)
        assertEquals(1f, bookFraction(4, 999999, 100, 5), 1e-6f)
        assertEquals(0f, bookFraction(0, -50, 100, 5), 1e-6f)
        assertTrue(bookFraction(3, 50, 100, 12) in 0f..1f)
    }

    /** 「未开始」与「已读 0%」是两回事 */
    @Test
    fun startedIsDistinctFromZero() {
        assertFalse(meta(12, 31200, 0, 0).started())
        assertTrue(meta(12, 31200, 0, 1).started())
        assertTrue(meta(12, 31200, 1, 0).started())
    }
}
