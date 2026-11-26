package app.echoread.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.ui.glass.GlassLight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 玻璃件的按压：整块鼓起来、被拖着的手指拉着走，松手弹回去。照 FlClash（prism_ui）ElasticPress 的做法重写（读过它的实现，
 * 只仿行为，没有搬代码）：
 * - 按下：整块（玻璃连同上面的图标）放大，长边最多多出 16dp、最多 25%；弹簧 k 311、ζ 0.593，会冲过头一点再回来；
 * - 拖动：朝手指被拉过去（橡皮筋，最远短边的 7/32），顺着拉的方向再拉长一点；
 * - 松手：拉过去的那一段弹回；鼓起要先升满（≥ 0.975）才开始落 —— 快点一下也能看到完整的一次鼓起；
 * - 只改画面：布局与命中区仍是静止时的样子。
 *
 * 按钮组（一枚胶囊里几个图标）是整个组一起鼓，组里的图标自己不再缩放 —— 0.7.1 以前只是图标在一块不动的玻璃里变大，
 * 看上去很僵。
 */

internal object PressRules {
    val Press = SpringParams.ratio(311f, 0.593f)
    val Track = SpringParams.perceptual(0.12f)
    const val SETTLE = 0.975f
    const val MAX_SCALE = 0.25f
    const val PULL_LIMIT = 7f / 32f
    const val PULL_STRETCH = 0.5f
    val MaxGrowth: Dp = 16.dp
}

/** 一块玻璃件的按压状态：鼓起程度与被拉过去的位移（像素），由一个按帧走的协程推着 */
@Stable
class PressMotion internal constructor(private val scope: CoroutineScope) {
    private val lift = LensSpring(0f)
    private val pullX = LensSpring(0f, rest = 0.01f)
    private val pullY = LensSpring(0f, rest = 0.01f)
    private var origin = Offset.Zero
    private var releasing = false
    private var frames: Job? = null

    /** 鼓起程度 0..1（弹簧会冲过 1 一点） */
    var swell by mutableFloatStateOf(0f)
        private set

    /** 被手指拉过去的位移（像素） */
    var pull by mutableStateOf(Offset.Zero)
        private set

    /** 长边最多多出的像素（由修饰符按屏幕密度填） */
    internal var growthPx = 0f

    /** 这块件此刻的缩放：x、y 各自（整体鼓起 × 顺着拉的方向拉长） */
    fun scaleOf(size: Size): Offset {
        if (size.width <= 0f || size.height <= 0f) return Offset(1f, 1f)
        val k = 1f + swell * min(PressRules.MAX_SCALE, growthPx / max(size.width, size.height))
        val p = pull
        return Offset(k * (1f + abs(p.x) / size.width * PressRules.PULL_STRETCH), k * (1f + abs(p.y) / size.height * PressRules.PULL_STRETCH))
    }

    internal fun down(at: Offset) {
        origin = at
        releasing = false
        lift.aim(1f, PressRules.Press)
        wake()
    }

    internal fun move(at: Offset, size: Size) {
        val limit = min(size.width, size.height) * PressRules.PULL_LIMIT
        pullX.aim(rubber(at.x - origin.x, limit), PressRules.Track)
        pullY.aim(rubber(at.y - origin.y, limit), PressRules.Track)
        wake()
    }

    internal fun up() {
        pullX.aim(0f, PressRules.Press)
        pullY.aim(0f, PressRules.Press)
        releasing = true
        wake()
    }

    private fun wake() {
        if (frames?.isActive == true) return
        frames = scope.launch {
            var last = -1L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last < 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                // 松手后先升满再落
                if (releasing && lift.value >= PressRules.SETTLE) {
                    releasing = false
                    lift.aim(0f, PressRules.Press)
                }
                var moving = lift.step(dt)
                moving = pullX.step(dt) || moving
                moving = pullY.step(dt) || moving
                swell = lift.value
                pull = Offset(pullX.value, pullY.value)
                if (!moving && !releasing && dt > 0f) break
            }
        }
    }
}

@Composable
fun rememberPressMotion(): PressMotion {
    val scope = rememberCoroutineScope()
    return remember { PressMotion(scope) }
}

/**
 * 给一块玻璃件装上按压：听手指（不消费事件）、整块鼓起与被拉动；可选地喂手指下的光 [light]。
 * 传了 [onClick] 就同时是一个按钮（抬手时还在件上、没被滚动拿走才算点击）；不传就只管鼓起 ——
 * 按钮组用这种：组里的图标各自处理点击，鼓起的是整组。
 */
@Composable
fun Modifier.elasticPress(
    press: PressMotion,
    light: GlassLight? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
): Modifier {
    press.growthPx = with(LocalDensity.current) { PressRules.MaxGrowth.toPx() }
    val enabledRef = rememberUpdatedState(enabled)
    val clickRef = rememberUpdatedState(onClick)
    return this
        .pointerInput(press, light) {
            coroutineScope {
                val scope = this
                awaitPointerEventScope {
                    while (true) {
                        // 第一遍（Initial）就看到：组里的图标怎么处理这次点击，都不影响整组鼓起
                        val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        if (!enabledRef.value) continue
                        press.down(down.position)
                        if (light != null) {
                            light.at = down.position
                            scope.launch { light.power.animateTo(1f, GlassLight.On) }
                        }
                        // 被别人拿走（滚动）：最后一遍（Final）上看得到父节点在中间那一遍消费过它
                        var taken = false
                        var up: Offset? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (light != null) light.at = change.position
                            if (change.pressed) press.move(change.position, Size(size.width.toFloat(), size.height.toFloat()))
                            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) taken = true
                            if (!change.pressed) {
                                up = change.position
                                break
                            }
                        }
                        press.up()
                        if (light != null) scope.launch { light.power.animateTo(0f, GlassLight.Off) }
                        val action = clickRef.value
                        val at = up
                        val slop = viewConfiguration.touchSlop
                        val inside = at != null && at.x >= -slop && at.y >= -slop && at.x <= size.width + slop && at.y <= size.height + slop
                        if (action != null && !taken && inside && enabledRef.value) action()
                    }
                }
            }
        }
        .then(
            if (onClick == null) Modifier else Modifier.semantics(mergeDescendants = true) {
                role = Role.Button
                // 停用时读屏发来的点击也不执行：disabled() 只是标记，拦不住无障碍服务直接触发动作
                onClick(label = onClickLabel) { if (enabled) clickRef.value?.invoke(); enabled }
                if (!enabled) disabled()
            }
        )
        .graphicsLayer {
            val s = press.scaleOf(size)
            scaleX = s.x
            scaleY = s.y
            translationX = press.pull.x
            translationY = press.pull.y
        }
}

/**
 * 一个按钮的按压（自带状态）。与 [elasticPress] 相同，只是不需要在外面拿着 [PressMotion] ——
 * 按钮下面若是一块采样背板的玻璃，要把同一个 [PressMotion] 也交给玻璃（见 Modifier.glass 的 press 参数），就用 [elasticPress]。
 */
@Composable
fun Modifier.liquidPress(
    enabled: Boolean = true,
    light: GlassLight? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = elasticPress(rememberPressMotion(), light, enabled, onClickLabel, onClick)
