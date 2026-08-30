package app.echoread.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView

/** 缩放档位：三档收敛。≤40dp 的图标按钮绝不低于 0.94，本就小的命中区不该再靠缩放找存在感。 */
object PressScale {
    /** 大卡片 / 列表行 / 书格 */
    const val Tile = 0.98f
    /** 胶囊按钮 */
    const val Button = 0.96f
    /** Chip、小型可点文字 */
    const val Chip = 0.94f
    /** ≤40dp 图标按钮（配合 pressedAlpha） */
    const val Icon = 0.94f
}

/** 最小可见时长：低端机上一次快点的按下-抬起间隔可能只有 40ms，没有下限就等于「根本没反馈」 */
private const val MIN_PRESS_MS = 80L

@Stable
class EchoPressState internal constructor() {
    internal val progress = Animatable(0f, Thr.SCALE)
    internal var gen = 0
}

/**
 * 零延迟按压反馈。三条硬性约束：
 *
 * 1. **当帧起动画**：在 `awaitFirstDown` 就启动缩放，绕过 `clickable` 在可滚动容器内对
 *    `PressInteraction.Press` 的 100~120ms 延迟（书架格子、弹层内按钮全部中招）。
 * 2. **命中区不缩水**：`pointerInput` 在外、`graphicsLayer` 在内 —— 缩放层的逆变换只作用于它内部的
 *    命中测试，本修饰符自己的命中范围始终是完整尺寸。旧的 `pressScale` 顺序相反，40dp 按钮按下瞬间
 *    有效命中区缩到 35dp，手指略动就「按了没反应」。
 * 3. **动画值只在 lambda 里读**：`progress` 只出现在 graphicsLayer 块里，按压全程零重组。
 *
 * 无障碍语义（role / onClick / onLongClick / disabled）显式提供，不依赖 `clickable`。
 */
@Composable
fun Modifier.echoPress(
    enabled: Boolean = true,
    pressedScale: Float = PressScale.Button,
    pressedAlpha: Float = 1f,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val state = remember { EchoPressState() }
    val enabledRef = rememberUpdatedState(enabled)
    val clickRef = rememberUpdatedState(onClick)
    val longRef = rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    val hostView = LocalView.current

    val semanticsBlock: SemanticsPropertyReceiver.() -> Unit = remember(enabled, hasLong, onClickLabel) {
        {
            role = Role.Button
            onClick(label = onClickLabel) { clickRef.value(); true }
            if (hasLong) onLongClick { longRef.value?.invoke(); true }
            if (!enabled) disabled()
        }
    }
    val layerBlock: GraphicsLayerScope.() -> Unit = remember(pressedScale, pressedAlpha) {
        {
            val p = state.progress.value
            val s = 1f + (pressedScale - 1f) * p
            scaleX = s
            scaleY = s
            if (pressedAlpha != 1f) {
                alpha = 1f + (pressedAlpha - 1f) * p
                // 按钮内容互不重叠，用 ModulateAlpha 把 alpha 下推到每个 draw op，不建离屏缓冲
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        }
    }

    return this
        .pointerInput(state) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledRef.value) return@awaitEachGesture
                    val gen = ++state.gen
                    val downAt = SystemClock.uptimeMillis()
                    launch { state.progress.animateTo(1f, EchoMotion.Instant.spec(Thr.SCALE)) }

                    var longFired = false
                    val long = longRef.value
                    val up: PointerInputChange? = if (long != null) {
                        try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            longFired = true
                            Haptics.longPress(hostView)
                            long()
                            consumeUntilUp()
                            null
                        }
                    } else {
                        waitForUpOrCancellation()
                    }

                    launch {
                        val held = SystemClock.uptimeMillis() - downAt
                        if (held < MIN_PRESS_MS) delay(MIN_PRESS_MS - held)
                        if (state.gen == gen) state.progress.animateTo(0f, EchoMotion.Instant.spec(Thr.SCALE))
                    }
                    if (!longFired && up != null && enabledRef.value) {
                        up.consume()
                        clickRef.value()
                    }
                }
            }
        }
        .semantics(mergeDescendants = true, properties = semanticsBlock)
        .graphicsLayer(layerBlock)
}

/** 纯点击拦截（遮罩层）：不做缩放、不建 InteractionSource、不加无障碍点击语义 */
@Composable
fun Modifier.echoTap(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val clickRef = rememberUpdatedState(onClick)
    val enabledRef = rememberUpdatedState(enabled)
    return this.pointerInput(Unit) {
        detectTapGestures { if (enabledRef.value) clickRef.value() }
    }
}

/** 长按已触发后吃掉本次手势的剩余事件，避免抬手再触发一次点击 */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
