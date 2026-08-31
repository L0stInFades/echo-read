package app.echoread

import app.echoread.core.GestureSettings
import app.echoread.core.PageAxis
import app.echoread.core.ReaderSettings
import app.echoread.data.BookScanner
import app.echoread.data.SettingsStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.0-exp 新增的翻页手势配置：默认值必须与 0.1.x 的固定行为逐项等价，
 * 值域守卫必须挡住会把阅读器变砖的取值，旧存档必须还能解码。
 */
class GestureTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }

    @Test
    fun defaultsReproduceLegacyBehaviour() {
        val g = GestureSettings()
        // 0.1.x：左右滑翻页；左右各 20% 点击热区；中间轻点朗读
        assertEquals(PageAxis.HORIZONTAL, g.axis)
        assertEquals(PageAxis.HORIZONTAL, g.tapAxis)
        assertTrue(g.tapTurn)
        assertEquals(0.2f, g.prevZone)
        assertEquals(0.2f, g.nextZone)
        assertFalse(g.invertZones)
        assertTrue(g.tapToRead)
        assertEquals(1f, g.slopScale)
        assertTrue(g.zonesActive)
    }

    @Test
    fun oldStoredJsonWithoutGesturesStillDecodes() {
        // 0.1.6 写出来的阅读设置里没有 gestures / dynamicColor 这两个键
        val legacy = """{"theme":"paper","fontSize":21,"lineHeight":2.0,"fontFamily":"sans","paraSpacing":1.2,"haptics":false}"""
        val r = json.decodeFromString(ReaderSettings.serializer(), legacy)
        assertEquals("paper", r.theme)
        assertEquals(21, r.fontSize)
        assertFalse(r.haptics)
        // 缺失的新键回落默认值 = 旧行为
        assertEquals(GestureSettings(), r.gestures)
        assertFalse(r.dynamicColor)
    }

    @Test
    fun unknownEnumValueFallsBackInsteadOfThrowing() {
        // coerceInputValues + 有默认值的属性：未知枚举值回落默认，而不是抛异常炸掉整个设置
        val weird = """{"gestures":{"axis":"diagonal"}}"""
        val r = json.decodeFromString(ReaderSettings.serializer(), weird)
        assertEquals(PageAxis.HORIZONTAL, r.gestures.axis)
    }

    @Test
    fun sanitizeClampsZones() {
        // 越界热区：单侧封顶 0.5
        val huge = SettingsStore.sanitizeGestures(GestureSettings(prevZone = 9f, nextZone = 9f))
        assertTrue(huge.prevZone <= 0.5f)
        assertTrue(huge.nextZone <= 0.5f)
        // 开着「轻点朗读」时必须给中间留出可点读的带子
        assertTrue("两侧之和=${huge.prevZone + huge.nextZone}", huge.prevZone + huge.nextZone <= 0.8f + 1e-4f)

        // 关掉朗读则允许各占一半、整页都是翻页区
        val noRead = SettingsStore.sanitizeGestures(GestureSettings(prevZone = 0.5f, nextZone = 0.5f, tapToRead = false))
        assertEquals(0.5f, noRead.prevZone)
        assertEquals(0.5f, noRead.nextZone)
    }

    @Test
    fun sanitizeRejectsNonFiniteAndOutOfRangeSlop() {
        val nan = SettingsStore.sanitizeGestures(GestureSettings(prevZone = Float.NaN, nextZone = Float.NEGATIVE_INFINITY, slopScale = Float.NaN))
        assertEquals(0.2f, nan.prevZone)
        assertEquals(0.2f, nan.nextZone)
        assertEquals(1f, nan.slopScale)
        // slop 归零会让任何一次点按都被判成拖动 —— 必须钳住
        assertEquals(0.5f, SettingsStore.sanitizeGestures(GestureSettings(slopScale = 0f)).slopScale)
        assertEquals(3f, SettingsStore.sanitizeGestures(GestureSettings(slopScale = 99f)).slopScale)
    }

    @Test
    fun sanitizeReaderAlsoSanitizesNestedGestures() {
        val r = SettingsStore.sanitizeReader(ReaderSettings(gestures = GestureSettings(prevZone = 5f, slopScale = -1f)))
        assertTrue(r.gestures.prevZone <= 0.5f)
        // 有限但越界的值一律钳到边界（只有 NaN/Inf 才回落默认），与 sanitizeRejectsNonFiniteAndOutOfRangeSlop 同语义
        assertEquals(0.5f, r.gestures.slopScale)
    }

    @Test
    fun zonesActiveReflectsRealUsability() {
        assertFalse(GestureSettings(tapTurn = false).zonesActive)
        assertFalse(GestureSettings(tapAxis = PageAxis.OFF).zonesActive)
        // 两侧都是 0 等同于没有热区
        assertFalse(GestureSettings(prevZone = 0f, nextZone = 0f).zonesActive)
        assertTrue(GestureSettings(prevZone = 0f, nextZone = 0.15f).zonesActive)
    }

    @Test
    fun scannerRecognisesOnlyBookExtensions() {
        assertTrue(BookScanner.isBook("三体.txt"))
        assertTrue(BookScanner.isBook("Dune.EPUB"))
        assertTrue(BookScanner.isBook("a.TxT"))
        assertFalse(BookScanner.isBook("cover.jpg"))
        assertFalse(BookScanner.isBook("notes.txt.bak"))
        assertFalse(BookScanner.isBook("txt"))
    }
}
