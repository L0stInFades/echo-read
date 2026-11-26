package app.echoread

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.echoread.core.ColorStyle
import app.echoread.core.ReaderSettings
import app.echoread.core.ThemeMode
import app.echoread.ui.dynamicSchemeOf
import app.echoread.ui.glass.Bezel
import app.echoread.ui.motion.Liquid
import app.echoread.ui.motion.dragToPos
import app.echoread.ui.motion.rubber
import app.echoread.ui.motion.settleIndex
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

    private val rest = Liquid.thumbRect(w = 300f, h = 44f, n = 3, pos = 1f, lift = 0f, velocity = 0f, recoil = 0f, inset = 4f, growW = 12f, growH = 9f)

    @Test
    fun thumbAtRestFillsItsCell() {
        // 三等分的中间一格：左右各留 4，上下各留 4
        assertEquals(104f, rest.left, 0.01f)
        assertEquals(196f, rest.right, 0.01f)
        assertEquals(4f, rest.top, 0.01f)
        assertEquals(40f, rest.bottom, 0.01f)
    }

    @Test
    fun liftedThumbGrowsAroundItsCentre() {
        val up = Liquid.thumbRect(300f, 44f, 3, 1f, lift = 1f, velocity = 0f, recoil = 0f, inset = 4f, growW = 12f, growH = 9f)
        assertEquals(rest.center.x, up.center.x, 0.01f)
        assertEquals(rest.center.y, up.center.y, 0.01f)
        assertEquals(rest.width + 12f, up.width, 0.01f)
        assertEquals(rest.height + 9f, up.height, 0.01f)
        // 悬起后比轨道还高一点：这就是它「浮出来」的那一下
        assertTrue(up.height > 44f)
    }

    @Test
    fun movingThumbStretchesAndFlattens() {
        val fast = Liquid.thumbRect(300f, 44f, 3, 1f, lift = 0f, velocity = 2f, recoil = 0f, inset = 4f, growW = 12f, growH = 9f)
        assertTrue("越快越长", fast.width > rest.width)
        assertTrue("越快越扁", fast.height < rest.height)
        // 方向无关：向左与向右拉得一样长
        val back = Liquid.thumbRect(300f, 44f, 3, 1f, lift = 0f, velocity = -2f, recoil = 0f, inset = 4f, growW = 12f, growH = 9f)
        assertEquals(fast.width, back.width, 0.001f)
        // 形变有上限：再快也不会被拉成一条线
        val absurd = Liquid.thumbRect(300f, 44f, 3, 1f, lift = 0f, velocity = 500f, recoil = 0f, inset = 4f, growW = 12f, growH = 9f)
        assertEquals(rest.width / 0.8f, absurd.width, 0.01f)
        assertEquals(rest.height * 0.8f, absurd.height, 0.01f)
    }

    @Test
    fun recoilSquashesOnLanding() {
        // 落位回弹量为负：变窄、变高，再弹回来
        val hit = Liquid.thumbRect(300f, 44f, 3, 1f, lift = 0f, velocity = 0f, recoil = -0.1f, inset = 4f, growW = 12f, growH = 9f)
        assertTrue(hit.width < rest.width)
        assertTrue(hit.height > rest.height)
        // 来得越快弹得越狠，但有上限
        assertTrue(Liquid.recoilKick(6f) < Liquid.recoilKick(0.5f))
        assertTrue(Liquid.recoilKick(1000f) >= -2.31f)
    }

    /**
     * 玻璃边缘的折射曲线（斯涅尔定律 + 超椭圆斜面）。这几条是它「像一块厚玻璃」的全部理由，
     * 着色器版与画布版都照这一个函数算，所以只在这里守一次。
     */
    @Test
    fun bezelBendsLightInwardAndOnlyNearTheRim() {
        val width = 44f
        val thickness = 100f
        fun at(x: Float) = Bezel.displacement(x, width, thickness)
        // 斜面内端表面已经是平的：光线不偏
        assertEquals(0f, at(1f), 0.01f)
        // 位移处处朝内（非负），峰值在贴边的地方，约为厚度的四成多
        var peak = 0f
        var peakAt = 0f
        for (i in 0..200) {
            val x = i / 200f
            val d = at(x)
            assertTrue("x=$x d=$d", d >= 0f)
            if (d > peak) { peak = d; peakAt = x }
        }
        assertTrue("peak=$peak", peak > thickness * 0.35f && peak < thickness * 0.75f)
        assertTrue("peakAt=$peakAt", peakAt < 0.12f)
        // 过了峰值单调下降，而且掉得快：斜面走到一半时只剩峰值的一成上下 —— 中间的画面几乎不动，只有贴边一圈被压扁
        var prev = at(0.12f)
        for (i in 13..100) {
            val d = at(i / 100f)
            assertTrue("monotone at $i", d <= prev + 1e-3f)
            prev = d
        }
        assertTrue(at(0.5f) < peak * 0.2f)
        // 没有厚度就没有折射；折射率写死 1.5（玻璃）
        assertEquals(0f, Bezel.displacement(0.2f, width, 0f), 0f)
        assertEquals(1.5f, Bezel.REFRACTIVE_INDEX, 0f)
        // 入射角再大，折射角也到不了全反射的临界值：结果始终是有限数
        assertTrue(at(0.0001f).isFinite())
    }

    @Test
    fun rubberBandSaturates() {
        assertEquals(0f, rubber(0f, 0.35f), 1e-6f)
        assertTrue(rubber(0.2f, 0.35f) < 0.2f)
        assertTrue(rubber(100f, 0.35f) < 0.35f)
        assertEquals(-rubber(0.3f, 0.35f), rubber(-0.3f, 0.35f), 1e-6f)
        // 轨道内一比一跟手；拖出两端后被橡皮筋拉住，最多多出 0.35 项
        assertEquals(1f, dragToPos(150f, 300f, 3), 0.001f)
        assertTrue(dragToPos(-500f, 300f, 3) > -0.35f)
        assertTrue(dragToPos(5000f, 300f, 3) < 2.35f)
    }

    @Test
    fun releaseSettlesOnTheNearestItemOrOneBeyond() {
        // 没有速度：落到最近的一项
        assertEquals(1, settleIndex(1.3f, 0f, 4, from = 0))
        assertEquals(2, settleIndex(1.6f, 0f, 4, from = 0))
        // 甩一下：往前多落一项，但绝不一口气越过两项
        assertEquals(2, settleIndex(1.2f, 5f, 4, from = 0))
        assertEquals(2, settleIndex(1.2f, 500f, 4, from = 0))
        assertEquals(0, settleIndex(1.2f, -500f, 4, from = 3))
        // 不会落到轨道外面
        assertEquals(3, settleIndex(3.2f, 50f, 4, from = 3))
        assertEquals(0, settleIndex(-0.2f, -50f, 4, from = 0))
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
