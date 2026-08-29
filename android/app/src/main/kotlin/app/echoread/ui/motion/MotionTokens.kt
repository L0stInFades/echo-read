package app.echoread.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 弹簧参数化：用 (response, damping) 而不是裸 stiffness。
 * response 是「一个无阻尼周期的秒数」，带明确时间语义，可与 One UI 的时长分级、
 * HarmonyOS `curves.springMotion(response, dampingFraction)`、iOS `.spring(response:dampingFraction:)` 直接互译。
 *
 *   ω = 2π / response      stiffness = ω²      稳定时间 ≈ 4 / (ζ·ω)      首峰过冲 = exp(-πζ/√(1-ζ²))
 */
@Immutable
class Spring2(val response: Float, val damping: Float) {
    val omega: Float = (2.0 * Math.PI / response).toFloat()
    val stiffness: Float = omega * omega

    /** 包络衰减到 2% 的时间（ms）：写规范/验收用，不参与运行时计算 */
    val settleMs: Int = (4000f / (damping * omega)).toInt()

    private val forFraction: SpringSpec<Float> = spring(damping, stiffness, Thr.FRAC)
    private val forPixels: SpringSpec<Float> = spring(damping, stiffness, Thr.PX)

    /** 归一化进度（0..1 或 -1..1）用 */
    fun float(): SpringSpec<Float> = forFraction

    /** 像素量用（阈值 0.5px，肉眼静止即收敛，不再空跑十几帧） */
    fun px(): SpringSpec<Float> = forPixels

    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> = spring(damping, stiffness, visibilityThreshold)
}

/**
 * 弹簧档位（6 档）。`settle` 为理论稳定时间，用于与设计规范对齐。
 *
 * | Token      | response | ζ    | settle | 过冲  | 语义                                   |
 * |------------|----------|------|--------|-------|----------------------------------------|
 * | Instant    | 0.15     | 1.00 |  95ms  | 0%    | 按压/松手、开关、图标态                 |
 * | Track      | 0.20     | 0.95 | 135ms  | ~0%   | 手势 settle 专用：拖拽回位、跟手收敛     |
 * | Standard   | 0.30     | 0.90 | 213ms  | 0.15% | 翻页、Chip、局部位移、列表项            |
 * | Emphasized | 0.40     | 0.85 | 300ms  | 0.6%  | 底部弹层、根导航、大面积转场            |
 * | Gentle     | 0.55     | 1.00 | 350ms  | 0%    | 低频大面积（进度条、glow、背景色）      |
 * | Playful    | 0.28     | 0.70 | 255ms  | 4.6%  | 播放键 pop（仅限 ≤52dp 的元素）         |
 */
object EchoMotion {
    val Instant = Spring2(0.15f, 1.00f)
    val Track = Spring2(0.20f, 0.95f)
    val Standard = Spring2(0.30f, 0.90f)
    val Emphasized = Spring2(0.40f, 0.85f)
    val Gentle = Spring2(0.55f, 1.00f)
    val Playful = Spring2(0.28f, 0.70f)
}

/** 时长档位：只用于不可打断的纯 alpha / color（可打断的位移一律用弹簧） */
object Dur {
    const val Micro = 100
    const val Short = 150
    const val Medium = 200
    const val Long = 300
}

object Ease {
    /** 通用（Harmony 标准曲线 / M3 emphasized） */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** 出场：仅限「离开视野」的元素 */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    /** 纯 alpha 交叉淡入必须线性，否则中点会暗 */
    val Linear: Easing = LinearEasing
}

/** 可见性阈值：动画在肉眼静止后就该停 */
object Thr {
    const val PX = 0.5f
    const val ALPHA = 1f / 255f
    const val SCALE = 0.002f
    const val FRAC = 0.001f
}

/** 落点投影与逃逸速度（单位空间：1 = 一个「单位」＝页宽 / 弹层高 / 屏宽） */
object Decay {
    /** 投影时长：projected = value + v · 0.12s。分页判定不用「滚到停」的完整投影，否则轻扫翻三页。 */
    const val ProjectionSec = 0.12f

    /** 强制换向速度阈值（单位/秒，≈ 一屏宽/秒） */
    const val EscapeVel = 1.1f
}

/** UIScrollView 橡皮筋常数：f(x) = (1 - 1/(x·C/dim + 1))·dim */
object Rubber {
    const val C = 0.55f
}

/**
 * 仍在使用的 Enter/Exit 组合。进场与出场是同一条曲线的正反向 —— 旧代码「进 spring / 出 tween」
 * 从定义上就无法实现「拉一半松手回去」的连续感。
 */
object EchoTransitions {
    private val sizeSpec = EchoMotion.Standard.spec(IntSize.VisibilityThreshold)
    private val offsetSpec = EchoMotion.Standard.spec(IntOffset.VisibilityThreshold)

    /** 轻提示上浮入场 */
    val riseIn: EnterTransition =
        fadeIn(tween(Dur.Short, easing = Ease.Linear)) + slideInVertically(offsetSpec) { -it / 3 }
    val sinkOut: ExitTransition =
        fadeOut(tween(Dur.Micro, easing = Ease.Linear)) + slideOutVertically(offsetSpec) { -it / 4 }

    /** 区块展开/收起（尺寸动画，只用于低频、内容轻的卡片） */
    val expandIn: EnterTransition = expandVertically(sizeSpec) + fadeIn(tween(Dur.Short, easing = Ease.Linear))
    val collapseOut: ExitTransition = shrinkVertically(sizeSpec) + fadeOut(tween(Dur.Micro, easing = Ease.Linear))
}
