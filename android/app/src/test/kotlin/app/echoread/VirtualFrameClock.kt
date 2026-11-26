package app.echoread

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * 虚拟时间里的帧时钟：帧对齐到 16ms 的整数倍，帧时间取测试调度器的当前时间。
 * 同一帧里等着的动画拿到同一个帧时间，与真机上「一帧里所有动画一起走一步」一致。
 * Animatable 与 MotionDriver 要在协程上下文里找到一个帧时钟才肯跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(FRAME_MS - scheduler.currentTime % FRAME_MS)
        return onFrame(scheduler.currentTime * 1_000_000L)
    }

    companion object {
        const val FRAME_MS = 16L
    }
}
