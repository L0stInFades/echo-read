package app.echoread.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.AppGraph
import app.echoread.core.GestureSettings
import app.echoread.core.PageAxis
import app.echoread.core.ReaderSettings

/**
 * 翻页手势设置。
 *
 * 「需要的范围」这种参数光靠一个百分比数字是调不准的，所以顶部放了一张**实时预览**：
 * 拖滑块的同时，两块热区在示意页面上按真实比例伸缩，中间剩下的就是「轻点朗读」的可用区域。
 *
 * 默认值与 0.1.x 的固定行为逐项等价（左右滑 + 左右各 20% 点击翻页 + 中间点读），
 * 所以什么都不动的用户升级后手感完全一致。
 */
@Composable
fun BoxScope.GestureSettingsSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val c = echo
    val reader by graph.settings.reader.collectAsState()
    val g = reader.gestures

    fun edit(block: (GestureSettings) -> GestureSettings) {
        graph.settings.updateReader { r: ReaderSettings -> r.copy(gestures = block(r.gestures)) }
    }

    EchoSheet(open = open, onDismiss = onClose, title = "翻页手势") {
        GesturePreview(g)
        Spacer(Modifier.height(18.dp))

        SectionLabel("滑动翻页") {
            if (g.axis == PageAxis.VERTICAL) Text("不与系统返回手势冲突", color = c.text3, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxisToggle("左右", EchoIcons.SwipeH, g.axis == PageAxis.HORIZONTAL, Modifier.weight(1f)) { edit { it.copy(axis = PageAxis.HORIZONTAL) } }
            AxisToggle("上下", EchoIcons.SwipeV, g.axis == PageAxis.VERTICAL, Modifier.weight(1f)) { edit { it.copy(axis = PageAxis.VERTICAL) } }
            AxisToggle("关闭", EchoIcons.Close, g.axis == PageAxis.OFF, Modifier.weight(1f)) { edit { it.copy(axis = PageAxis.OFF) } }
        }
        if (g.axis == PageAxis.HORIZONTAL) {
            Spacer(Modifier.height(6.dp))
            Text(
                "提示：系统手势导航会先吃掉屏幕左右边缘约 20~40dp 内起手的横滑，那一带只能返回、翻不了页。改成「上下」可以完全避开。",
                color = c.text3, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("点击翻页热区")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxisToggle("左右两侧", EchoIcons.TapZone, g.tapTurn && g.tapAxis == PageAxis.HORIZONTAL, Modifier.weight(1f)) {
                edit { it.copy(tapTurn = true, tapAxis = PageAxis.HORIZONTAL) }
            }
            AxisToggle("上下两端", EchoIcons.SwipeV, g.tapTurn && g.tapAxis == PageAxis.VERTICAL, Modifier.weight(1f)) {
                // 上下热区会吃掉首尾几行的点读，默认收窄一些
                edit { it.copy(tapTurn = true, tapAxis = PageAxis.VERTICAL, prevZone = minOf(it.prevZone, 0.12f), nextZone = minOf(it.nextZone, 0.12f)) }
            }
            AxisToggle("关闭", EchoIcons.Close, !g.tapTurn, Modifier.weight(1f)) { edit { it.copy(tapTurn = false) } }
        }

        if (g.tapTurn) {
            Spacer(Modifier.height(16.dp))
            val startLabel = if (g.tapAxis == PageAxis.VERTICAL) "顶部热区" else "左侧热区"
            val endLabel = if (g.tapAxis == PageAxis.VERTICAL) "底部热区" else "右侧热区"
            // 两侧之和有上限（开着「轻点朗读」时 0.8）。若直接把原值丢给 sanitizeGestures，
            // 它会**按比例同时缩放两侧** —— 拖右边会把左边悄悄拖小，而且最终值取决于滑块回调的采样密度，
            // 同一个手势拖两次结果还不一样。这里先按「另一侧不动」把被拖的这一侧钳住，
            // 让步的永远是正在编辑的那个值；sanitizeGestures 的比例缩放只留给加载腐坏存档时兜底。
            val zoneCap = (if (g.tapToRead) 0.8f else 1f)
            SectionLabel(startLabel) { Text("${(g.prevZone * 100).toInt()}%", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            EchoSlider(g.prevZone, { v -> edit { it.copy(prevZone = minOf(Math.round(v * 100) / 100f, zoneCap - it.nextZone)) } }, 0f..0.5f, steps = 9)
            Spacer(Modifier.height(10.dp))
            SectionLabel(endLabel) { Text("${(g.nextZone * 100).toInt()}%", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            EchoSlider(g.nextZone, { v -> edit { it.copy(nextZone = minOf(Math.round(v * 100) / 100f, zoneCap - it.prevZone)) } }, 0f..0.5f, steps = 9)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (g.tapAxis == PageAxis.VERTICAL) "点上翻上一页" else "点左翻上一页", selected = !g.invertZones, modifier = Modifier.weight(1f)) { edit { it.copy(invertZones = false) } }
                Chip(if (g.tapAxis == PageAxis.VERTICAL) "点上翻下一页" else "点左翻下一页", selected = g.invertZones, modifier = Modifier.weight(1f)) { edit { it.copy(invertZones = true) } }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("轻点正文朗读") { Text(if (g.tapToRead) "热区之外生效" else "已关闭", color = c.text3, fontSize = 11.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("开启", selected = g.tapToRead, modifier = Modifier.weight(1f)) { edit { it.copy(tapToRead = true) } }
            Chip("关闭", selected = !g.tapToRead, modifier = Modifier.weight(1f)) { edit { it.copy(tapToRead = false) } }
        }
        if (!g.tapToRead) {
            Spacer(Modifier.height(6.dp))
            Text("关掉之后就只能用底栏的播放键从当前页开头朗读了。", color = c.text3, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("滑动灵敏度") {
            Text(
                when {
                    g.slopScale <= 0.8f -> "灵敏"
                    g.slopScale >= 1.6f -> "迟钝"
                    else -> "标准"
                },
                color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
        EchoSlider(g.slopScale, { v -> edit { it.copy(slopScale = (Math.round(v * 10) / 10f)) } }, 0.5f..3f, steps = 24)
        Text("调大之后手指要移动更远才算「滑动」，可以避免想点读却翻了页。", color = c.text3, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(22.dp))
        OutlineButton("恢复默认", Modifier.fillMaxWidth()) { edit { GestureSettings() } }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 选中态用 M3 Expressive 的 ToggleButton：选中时按 Expressive 弹簧做形状变化。
 *
 * 未选中态必须显式描边：`ToggleButtonDefaults` 的未选中容器色是 `surfaceContainer`，
 * 而本应用的弹层面板底色（`echo.card`）恰好也映射到 `surfaceContainer` —— 两者逐位相同，
 * 不加边框的话三选一里未选中的那两个会完全没有容器，看上去像凭空浮着的文字。
 */
@Composable
private fun AxisToggle(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val c = echo
    ToggleButton(
        checked = selected,
        onCheckedChange = { if (it) onSelect() },
        modifier = modifier,
        border = if (selected) null else BorderStroke(1.dp, c.border)
    ) {
        Icon(icon, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * 热区实时预览：一张按真实比例绘制的示意页面。
 * 两块热区用强调色着色并标注方向，中间剩余区域标注「轻点朗读」——
 * 「范围」这个概念只有画出来才调得准。
 */
@Composable
private fun GesturePreview(g: GestureSettings) {
    val c = echo
    val vertical = g.tapAxis == PageAxis.VERTICAL
    val zonesOn = g.zonesActive
    val startIsPrev = !g.invertZones
    val zoneColor = c.accent.copy(alpha = 0.28f)
    val readColor = c.accent.copy(alpha = 0.08f)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .clip(RoundedCornerShape(Radius.md))
            .background(c.cardAlt)
            .border(1.dp, c.border, RoundedCornerShape(Radius.md))
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (!zonesOn) {
                    if (g.tapToRead) drawRect(readColor)
                    return@drawBehind
                }
                val extent = if (vertical) size.height else size.width
                val a = g.prevZone * extent
                val b = g.nextZone * extent
                if (g.tapToRead && extent - a - b > 1f) {
                    if (vertical) drawRect(readColor, topLeft = Offset(0f, a), size = Size(size.width, size.height - a - b))
                    else drawRect(readColor, topLeft = Offset(a, 0f), size = Size(size.width - a - b, size.height))
                }
                if (a > 0.5f) {
                    if (vertical) drawRect(zoneColor, size = Size(size.width, a))
                    else drawRect(zoneColor, size = Size(a, size.height))
                }
                if (b > 0.5f) {
                    if (vertical) drawRect(zoneColor, topLeft = Offset(0f, size.height - b), size = Size(size.width, b))
                    else drawRect(zoneColor, topLeft = Offset(size.width - b, 0f), size = Size(b, size.height))
                }
            }
        )
        // 三段文字标注按同样的比例排布，与上面的着色严格对齐
        if (vertical) {
            Column(Modifier.fillMaxSize()) {
                ZoneLabel(if (startIsPrev) "上一页" else "下一页", g.prevZone, zonesOn, Modifier.fillMaxWidth().weight(g.prevZone.coerceAtLeast(0.0001f)))
                ZoneLabel(if (g.tapToRead) "轻点朗读" else "—", 1f, true, Modifier.fillMaxWidth().weight((1f - g.prevZone - g.nextZone).coerceAtLeast(0.0001f)), dim = true)
                ZoneLabel(if (startIsPrev) "下一页" else "上一页", g.nextZone, zonesOn, Modifier.fillMaxWidth().weight(g.nextZone.coerceAtLeast(0.0001f)))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                ZoneLabel(if (startIsPrev) "上一页" else "下一页", g.prevZone, zonesOn, Modifier.fillMaxSize().weight(g.prevZone.coerceAtLeast(0.0001f)))
                ZoneLabel(if (g.tapToRead) "轻点朗读" else "—", 1f, true, Modifier.fillMaxSize().weight((1f - g.prevZone - g.nextZone).coerceAtLeast(0.0001f)), dim = true)
                ZoneLabel(if (startIsPrev) "下一页" else "上一页", g.nextZone, zonesOn, Modifier.fillMaxSize().weight(g.nextZone.coerceAtLeast(0.0001f)))
            }
        }
    }
}

@Composable
private fun ZoneLabel(text: String, share: Float, visible: Boolean, modifier: Modifier, dim: Boolean = false) {
    val c = echo
    Box(modifier, contentAlignment = Alignment.Center) {
        if (visible && share > 0.09f) {
            Text(
                text,
                color = if (dim) c.text3 else c.accent,
                fontSize = 11.sp,
                fontWeight = if (dim) FontWeight.Normal else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
