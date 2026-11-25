package app.echoread.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress
import kotlinx.coroutines.launch

/** 分段控件的一项 */
data class SegmentItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true
)

/**
 * 分段单选：一条胶囊轨道，选中项是一块在轨道里滑动的「水滴」。
 *
 * 0.4.x 用的是 M3 的 connected button group（几个相连的按钮各自变形）。换掉它有两个具体原因：
 * 1. **英文标签被切断**。按钮内部左右各 24dp 内边距，375dp 宽的屏上三等分后文字只剩 60dp；
 *    「Left/right」在斜杠处折成两行，而 `maxLines = 1` 把第二行直接裁掉 —— 界面上只剩「Left/」。
 *    这里的文字区几乎占满整格，放不下时先缩字号（14 → 11sp），再不行才省略，绝不折行。
 * 2. 选中态是「跳」过去的。滑块让「从这一项换到那一项」是一个连续的动作：前缘用硬弹簧先到，
 *    后缘用软弹簧跟上，中途被拉长一点再收拢 —— 液态玻璃控件的那种手感，不靠任何着色器。
 *
 * 读屏语义：整组是 selectableGroup，每项是 RadioButton，与原来一致。
 */
@Composable
fun EchoSegmented(
    items: List<SegmentItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /**
     * 显式配色。阅读器里的面板必须传入阅读主题色 —— 默认取的是 app 配色，
     * 而阅读主题是独立于系统深浅色的用户选择，直接落在书页上会错配
     * （最常见的一种：浅色系统 + 暗夜阅读主题，深色底上出现浅色容器）。
     */
    containerColor: Color? = null,
    contentColor: Color? = null,
    checkedContainerColor: Color? = null,
    checkedContentColor: Color? = null,
    height: Dp = 44.dp,
    onSelect: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val c = echo
    val view = LocalView.current
    val n = items.size
    val track = containerColor ?: c.fillStrong
    val thumb = checkedContainerColor ?: c.accent
    val onThumb = checkedContentColor ?: c.onAccent
    val idle = contentColor ?: c.text2

    // 滑块的左右两缘各是一条独立的弹簧（单位：占整条轨道的比例）
    val start = if (selectedIndex in 0 until n) selectedIndex.toFloat() / n else 0f
    val left = remember { Animatable(start) }
    val right = remember { Animatable(start + 1f / n) }
    val shown = remember { Animatable(if (selectedIndex in 0 until n) 1f else 0f) }
    LaunchedEffect(selectedIndex, n) {
        if (selectedIndex !in 0 until n) {
            shown.animateTo(0f, tween(Dur.Short, easing = Ease.Linear))
            return@LaunchedEffect
        }
        val l = selectedIndex.toFloat() / n
        val r = (selectedIndex + 1f) / n
        if (shown.value < 0.01f) {
            // 从「没有选中项」出现：直接落位再淡入，不要从别处滑过来
            left.snapTo(l)
            right.snapTo(r)
        }
        launch { shown.animateTo(1f, tween(Dur.Short, easing = Ease.Linear)) }
        val movingRight = l > left.value
        // 前缘硬、后缘软：滑动途中滑块被拉长，到位后收拢
        launch { left.animateTo(l, if (movingRight) EchoMotion.Emphasized.float() else EchoMotion.Standard.float()) }
        launch { right.animateTo(r, if (movingRight) EchoMotion.Standard.float() else EchoMotion.Emphasized.float()) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val pill = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = pill)
                val inset = 4.dp.toPx()
                val x0 = left.value * size.width + inset
                val x1 = right.value * size.width - inset
                if (x1 > x0 && shown.value > 0.01f) {
                    val h = size.height - inset * 2
                    drawRoundRect(
                        thumb, topLeft = Offset(x0, inset), size = Size(x1 - x0, h),
                        cornerRadius = CornerRadius(h / 2f), alpha = shown.value
                    )
                }
            }
            .semantics { selectableGroup() }
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { i, item ->
                val checked = i == selectedIndex
                val fg by animateColorAsState(if (checked) onThumb else idle, tween(Dur.Short, easing = Ease.Standard), label = "segFg")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = if (item.enabled) 1f else 0.38f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .echoPress(enabled = item.enabled, pressedScale = PressScale.Chip, onClickLabel = item.label) {
                            if (!checked) {
                                Haptics.tick(view)
                                onSelect(i)
                            }
                        }
                        .semantics {
                            role = Role.RadioButton
                            selected = checked
                        }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    if (item.icon != null) {
                        Icon(item.icon, null, tint = fg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        item.label,
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        // 放不下先缩字号，缩到 11sp 还放不下才省略
                        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

/** 两项的常用简写（开/关、A/B） */
@Composable
fun EchoSegmented(
    first: String,
    second: String,
    firstSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (firstChosen: Boolean) -> Unit
) = EchoSegmented(
    items = listOf(SegmentItem(first), SegmentItem(second)),
    selectedIndex = if (firstSelected) 0 else 1,
    modifier = modifier
) { onSelect(it == 0) }
