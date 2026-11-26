package app.echoread

import app.echoread.ui.motion.LensMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选中透镜的时间线，对照行为规格里那张逐帧表（导航栏透镜，60 Hz）：点一下从 0 到 2、按住、拖动、外部改选择。
 * 帧时钟是 16 ms 一帧的虚拟时间，所以时刻只要求落在一两帧之内。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LensMotionTest {

    private class Frame(val t: Long, val pos: Float, val lift: Float, val jelly: Float)

    /** 从 0 开始逐帧推进 [ms] 毫秒；[at] 里登记的动作在对应时刻（之后的第一帧前）执行 */
    private fun TestScope.run(ms: Long, start: Float = 0f, at: Map<Long, (LensMotion) -> Unit>): List<Frame> {
        val job = Job()
        val motion = LensMotion(start, CoroutineScope(coroutineContext + job + VirtualFrameClock(testScheduler)))
        val pending = at.toSortedMap()
        val out = ArrayList<Frame>()
        var t = 0L
        while (t <= ms) {
            while (pending.isNotEmpty() && pending.firstKey() <= t) pending.remove(pending.firstKey())!!.invoke(motion)
            runCurrent()
            advanceTimeBy(VirtualFrameClock.FRAME_MS)
            runCurrent()
            t += VirtualFrameClock.FRAME_MS
            out += Frame(t, motion.position, motion.lifted, motion.stretch)
        }
        job.cancel()
        return out
    }

    @Test
    fun quickTapLiftsAllTheWayTravelsAndOnlyThenDrops() = runTest {
        val f = run(900, at = mapOf(0L to { m -> m.press(2) }, 80L to { m -> m.release(2) }))
        val risen = f.first { it.lift >= 0.975f }.t
        assertTrue("lift reaches 0.975 at $risen ms (spec ≈ 233)", risen in 190..260)
        val arrived = f.first { kotlin.math.abs(it.pos - 2f) <= 0.04f }.t
        assertTrue("lens within 0.04 at $arrived ms (spec ≈ 267)", arrived in 230..310)
        // 升满、到位之前不落：之前每一帧的悬起都只升不降
        val beforeDrop = f.takeWhile { it.t < maxOf(risen, arrived) }
        assertTrue(beforeDrop.zipWithNext().all { (a, b) -> b.lift >= a.lift - 1e-4f })
        // 之后落回 0（吸附到正好 0），规格里约 650 ms
        val gone = f.first { it.t > arrived && it.lift == 0f }.t
        assertTrue("lift snaps to 0 at $gone ms (spec ≈ 650)", gone in 560..760)
        // 透镜冲过 2 一点点（规格 2.0126）
        val peak = f.maxOf { it.pos }
        assertTrue("overshoot $peak", peak > 2.003f && peak < 2.025f)
        // 越快越长：形变先正后负，幅度与规格同一量级（+0.085 / −0.036）
        val stretch = f.maxOf { it.jelly }
        val squash = f.minOf { it.jelly }
        assertTrue("stretch $stretch", stretch in 0.06f..0.11f)
        assertTrue("squash $squash", squash < -0.01f && squash > -0.08f)
        assertEquals(2f, f.last().pos, 0f)
    }

    @Test
    fun holdingKeepsTheLensLiftedUntilRelease() = runTest {
        val f = run(900, at = mapOf(0L to { m -> m.press(2) }, 400L to { m -> m.release(2) }))
        assertTrue(f.filter { it.t in 260L..400L }.all { it.lift >= 0.975f })
        val gone = f.first { it.t > 400 && it.lift == 0f }.t
        assertTrue("lift snaps to 0 at $gone ms (spec ≈ 783)", gone in 700..880)
    }

    @Test
    fun followingADragNeverStallsAndTrailsTheFinger() = runTest {
        // 手指以 8 项/秒往右拖（约 700dp/s 在 86dp 一项的轨道上），每帧一个样本
        val samples = (1..12).associate { i -> (i * 16L) to { m: LensMotion -> m.follow(i * 16 / 1000f * 8f) } }
        val f = run(200, at = mapOf(0L to { m: LensMotion -> m.press(0) }) + samples)
        val moving = f.filter { it.t in 48L..192L }
        assertTrue("moves every frame", moving.zipWithNext().all { (a, b) -> b.pos > a.pos })
        // 不是一比一跟手：落后手指一小段（规格：约 0.056 秒 × 速度）
        val at160 = f.first { it.t == 176L }.pos
        assertTrue("trails the finger: $at160 < ${160 / 1000f * 8f}", at160 < 160 / 1000f * 8f)
        assertTrue("but not by much: $at160", at160 > 160 / 1000f * 8f - 0.8f)
    }

    @Test
    fun anExternalChangeSlidesThePlatterWithoutLifting() = runTest {
        val f = run(800, start = 0f, at = mapOf(0L to { m -> m.slide(2) }))
        assertTrue(f.all { it.lift == 0f })
        assertEquals(2f, f.last().pos, 0f)
    }
}
