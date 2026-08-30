package app.echoread.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触觉反馈：只在「手势跨过一个语义边界」的瞬间发一次，绝不按帧、绝不给程序化动画（引擎自动翻页不震）。
 * 全部走 [View.performHapticFeedback]：跟随系统「触摸振动」开关，MIUI / One UI 会映射到各自的马达波形。
 *
 * | 事件        | 场景                                   | 系统常量（按 API 回退）                  |
 * |-------------|----------------------------------------|------------------------------------------|
 * | tick        | 翻页越过 50%、弹层吸附到位、Chip 选中   | SEGMENT_TICK(34) → CLOCK_TICK            |
 * | threshold   | 拖动跨过提交阈值（预测性返回）          | GESTURE_THRESHOLD_ACTIVATE(34) → CONTEXT_CLICK(23) → CLOCK_TICK |
 * | gestureEnd  | 手势提交完成（返回、关闭）              | GESTURE_END(30) → CONTEXT_CLICK(23) → CLOCK_TICK |
 * | reject      | 到书首/书尾、邻章未就绪                 | REJECT(30) → CLOCK_TICK                   |
 * | longPress   | 长按弹出操作                            | LONG_PRESS                                |
 */
object Haptics {
    /** 用户开关（阅读设置里的「触觉反馈」），默认开 */
    @Volatile var enabled: Boolean = true

    fun tick(view: View?) = fire(view, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun threshold(view: View?) = fire(
        view,
        when {
            Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
    )

    fun gestureEnd(view: View?) = fire(
        view,
        when {
            Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.GESTURE_END
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
    )

    fun reject(view: View?) = fire(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CLOCK_TICK)

    fun longPress(view: View?) = fire(view, HapticFeedbackConstants.LONG_PRESS)

    private fun fire(view: View?, constant: Int) {
        if (!enabled || view == null) return
        view.performHapticFeedback(constant)
    }
}
