package app.echoread.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.ui.glass.GlassLight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 「液态」的那一半：玻璃控件怎么动。
 *
 * 材质（模糊、折射、高光）只回答了「它是什么做的」；让人觉得它是一滴有弹性的东西，靠的是运动。
 * 这里的规则与数值来自对两个参考对象（BiliPai、FlClash）交互逻辑的拆解，各自独立重写：
 *
 * - **按下是鼓起来，不是缩下去。** 普通按钮被按下会缩小（它被压进去了）；玻璃件相反 ——
 *   手指压上去它微微胀大、朝手指的方向被带偏一点，松手时带着过冲弹回去，像按在一颗水珠上。
 * - **移动时被拉长，落位时回弹。** 选中滑块的宽高跟着它的速度变：越快越扁越长；到位那一下再缩一缩。
 * - **悬起来的时候才是透镜。** 滑块静止时是一块实心的色块，移动或被按住期间变大、变清澈、边缘出现折射与高光。
 *
 * 弹簧一律用（阻尼比, 刚度）写，方便与参考值逐项对照。
 */
object Liquid {
    /** 按钮的鼓起与回弹：欠阻尼，松手后晃一下 */
    val Gel: SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 悬起 / 落下的进度，以及跟手的位置：又快又不过冲 */
    val Lift: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)

    /** 滑块从一项走到另一项 */
    val Travel: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 320f, visibilityThreshold = 0.001f)

    /** 面板弹出：比常规转场多一点过冲，升到位后轻轻回落一下（Q 弹）。只用于程序化的打开，手势松手仍走不过冲的那条 */
    val Sheet: SpringSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 普通按压件松手时的回弹：缩下去的按钮弹回来时略微冲过原大小再收住 */
    val Rebound: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 420f, visibilityThreshold = 0.002f)

    /** 落位回弹 */
    val Recoil: SpringSpec<Float> = spring(dampingRatio = 0.65f, stiffness = 300f, visibilityThreshold = 0.0005f)

    /** 松手时按速度往前多看这么久，决定落到哪一项 */
    const val ProjectionSec = 0.14f

    /**
     * 滑块此刻的外形。
     *
     * @param w 整条轨道的宽；[h] 轨道的高；[n] 项数；[pos] 滑块中心在第几项上（可以是小数）
     * @param lift 悬起进度 0..1；[velocity] 速度，单位「整条轨道 / 秒」；[recoil] 落位回弹量（约 ±0.18）
     * @param inset 静止时滑块四周离轨道的留白；[growW] / [growH] 悬起后宽、高各多出多少
     */
    fun thumbRect(
        w: Float, h: Float, n: Int, pos: Float,
        lift: Float, velocity: Float, recoil: Float,
        inset: Float, growW: Float, growH: Float
    ): Rect {
        val item = w / n
        val baseW = (item - inset * 2).coerceAtLeast(1f)
        val baseH = (h - inset * 2).coerceAtLeast(1f)
        // 速度越大越扁越长（参考值：宽 ÷(1 − 0.075v)，高 ×(1 − 0.025v)，各封顶 20%）
        val stretch = (abs(velocity) * 0.075f).coerceAtMost(0.2f)
        val squash = (abs(velocity) * 0.025f).coerceAtMost(0.2f)
        val tw = (baseW + growW * lift) / (1f - stretch) * (1f + recoil)
        val th = (baseH + growH * lift) * (1f - squash) * (1f - 1.4f * recoil)
        val cx = (pos + 0.5f) * item
        val cy = h / 2f
        return Rect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
    }

    /** 落位回弹的初速度：来得越快，弹得越狠（封顶） */
    fun recoilKick(speed: Float): Float = -(1.8f + 0.5f * tanh(abs(speed) / 3f))
}

@Stable
class LiquidPressState internal constructor() {
    internal val press = Animatable(0f, 0.001f)
    internal val dragX = Animatable(0f)
    internal val dragY = Animatable(0f)
    internal var gen = 0
}

/** 最小可见时长：与 [echoPress] 同一个下限，快点一下也看得见它鼓了一下 */
private const val MIN_PRESS_MS = 90L

/**
 * 玻璃按钮的按压：鼓起、被手指带偏、松手弹回；可选地把触点喂给 [light]，让玻璃在手指下亮起来。
 *
 * 与 [echoPress] 的三条硬性约束相同：当帧起动画、命中区不随缩放变化、动画值只在 layer 块里读。
 * 被父级滚动容器拿走手势（事件被消费）或手指滑出按钮时，这次点击作废。
 *
 * @param lift 按下时四周各「鼓」出多少。参考值是 4dp：缩放 = 1 + lift / 高度，按钮越小鼓得越明显
 */
@Composable
fun Modifier.liquidPress(
    enabled: Boolean = true,
    light: GlassLight? = null,
    lift: Dp = 4.dp,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val state = remember { LiquidPressState() }
    val enabledRef = rememberUpdatedState(enabled)
    val clickRef = rememberUpdatedState(onClick)
    return this
        .pointerInput(state, light) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledRef.value) return@awaitEachGesture
                    val gen = ++state.gen
                    val downAt = SystemClock.uptimeMillis()
                    launch { state.press.animateTo(1f, Liquid.Gel) }
                    if (light != null) {
                        light.at = down.position
                        launch { light.power.animateTo(1f, tween(110, easing = LinearEasing)) }
                    }
                    var clicked = false
                    var alive = true
                    while (alive) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val p = change.position
                        if (light != null) light.at = p
                        if (!change.pressed) {
                            // 抬手：仍在按钮上（放宽一个触摸容差）才算点击
                            val slop = viewConfiguration.touchSlop
                            clicked = p.x >= -slop && p.y >= -slop && p.x <= size.width + slop && p.y <= size.height + slop
                            if (clicked) change.consume()
                            alive = false
                        } else {
                            val d = p - down.position
                            launch {
                                state.dragX.snapTo(d.x)
                                state.dragY.snapTo(d.y)
                            }
                        }
                    }
                    launch {
                        val held = SystemClock.uptimeMillis() - downAt
                        if (held < MIN_PRESS_MS) delay(MIN_PRESS_MS - held)
                        if (state.gen == gen) {
                            launch { state.press.animateTo(0f, Liquid.Gel) }
                            launch { state.dragX.animateTo(0f, Liquid.Gel) }
                            launch { state.dragY.animateTo(0f, Liquid.Gel) }
                            if (light != null) launch { light.power.animateTo(0f, tween(420, easing = LinearEasing)) }
                        }
                    }
                    if (clicked && enabledRef.value) clickRef.value()
                }
            }
        }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(label = onClickLabel) { clickRef.value(); true }
            if (!enabled) disabled()
        }
        .graphicsLayer {
            val p = state.press.value
            val short = minOf(size.width, size.height).coerceAtLeast(1f)
            val s = 1f + lift.toPx() / size.height.coerceAtLeast(1f) * p
            scaleX = s
            scaleY = s
            // 被手指带偏：位移随拖动距离饱和（tanh），拖得再远也只挪一点点
            translationX = short * tanh(0.05f * state.dragX.value / short)
            translationY = short * tanh(0.05f * state.dragY.value / short)
        }
}

/** 给不想自己管 [GlassLight] 的调用方：按下发光用的那团光 */
@Composable
fun rememberGlassLight(): GlassLight = remember { GlassLight() }

/** 橡皮筋：拖过头时越拖越拖不动（[limit] 是能拖出去的最大距离） */
fun rubber(x: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val a = abs(x)
    val out = limit * (1f - 1f / (a * 0.55f / limit + 1f))
    return if (x < 0f) -out else out
}

/** 手指位置换算成「滑块中心在第几项」，两端允许带橡皮筋地多拖出去一点 */
fun dragToPos(x: Float, width: Float, n: Int): Float {
    val item = width / n
    val raw = x / item - 0.5f
    val max = (n - 1).toFloat()
    return when {
        raw < 0f -> rubber(raw, 0.35f)
        raw > max -> max + rubber(raw - max, 0.35f)
        else -> raw
    }
}

/** 松手后落到哪一项：按速度往前看一小段，最多越过一项 */
fun settleIndex(pos: Float, velocityItemsPerSec: Float, n: Int, from: Int): Int {
    val projected = pos + velocityItemsPerSec * Liquid.ProjectionSec
    val target = Math.round(projected).coerceIn(0, n - 1)
    val nearest = Math.round(pos).coerceIn(0, n - 1)
    return target.coerceIn(nearest - 1, nearest + 1).coerceIn(0, n - 1).let { if (n == 1) from else it }
}
