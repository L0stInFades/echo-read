package app.echoread.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.rememberBackdrop
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.Liquid
import app.echoread.ui.motion.dragToPos
import app.echoread.ui.motion.settleIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 分段控件的一项 */
data class SegmentItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true
)

/**
 * 分段单选：一条胶囊轨道，选中项是一滴在轨道里滑动的「水」。
 *
 * 0.4.x 用的是 M3 的 connected button group（几个相连的按钮各自变形）。换掉它有两个具体原因：
 * 1. **英文标签被切断**。按钮内部左右各 24dp 内边距，375dp 宽的屏上三等分后文字只剩 60dp；
 *    「Left/right」在斜杠处折成两行，而 `maxLines = 1` 把第二行直接裁掉 —— 界面上只剩「Left/」。
 *    这里的文字区几乎占满整格，放不下时先缩字号（14 → 11sp），再不行才省略，绝不折行。
 * 2. 选中态是「跳」过去的。
 *
 * 0.6.0 起滑块是一枚**液态透镜**（规则见 [Liquid]）：
 * - 静止时是一块浅色的托盘；换到另一项的途中它悬起来 —— 变大、褪成透明、边缘出现高光与折射
 *   （旁边的标签被它的边缘「掰」进来），越快越扁越长，落位时回弹一下再落回实心；
 * - 可以直接按住它拖：跟手、两端有橡皮筋，松手按速度落到相邻一项。
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
    // 滑块静止时是一块浅色的「托盘」（两个参考对象与系统控件都是这个样子），不是强调色的实心块：
    // 它悬起来要褪成一枚清透镜，里面的标签得一直读得清 —— 深色字在托盘上、在透镜里都站得住，白字不行
    val thumb = checkedContainerColor ?: if (c.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
    val onThumb = checkedContentColor ?: c.text
    val fade = if (c.glassOn) 0.88f else 0f
    val idle = contentColor ?: c.text2
    val scope = rememberCoroutineScope()
    val selectedRef = rememberUpdatedState(selectedIndex)
    val selectRef = rememberUpdatedState(onSelect)
    val itemsRef = rememberUpdatedState(items)

    // 滑块中心在第几项上（小数）、悬起进度、落位回弹量、有无选中项
    val valid = selectedIndex in 0 until n
    val pos = remember { Animatable(if (valid) selectedIndex.toFloat() else 0f) }
    val lift = remember { Animatable(0f) }
    val recoil = remember { Animatable(0f) }
    val shown = remember { Animatable(if (valid) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动途中滑块压着哪一项（-1 = 没在拖）：标签的选中色跟着它走，松手才真正改选择
    var hover by remember { mutableIntStateOf(-1) }
    // 松手后正在自己落向的那一项：宿主随后把 selectedIndex 改成它时，下面的 effect 不必再驱动一遍
    val settlingTo = remember { intArrayOf(-1) }

    suspend fun land(speed: Float) {
        coroutineScope {
            launch { lift.animateTo(0f, Liquid.Lift) }
            recoil.snapTo(0f)
            recoil.animateTo(0f, Liquid.Recoil, initialVelocity = Liquid.recoilKick(speed))
        }
    }

    LaunchedEffect(selectedIndex, n) {
        if (dragging) return@LaunchedEffect
        if (selectedIndex !in 0 until n) {
            shown.animateTo(0f, tween(Dur.Short, easing = Ease.Linear))
            return@LaunchedEffect
        }
        // 从「没有选中项」出现：直接落位再淡入，不要从别处滑过来
        if (shown.value < 0.01f) pos.snapTo(selectedIndex.toFloat())
        launch { shown.animateTo(1f, tween(Dur.Short, easing = Ease.Linear)) }
        if (settlingTo[0] == selectedIndex) return@LaunchedEffect
        val target = selectedIndex.toFloat()
        if (abs(pos.value - target) > 0.01f) {
            val distance = abs(pos.value - target)
            launch { lift.animateTo(1f, Liquid.Lift) }
            pos.animateTo(target, Liquid.Travel)
            land(distance * 2f)
        } else if (lift.value > 0.01f) {
            lift.animateTo(0f, Liquid.Lift)
        }
    }

    val lensBackdrop = rememberBackdrop(snapshots = false).takeIf { c.glassOn }
    val lensStyle = remember(c.isDark) { segmentLens(c.isDark) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(n) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val item = size.width.toFloat() / n
                        val downIndex = (down.position.x / item).toInt().coerceIn(0, n - 1)
                        // 按在滑块上：立刻悬起来，等着被拖
                        val onThumb = shown.value > 0.5f && downIndex == pos.value.roundToInt()
                        if (onThumb) launch { lift.animateTo(1f, Liquid.Lift) }
                        val tracker = VelocityTracker()
                        var moved = false
                        var upAt: androidx.compose.ui.geometry.Offset? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                upAt = change.position
                                if (moved || !change.isConsumed) change.consume() else upAt = null
                                break
                            }
                            val d = change.position - down.position
                            if (!moved) {
                                // 被别人拿走（面板的竖向滚动）或明显是竖着划：这次手势不归这里
                                if (change.isConsumed || abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) break
                                if (abs(d.x) > viewConfiguration.touchSlop) {
                                    moved = true
                                    dragging = true
                                    launch { lift.animateTo(1f, Liquid.Lift) }
                                    launch { shown.animateTo(1f, tween(Dur.Short, easing = Ease.Linear)) }
                                }
                            }
                            if (moved) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val p = dragToPos(change.position.x, size.width.toFloat(), n)
                                launch { pos.animateTo(p, Liquid.Lift) }
                                val over = p.roundToInt().coerceIn(0, n - 1)
                                if (over != hover) {
                                    if (hover >= 0) Haptics.tick(view)
                                    hover = over
                                }
                            }
                        }
                        val current = selectedRef.value
                        if (moved) {
                            val v = tracker.calculateVelocity().x / item
                            var target = settleIndex(pos.value, v, n, current)
                            if (!itemsRef.value[target].enabled) target = if (current in 0 until n) current else target
                            settlingTo[0] = target
                            dragging = false
                            hover = -1
                            if (target != current) selectRef.value(target)
                            scope.launch {
                                pos.animateTo(target.toFloat(), Liquid.Travel, initialVelocity = v)
                                land(abs(v))
                                settlingTo[0] = -1
                                // 宿主没有接受这次选择：回到它认的那一项
                                val now = selectedRef.value
                                if (now in 0 until n && now != target) pos.animateTo(now.toFloat(), Liquid.Travel)
                            }
                        } else {
                            val at = upAt
                            val tapped = if (at != null) (at.x / item).toInt().coerceIn(0, n - 1) else -1
                            if (tapped >= 0 && tapped != current && itemsRef.value[tapped].enabled) {
                                Haptics.tick(view)
                                selectRef.value(tapped)
                            } else {
                                launch { lift.animateTo(0f, Liquid.Lift) }
                            }
                        }
                    }
                }
            }
            .semantics { selectableGroup() }
    ) {
        val insetPx = 4.dp
        val growW = 12.dp
        val growH = 9.dp
        // 轨道、实心的滑块与标签：它们同时也是透镜采样的背板
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .backdropSource(lensBackdrop, Color.Transparent)
                .drawBehind {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
                    if (shown.value > 0.01f) {
                        val r = Liquid.thumbRect(
                            size.width, size.height, n, pos.value, lift.value, pos.velocity / n, recoil.value,
                            insetPx.toPx(), growW.toPx(), growH.toPx()
                        )
                        val corner = CornerRadius(r.height / 2f)
                        val solid = shown.value * (1f - fade * lift.value)
                        // 托盘底下一线很淡的影子：它是浮在轨道上的
                        drawRoundRect(
                            Color.Black, topLeft = r.topLeft + androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()), size = r.size,
                            cornerRadius = corner, alpha = 0.10f * solid
                        )
                        // 悬起时托盘褪掉：它正在从「一块浅色的托盘」变成「一枚清透镜」
                        drawRoundRect(thumb, topLeft = r.topLeft, size = r.size, cornerRadius = corner, alpha = solid)
                    }
                }
        ) {
            val visual = if (dragging && hover >= 0) hover else selectedIndex
            items.forEachIndexed { i, item ->
                val checked = i == visual
                val fg by animateColorAsState(if (checked) onThumb else idle, tween(Dur.Short, easing = Ease.Standard), label = "segFg")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = if (item.enabled) 1f else 0.38f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            selected = i == selectedIndex
                            if (!item.enabled) disabled()
                            onClick(label = item.label) {
                                if (item.enabled && i != selectedIndex) onSelect(i)
                                true
                            }
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
        // 透镜：与实心滑块同一个外形，叠在它上面。悬起进度为 0 时什么都不画
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val r = Liquid.thumbRect(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), n, pos.value, lift.value, pos.velocity / n, recoil.value,
                        insetPx.toPx(), growW.toPx(), growH.toPx()
                    )
                    val placeable = measurable.measure(
                        Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1))
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .glass(CircleShape, lensStyle, lensBackdrop, power = { lift.value * shown.value })
        )
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
