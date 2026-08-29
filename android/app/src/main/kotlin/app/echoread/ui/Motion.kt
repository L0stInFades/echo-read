package app.echoread.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * 动效体系（ColorOS 风格）：一切位移/缩放/展开都走弹簧物理曲线，按压有回弹，
 * 入场轻微上浮 + 淡入，退场快速收敛。
 */
object Motion {
    /** 通用弹簧：略欠阻尼，收尾干脆 */
    val spring: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 420f)
    /** 柔和弹簧：大面积位移（弹层、页面） */
    val soft: SpringSpec<Float> = spring(dampingRatio = 0.88f, stiffness = 260f)
    /** 弹性弹簧：按压回弹、指示器 */
    val bouncy: SpringSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 520f)
    val colorSpring: SpringSpec<androidx.compose.ui.graphics.Color> = spring(dampingRatio = 0.82f, stiffness = 420f)
    val offsetSpring: SpringSpec<IntOffset> = spring(dampingRatio = 0.86f, stiffness = 300f)
    val sizeSpring: SpringSpec<androidx.compose.ui.unit.IntSize> = spring(dampingRatio = 0.86f, stiffness = 320f)

    /** 弹层入场：自底上浮 */
    val sheetEnter: EnterTransition = slideInVertically(animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)) { it } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
    val sheetExit: ExitTransition = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(180))

    /** 内容入场：轻微上浮 + 淡入 */
    val riseIn: EnterTransition = fadeIn(spring(stiffness = 300f)) + slideInVertically(animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f)) { it / 8 }
    val sinkOut: ExitTransition = fadeOut(tween(160)) + slideOutVertically(animationSpec = tween(160)) { it / 10 }

    val expandIn: EnterTransition = expandVertically(animationSpec = spring(dampingRatio = 0.86f, stiffness = 320f)) + fadeIn(spring(stiffness = 300f))
    val collapseOut: ExitTransition = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(140))
}

/** 按压回弹缩放（无水波纹）：ColorOS 式的“可捏”反馈 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Motion.bouncy, label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 可点击 + 按压回弹 */
fun Modifier.bounceClick(enabled: Boolean = true, pressedScale: Float = 0.96f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this
        .pressScale(interaction, pressedScale)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** 可点击 + 长按 + 按压回弹 */
@androidx.compose.foundation.ExperimentalFoundationApi
fun Modifier.bounceCombinedClick(pressedScale: Float = 0.96f, onLongClick: () -> Unit, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this
        .pressScale(interaction, pressedScale)
        .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}
