package app.echoread

import app.echoread.ui.motion.Decay
import app.echoread.ui.motion.Rubber
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.Spring2
import app.echoread.ui.motion.applyRubberBand
import app.echoread.ui.motion.settleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** 动画管线里可以脱离 UI 验证的纯函数：弹簧换算、落点判定、橡皮筋 */
class MotionTest {

    @Test
    fun springParameterization() {
        val s = Spring2(0.30f, 0.90f)
        val omega = (2 * PI / 0.30).toFloat()
        assertEquals(omega.toDouble(), s.omega.toDouble(), 1e-3)
        assertEquals((omega * omega).toDouble(), s.stiffness.toDouble(), 1e-1)
        // 稳定时间 ≈ 4/(ζ·ω)
        assertTrue("settle=${s.settleMs}", s.settleMs in 190..230)
    }

    /**
     * 0.2.0-exp 的动效契约：八档里有五档必须与 Material 3 的动效 token **数值相等**。
     *
     * 这些数字取自 material3 1.5.0-alpha18 里 StandardMotionTokens / ExpressiveMotionTokens 的字节码常量。
     * 谁要是随手改了 [EchoMotion] 的取值，这里就会红 —— 这正是「谷歌动画标准 × 自研 CA 管线」这条融合线的守门测试。
     */
    @Test
    fun echoMotionMatchesMaterial3Tokens() {
        // token 名 to (ζ, stiffness)
        fun check(name: String, spring: Spring2, damping: Float, stiffness: Float) {
            assertEquals("$name damping", damping.toDouble(), spring.damping.toDouble(), 1e-4)
            // response 只保留三位小数，刚度允许 1% 误差
            assertEquals("$name stiffness", stiffness.toDouble(), spring.stiffness.toDouble(), stiffness * 0.01)
        }
        check("Flash = M3 fastEffects", EchoMotion.Flash, 1.0f, 3800f)
        check("Instant = M3 defaultEffects", EchoMotion.Instant, 1.0f, 1600f)
        check("Standard = M3 standard defaultSpatial", EchoMotion.Standard, 0.9f, 700f)
        check("Playful = M3 expressive fastSpatial", EchoMotion.Playful, 0.6f, 800f)
        check("Emphasized = M3 expressive defaultSpatial", EchoMotion.Emphasized, 0.8f, 380f)
        check("Expand = M3 expressive slowSpatial", EchoMotion.Expand, 0.8f, 200f)
    }

    /**
     * 两档刻意不跟随 Google，理由写在 MotionTokens.kt 里，这里把「刻意」钉死成断言：
     * - Track 是手势 settle 专用，绝不能带上 Expressive fastSpatial 的 ζ=0.6（9.5% 过冲 = 正文滑出页边再弹回）。
     * - Gentle 必须是临界阻尼：颜色一旦过冲就会溢出色域，观感上是一次闪烁。
     */
    @Test
    fun ourOwnTiersStayOurs() {
        assertEquals(0.95f, EchoMotion.Track.damping)
        assertTrue("track 必须几乎不过冲", EchoMotion.Track.damping >= 0.9f)
        // 刚度借自 Expressive fastSpatial（800），阻尼是我们自己的
        assertEquals(800.0, EchoMotion.Track.stiffness.toDouble(), 40.0)

        assertEquals(1.0f, EchoMotion.Gentle.damping)
        // 与 Google 最慢的动效同时长（约 350ms），但零过冲
        assertTrue("gentle=${EchoMotion.Gentle.settleMs}", EchoMotion.Gentle.settleMs in 330..370)

        // effects 三档一律临界阻尼 —— 与 M3 两套 scheme 的硬约束一致
        for (s in listOf(EchoMotion.Flash, EchoMotion.Instant, EchoMotion.Gentle)) {
            assertEquals("effects 档不得过冲", 1.0f, s.damping)
        }
    }

    private val pageCandidates = listOf(-1f, 0f, 1f)

    @Test
    fun settleStaysWhenBarelyDragged() {
        // 拖了 20% 又几乎没速度 → 回原页
        assertEquals(0f, settleTarget(0.20f, 0f, pageCandidates))
        assertEquals(0f, settleTarget(-0.20f, 0f, pageCandidates))
    }

    @Test
    fun settleAdvancesPastHalf() {
        assertEquals(1f, settleTarget(0.60f, 0f, pageCandidates))
        assertEquals(-1f, settleTarget(-0.60f, 0f, pageCandidates))
    }

    @Test
    fun settleFollowsEscapeVelocityEvenWhenBarelyMoved() {
        // 轻扫：位移远不到一半，但速度超过逃逸阈值 → 必须翻页（旧实现纯看位移，"甩不动"）
        val v = Decay.EscapeVel + 0.5f
        assertEquals(1f, settleTarget(0.08f, v, pageCandidates))
        assertEquals(-1f, settleTarget(-0.08f, -v, pageCandidates))
    }

    @Test
    fun settleCanReverse() {
        // 已经拖过一半，但反向甩回来 → 回原页（可反向）
        assertEquals(0f, settleTarget(0.62f, -(Decay.EscapeVel + 0.5f), pageCandidates))
    }

    @Test
    fun settleUsesProjection() {
        // 位移 0.4、速度 1.0 单位/秒：投影 0.4 + 1.0*0.12 = 0.52 → 越过中点
        assertEquals(1f, settleTarget(0.40f, 1.0f, pageCandidates))
        // 同样位移但速度 0.3：投影 0.436 → 不翻
        assertEquals(0f, settleTarget(0.40f, 0.3f, pageCandidates))
    }

    @Test
    fun settleRespectsRestrictedCandidates() {
        // 书尾：候选里没有 +1，再怎么甩也只能回到 0
        assertEquals(0f, settleTarget(0.30f, 5f, listOf(-1f, 0f)))
    }

    @Test
    fun rubberBandIsIdentityInsideBounds() {
        val b = -1f..1f
        assertEquals(0.5f, applyRubberBand(0.5f, b))
        assertEquals(-1f, applyRubberBand(-1f, b))
        assertEquals(1f, applyRubberBand(1f, b))
    }

    @Test
    fun rubberBandCompressesAndStaysBounded() {
        val b = 0f..0f
        var prev = 0f
        for (i in 1..40) {
            val x = i * 0.25f
            val y = applyRubberBand(x, b)
            assertTrue("单调", y > prev)
            assertTrue("压缩：$x -> $y", y < x)
            assertTrue("有界：$y", y < 1f)
            prev = y
        }
        // 对称
        assertEquals(-applyRubberBand(2f, b), applyRubberBand(-2f, b), 1e-6f)
    }

    @Test
    fun rubberBandInitialResistanceIsC() {
        // 刚越界时的斜率就是 UIScrollView 的 C=0.55：手指动 10px 边界外只走 5.5px
        val b = 0f..0f
        val y = applyRubberBand(0.02f, b)
        assertEquals(0.02f * Rubber.C, y, 3e-4f)
    }
}
