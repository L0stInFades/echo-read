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
 * 弹簧档位（0.2.0-exp：8 档）—— **谷歌 Material 3 Expressive 动效标准与自研 CA 管线的融合点**。
 *
 * 取值全部来自 material3 1.5.0-alpha18 里 `StandardMotionTokens` / `ExpressiveMotionTokens` 的实际字节码
 * （javap 读出的 ldc 常量），再用 `response = 2π/√k` 换算到我们的 (response, damping) 参数化。
 * 八档里有五档与 Google 的 token **数值完全相等**，两档是我们独有、且有明确理由不采用 Google 的对应档，
 * 一档（Track）是真正的融合：借 M3 Expressive 的刚度，保留我们的阻尼。
 *
 * | Token      | response | ζ    | k    | settle | 过冲  | 对应 Google token            | 语义 |
 * |------------|----------|------|------|--------|-------|------------------------------|------|
 * | Flash      | 0.102    | 1.00 | 3795 |  65ms  | 0%    | = fastEffects（两套一致）     | 可打断的 alpha/颜色：消散、取消 |
 * | Instant    | 0.157    | 1.00 | 1602 | 100ms  | 0%    | = defaultEffects（两套一致）  | 按压/松手、开关、图标态 |
 * | Gentle     | 0.550    | 1.00 |  130 | 350ms  | 0%    | **我们独有**                  | 低频大面积（进度条、glow、背景色） |
 * | Track      | 0.220    | 0.95 |  816 | 147ms  | 0.01% | **融合**：刚度取 Expressive fastSpatial，阻尼保留我们的 | 手势 settle 专用 |
 * | Standard   | 0.238    | 0.90 |  697 | 168ms  | 0.15% | = STANDARD defaultSpatial     | 翻页、Chip、局部位移、列表项 |
 * | Playful    | 0.222    | 0.60 |  801 | 236ms  | 9.5%  | = EXPRESSIVE fastSpatial      | 播放键 pop（仅限 ≤52dp 的元素） |
 * | Emphasized | 0.322    | 0.80 |  381 | 256ms  | 1.5%  | = EXPRESSIVE defaultSpatial   | 底部弹层、根导航、大面积转场 |
 * | Expand     | 0.444    | 0.80 |  200 | 353ms  | 1.5%  | = EXPRESSIVE slowSpatial      | 大面积尺寸变化 / 形状 morph |
 *
 * 两处刻意不跟随 Google：
 * 1. **Track 不用 Expressive fastSpatial 的 ζ=0.6**。9.5% 过冲落在翻页上，意味着接近一页宽的正文
 *    滑出页边再弹回来 —— 在阅读器里不可接受。取它的刚度（到位节奏是 M3 的），阻尼仍用 0.95（不见过冲）。
 * 2. **Gentle 无对应档**。Google 最慢的 effects 档 141ms、最慢的 spatial 档 354ms 但 ζ=0.8。
 *    Gentle 是两套体系里唯一「又慢又临界阻尼」的弹簧，而颜色一旦过冲就会溢出色域、观感上是一次闪烁。
 *    它的 350ms 恰好与 Google 最慢动效同时长，却零过冲。
 *
 * 另注：Google 的三条 effects 档在 standard 与 expressive 两套里逐字节相同（ζ 恒为 1.0）——
 * 「颜色与透明度永不过冲」是硬约束而非风格选择，这一点与本文件原有的 [Dur] 注释完全一致。
 */
object EchoMotion {
    /* ---- effects（ζ=1.0，绝不过冲） ---- */
    val Flash = Spring2(0.102f, 1.00f)
    val Instant = Spring2(0.157f, 1.00f)
    val Gentle = Spring2(0.550f, 1.00f)

    /* ---- spatial ---- */
    val Track = Spring2(0.220f, 0.95f)
    val Standard = Spring2(0.238f, 0.90f)
    val Playful = Spring2(0.222f, 0.60f)
    val Emphasized = Spring2(0.322f, 0.80f)
    val Expand = Spring2(0.444f, 0.80f)
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
