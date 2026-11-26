package app.echoread

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.echoread.core.ColorStyle
import app.echoread.core.ReaderSettings
import app.echoread.core.ThemeMode
import app.echoread.ui.dynamicSchemeOf
import app.echoread.ui.glass.Optics
import app.echoread.ui.motion.LensRules
import app.echoread.ui.motion.LensSpring
import app.echoread.ui.motion.SegmentLayout
import app.echoread.ui.motion.rubber
import app.echoread.ui.toColorScheme
import app.echoread.ui.toPureBlack
import com.materialkolor.hct.Hct
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.0：液态控件的运动规则、纯黑配色、主题设置 —— 都是可以脱离界面验证的纯函数 */
class LiquidTest {

    // 三项、300 宽、40 高的轨道：留白 4，相邻透镜重叠 8 → 静止透镜宽 (292 + 16) / 3，中心间距再减 8
    private val geo = SegmentLayout(width = 300f, height = 40f, n = 3, pad = 4f, overlap = 8f)

    @Test
    fun restingLensFillsTheInnerHeightAndOverlapsItsNeighbours() {
        assertEquals((292f + 16f) / 3f, geo.lens, 0.001f)
        assertEquals(geo.lens - 8f, geo.pitch, 0.001f)
        val first = geo.lensRect(0f, lift = 0f, jelly = 0f, growth = 16f)
        val last = geo.lensRect(2f, lift = 0f, jelly = 0f, growth = 16f)
        // 两端的透镜正好贴住轨道内缘，上下各留 4
        assertEquals(4f, first.left, 0.01f)
        assertEquals(296f, last.right, 0.01f)
        assertEquals(4f, first.top, 0.01f)
        assertEquals(36f, first.bottom, 0.01f)
        // 相邻两枚透镜压住 8
        val middle = geo.lensRect(1f, 0f, 0f, 16f)
        assertEquals(8f, first.right - middle.left, 0.01f)
    }

    @Test
    fun liftGrowsTheLensAroundItsCentreAndJellyTradesHeightForWidth() {
        val rest = geo.lensRect(1f, 0f, 0f, 16f)
        val up = geo.lensRect(1f, 1f, 0f, 16f)
        assertEquals(rest.center.x, up.center.x, 0.01f)
        assertEquals(rest.center.y, up.center.y, 0.01f)
        assertEquals(rest.width + 16f, up.width, 0.01f)
        // 悬起后比轨道高 8：上下各伸出去 4
        assertEquals(geo.height + 8f, up.height, 0.01f)
        val stretched = geo.lensRect(1f, 1f, 0.1f, 16f)
        assertEquals(up.width * 1.1f, stretched.width, 0.01f)
        assertEquals(up.height * (1f - 0.14f), stretched.height, 0.01f)
    }

    @Test
    fun fingerMapsOntoSegmentsWithARubberBandPastTheEnds() {
        for (i in 0..2) assertEquals(i.toFloat(), geo.positionAt(geo.centerX(i.toFloat())), 0.001f)
        assertEquals(1, geo.indexAt(geo.positionAt(geo.centerX(1f) + geo.pitch * 0.45f)))
        assertEquals(2, geo.indexAt(geo.positionAt(geo.centerX(1f) + geo.pitch * 0.55f)))
        // 拖出两端：被橡皮筋拉住，最多多出 0.35 项
        assertTrue(geo.positionAt(-500f) > -0.35f && geo.positionAt(-500f) < 0f)
        assertTrue(geo.positionAt(5000f) < 2.35f && geo.positionAt(5000f) > 2f)
        assertEquals(0, geo.indexAt(-0.3f))
        assertEquals(2, geo.indexAt(2.3f))
        assertEquals(0f, rubber(0f, 0.35f), 1e-6f)
        assertEquals(-rubber(0.3f, 0.35f), rubber(-0.3f, 0.35f), 1e-6f)
    }

    @Test
    fun jellyStretchesWithSpeedAndSquashesWhenBraking() {
        assertEquals(0f, LensRules.jellyTarget(0f, 0f), 1e-6f)
        val fast = LensRules.jellyTarget(9f, 0f)
        assertTrue("越快越长", fast > LensRules.jellyTarget(3f, 0f))
        assertTrue("有上限", LensRules.jellyTarget(1000f, 0f) <= 0.2f)
        // 方向无关
        assertEquals(fast, LensRules.jellyTarget(-9f, 0f), 1e-6f)
        // 刹车（加速度与速度反向）时往回收，最多收 0.15
        assertTrue(LensRules.jellyTarget(0.5f, -400f) < 0f)
        assertTrue(LensRules.jellyTarget(0.0001f, -1e6f) >= -0.15f)
        // 同向加速不算刹车
        assertEquals(LensRules.jellyTarget(2f, 0f), LensRules.jellyTarget(2f, 300f), 1e-6f)
    }

    @Test
    fun releaseProjectsTheFlingButMovesAtMostOneBeyondThePressedSegment() {
        assertEquals(1, LensRules.flingTarget(1.3f, 0f, pressed = 1, n = 4))
        assertEquals(2, LensRules.flingTarget(1.3f, 5f, pressed = 1, n = 4))
        assertEquals(2, LensRules.flingTarget(1.3f, 500f, pressed = 1, n = 4))
        assertEquals(0, LensRules.flingTarget(1.3f, -500f, pressed = 1, n = 4))
        assertEquals(3, LensRules.flingTarget(3.2f, 50f, pressed = 3, n = 4))
        assertEquals(0, LensRules.flingTarget(-0.2f, -50f, pressed = 0, n = 4))
    }

    @Test
    fun liftDropsOnlyOnceReleasedRisenAndArrived() {
        assertTrue(LensRules.mayDrop(released = true, lift = 0.98f, target = 2f, value = 1.97f))
        assertFalse("还按着", LensRules.mayDrop(released = false, lift = 1f, target = 2f, value = 2f))
        assertFalse("还没升满", LensRules.mayDrop(released = true, lift = 0.9f, target = 2f, value = 2f))
        assertFalse("还没到位", LensRules.mayDrop(released = true, lift = 1f, target = 2f, value = 1.9f))
    }

    @Test
    fun springParametersMatchTheirPerceptualDefinitions() {
        // 400 ms、回弹 0.15 → k = (2π/0.4)² ≈ 246.7，ζ = 0.85
        assertEquals(246.74f, LensRules.Travel.k, 0.05f)
        assertEquals(2f * 0.85f * kotlin.math.sqrt(246.74f), LensRules.Travel.c, 0.05f)
        assertEquals(631.65f, LensRules.Lift.k, 0.05f)
        assertEquals(2f * kotlin.math.sqrt(631.65f), LensRules.Lift.c, 0.05f)
        assertEquals(2f * 0.9f * 32f, LensRules.Follow.c, 0.001f)
    }

    @Test
    fun springSettlesExactlyAndRetargetsWithoutLosingItsVelocity() {
        val s = LensSpring(0f)
        s.aim(2f, LensRules.Travel)
        var t = 0f
        var peak = 0f
        while (s.step(1f / 60f)) {
            t += 1f / 60f
            peak = maxOf(peak, s.value)
            assertTrue("never runs away", t < 3f)
        }
        // 停下时正好落在目标上，过冲很小（回弹 0.15 的弹簧约 0.6%）
        assertEquals(2f, s.value, 0f)
        assertEquals(0f, s.velocity, 0f)
        assertTrue("overshoot=$peak", peak > 2f && peak < 2f * 1.01f)
        // 中途改目标：值与速度原样接着走，下一帧就动，不会原地停一帧
        val r = LensSpring(0f)
        r.aim(1f, LensRules.Follow)
        repeat(5) { r.step(1f / 60f) }
        val v = r.velocity
        val x = r.value
        r.aim(3f, LensRules.Follow)
        assertEquals(v, r.velocity, 0f)
        r.step(1f / 60f)
        assertTrue(r.value > x)
    }

    /** 普通玻璃的斜面（规格里的圆弧斜面）：平面不动，斜面先放大，最外一圈读到最深处、画面翻转 */
    @Test
    fun bevelReadsInwardAndDeepestAtTheRim() {
        val b = 20f
        val depth = 44f
        assertEquals(0f, Optics.inward(b, b, depth), 0f)
        assertEquals(0f, Optics.inward(30f, b, depth), 0f)
        assertEquals(depth, Optics.inward(0f, b, depth), 0.001f)
        // 往外单调变深
        var prev = 0f
        for (i in 20 downTo 0) {
            val v = Optics.inward(i.toFloat(), b, depth)
            assertTrue("monotone at $i", v >= prev - 1e-4f)
            prev = v
        }
        // 平面相接处几乎不动（斜率为 0，看不出接缝）
        assertTrue(Optics.inward(19f, b, depth) < 0.2f)
        // 最外一圈：取样点（离边距离 + 位移）往外反而变深 —— 那一圈是翻转的
        fun sampled(d: Float) = d + Optics.inward(d, b, depth)
        assertTrue(sampled(0f) > sampled(3f))
        assertEquals(0f, Optics.inward(5f, b, 0f), 0f)
    }

    @Test
    fun pureBlackTurnsThePageOffAndKeepsTheLadder() {
        val seed = Color(ReaderSettings.DEFAULT_SEED)
        for (style in ColorStyle.entries) {
            val base = dynamicSchemeOf(seed, style, dark = true).toColorScheme()
            val black = base.toPureBlack()
            assertEquals("$style surface", 0xFF000000.toInt(), black.surface.toArgb())
            assertEquals("$style background", 0xFF000000.toInt(), black.background.toArgb())
            assertEquals("$style lowest", 0xFF000000.toInt(), black.surfaceContainerLowest.toArgb())
            fun tone(c: Color) = Hct.fromInt(c.toArgb()).tone
            // 容器仍然一级比一级亮，而且每一级都比原来暗
            val ladder = listOf(black.surfaceContainerLow, black.surfaceContainer, black.surfaceContainerHigh, black.surfaceContainerHighest)
            for (i in 1 until ladder.size) assertTrue("$style ladder $i", tone(ladder[i]) >= tone(ladder[i - 1]))
            assertTrue("$style highest is raised", tone(black.surfaceContainerHighest) > 3.0)
            assertTrue("$style darker", tone(black.surfaceContainerHigh) < tone(base.surfaceContainerHigh))
            // 强调色与文字色一个都不动
            assertEquals(base.primary, black.primary)
            assertEquals(base.onSurface, black.onSurface)
            assertEquals(base.onSurfaceVariant, black.onSurfaceVariant)
            assertEquals(base.outline, black.outline)
        }
    }

    @Test
    fun themeSettingsDefaultForOldSaves() {
        val json = Json { ignoreUnknownKeys = true }
        // 0.5.x 的存档里没有这两个键：跟随系统、不开纯黑
        val old = json.decodeFromString(ReaderSettings.serializer(), """{"theme":"paper","glass":true}""")
        assertEquals(ThemeMode.SYSTEM, old.themeMode)
        assertFalse(old.pureBlack)
        val saved = json.decodeFromString(ReaderSettings.serializer(), """{"themeMode":"dark","pureBlack":true}""")
        assertEquals(ThemeMode.DARK, saved.themeMode)
        assertTrue(saved.pureBlack)
        assertTrue(json.encodeToString(ReaderSettings.serializer(), saved).contains("\"themeMode\":\"dark\""))
    }
}
