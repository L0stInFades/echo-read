package app.echoread.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 分段控件的一项 */
data class SegmentItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true
)

/**
 * 分段单选（M3 Expressive 的 **connected button group**）。
 *
 * 为什么值得替掉原来的 `Chip` 行：这些位置在语义上从来就不是「筛选标签」，而是单选 ——
 * Google 概览图里那组「Going / Not Going / Maybe」正是这个组件。它带来三样 Chip 给不了的东西：
 * ① 相连的形状把「这几项是一组、互斥」直接画了出来（首/中/尾三种圆角）；
 * ② 选中/按下会做形状形变，动画走的是主题里的 Expressive 弹簧（也就是我们自研的 CA 管线，
 *    经 [app.echoread.ui.motion.EchoMotionScheme] 接进 M3）；
 * ③ `Role.RadioButton` 让读屏软件正确读成单选组，而不是一串互不相干的按钮。
 *
 * 未选中态显式描边：`ToggleButtonDefaults` 的未选中容器色是 `surfaceContainer`，
 * 而弹层面板底色（`echo.card`）恰好也映射到 `surfaceContainer` —— 两者逐位相同，
 * 不加边框的话未选中项会完全没有容器，看上去像凭空浮着的文字。
 */
@Composable
fun EchoSegmented(
    items: List<SegmentItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /**
     * 显式配色。阅读器里的面板必须传入阅读主题色 —— `ToggleButtonDefaults` 取的是 app 配色，
     * 而阅读主题是独立于系统深浅色的用户选择，直接落在书页上会错配
     * （最常见的一种：浅色系统 + 暗夜阅读主题，深色底上出现浅色容器）。
     */
    containerColor: Color? = null,
    contentColor: Color? = null,
    checkedContainerColor: Color? = null,
    checkedContentColor: Color? = null,
    borderColor: Color? = null,
    onSelect: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val c = echo
    val colors = if (containerColor == null && checkedContainerColor == null) {
        ToggleButtonDefaults.toggleButtonColors()
    } else {
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = containerColor ?: Color.Transparent,
            contentColor = contentColor ?: c.text2,
            checkedContainerColor = checkedContainerColor ?: c.accent,
            checkedContentColor = checkedContentColor ?: c.onAccent
        )
    }
    val stroke = borderColor ?: c.border
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        items.forEachIndexed { i, item ->
            val checked = i == selectedIndex
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (it) onSelect(i) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                enabled = item.enabled,
                colors = colors,
                // 首 / 中 / 尾三种形状：这就是「相连」的全部实现，M3 会在按下与选中时各取一档
                shapes = when {
                    items.size == 1 -> ToggleButtonDefaults.shapes()
                    i == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    i == items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                border = if (checked) null else BorderStroke(1.dp, stroke),
                contentPadding = ToggleButtonDefaults.ContentPadding
            ) {
                if (item.icon != null) {
                    Icon(item.icon, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

/** 供已经在 Row 里手工布局的场景使用：单独取某一档的形状 */
@Composable
fun connectedShapesAt(index: Int, count: Int) = when {
    count <= 1 -> ToggleButtonDefaults.shapes()
    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}
