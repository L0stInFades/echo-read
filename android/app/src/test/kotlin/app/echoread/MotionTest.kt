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
        // 稳定时间 ≈ 4/(ζ·ω)，Standard 档约 210ms
        assertTrue("settle=${s.settleMs}", s.settleMs in 190..230)
        assertTrue("instant=${EchoMotion.Instant.settleMs}", EchoMotion.Instant.settleMs in 80..110)
        assertTrue("track=${EchoMotion.Track.settleMs}", EchoMotion.Track.settleMs in 120..150)
        assertTrue("emphasized=${EchoMotion.Emphasized.settleMs}", EchoMotion.Emphasized.settleMs in 280..320)
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
