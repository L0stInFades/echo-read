package app.echoread.ui.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * 选中透镜的运动：分段控件里那块「托盘 → 透镜」怎么动。
 *
 * 规则与数值取自 FlClash（prism_ui）导航栏选中透镜的行为规格，那一套是照 iOS 的标签栏逐帧量出来的；
 * 这里按规格独立重写，未使用其代码。要点：
 * - 按下就悬起（lift → 1）并朝按住的那一项走，松手才提交；
 * - 拖动时追着手指（follow 弹簧，不是一比一），两端有橡皮筋；松手按速度往前看 0.1 秒，最多越过一项；
 * - 悬起要升满、并且到了位，才落下来 —— 快点一下也能看到完整的一次悬起；
 * - 越快越扁越长，刹车时反过来收一下（jelly），落位后再晃一两下。
 */

/** 弹簧参数（质量 1）：刚度 k 与阻尼系数 c */
internal class SpringParams(val k: Float, val c: Float) {
    companion object {
        /** 刚度 + 阻尼比 */
        fun ratio(k: Float, zeta: Float) = SpringParams(k, 2f * zeta * sqrt(k))

        /** 感知时长 + 回弹（与 SwiftUI 的 Spring(duration:bounce:) 同一个定义）：k = (2π/T)²，ζ = 1 − bounce */
        fun perceptual(seconds: Float, bounce: Float = 0f): SpringParams {
            val w = (2.0 * PI / seconds).toFloat()
            return ratio(w * w, if (bounce > 0f) 1f - bounce else 1f)
        }
    }
}

/**
 * 一根随时可以改目标的弹簧，按真实的帧间隔积分。
 *
 * 不用 Animatable：跟手时每个指针样本都要改一次目标，而 Animatable 每次重新 animateTo，
 * 第一帧都按「过去了 0 秒」算 —— 手指一直在动，它就一直原地不动。这里改目标只换 target 和参数，
 * 值与速度原样接着走。停下的判定：离目标不到 [rest]、速度不到 rest·√k 时，值直接落在目标上
 * （「悬起有没有落到 0」这类判断依赖这一下吸附）。
 */
internal class LensSpring(initial: Float, private val rest: Float = 0.001f) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var params: SpringParams? = null

    val moving: Boolean get() = params != null

    /** 此刻的加速度（弹簧停着时为 0）：刹车量从这里算 */
    val acceleration: Float
        get() = params?.let { it.k * (target - value) - it.c * velocity } ?: 0f

    fun aim(to: Float, spec: SpringParams) {
        target = to
        params = spec
    }

    fun jump(to: Float) {
        value = to
        target = to
        velocity = 0f
        params = null
    }

    /** 走 [dt] 秒，返回是否还在动 */
    fun step(dt: Float): Boolean {
        val p = params ?: return false
        var left = dt
        while (left > 0f) {
            // 半隐式欧拉，步长不超过 1/240 秒：这里最硬的弹簧 k = 1024，步长再大会开始失真
            val h = minOf(left, 1f / 240f)
            velocity += (p.k * (target - value) - p.c * velocity) * h
            value += velocity * h
            left -= h
        }
        if (abs(target - value) < rest && abs(velocity) < rest * sqrt(p.k)) {
            jump(target)
            return false
        }
        return true
    }
}

/** 选中透镜的弹簧与阈值 */
internal object LensRules {
    /** 走到某一项（点击、松手、外部改了选择）：400 ms，回弹 0.15 */
    val Travel = SpringParams.perceptual(0.40f, 0.15f)

    /** 拖动时追手指 */
    val Follow = SpringParams.ratio(1024f, 0.9f)

    /** 悬起与落下：250 ms，不过冲 */
    val Lift = SpringParams.perceptual(0.25f)

    /** 形变 */
    val Jelly = SpringParams.ratio(100f, 0.4f)
    const val JELLY_REST = 0.003f

    /** 悬起要升到这么高、并且离目标不到 [ARRIVED] 项，才开始落下 */
    const val LIFT_SNAP = 0.975f
    const val ARRIVED = 0.04f

    /** 拖出两端时最多再多出去多少项（橡皮筋） */
    const val OVERDRAG = 0.35f

    /** 松手时按速度往前看多久 */
    const val FLING_SECONDS = 0.1f

    /** 悬起后透镜里的字放大多少 */
    const val MAGNIFY = 0.16f

    /** 速度（项/秒）与加速度（项/秒²）→ 形变的目标：越快越扁越长，刹车时反向收 */
    fun jellyTarget(velocity: Float, acceleration: Float): Float {
        val braking = maxOf(0f, -acceleration * sign(velocity))
        return 0.2f * tanh(abs(velocity) / 3f) - 0.15f * tanh(braking / 25f)
    }

    /** 松手后落到哪一项：往前看一小段，最多比按着的那一项多走一项，且不出界 */
    fun flingTarget(target: Float, velocity: Float, pressed: Int, n: Int): Int =
        (target + velocity * FLING_SECONDS).roundToInt().coerceIn(pressed - 1, pressed + 1).coerceIn(0, n - 1)

    fun mayDrop(released: Boolean, lift: Float, target: Float, value: Float): Boolean =
        released && lift >= LIFT_SNAP && abs(target - value) <= ARRIVED
}

/**
 * 分段控件的几何。与导航栏同一套：轨道四周留 [pad]，相邻两项的透镜互相压住 [overlap]，
 * 所以静止的透镜比一格宽一点，两端的透镜正好贴住轨道内缘。位置 p 以「项」为单位，可以是小数，越界照样外推。
 */
internal class SegmentLayout(val width: Float, val height: Float, val n: Int, pad: Float, overlap: Float) {
    /** 静止时的透镜宽 */
    val lens = (width - 2f * pad + overlap * (n - 1)) / n

    /** 相邻两项中心的距离 */
    val pitch = lens - overlap
    val innerHeight = height - 2f * pad
    private val first = pad + overlap / 2f + pitch / 2f

    fun centerX(p: Float) = first + pitch * p

    /** 手指的横坐标 → 透镜该在的位置；拖出两端时被橡皮筋拉住 */
    fun positionAt(x: Float): Float {
        val raw = (x - first) / pitch
        val last = (n - 1).toFloat()
        return when {
            raw < 0f -> rubber(raw, LensRules.OVERDRAG)
            raw > last -> last + rubber(raw - last, LensRules.OVERDRAG)
            else -> raw
        }
    }

    /** 位置 p 压着哪一项 */
    fun indexAt(p: Float): Int = p.roundToInt().coerceIn(0, n - 1)

    /** 透镜此刻的外形：悬起时四周各长 growth/2，形变时宽 ×(1 + j)、高 ×(1 − 1.4j) */
    fun lensRect(p: Float, lift: Float, jelly: Float, growth: Float): Rect {
        val g = growth * lift
        val w = (lens + g) * (1f + jelly)
        val h = (innerHeight + g) * (1f - 1.4f * jelly)
        val cx = centerX(p)
        val cy = height / 2f
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}

/**
 * 透镜的运动状态：位置、悬起、形变三根弹簧，由一个按帧走的协程推着（有东西在动时才跑）。
 * 三个值是快照状态，只在布局与绘制阶段读，动画期间不引起重组。
 */
@Stable
internal class LensMotion(initial: Float, private val scope: CoroutineScope) {
    private val pos = LensSpring(initial)
    private val lift = LensSpring(0f)
    private val jelly = LensSpring(0f, LensRules.JELLY_REST)

    var position by mutableFloatStateOf(initial)
        private set
    var lifted by mutableFloatStateOf(0f)
        private set
    var stretch by mutableFloatStateOf(0f)
        private set

    /** 透镜正走向的那一项 */
    val heading: Float get() = pos.target

    /** 手指已经松开（或者根本没按）：悬起可以落下了 */
    var released = true
        private set

    /** 每次停稳后调一次：透镜停的位置与宿主认的选择不一致时（宿主没接受这次选择），由它决定回到哪里 */
    var onIdle: (() -> Unit)? = null

    private var frames: Job? = null

    /** 按下：悬起，并朝 [index] 走 */
    fun press(index: Int) {
        released = false
        lift.aim(1f, LensRules.Lift)
        pos.aim(index.toFloat(), LensRules.Travel)
        wake()
    }

    /** 拖动：追着手指 */
    fun follow(p: Float) {
        pos.aim(p, LensRules.Follow)
        wake()
    }

    /** 松手（或手势被别人拿走）：走到 [index]，到位后落下 */
    fun release(index: Int) {
        released = true
        pos.aim(index.toFloat(), LensRules.Travel)
        wake()
    }

    /** 不是手指引起的换位：托盘滑过去，不悬起 */
    fun slide(index: Int) {
        pos.aim(index.toFloat(), LensRules.Travel)
        wake()
    }

    fun jumpTo(index: Int) {
        pos.jump(index.toFloat())
        position = pos.value
    }

    private fun wake() {
        if (frames?.isActive == true) return
        frames = scope.launch {
            do {
                var last = -1L
                while (true) {
                    val now = withFrameNanos { it }
                    // 第一帧按 0 秒算（从静止起步的那一帧还在原地）；之后每帧走真实的间隔，掉帧时最多补 50 ms
                    val dt = if (last < 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                    last = now
                    val moving = step(dt)
                    position = pos.value
                    lifted = lift.value.coerceIn(0f, 1f)
                    stretch = jelly.value
                    if (!moving && dt > 0f) break
                }
                // onIdle 可能又给了新目标（回到宿主认的那一项）：这时协程还算活着，wake() 不会另起一个，由这里接着走
                onIdle?.invoke()
            } while (pos.moving || lift.moving || jelly.moving)
        }
    }

    private fun step(dt: Float): Boolean {
        var moving = pos.step(dt)
        val j = LensRules.jellyTarget(pos.velocity, pos.acceleration)
        if (j != jelly.target) jelly.aim(j, LensRules.Jelly)
        moving = jelly.step(dt) || moving
        if (lift.target > 0.5f && LensRules.mayDrop(released, lift.value, pos.target, pos.value)) lift.aim(0f, LensRules.Lift)
        moving = lift.step(dt) || moving
        return moving
    }
}
