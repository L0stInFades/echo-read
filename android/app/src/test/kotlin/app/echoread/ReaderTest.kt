package app.echoread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextLayoutResult
import app.echoread.core.ColorStyle
import app.echoread.core.GestureSettings
import app.echoread.core.PageAxis
import app.echoread.core.ReaderSettings
import app.echoread.core.ThemeMode
import app.echoread.core.i18n.AppLanguage
import app.echoread.data.DerivedChapter
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.reader.ChapterPages
import app.echoread.ui.reader.ChapterWindow
import app.echoread.ui.reader.LayoutSpec
import app.echoread.ui.reader.PageRef
import app.echoread.ui.reader.ReaderPager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读器里脱离界面也能验证的一层：章节窗口、翻页器、排版规格 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTest {

    /* ---------------- 章节窗口 ---------------- */

    @Test
    fun windowResolvesAcrossChapterEdgesOnlyWhenTheNeighbourIsLaid() {
        val w = ChapterWindow()
        w.put(1, pages(3), spec)
        assertEquals(PageRef(1, 2), w.resolve(PageRef(1, 1), 1))
        assertEquals(PageRef(1, 0), w.resolve(PageRef(1, 1), -1))
        // 邻章还没排好：翻不过去，交给橡皮筋
        assertNull(w.resolve(PageRef(1, 2), 1))
        assertNull(w.resolve(PageRef(1, 0), -1))
        // 锚点所在的章不在窗口里：什么都解析不出来
        assertNull(w.resolve(PageRef(2, 0), 0))
        // delta = 0 把越界的页码钳回本章（重排版后页数变少）
        assertEquals(PageRef(1, 2), w.resolve(PageRef(1, 7), 0))

        w.put(0, pages(2), spec)
        w.put(2, pages(4), spec)
        // 往回翻进上一章的末页，往后翻进下一章的首页
        assertEquals(PageRef(0, 1), w.resolve(PageRef(1, 0), -1))
        assertEquals(PageRef(2, 0), w.resolve(PageRef(1, 2), 1))
    }

    @Test
    fun windowKeepsOnlyTheCentreAndItsNeighbours() {
        val w = ChapterWindow()
        for (ch in 0..4) w.put(ch, pages(2), spec)
        w.retain(2)
        assertEquals(listOf(false, true, true, true, false), (0..4).map { w.pagesOf(it) != null })
        assertNull(w.specOf(0))
        assertNull(w.specOf(4))
        assertEquals(spec, w.specOf(3))
    }

    /* ---------------- 翻页器 ---------------- */

    @Test
    fun boundsOpenOnlyTowardsPagesThatCanBeShown() = runTest {
        val w = ChapterWindow().apply { chapterCount = 2; put(0, pages(2), spec) }
        val pager = pagerOn(w)
        // 书首：不能往回
        assertEquals(0f..1f, pager.bounds())
        pager.jumpTo(PageRef(0, 1), crossFade = false)
        // 章尾，下一章还没排好：只能往回
        assertEquals(-1f..0f, pager.bounds())
        w.put(1, pages(3), spec)
        assertEquals(-1f..1f, pager.bounds())
    }

    @Test
    fun tapFlipIntoTheNextChapterLandsOnItsFirstPage() = runTest {
        val w = ChapterWindow().apply { chapterCount = 2; put(0, pages(2), spec); put(1, pages(3), spec) }
        val driver = MotionDriver()
        val pager = pagerOn(w, driver)
        pager.jumpTo(PageRef(0, 1), crossFade = false)
        pager.flip(1)
        advanceUntilIdle()
        // 到位那一下原子换页：锚点进到下一章首页，呈现值同时平移回 0，画面停在新页上
        assertEquals(PageRef(1, 0), pager.anchor)
        assertEquals(0f, driver.value, 0f)
        assertEquals(PageRef(1, 0), pager.displayed.value)
        assertTrue("点按翻页算用户驱动（触感反馈只给它）", pager.userDriven)
    }

    @Test
    fun flipPastTheLastLaidPageIsBlockedAndReported() = runTest {
        val w = ChapterWindow().apply { chapterCount = 2; put(0, pages(2), spec) }
        val driver = MotionDriver()
        val pager = pagerOn(w, driver)
        var blocked = 0
        pager.onBlocked = { blocked = it }
        pager.jumpTo(PageRef(0, 1), crossFade = false)
        pager.flip(1)
        advanceUntilIdle()
        assertEquals(1, blocked)
        assertEquals(PageRef(0, 1), pager.anchor)
        assertEquals(0f, driver.value, 0f)
    }

    @Test
    fun releaseCommitsOnlyWhenTheDragCrossesOver() = runTest {
        val w = ChapterWindow().apply { chapterCount = 1; put(0, pages(3), spec) }
        val driver = MotionDriver()
        val pager = pagerOn(w, driver)
        // 拖过一大半松手：翻到下一页
        driver.drive { dragTo(0.7f) }
        pager.settle(0f)
        advanceUntilIdle()
        assertEquals(PageRef(0, 1), pager.anchor)
        assertEquals(0f, driver.value, 0f)
        // 只拖了一点：弹回原页，锚点不动
        driver.drive { dragTo(-0.2f) }
        pager.settle(0f)
        advanceUntilIdle()
        assertEquals(PageRef(0, 1), pager.anchor)
        assertEquals(0f, driver.value, 0f)
    }

    @Test
    fun rebaseCommitsModelAndPresentationInOneSnapshot() {
        val driver = MotionDriver(1f)
        var page by mutableIntStateOf(0)
        // 先把别的测试留在全局快照里的改动发出去，下面只看 rebase 这一次提交
        Snapshot.sendApplyNotifications()
        val seen = ArrayList<Pair<Int, Float>>()
        val observer = Snapshot.registerApplyObserver { _, _ -> seen += page to driver.value }
        try {
            driver.rebase(1f) { page = 1 }
        } finally {
            observer.dispose()
        }
        // 只有一次提交，提交时两样都已经变了：任何一帧都看不到「换了页、位移还没平移回来」
        assertEquals(listOf(1 to 0f), seen)
    }

    /* ---------------- 排版规格 ---------------- */

    @Test
    fun appearanceOnlySettingsDoNotChangeTheLayoutSpec() {
        val base = ReaderSettings()
        val appearance = listOf(
            base.copy(haptics = false),
            base.copy(gestures = GestureSettings(axis = PageAxis.VERTICAL, prevZone = 0.3f, tapToRead = false)),
            base.copy(dynamicColor = true),
            base.copy(seedColor = 0xFF123456.toInt()),
            base.copy(colorStyle = ColorStyle.VIBRANT),
            base.copy(contrast = 1f),
            base.copy(language = AppLanguage.EN),
            base.copy(glass = false),
            base.copy(themeMode = ThemeMode.DARK),
            base.copy(pureBlack = true)
        )
        for (r in appearance) assertEquals(r.toString(), spec, LayoutSpec.of(r, WIDTH, HEIGHT))
    }

    @Test
    fun everyLayoutFieldChangesTheLayoutSpec() {
        val base = ReaderSettings()
        val layout = mapOf(
            "theme" to base.copy(theme = "paper"),
            "fontSize" to base.copy(fontSize = base.fontSize + 1),
            "lineHeight" to base.copy(lineHeight = base.lineHeight + 0.1f),
            "fontFamily" to base.copy(fontFamily = "sans"),
            "paraSpacing" to base.copy(paraSpacing = base.paraSpacing + 0.5f)
        )
        for ((field, r) in layout) assertNotEquals(field, spec, LayoutSpec.of(r, WIDTH, HEIGHT))
        assertNotEquals("width", spec, LayoutSpec.of(base, WIDTH - 1, HEIGHT))
        assertNotEquals("height", spec, LayoutSpec.of(base, WIDTH, HEIGHT - 1))
    }

    private fun TestScope.pagerOn(window: ChapterWindow, driver: MotionDriver = MotionDriver()) =
        ReaderPager(driver, window, this + VirtualFrameClock(testScheduler))

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1800
        val spec = LayoutSpec.of(ReaderSettings(), WIDTH, HEIGHT)

        /**
         * 一章 [n] 页（每页一行）的排版结果。翻页逻辑只看页数；JVM 单测里造不出真的 TextLayoutResult
         * （它要走平台排版），这里绕过构造函数分配一个空壳占位 —— 只要没人去读它的内容就没事。
         */
        fun pages(n: Int) = ChapterPages(DerivedChapter("t", "x", emptyList(), emptyList()), blankLayout, 0, List(n) { it..it })

        val blankLayout: TextLayoutResult by lazy {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, TextLayoutResult::class.java) as TextLayoutResult
        }
    }
}
