package app.echoread.ui.motion

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 速度继承系数：完全继承会让连续快扫失控，完全丢弃会让「追着自己的动画再推一把」没反应 */
const val VELOCITY_CARRY_RATIO = 0.5f

private sealed interface DriveSignal {
    data object Start : DriveSignal
    class Delta(val px: Float) : DriveSignal
    class Stop(val velocityPxPerSec: Float) : DriveSignal
    data object Cancel : DriveSignal
}

/**
 * 水平手势绑定：tap 与 drag 在同一个 `awaitEachGesture` 里裁决，位移 1:1 直写驱动器。
 *
 * `pointerInput(Unit)`：手势协程的 key 必须是常量，业务状态（播放中/重排版）绝不能重启手势协程 ——
 * 那正是「拖到一半手势突然失灵」的直接原因。所有可变数据必须通过 lambda 参数读取（且 lambda 只许
 * 捕获 State / @Stable 对象，不能捕获组合期的普通局部值）。
 */
fun Modifier.driveHorizontally(
    driver: MotionDriver,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    onDragStart: () -> Unit = {},
    onTap: ((position: Offset, size: IntSize) -> Unit)? = null,
    onSettle: (velocityPxPerSec: Float) -> Unit,
): Modifier = driveAxis(driver, Orientation.Horizontal, enabled, bounds, onDragStart, onTap, onSettle)

/** 垂直手势绑定（底部弹层）：语义与 [driveHorizontally] 完全一致 */
fun Modifier.driveVertically(
    driver: MotionDriver,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    onDragStart: () -> Unit = {},
    onSettle: (velocityPxPerSec: Float) -> Unit,
): Modifier = driveAxis(driver, Orientation.Vertical, enabled, bounds, onDragStart, null, onSettle)

private fun Modifier.driveAxis(
    driver: MotionDriver,
    orientation: Orientation,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    onDragStart: () -> Unit,
    onTap: ((Offset, IntSize) -> Unit)?,
    onSettle: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    val signals = Channel<DriveSignal>(Channel.UNLIMITED)
    coroutineScope {
        // 消费者：驱动器的写入必须在协程里（drive{} 要拿 MutatorMutex），指针回调只负责投递位移
        launch {
            while (isActive) {
                if (signals.receive() !is DriveSignal.Start) continue
                var release: Float? = null
                driver.drive {
                    // 抢占正在跑的动画时残余的速度按系数并入松手速度
                    val carried = consumeCarriedVelocityPxPerSec() * VELOCITY_CARRY_RATIO
                    while (true) {
                        val s = signals.receive()
                        if (s is DriveSignal.Delta) {
                            dragByPx(s.px, bounds())
                        } else if (s is DriveSignal.Stop) {
                            release = s.velocityPxPerSec + carried
                            break
                        } else if (s is DriveSignal.Cancel) {
                            release = carried
                            break
                        }
                    }
                }
                release?.let(onSettle)
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled()) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var overSlop = 0f
            var dragging = false
            val slopChange = if (orientation == Orientation.Horizontal) {
                awaitHorizontalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); dragging = true; overSlop = over }
            } else {
                awaitVerticalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); dragging = true; overSlop = over }
            }
            if (!dragging || slopChange == null) {
                // 未达到 touch slop 就抬手 —— 纯点击（左右点按翻页 / 中间点读）
                if (!dragging && onTap != null) onTap(down.position, size)
                return@awaitEachGesture
            }
            tracker.addPointerInputChange(slopChange)
            onDragStart()
            signals.trySend(DriveSignal.Start)
            // 内容位移与手指方向相反：手指左滑 = 下一页进来 = value 增大
            if (overSlop != 0f) signals.trySend(DriveSignal.Delta(-overSlop))
            val onDrag: (PointerInputChange) -> Unit = { c ->
                tracker.addPointerInputChange(c)
                signals.trySend(DriveSignal.Delta(-axisOf(c.positionChange(), orientation)))
                c.consume()
            }
            val completed = if (orientation == Orientation.Horizontal) {
                horizontalDrag(down.id, onDrag)
            } else {
                verticalDrag(down.id, onDrag)
            }
            if (completed) {
                val v = tracker.calculateVelocity()
                signals.trySend(DriveSignal.Stop(-if (orientation == Orientation.Horizontal) v.x else v.y))
            } else {
                signals.trySend(DriveSignal.Cancel)
            }
        }
    }
}

private fun axisOf(offset: Offset, orientation: Orientation): Float =
    if (orientation == Orientation.Horizontal) offset.x else offset.y
