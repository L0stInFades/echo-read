package app.echoread.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sign

/**
 * 手势驱动过渡的「呈现层」（Core Animation presentationLayer + UIViewPropertyAnimator 的 Compose 等价物）。
 *
 * - [value] 是唯一的呈现真相，**只允许在 graphicsLayer / draw / layout 的 lambda 里读**（组合期读＝每帧重组）。
 * - [drive] 以 UserInput 优先级抢占一切：手指按下即接管正在跑的动画，从当前呈现值继续，无跳变。
 * - [animateTo] 默认 Default 优先级，手指一按就让位；被抢占时残余速度记入 [consumeCarriedVelocityPxPerSec]。
 * - [rebase] 在一次 snapshot 内同时提交模型层与呈现层，任何一帧都看不到「模型已变、呈现未变」的中间态。
 *
 * 翻页 / 底部弹层共用同一实例语义：settle 判定、投影常数、橡皮筋公式、并发仲裁完全一致。
 */
@Stable
class MotionDriver(initialValue: Float = 0f, private val threshold: Float = Thr.FRAC) {

    /** 呈现值。1 个单位 = [unitPx] 像素。 */
    var value: Float by mutableFloatStateOf(initialValue)
        private set

    /** 一个「单位进度」对应多少像素（页宽 / 弹层高 / 屏宽），由 onSizeChanged 写入 */
    var unitPx: Float by mutableFloatStateOf(1f)

    /** 是否被手指持有（低频，可在组合期读） */
    var isDragging: Boolean by mutableStateOf(false)
        private set

    /** 是否有动画在跑（低频，可在组合期读） */
    var isSettling: Boolean by mutableStateOf(false)
        private set

    private val mutex = MutatorMutex()
    private var running: Animatable<Float, AnimationVector1D>? = null

    /** 被抢占时残留的动画速度（单位/秒），用于速度继承 */
    private var carried = 0f

    private val safeUnit: Float get() = if (unitPx > 0f) unitPx else 1f

    interface DriveScope {
        /** 同步写入呈现值：无协程、无重组、无分配。[bounds] 非空时越界走橡皮筋。 */
        fun dragByPx(deltaPx: Float, bounds: ClosedFloatingPointRange<Float>? = null)

        /** 取走并清空「被抢占时的残余速度」，用于并入 VelocityTracker 的结果 */
        fun consumeCarriedVelocityPxPerSec(): Float
    }

    private inner class DriveScopeImpl : DriveScope {
        override fun dragByPx(deltaPx: Float, bounds: ClosedFloatingPointRange<Float>?) {
            val raw = value + deltaPx / safeUnit
            value = if (bounds == null) raw else applyRubberBand(raw, bounds)
        }

        override fun consumeCarriedVelocityPxPerSec(): Float = (carried * safeUnit).also { carried = 0f }
    }

    /** 手指接管：UserInput 优先级，抢占一切（包括另一根手指的 drive） */
    suspend fun <R> drive(block: suspend DriveScope.() -> R): R = mutex.mutate(MutatePriority.UserInput) {
        carried = running?.velocity ?: 0f
        running = null
        isDragging = true
        try {
            DriveScopeImpl().block()
        } finally {
            isDragging = false
        }
    }

    /** 程序化动画到固定目标 */
    suspend fun animateTo(
        target: Float,
        velocityPxPerSec: Float = 0f,
        spec: SpringSpec<Float> = EchoMotion.Standard.float(),
        priority: MutatePriority = MutatePriority.Default,
    ) {
        animateToBy(velocityPxPerSec, spec, priority, target = { target })
    }

    /**
     * 在互斥区内计算目标再动画：[target] 与 [onArrive] 都在「已独占驱动器」的状态下执行，
     * 因此可以安全地做 rebase 等原子改动，不会被正在跑的动画覆写。[target] 返回 null 表示放弃。
     */
    suspend fun animateToBy(
        velocityPxPerSec: Float = 0f,
        spec: SpringSpec<Float> = EchoMotion.Standard.float(),
        priority: MutatePriority = MutatePriority.Default,
        onArrive: (() -> Unit)? = null,
        target: () -> Float?,
    ) {
        mutex.mutate(priority) {
            val t = target()
            if (t == null) {
                Unit
            } else {
                val a = Animatable(value, threshold)
                running = a
                isSettling = true
                try {
                    a.animateTo(t, spec, velocityPxPerSec / safeUnit) { this@MotionDriver.value = this.value }
                    onArrive?.invoke()
                } finally {
                    carried = a.velocity
                    if (running === a) running = null
                    isSettling = false
                }
            }
        }
    }

    /**
     * 原子重基：模型层前进 [delta] 个单位的同时把呈现值平移回来。
     * 必须在独占驱动器时调用（drive{} 或 animateToBy 的 onArrive 内），否则会被在跑的动画覆写。
     */
    fun rebase(delta: Float, commitModel: () -> Unit) {
        Snapshot.withMutableSnapshot {
            commitModel()
            value -= delta
        }
    }

    /** 抢占并原子落位：取消在跑的动画后，把模型层与呈现值放在同一次 snapshot 里复位 */
    suspend fun snapTo(
        v: Float,
        priority: MutatePriority = MutatePriority.PreventUserInput,
        commitModel: (() -> Unit)? = null,
    ) {
        mutex.mutate(priority) {
            running = null
            carried = 0f
            Snapshot.withMutableSnapshot {
                commitModel?.invoke()
                value = v
            }
        }
    }

    /**
     * 非挂起路径的同步位移（NestedScroll 用，语义同 AnchoredDraggableState.dispatchRawDelta）：
     * 返回实际消费的像素。动画进行中不参与，避免与 Animatable 抢写同一个值。
     */
    fun dispatchRawDeltaPx(deltaPx: Float, bounds: ClosedFloatingPointRange<Float>? = null): Float {
        if (isSettling) return 0f
        val before = value
        val raw = before + deltaPx / safeUnit
        value = if (bounds == null) raw else applyRubberBand(raw, bounds)
        return (value - before) * safeUnit
    }
}

/**
 * iOS 分页语义的落点判定：先看逃逸速度（轻扫一下就过去），再看投影落点，最后才看位置阈值。
 * [candidates] 翻页 [-1,0,1]；弹层 [0,1]。
 */
fun settleTarget(value: Float, velocityUnitPerSec: Float, candidates: List<Float>): Float {
    if (candidates.isEmpty()) return value
    if (abs(velocityUnitPerSec) >= Decay.EscapeVel) {
        val dir = sign(velocityUnitPerSec)
        val ahead = candidates.filter { (it - value) * dir > 0f }.minByOrNull { abs(it - value) }
        if (ahead != null) return ahead
    }
    val projected = value + velocityUnitPerSec * Decay.ProjectionSec
    return candidates.minByOrNull { abs(it - projected) } ?: value
}

/** 越界时对位移做非线性压缩（单位空间 dim = 1）：最大越界量渐近于 1 个单位。 */
fun applyRubberBand(x: Float, bounds: ClosedFloatingPointRange<Float>): Float {
    val over = when {
        x > bounds.endInclusive -> x - bounds.endInclusive
        x < bounds.start -> x - bounds.start
        else -> return x
    }
    val edge = if (over > 0f) bounds.endInclusive else bounds.start
    return edge + sign(over) * (1f - 1f / (abs(over) * Rubber.C + 1f))
}

/**
 * 驱动器被更高优先级抢占（手指按下打断程序化动画，或程序化动画让位给手指）时，`MutatorMutex`
 * 抛的是 `CancellationException` —— 那不是「我这个协程被取消了」，不该把外层的手势泵 / Flow 收集
 * 一起带走。只有自身确实被取消时才继续向上抛。
 */
suspend fun preemptable(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        if (!coroutineContext.isActive) throw e
    }
}
