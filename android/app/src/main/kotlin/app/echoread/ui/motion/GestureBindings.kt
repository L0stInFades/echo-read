package app.echoread.ui.motion

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

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
): Modifier = driveAxis(driver, { Orientation.Horizontal }, enabled, bounds, { 1f }, onDragStart, onTap, onSettle)

/** 垂直手势绑定（底部弹层）：语义与 [driveHorizontally] 完全一致 */
fun Modifier.driveVertically(
    driver: MotionDriver,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    onDragStart: () -> Unit = {},
    onSettle: (velocityPxPerSec: Float) -> Unit,
): Modifier = driveAxis(driver, { Orientation.Vertical }, enabled, bounds, { 1f }, onDragStart, null, onSettle)

/**
 * 轴向可配置的翻页手势绑定（阅读器专用）。
 *
 * [axis] 返回 null 表示「关闭滑动翻页」——此时仍然识别点按（点读 / 点击热区翻页），
 * 但任何超过 slop 的位移都被放弃，绝不接管。
 *
 * 轴向与 slop 都在**每次手势开始时读一次**：设置里换方向不会重启手势协程
 * （`pointerInput` 的 key 必须恒定，见下方说明），进行中的那一次拖动也不会中途变轴。
 */
fun Modifier.drivePaging(
    driver: MotionDriver,
    axis: () -> Orientation?,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    slopScale: () -> Float = { 1f },
    onDragStart: () -> Unit = {},
    onTap: ((position: Offset, size: IntSize) -> Unit)? = null,
    onSettle: (velocityPxPerSec: Float) -> Unit,
): Modifier = driveAxis(driver, axis, enabled, bounds, slopScale, onDragStart, onTap, onSettle)

private fun Modifier.driveAxis(
    driver: MotionDriver,
    axis: () -> Orientation?,
    enabled: () -> Boolean,
    bounds: () -> ClosedFloatingPointRange<Float>,
    slopScale: () -> Float,
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
                try {
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
                } catch (e: CancellationException) {
                    // 被更高优先级（程序化 snapTo）抢占：本次拖动作废，但手势泵必须活下来
                    if (!isActive) throw e
                    release = null
                }
                release?.let(onSettle)
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled()) return@awaitEachGesture
            // 本次手势的轴向与阈值：开始时读一次，中途改设置不影响进行中的这一次
            val orientation = axis()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            // 自己等 slop：官方的 awaitXxxTouchSlopOrCancellation 用一个 null 同时表示「抬手」「被别人消费」
            // 「另一轴移动过大」，三者不能混为一谈 —— 否则一次纵向滑动会被当成点按去翻页 / 点读。
            val slop = viewConfiguration.touchSlop * slopScale().coerceIn(0.5f, 3f)
            var travel = Offset.Zero
            var overSlop = 0f
            var dragging = false
            var tapped = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || ch.isConsumed) break
                if (!ch.pressed) {
                    // 抬手：位移仍在 slop 内才算点按
                    tapped = travel.getDistance() <= slop
                    break
                }
                tracker.addPointerInputChange(ch)
                travel += ch.positionChange()
                if (orientation == null) {
                    // 滑动翻页已关闭：不接管任何拖动，超过 slop 就放弃本次手势（也不再算点按）
                    if (travel.getDistance() > slop) break
                    continue
                }
                val main = axisOf(travel, orientation)
                if (abs(main) > slop) {
                    overSlop = main - sign(main) * slop
                    dragging = true
                    ch.consume()
                    break
                }
                // 另一轴明显主导：既不接管也不算点按，把手势让给别人
                if (abs(crossAxisOf(travel, orientation)) > slop * 1.5f) break
            }
            if (!dragging || orientation == null) {
                if (tapped && onTap != null) onTap(down.position, size)
                return@awaitEachGesture
            }
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

private fun crossAxisOf(offset: Offset, orientation: Orientation): Float =
    if (orientation == Orientation.Horizontal) offset.y else offset.x
