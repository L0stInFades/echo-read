package app.echoread.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.MotionScheme

/**
 * 把 Material 3 的 6 槽动效协议接到我们自己的弹簧档位上。
 *
 * 这是「谷歌的动画标准 × 自研 CA 管线」在**组件侧**的接缝：装进
 * `MaterialExpressiveTheme(motionScheme = EchoMotionScheme)` 之后，所有读取主题动效的 M3 组件
 * （ToggleButton / ButtonGroup / FloatingToolbar / LoadingIndicator / Sheet …）都会跑在
 * [EchoMotion] 的曲线上，而不是 Google 的默认曲线 —— 全应用只有一套动效词汇。
 *
 * 反过来，**我们自己驱动的表面（翻页、弹层拖拽、预测性返回、按压回弹）绝不经过这里**：
 * 它们直接调 `driver.animateToBy(spec = EchoMotion.X.float())`。原因是 [MotionScheme] 只能交出
 * 一条裸的 `FiniteAnimationSpec`，既没有 MutatorMutex 仲裁、没有速度继承、没有 rebase/snapTo，
 * 也没有橡皮筋 —— 用它替换 [MotionDriver] 会是一次实打实的能力倒退。
 *
 * 槽位映射的三个刻意选择：
 * - `fastSpatialSpec → Playful`：这一槽正是 M3 Expressive 的性格所在（ToggleButton、FAB、
 *   FloatingToolbar 等小控件），9.5% 的过冲是设计意图。**不能给 Track** —— Track 是手势 settle 专用，
 *   一旦漏进组件，组件就会带上为翻页调的收敛特性。
 * - `defaultEffectsSpec → Instant`：与 Google 的 defaultEffects 数值完全相同，因此 M3 组件的按压形变
 *   与我们 `Modifier.echoPress` 的缩放是逐帧同步的。
 * - `slowEffectsSpec → Gentle`：唯一一处有意偏离（Google 141ms，我们 350ms）。大面积遮罩与背景色渐变
 *   用更慢的临界阻尼是 Lector 的既定风格，属选择而非疏漏。
 *
 * 六个 accessor 都是 O(1) 读取：`Spring2` 内部已缓存好 SpringSpec，不会每次组合分配。
 */
object EchoMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = EchoMotion.Standard.spec()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = EchoMotion.Playful.spec()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = EchoMotion.Expand.spec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = EchoMotion.Instant.spec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = EchoMotion.Flash.spec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = EchoMotion.Gentle.spec()
}
