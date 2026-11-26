package app.echoread.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.lensAvailable
import app.echoread.ui.glass.rememberBackdrop
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.LensMotion
import app.echoread.ui.motion.LensRules
import app.echoread.ui.motion.SegmentLayout
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.roundToInt

/** 分段控件的一项 */
data class SegmentItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true
)

/** 轨道四周的留白；相邻两项透镜互相压住的宽度；悬起时透镜四周各长出去的量（共 16dp） */
private val Pad = 4.dp
private val Overlap = 8.dp
private val Growth = 16.dp

/** 在可以滚动的容器里，手指按住多久没动才算「按下」（之前动了就交给滚动，透镜一下都不动） */
private const val PRESS_DELAY_MS = 100L

/**
 * 分段单选：一条胶囊轨道，选中项底下垫着一块托盘；按住它，托盘悬起成一枚液态透镜。
 *
 * 0.8.0 起按 FlClash（prism_ui）导航栏选中透镜的做法重写（仿其行为与数值，未用其代码），规则见 [LensMotion]：
 * - 静止：选中项底下一块浅色的托盘，那一项的字是强调色（托盘盖住的部分逐像素变色，托盘滑过去时字跟着它变）；
 * - 按下：托盘悬起 —— 长大 16dp、褪成透明，变成一枚透镜：透镜只折射轨道与托盘，
 *   **字不进透镜**：透镜里的字是另画的一份，放大 16%、强调色，叠在玻璃上面（所以不会出现以前那种
 *   「透明的透镜底下透出原来的字，与折射过的字叠成两层」）；
 * - 拖动：透镜追着手指，两端有橡皮筋，每换一项一下触感；松手按速度往前看一小段，最多越过一项；
 * - 松手才提交；透镜要升满、到位之后才落回托盘。
 *
 * 放不下的字先缩字号（14 → 11sp），再不行才省略，绝不折行。读屏：整组是 selectableGroup，每项是 RadioButton。
 */
@Composable
fun EchoSegmented(
    items: List<SegmentItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /**
     * 显式配色。阅读器里的面板必须传入阅读主题色 —— 默认取的是 app 配色，
     * 而阅读主题是独立于系统深浅色的用户选择，直接落在书页上会错配。
     */
    containerColor: Color? = null,
    contentColor: Color? = null,
    /** 托盘的颜色（默认：次要色在 HCT 里的 90 / 4 色调，85% 浓） */
    checkedContainerColor: Color? = null,
    /** 选中项文字的颜色（默认：强调色） */
    checkedContentColor: Color? = null,
    height: Dp = 44.dp,
    onSelect: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val c = echo
    val view = LocalView.current
    val n = items.size
    val scope = rememberCoroutineScope()
    val valid = selectedIndex in 0 until n
    val motion = remember { LensMotion(if (valid) selectedIndex.toFloat() else 0f, scope) }
    val selectedRef = rememberUpdatedState(selectedIndex)
    val selectRef = rememberUpdatedState(onSelect)
    val itemsRef = rememberUpdatedState(items)
    val shown = animateFloatAsState(if (valid) 1f else 0f, tween(Dur.Short, easing = Ease.Linear), label = "segShown")

    // 玻璃画得出（Android 10 起、用户没关）：悬起时是一枚透镜；画不出就是一块实心托盘照样长大、变形
    val lensGlass = c.glassOn && lensAvailable
    val lensBackdrop = rememberBackdrop(snapshots = false).takeIf { lensGlass }
    // 透镜里那份强调色的字：悬起时不直接上屏，由一块「透镜内容」玻璃读着它、在透镜边缘掰弯了再画 —— 玻璃对字也起作用
    val labelBackdrop = rememberBackdrop(snapshots = false).takeIf { lensGlass }
    val track = containerColor ?: c.fillStrong
    val idle = contentColor ?: c.text2
    val tint = checkedContentColor ?: c.accent
    val secondary = MaterialTheme.colorScheme.secondary
    val platter = checkedContainerColor ?: remember(secondary, track, c.isDark, lensGlass, c.text) {
        when {
            lensGlass -> withTone(secondary, if (c.isDark) 4.0 else 90.0).copy(alpha = 0.85f)
            c.isDark -> lerp(track, c.text, 0.14f).copy(alpha = 1f)
            else -> Color.White
        }
    }
    val lensStyle = c.panes.lens
    val shadowColor = Color.Black.copy(alpha = 0.055f * (if (c.isDark) 3f else 1f))
    val geometry = remember { GeometryCache() }

    motion.onIdle = {
        // 宿主没接受这次选择（或者选择在别处又变了）：回到它认的那一项
        val now = selectedRef.value
        if (motion.released && now in 0 until n && abs(motion.heading - now) > 0.001f) motion.slide(now)
    }
    LaunchedEffect(selectedIndex, n) {
        if (!valid || !motion.released) return@LaunchedEffect
        // 从「没有选中项」出现：直接落位再淡入，不要从别处滑过来
        if (shown.value < 0.01f) motion.jumpTo(selectedIndex)
        else if (abs(motion.heading - selectedIndex) > 0.001f) motion.slide(selectedIndex)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(n) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geo = geometry.of(size.width.toFloat(), size.height.toFloat(), n, Pad.toPx(), Overlap.toPx())
                    var pressed = geo.indexAt(geo.positionAt(down.position.x))
                    var engaged = false
                    var dragging = false
                    var cancelled = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    fun engage() {
                        if (engaged) return
                        engaged = true
                        // 原来没有选中项：透镜就在按住的地方冒出来
                        if (shown.value < 0.5f) motion.jumpTo(pressed)
                        motion.press(pressed)
                    }
                    try {
                        while (true) {
                            val event = if (engaged) awaitPointerEvent() else {
                                val left = PRESS_DELAY_MS - (SystemClock.uptimeMillis() - down.uptimeMillis)
                                withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                            }
                            if (event == null) {
                                engage()
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            if (!change.pressed) {
                                // 已被消费的抬起 = 手势被取消（系统截屏手势、窗口失焦时 Compose 发来的就是这样一个抬起）：拖到一半也不提交
                                if (change.isConsumed) cancelled = true else change.consume()
                                break
                            }
                            if (change.isConsumed) {
                                cancelled = true
                                break
                            }
                            // 父节点（列表、面板的滚动）在中间那一遍之后才消费：最后一遍（Final）上看它有没有把这次手势拿走。
                            // 内容跟着手指滚动时，手指相对这个控件几乎不动，单看位移判断不出来
                            if (!dragging && awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) {
                                cancelled = true
                                break
                            }
                            val d = change.position - down.position
                            if (!dragging) {
                                // 明显是竖着划（面板、书架在滚动）：这次手势不归这里
                                if (abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) {
                                    cancelled = true
                                    break
                                }
                                if (abs(d.x) > viewConfiguration.touchSlop) {
                                    dragging = true
                                    engage()
                                }
                            }
                            if (dragging) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val p = geo.positionAt(change.position.x)
                                motion.follow(p)
                                val over = geo.indexAt(p)
                                if (over != pressed) {
                                    Haptics.tick(view)
                                    pressed = over
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 手势协程被取消（控件离开界面、项数变了）：透镜照样落回宿主认的那一项，不会一直悬着
                        if (engaged) selectedRef.value.let { motion.release(if (it in 0 until n) it else pressed) }
                        throw e
                    }
                    val current = selectedRef.value
                    val fallback = if (current in 0 until n) current else pressed
                    if (cancelled) {
                        if (engaged) motion.release(fallback)
                        return@awaitEachGesture
                    }
                    if (dragging) {
                        val flung = LensRules.flingTarget(motion.heading, tracker.calculateVelocity().x / geo.pitch, pressed, n)
                        if (flung != pressed) {
                            Haptics.tick(view)
                            pressed = flung
                        }
                    } else {
                        engage()
                    }
                    // 停用的项选不中：回到原来那一项（原来也没有选中项，就只是落回去）
                    val target = if (itemsRef.value[pressed].enabled) pressed else fallback
                    motion.release(target)
                    if (target != current && itemsRef.value[target].enabled) selectRef.value(target)
                }
            }
            .semantics { selectableGroup() }
    ) {
        // 1. 轨道与托盘。悬起时屏幕上这一份在透镜底下挖掉（透镜画的是它的折射版）；录给透镜的那一份是完整的
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    val lifted = motion.lifted
                    if (lensBackdrop == null || lifted < 0.004f) {
                        drawContent()
                        return@drawWithContent
                    }
                    clipPath(geometry.lensPath(this.size, n, Pad.toPx(), Overlap.toPx(), Growth.toPx(), motion), ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                }
                .backdropSource(lensBackdrop, Color.Transparent)
                .drawBehind {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
                    val shownNow = shown.value
                    if (shownNow < 0.01f) return@drawBehind
                    val r = geometry.lensRect(size, n, Pad.toPx(), Overlap.toPx(), Growth.toPx(), motion)
                    val corner = CornerRadius(r.height / 2f)
                    val lifted = motion.lifted
                    // 有玻璃时托盘随悬起褪掉（它正在变成透镜）；没有玻璃时它一直实心，悬起时压上一层 8% 的前景色
                    val alpha = shownNow * (if (lensBackdrop != null) 1f - lifted else 1f)
                    if (alpha > 0.004f) drawRoundRect(platter, r.topLeft, r.size, corner, alpha = alpha)
                    if (lensBackdrop == null && lifted > 0.004f) drawRoundRect(c.text, r.topLeft, r.size, corner, alpha = 0.08f * lifted * shownNow)
                }
        )
        // 2. 透镜：只在悬起时显形（玻璃一点点弯出来），只折射轨道与托盘
        if (lensBackdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(lensPlacement(geometry, n, motion))
                    .glassShadow(CircleShape, shadowColor, blur = 5.dp, dy = 8.dp, alpha = { motion.lifted })
                    .glass(CircleShape, lensStyle, lensBackdrop, power = { motion.lifted })
            )
        }
        // 3. 字：透镜外是原样的一份；透镜（或托盘）里是另一份，强调色，悬起时放大
        val clipOut: Modifier = Modifier.drawWithContent {
            // 没有选中项、也没在按：没有托盘，字原样画全
            if (shown.value < 0.01f && motion.lifted < 0.004f) {
                drawContent()
                return@drawWithContent
            }
            clipPath(geometry.lensPath(size, n, Pad.toPx(), Overlap.toPx(), Growth.toPx(), motion), ClipOp.Difference) {
                this@drawWithContent.drawContent()
            }
        }
        val clipIn: Modifier = Modifier.drawWithContent {
            if (shown.value < 0.01f && motion.lifted < 0.004f) return@drawWithContent
            clipPath(geometry.lensPath(size, n, Pad.toPx(), Overlap.toPx(), Growth.toPx(), motion)) {
                this@drawWithContent.drawContent()
            }
        }
        Labels(items, selectedIndex, idle, magnify = null, modifier = Modifier.matchParentSize().then(clipOut), onSelect = onSelect)
        Labels(
            items, selectedIndex, tint, magnify = { 1f + LensRules.MAGNIFY * motion.lifted },
            modifier = Modifier
                .matchParentSize()
                .then(clipIn)
                .backdropSource(labelBackdrop, Color.Transparent, show = { labelBackdrop == null || motion.lifted < 0.004f })
                .clearAndSetSemantics { },
            onSelect = null
        )
        if (labelBackdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(lensPlacement(geometry, n, motion))
                    .glass(CircleShape, c.panes.lensContent, labelBackdrop, power = { motion.lifted })
            )
        }
    }
}

/** 一排字。[magnify] 不为空时每一项绕自己的中心放大（透镜里那一份）；[onSelect] 为空时不带读屏语义 */
@Composable
private fun Labels(
    items: List<SegmentItem>,
    selectedIndex: Int,
    color: Color,
    magnify: (() -> Float)?,
    modifier: Modifier,
    onSelect: ((Int) -> Unit)?
) {
    // 每格宽 = 相邻两项中心的距离：两端各让出 轨道留白 + 半个重叠，与透镜的几何一致
    Row(modifier.padding(horizontal = Pad + Overlap / 2)) {
        items.forEachIndexed { i, item ->
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = if (item.enabled) 1f else 0.38f
                        val k = magnify?.invoke() ?: 1f
                        scaleX = k
                        scaleY = k
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .then(
                        if (onSelect == null) Modifier else Modifier.semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            selected = i == selectedIndex
                            if (!item.enabled) disabled()
                            onClick(label = item.label) {
                                if (item.enabled && i != selectedIndex) onSelect(i)
                                true
                            }
                        }
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (item.icon != null) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    item.label,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    // 选中与否不换字重：透镜里那一份要与原来的字形一笔一笔重合
                    fontWeight = FontWeight.Medium,
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

/** 把一个盒子摆到透镜此刻的位置与大小上（在布局期读运动状态，不引起重组） */
private fun lensPlacement(geometry: GeometryCache, n: Int, motion: LensMotion): Modifier = Modifier.layout { measurable, constraints ->
    val r = geometry.lensRect(
        Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
        n, Pad.toPx(), Overlap.toPx(), Growth.toPx(), motion
    )
    val placeable = measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/** 几何缓存：尺寸不变时复用同一份 [SegmentLayout]；透镜的外形路径每帧改写同一条 */
private class GeometryCache {
    private var layout: SegmentLayout? = null
    private val path = Path()

    fun of(width: Float, height: Float, n: Int, pad: Float, overlap: Float): SegmentLayout {
        layout?.let { if (it.width == width && it.height == height && it.n == n) return it }
        return SegmentLayout(width, height, n, pad, overlap).also { layout = it }
    }

    /** 吸附到整像素：透镜那个盒子只能摆在整像素上，挖洞与字的裁剪必须用同一个矩形，否则棱边上会漏出一线 */
    fun lensRect(size: Size, n: Int, pad: Float, overlap: Float, growth: Float, motion: LensMotion): Rect {
        val r = of(size.width, size.height, n, pad, overlap).lensRect(motion.position, motion.lifted, motion.stretch, growth)
        val left = r.left.roundToInt().toFloat()
        val top = r.top.roundToInt().toFloat()
        return Rect(left, top, left + r.width.roundToInt().coerceAtLeast(1), top + r.height.roundToInt().coerceAtLeast(1))
    }

    fun lensPath(size: Size, n: Int, pad: Float, overlap: Float, growth: Float, motion: LensMotion): Path {
        val r = lensRect(size, n, pad, overlap, growth, motion)
        path.rewind()
        path.addRoundRect(RoundRect(r, CornerRadius(r.height / 2f)))
        return path
    }
}

/** 同一个色相与彩度、换一个 HCT 色调 */
private fun withTone(color: Color, tone: Double): Color {
    val h = Hct.fromInt(color.toArgb())
    return Color(Hct.from(h.hue, h.chroma, tone).toInt())
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
