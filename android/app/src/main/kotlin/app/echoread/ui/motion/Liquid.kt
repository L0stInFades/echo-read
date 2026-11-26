package app.echoread.ui.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.echoread.ui.glass.GlassLight
import kotlin.math.abs

/**
 * 「液态」的那一半：玻璃控件怎么动。
 *
 * 材质（模糊、折射、高光）只回答了「它是什么做的」；让人觉得它是一滴有弹性的东西，靠的是运动。
 * 这里的规则与数值来自对两个参考对象（BiliPai、FlClash）交互逻辑的拆解，各自独立重写：
 *
 * - **按下是鼓起来，不是缩下去。** 普通按钮被按下会缩小（它被压进去了）；玻璃件相反 ——
 *   手指压上去它整块胀大、被拖着的手指拉过去一点，松手时带着过冲弹回去（见 ElasticPress.kt）。
 * - **悬起来的时候才是透镜。** 滑块静止时是一块实心的色块，移动或被按住期间变大、变清澈、边缘出现折射与高光。
 *   分段控件的选中透镜另有一套更完整的规则，见 LensMotion.kt。
 *
 * 弹簧一律用（阻尼比, 刚度）写，方便与参考值逐项对照。
 */
object Liquid {
    /** 悬起 / 落下的进度，以及跟手的位置：又快又不过冲 */
    val Lift: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)

    /** 面板弹出：比常规转场多一点过冲，升到位后轻轻回落一下（Q 弹）。只用于程序化的打开，手势松手仍走不过冲的那条 */
    val Sheet: SpringSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 普通按压件松手时的回弹：缩下去的按钮弹回来时略微冲过原大小再收住 */
    val Rebound: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 420f, visibilityThreshold = 0.002f)
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
