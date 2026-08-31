package app.echoread.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress

/**
 * 设置页的组织单元，对齐原生安卓「设置」的做法。
 *
 * 三条规则，都不是审美偏好而是可用性：
 * 1. **当前值要直接看得见**。每一行的副标题就是这一项此刻的取值，
 *    用户扫一眼整页就知道配置成什么样了，不必逐个点开。
 *    旧版把值藏在输入框里、把标签放在框外，扫读时读到的全是标签。
 * 2. **相关的项聚成一组**，组与组之间留白，组内用分段圆角
 *    （[ListItemDefaults.segmentedShapes]，首项圆上、末项圆下）—— 这是新版安卓设置的形态。
 * 3. **改得最频繁的排在最上面**。凭据是一次性的，音色和语速是每次都可能动的。
 */

/** 一组设置项。[title] 是组标题，省略则不画标题 */
@Composable
fun SettingsSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: SettingsScope.() -> Unit
) {
    val scope = remember(content) { SettingsScope().apply(content) }
    val items = scope.items
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                color = echo.accent,
                style = MaterialTheme.typography.labelLargeEmphasized,
                // 与组内行的文字左缘对齐（16dp），不是与卡片边缘对齐 ——
                // 原生设置里小节标题正是缩进到列表项文字的位置
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        // 分段之间留 2dp：新版安卓设置就是分离的圆角块，不是一整张卡里画分隔线
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, item -> item(i, items.size) }
        }
    }
}

/** 收集组内条目的 DSL（非 @Composable，与 M3 的 ButtonGroupScope 同构） */
class SettingsScope internal constructor() {
    internal val items = mutableListOf<@Composable (Int, Int) -> Unit>()

    /** 普通一行：标题 + 当前值 + 可选尾部内容 */
    fun row(
        title: String,
        value: String? = null,
        icon: ImageVector? = null,
        enabled: Boolean = true,
        trailing: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ) {
        items += { i, n -> SettingsRow(i, n, title, value, icon, enabled, trailing, onClick) }
    }

    /** 开关行 */
    fun switch(
        title: String,
        value: String? = null,
        checked: Boolean,
        icon: ImageVector? = null,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ) {
        items += { i, n ->
            SettingsRow(i, n, title, value, icon, enabled,
                trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
                onClick = { if (enabled) onChange(!checked) })
        }
    }

    /** 任意自定义内容，仍然套用分段圆角与组内配色 */
    fun custom(content: @Composable ColumnScope.() -> Unit) {
        items += { i, n ->
            SettingsSurface(i, n) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), content = content)
            }
        }
    }

    /**
     * 左右不加内缩的自定义内容，给横向滚动的一排用。
     * 内缩若加在滚动容器**外面**，最后一项会被永久裁掉一半 —— 它滚不进那 16dp 里。
     * 所以这里把内缩交给内容自己当作 contentPadding 处理。
     */
    fun customFullBleed(content: @Composable ColumnScope.() -> Unit) {
        items += { i, n ->
            SettingsSurface(i, n) {
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), content = content)
            }
        }
    }
}

/** 分段容器：只负责形状与底色，内容自定 */
@Composable
fun SettingsSurface(index: Int, count: Int, content: @Composable () -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    androidx.compose.material3.Surface(
        shape = shapes.shape,
        color = echo.cardAlt,
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun SettingsRow(
    index: Int,
    count: Int,
    title: String,
    value: String?,
    icon: ImageVector?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?
) {
    val c = echo
    val alpha = if (enabled) 1f else 0.38f
    val colors = ListItemDefaults.colors(
        containerColor = c.cardAlt,
        headlineColor = c.text.copy(alpha = alpha),
        supportingColor = c.text2.copy(alpha = alpha),
        leadingIconColor = c.text2.copy(alpha = alpha),
        trailingIconColor = c.text2.copy(alpha = alpha)
    )
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
    // 只走「不可点」这一个重载：它的插槽可空，没有内容就真的不占位。
    // 点击交给自研的 echoPress —— 按压反馈与全应用一致，也不必为两条路径各写一遍插槽。
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = value?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = icon?.let {
            { Icon(it, null, modifier = Modifier.size(24.dp)) }
        },
        trailingContent = trailing,
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (onClick != null && enabled) Modifier.echoPress(pressedScale = PressScale.Tile, onClick = onClick)
                else Modifier
            )
    )
}

/** 组内的滑块行：标题 + 右侧当前值 + 整行宽的滑块 */
@Composable
fun SettingsSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val c = echo
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(valueLabel, color = c.accent, style = MaterialTheme.typography.labelLargeEmphasized)
    }
    Spacer(Modifier.height(4.dp))
    EchoSlider(value, onChange, range, steps)
}
