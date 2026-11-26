package app.echoread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import app.echoread.ui.motion.Retarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 跟手动画改由 [Retarget] 接手之后，必须与原来「每个样本起一个协程跑 animateTo」逐帧一模一样：
 * 弹簧接着旧动画的速度与时间往下走，滑块的拉伸压扁、分段滑块追手指的手感全在这里（LiquidTest 只钉住了外形公式）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetargetTest {

    private val spec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.01f)

    /** 一帧里发生的事：零个、一个或两个手指样本，或者别处插进来的一段动画（松手落位） */
    private sealed interface Step
    private data class Samples(val values: List<Float>) : Step
    private data class Interrupt(val target: Float) : Step

    private val script: List<Step> = listOf(
        Samples(listOf(0.1f)), Samples(listOf(0.4f)), Samples(listOf(0.9f)), Samples(emptyList()),
        Samples(listOf(1.6f, 1.8f)), Samples(listOf(1.2f)), Samples(emptyList()), Samples(emptyList()),
        Interrupt(0f), Samples(emptyList()), Samples(listOf(-0.5f)), Samples(listOf(-0.2f))
    ) + List(30) { Samples(emptyList()) }

    /** 按脚本一帧一帧推进，记下每帧之后的值与速度 */
    private fun TestScope.trace(useRetarget: Boolean): List<Pair<Float, Float>> {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job + VirtualFrameClock(testScheduler))
        val anim = Animatable(0f)
        val retarget = Retarget { anim.animateTo(it, spec) }
        if (useRetarget) scope.launch { retarget.run() }
        runCurrent()
        val out = ArrayList<Pair<Float, Float>>()
        for (step in script) {
            when (step) {
                is Samples -> for (v in step.values) {
                    if (useRetarget) retarget.offer(v) else scope.launch { anim.animateTo(v, spec) }
                }
                is Interrupt -> scope.launch { anim.animateTo(step.target, spec) }
            }
            runCurrent()
            advanceTimeBy(VirtualFrameClock.FRAME_MS)
            runCurrent()
            out += anim.value to anim.velocity
        }
        job.cancel()
        return out
    }

    @Test
    fun retargetMovesExactlyLikeOneCoroutinePerSample() {
        var perSample = emptyList<Pair<Float, Float>>()
        var retargeted = emptyList<Pair<Float, Float>>()
        runTest { perSample = trace(useRetarget = false) }
        runTest { retargeted = trace(useRetarget = true) }
        // 自检：这串样本确实把弹簧带动了，否则下面的逐帧比较没有意义
        assertTrue(perSample.any { abs(it.second) > 0.5f })
        assertEquals(perSample, retargeted)
    }
}
