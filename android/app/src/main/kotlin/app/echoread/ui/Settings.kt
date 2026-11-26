package app.echoread.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.ui.motion.echoPress

/**
 * 设置页的组织单元。
 *
 * 三条规则，都不是审美偏好而是可用性：
 * 1. **当前值要直接看得见**。每一行的副标题（或行尾）就是这一项此刻的取值，
 *    用户扫一眼整页就知道配置成什么样了，不必逐个点开。
 * 2. **相关的项聚成一组**：一组是一张圆角卡片，组内用发丝线分隔，组与组之间留白。
 *    （0.4.x 是「每行一块、块间 2dp」的分段形态，它有一个实打实的缺陷：只有一行的组
 *    拿到的是「中间段」的小圆角，于是同一屏里有的卡片 28dp 圆角、有的几乎是直角。）
 * 3. **改得最频繁的排在最上面**。凭据是一次性的，音色和语速是每次都可能动的。
 *
 * 行不做按压缩放：行嵌在整张卡片里，缩放会让它从卡片里凸出来、分隔线跟着错位。
 * 按下时只盖一层淡淡的状态色（与参考对象 FlClash 的做法一致）。
 */

/** 分组卡片的圆角 */
val GroupRadius: Dp = 22.dp

/** 一组设置项。[title] 是组标题，省略则不画标题；[footer] 是组下方的一句说明 */
@Composable
fun SettingsSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: SettingsScope.() -> Unit
) {
    val scope = remember(content) { SettingsScope().apply(content) }
    val items = scope.items
    val c = echo
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionLabel(title)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(GroupRadius)).background(c.fill)) {
            items.forEachIndexed { i, item ->
                item()
                if (i < items.lastIndex) Hairline(start = 16.dp)
            }
        }
        if (footer != null) {
            Text(
                footer, color = c.text2, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }
    }
}

/** 组内分隔线：从文字左缘起，不顶到卡片边 */
@Composable
fun Hairline(start: Dp = 0.dp, color: Color = echo.hairline) {
    Spacer(
        Modifier.fillMaxWidth().height(1.dp).drawBehind {
            val y = size.height / 2f
            drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 1f.coerceAtLeast(density * 0.6f))
        }
    )
}

/** 收集组内条目的 DSL（非 @Composable，与 M3 的 ButtonGroupScope 同构） */
class SettingsScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    /** 普通一行：标题 + 当前值 + 可选尾部内容 */
    fun row(
        title: String,
        value: String? = null,
        icon: ImageVector? = null,
        enabled: Boolean = true,
        /** 自定义的前置内容（服务商头像）；给了它就不画 [icon] */
        leading: (@Composable () -> Unit)? = null,
        /** 行尾的短取值（「1.5×」「English」），与副标题二选一：短的放行尾，长的放副标题 */
        end: String? = null,
        valueColor: Color? = null,
        trailing: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ) {
        items += { SettingsRow(title, value, icon, enabled, leading, end, valueColor, trailing, onClick) }
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
        items += {
            SettingsRow(
                title, value, icon, enabled, null, null, null,
                trailing = {
                    LiquidSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
                },
                onClick = { if (enabled) onChange(!checked) }
            )
        }
    }

    /** 任意自定义内容，仍然套用组内的内边距 */
    fun custom(content: @Composable ColumnScope.() -> Unit) {
        items += { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), content = content) }
    }

    /**
     * 左右不加内缩的自定义内容，给横向滚动的一排用。
     * 内缩若加在滚动容器**外面**，最后一项会被永久裁掉一半 —— 它滚不进那 16dp 里。
     * 所以这里把内缩交给内容自己当作 contentPadding 处理。
     */
    fun customFullBleed(content: @Composable ColumnScope.() -> Unit) {
        items += { Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), content = content) }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String?,
    icon: ImageVector?,
    enabled: Boolean,
    leading: (@Composable () -> Unit)?,
    end: String?,
    valueColor: Color?,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?
) {
    val c = echo
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.4f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(
                if (onClick != null && enabled) Modifier.echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f), onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = c.text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(value, color = valueColor ?: c.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (end != null) {
            Spacer(Modifier.width(12.dp))
            // 行尾取值封顶 160dp：再长就省略，绝不把标题挤没
            Text(
                end, color = c.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 160.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(if (end != null) 4.dp else 12.dp))
            trailing()
        }
    }
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
        Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(valueLabel, color = c.accent, style = MaterialTheme.typography.labelLargeEmphasized, maxLines = 1)
    }
    Spacer(Modifier.height(4.dp))
    EchoSlider(value, onChange, range, steps)
}

/** 行尾的「进入」箭头，所有可点进下一页的行共用 */
@Composable
fun ChevronEnd() {
    Icon(EchoIcons.ChevronRight, null, tint = echo.text3, modifier = Modifier.size(18.dp))
}

/** 服务商 / 模型的头像：品牌色圆角方块 + 名字首字母。没有任何一家的商标图形 —— 只是一个好认的色块 */
@Composable
fun Monogram(text: String, color: Color, size: Dp = 40.dp) {
    val letters = remember(text) { monogramOf(text) }
    Box(
        Modifier.size(size).background(color, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letters,
            color = if (color.luminanceApprox() > 0.6f) Color(0xFF111111) else Color.White,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1, softWrap = false
        )
    }
}

/** 取一到两个字符：拉丁名取首字母（两个词取两个），中文名取第一个字 */
internal fun monogramOf(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    val first = t[0]
    if (first.code > 0x2E7F) return first.toString()
    val words = t.split(' ', '-', '·', '_', '.', '/').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
    return when {
        words.size >= 2 && words[1][0].code <= 0x2E7F -> "${words[0][0].uppercaseChar()}${words[1][0].uppercaseChar()}"
        else -> first.uppercaseChar().toString()
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
