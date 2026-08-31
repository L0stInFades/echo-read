package app.echoread

import app.echoread.core.ColorStyle
import app.echoread.core.ReaderSettings
import app.echoread.ui.dynamicSchemeOf
import androidx.compose.ui.graphics.Color
import com.materialkolor.scheme.DynamicScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 配色生成的契约测试。
 *
 * 配色是运行时按「种子色 × 风格 × 明暗 × 对比度」现算的，不再是写死的常量 ——
 * 所以能保证它正确的只有这些不变量。它们全部是**可读性**约束：
 * 一套配色可以难看，但不能让文字读不出来。
 */
class ColorSchemeTest {

    private val seed = Color(ReaderSettings.DEFAULT_SEED)

    /** WCAG 相对亮度 */
    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = ch((argb shr 16) and 0xFF); val g = ch((argb shr 8) and 0xFF); val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val a = luminance(fg); val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun tone(argb: Int): Double = luminance(argb)

    private fun all(): List<Triple<ColorStyle, Boolean, DynamicScheme>> =
        ColorStyle.entries.flatMap { st ->
            listOf(false, true).map { dark -> Triple(st, dark, dynamicSchemeOf(seed, st, dark, 0f)) }
        }

    /**
     * 正文可读性：M3 的 on* 角色与其容器必须达到 WCAG AA（4.5:1）。
     * 这是整套算法存在的理由 —— 它不是在配好看的颜色，是在保证任何种子色下文字都读得出来。
     */
    @Test
    fun onColorsMeetTextContrast() {
        for ((style, dark, s) in all()) {
            val label = "$style ${if (dark) "深色" else "浅色"}"
            fun check(name: String, fg: Int, bg: Int) {
                val r = ratio(fg, bg)
                assertTrue("$label 的 $name 对比度 ${"%.2f".format(r)} < 4.5", r >= 4.5)
            }
            check("onSurface/surface", s.onSurface, s.surface)
            check("onPrimary/primary", s.onPrimary, s.primary)
            check("onSecondary/secondary", s.onSecondary, s.secondary)
            check("onTertiary/tertiary", s.onTertiary, s.tertiary)
            check("onError/error", s.onError, s.error)
            check("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            check("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            check("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer)
            check("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
            check("onBackground/background", s.onBackground, s.background)
        }
    }

    /** 描边/分隔线是非文本元素，门槛 3:1 */
    @Test
    fun outlinesMeetNonTextContrast() {
        for ((style, dark, s) in all()) {
            val r = ratio(s.outline, s.surface)
            assertTrue("$style ${if (dark) "深" else "浅"} 的 outline 对比度 ${"%.2f".format(r)} < 3.0", r >= 3.0)
        }
    }

    /**
     * 表面层级必须单调。应用靠这条阶梯表达「谁在上面」——
     * 书架的继续阅读卡用 surfaceContainerHigh、书格卡用 surfaceContainer，
     * 一旦阶梯塌了或反了，层级就传达不出来。
     */
    @Test
    fun surfaceLadderIsMonotonic() {
        for ((style, dark, s) in all()) {
            val ladder = listOf(
                "Lowest" to s.surfaceContainerLowest, "Low" to s.surfaceContainerLow,
                "Container" to s.surfaceContainer, "High" to s.surfaceContainerHigh,
                "Highest" to s.surfaceContainerHighest
            )
            val tones = ladder.map { tone(it.second) }
            // 浅色模式越往上越暗，深色模式越往上越亮；两种情况都必须严格单调
            val ok = if (dark) tones.zipWithNext().all { (a, b) -> b >= a }
            else tones.zipWithNext().all { (a, b) -> b <= a }
            assertTrue("$style ${if (dark) "深" else "浅"} 的表面阶梯非单调: " +
                ladder.zip(tones).joinToString { "${it.first.first}=${"%.3f".format(it.second)}" }, ok)
        }
    }

    /**
     * 摆给用户选的风格必须两两可区分，否则「可切换」是假的。
     * 只断言 [ColorStyle.PICKABLE]：全部九个变体里 FIDELITY 与 CONTENT 在固定种子色下
     * 主色完全相同（均为 #3758B8），它们是为图片取色设计的，已从选择器里排除。
     *
     * 断言的是**四个角色组成的整体**而不是单个主色。深色模式下算法会把 primary 统一拉到
     * 高明度档，TonalSpot / Vibrant / Rainbow 的 primary 实测都是 #B5C4FF ——
     * 区别落在 primaryContainer（#344479 / #003CAC / #254190）与次、三色上。
     * 这条实测结论直接决定了风格色卡必须画多个颜色，只画主色在深色模式下会有三个一模一样的选项。
     */
    @Test
    fun pickableStylesAreDistinct() {
        for (dark in listOf(false, true)) {
            val palettes = ColorStyle.PICKABLE.map {
                val s = dynamicSchemeOf(seed, it, dark, 0f)
                listOf(s.primary, s.primaryContainer, s.secondary, s.tertiary)
            }
            assertEquals(
                "${if (dark) "深" else "浅"}色下可选风格的整体色板应两两不同",
                ColorStyle.PICKABLE.size, palettes.toSet().size
            )
        }
    }

    /** 风格色卡至少要画哪几个颜色，才能在两种明暗下都区分开所有可选风格 */
    @Test
    fun styleSwatchNeedsMoreThanPrimary() {
        val darkPrimaries = ColorStyle.PICKABLE.map { dynamicSchemeOf(seed, it, true, 0f).primary }
        assertTrue(
            "若这条不再成立，说明算法变了，风格色卡可以简化为单色",
            darkPrimaries.toSet().size < ColorStyle.PICKABLE.size
        )
        val withContainer = ColorStyle.PICKABLE.map {
            val s = dynamicSchemeOf(seed, it, true, 0f); s.primary to s.primaryContainer
        }
        assertEquals("主色 + 容器色即可区分全部可选风格", ColorStyle.PICKABLE.size, withContainer.toSet().size)
    }

    /** 提高对比度设置必须真的提高对比度 */
    @Test
    fun higherContrastActuallyIncreasesContrast() {
        for (dark in listOf(false, true)) {
            val lo = dynamicSchemeOf(seed, ColorStyle.TONAL_SPOT, dark, 0f)
            val hi = dynamicSchemeOf(seed, ColorStyle.TONAL_SPOT, dark, 1f)
            val rLo = ratio(lo.onSurface, lo.surface)
            val rHi = ratio(hi.onSurface, hi.surface)
            assertTrue("${if (dark) "深" else "浅"}色下高对比度未提升：$rLo → $rHi", rHi >= rLo)
        }
    }

    /**
     * 默认配色的回归锁。
     * 这些值是 0.1.x 起就在用的品牌色板，当初由 Google 自己的工具生成并人工核验过；
     * 换成运行时生成后仍必须逐位复现，否则就是算法或参数被动过了。
     */
    @Test
    fun defaultPaletteIsUnchanged() {
        val s = dynamicSchemeOf(seed, ColorStyle.TONAL_SPOT, dark = false, contrast = 0f)
        fun hx(v: Int) = "%06X".format(v and 0xFFFFFF)
        assertEquals("4C5C92", hx(s.primary))
        assertEquals("FFFFFF", hx(s.onPrimary))
        assertEquals("DBE1FF", hx(s.primaryContainer))
        assertEquals("595E72", hx(s.secondary))
        assertEquals("745470", hx(s.tertiary))
        assertEquals("FAF8FF", hx(s.surface))
        assertEquals("1A1B21", hx(s.onSurface))
        assertEquals("C6C6D0", hx(s.outlineVariant))
        assertEquals("BA1A1A", hx(s.error))
    }
}
