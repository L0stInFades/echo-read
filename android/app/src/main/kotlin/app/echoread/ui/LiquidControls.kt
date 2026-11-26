package app.echoread.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.ui.glass.Backdrop
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.backdropSource
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.lensAvailable
import app.echoread.ui.glass.rememberBackdrop
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.Liquid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 滑杆与开关 —— 0.6.0 按两个参考对象（主要是 BiliPai 的那一套「值控件」）的行为重写，未使用其代码。
 *
 * 两个控件共用同一颗滑块，它有两副面孔：
 *
 * - **静止**：一粒 40x24 的实心白色胶囊，底下一圈很淡的影子。就是一个普通的滑块。
 * - **被按住 / 移动中**：它「绽开」—— 放大到 1.39 倍（比轨道还高，浮出来），同时白色褪到全透明，变成一枚**透镜**：
 *   里面看到的是轨道的一份实时副本，在边缘那一圈被折射掰弯，棱上一线反光，内侧一圈暗影让它鼓起来。
 *
 * 几条让它「像水」的规则：
 * - 绽开有自己的弹簧，宽和高各一条、阻尼不同（0.6 / 0.7）：按下去那一下宽度先冲出去，高度慢半拍，整颗晃一下才定住；
 * - 手指拖着走时它**跟手**，不走弹簧；形变跟着速度来 —— 往前拖变宽变扁，往回拖变窄变高，各封顶 20%；
 * - 松手不会立刻缩回去：先等绽开完成（最多 0.3 秒）、再等滑到位（最多 0.45 秒），然后才合拢。
 *   所以哪怕只是快点一下，也看得到完整的一次绽开；
 * - 透镜下面那一段真轨道要挖掉：否则透过透明的滑块会同时看到「没被折射的真轨道」和「被折射的副本」两条。
 */

private val ThumbWidth = 40.dp
private val ThumbHeight = 24.dp

/** 绽开后的大小（参考值：56 → 78） */
private const val Bloomed = 78f / 56f

private val GrowX = spring<Float>(dampingRatio = 0.6f, stiffness = 250f, visibilityThreshold = 0.001f)
private val GrowY = spring<Float>(dampingRatio = 0.7f, stiffness = 250f, visibilityThreshold = 0.001f)
private val Smooth = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.01f)

/** 滑块的运动状态：位置、绽开进度、两个方向的大小、平滑过的速度 */
@Stable
internal class ThumbMotion(initial: Float, private val scope: CoroutineScope) {
    /** 滑块中心在行程上的位置，0..1 */
    val position = Animatable(initial)

    /** 绽开进度 0..1：白色褪掉多少、透镜显出多少 */
    val press = Animatable(0f, 0.001f)

    private val growX = Animatable(1f)
    private val growY = Animatable(1f)

    /** 手指拖动的速度（行程 / 秒，带方向），用弹簧抹平：手指的采样很抖，直接拿来做形变会跳 */
    private val speed = Animatable(0f)

    var held = false
        private set
    private var landing: Job? = null

    private val velocity: Float get() = speed.value + position.velocity

    /** 往 1 的方向走：变宽、变扁；往回走：变窄、变高 */
    val scaleX: Float get() = growX.value / (1f - (0.075f * velocity).coerceIn(-0.2f, 0.2f))
    val scaleY: Float get() = growY.value * (1f - (0.025f * velocity).coerceIn(-0.2f, 0.2f))

    private fun bloom() {
        landing?.cancel()
        scope.launch { press.animateTo(1f, Liquid.Lift) }
        scope.launch { growX.animateTo(Bloomed, GrowX) }
        scope.launch { growY.animateTo(Bloomed, GrowY) }
    }

    /** 手指按下 */
    fun down() {
        held = true
        bloom()
    }

    /** 手指拖到了这里 */
    fun follow(fraction: Float, fingerVelocity: Float) {
        scope.launch { position.snapTo(fraction) }
        scope.launch { speed.animateTo(fingerVelocity, Smooth) }
    }

    /** 手指松开，滑块该落到 [target] */
    fun up(target: Float) {
        held = false
        land(target)
    }

    /** 不是手指拖过去的换位（点了一下、或外部改了值）：绽开、滑过去、合拢 */
    fun travel(target: Float) {
        if (held) return
        bloom()
        land(target)
    }

    private fun land(target: Float) {
        landing?.cancel()
        landing = scope.launch {
            launch { speed.animateTo(0f, Smooth) }
            launch { position.animateTo(target, Liquid.Lift) }
            // 先让绽开播完，再等滑到位，然后才合拢
            withTimeoutOrNull(300) { snapshotFlow { minOf(growX.value, growY.value) }.first { it >= Bloomed * 0.985f } }
            withTimeoutOrNull(450) { snapshotFlow { abs(position.value - target) }.first { it <= 0.015f } }
            launch { press.animateTo(0f, Liquid.Lift) }
            launch { growX.animateTo(1f, GrowX) }
            growY.animateTo(1f, GrowY)
        }
    }

    /** 滑块此刻的外形。[start] 是位置 0 时滑块中心的 x，[travel] 是行程 */
    fun rect(density: Density, start: Float, travel: Float, centerY: Float): Rect = with(density) {
        val w = ThumbWidth.toPx() * scaleX
        val h = ThumbHeight.toPx() * scaleY
        val cx = start + travel * position.value
        Rect(cx - w / 2f, centerY - h / 2f, cx + w / 2f, centerY + h / 2f)
    }
}

/**
 * 滑块的透镜：不模糊、不着色。斜面 7dp、玻璃厚 12dp，边缘的位移约 7dp —— 与斜面同宽，轨道在滑块两端鼓起来，但不至于翻成倒像。
 * （参考实现是 5dp / 10dp，位移是斜面的两倍；逐像素算的着色器上那是一圈顺滑的倒像，
 * 而 Android 10 ~ 12L 上折射是一片片拼出来的，滑块又只有二十几 dp 高，倒像会碎成几块。两档取同一组温和的值，观感才一致。）
 */
private fun thumbLens(dark: Boolean) = GlassStyle(
    tint = Color.Transparent, fallback = Color.Transparent,
    rimHi = Color.White.copy(alpha = if (dark) 0.80f else 0.95f),
    rimLo = Color.White.copy(alpha = 0.25f),
    rimWidth = 1.dp,
    lensDepth = 7.dp, lensAmount = 12.dp, dispersion = 0.5f,
    innerShade = 0.12f
)

/**
 * 画那颗滑块。它的位置与大小在布局期按 [motion] 定（而不是用图层缩放）：这样透镜量到的就是自己真实的外形，
 * 折射出来的画面与底下严丝合缝，不需要再把缩放反算回去。
 */
@Composable
private fun BoxScope.LiquidThumb(motion: ThumbMotion, backdrop: Backdrop?, start: Dp, travel: Density.(width: Float) -> Float) {
    val c = echo
    val dark = c.isDark
    val style = remember(dark) { thumbLens(dark) }
    // 画得出透镜时白色才褪掉；画不出（关了玻璃、或系统太旧）就一直是实心的，只是照样会绽开
    val clears = backdrop != null && lensAvailable
    val fill = if (dark) Color(0xFFD0D2D6) else Color.White
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val r = motion.rect(this, start.toPx(), travel(constraints.maxWidth.toFloat()), constraints.maxHeight / 2f)
                val placeable = measurable.measure(
                    Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1))
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .glassShadow(CircleShape, Color.Black.copy(alpha = if (dark) 0.30f else 0.14f), blur = 4.dp, dy = 1.dp)
            .glass(CircleShape, style, backdrop, power = { motion.press.value.coerceIn(0f, 1f) })
            // 白色画在透镜上面、棱的下面：按下去是白慢慢褪掉露出透镜，而不是透镜盖上来
            .drawBehind {
                val p = if (clears) motion.press.value.coerceIn(0f, 1f) else 0f
                if (p < 0.995f) drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f), alpha = 1f - p)
            }
    )
}

/** 把轨道在滑块底下的那一段挖掉（滑块还是实心时不必：它自己盖着） */
private fun Modifier.cutOutUnder(motion: ThumbMotion, enabled: Boolean, start: Dp, travel: Density.(width: Float) -> Float): Modifier {
    if (!enabled) return this
    val hole = Path()
    return drawWithContent {
        if (motion.press.value < 0.004f) {
            drawContent()
            return@drawWithContent
        }
        val r = motion.rect(this, start.toPx(), travel(size.width), size.height / 2f)
        hole.rewind()
        hole.addRoundRect(RoundRect(r, CornerRadius(r.height / 2f)))
        clipPath(hole, ClipOp.Difference) { this@drawWithContent.drawContent() }
    }
}

private fun inactiveTrack(dark: Boolean): Color = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.20f)

/**
 * 滑杆。整行高 40dp 都可以按；轨道是一条 6dp 的细胶囊，两端各缩进半个滑块，滑块中心正好走满整条轨道。
 *
 * - 按下：滑块就地绽开；横向拖过触摸容差后跳到手指底下并跟手。竖着划（面板在滚动）不抢手势；
 * - 点一下轨道：滑块绽开、滑到那里、合拢；
 * - 有档位时：回报出去的值逐档跳（每过一档一下触感），滑块本身仍然连续跟手，松手才弹到那一档上。
 */
@Composable
fun EchoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = echo
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val motion = remember { ThumbMotion(fraction, scope) }
    val fractionRef = rememberUpdatedState(fraction)
    val changeRef = rememberUpdatedState(onValueChange)
    val enabledRef = rememberUpdatedState(enabled)
    val backdrop = rememberBackdrop(snapshots = false).takeIf { c.glassOn }
    val dark = c.isDark
    val active = c.accent
    val inactive = inactiveTrack(dark)
    val inset = ThumbWidth / 2
    // 行程 = 整行宽度减去两端各半个滑块
    val travel: Density.(Float) -> Float = remember { { width -> width - ThumbWidth.toPx() } }

    // 外部改了值（不是这根手指拖出来的）：滑过去，不绽开
    LaunchedEffect(fraction) {
        if (!motion.held && abs(motion.position.targetValue - fraction) > 0.0005f) motion.position.animateTo(fraction, Liquid.Lift)
    }

    fun snap(f: Float): Float = if (steps <= 0) f else ((f * (steps + 1)).roundToInt() / (steps + 1f)).coerceIn(0f, 1f)
    val lastReported = remember { floatArrayOf(Float.NaN) }
    val lastTick = remember { longArrayOf(0L) }
    fun report(f: Float, at: Long) {
        // 这次手势里第一次回报时，与当前值相同就不必报；之后只在换了值时报
        val previous = if (lastReported[0].isNaN()) fractionRef.value else lastReported[0]
        lastReported[0] = f
        if (abs(f - previous) < 0.0001f) return
        if (steps > 0 && at - lastTick[0] >= 55L) {
            lastTick[0] = at
            Haptics.tick(view)
        }
        changeRef.value(range.start + f * span)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.38f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .pointerInput(steps, range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledRef.value) return@awaitEachGesture
                    val start = inset.toPx()
                    val length = (size.width - start * 2f).coerceAtLeast(1f)
                    fun at(x: Float) = ((x - start) / length).coerceIn(0f, 1f)
                    motion.down()
                    lastReported[0] = Float.NaN
                    var dragging = false
                    var cancelled = false
                    var upX = Float.NaN
                    var lastTime = down.uptimeMillis
                    var lastF = motion.position.value
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging || !change.isConsumed) {
                                upX = change.position.x
                                change.consume()
                            } else {
                                cancelled = true
                            }
                            break
                        }
                        val d = change.position - down.position
                        if (!dragging) {
                            if (change.isConsumed || abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) {
                                cancelled = true
                                break
                            }
                            if (abs(d.x) > viewConfiguration.touchSlop) dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            val f = at(change.position.x)
                            val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L) / 1000f
                            motion.follow(f, (f - lastF) / dt)
                            lastTime = change.uptimeMillis
                            lastF = f
                            report(snap(f), change.uptimeMillis)
                        }
                    }
                    when {
                        cancelled || upX.isNaN() -> motion.up(fractionRef.value)
                        dragging -> motion.up(snap(motion.position.value))
                        else -> {
                            val f = snap(at(upX))
                            report(f, android.os.SystemClock.uptimeMillis())
                            motion.up(f)
                        }
                    }
                }
            }
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
                setProgress { target ->
                    val f = snap(((target - range.start) / span).coerceIn(0f, 1f))
                    onValueChange(range.start + f * span)
                    true
                }
            }
    ) {
        // 轨道：屏幕上画的这一份在滑块底下被挖掉；录给透镜的那一份是完整的
        Spacer(
            Modifier
                .matchParentSize()
                .cutOutUnder(motion, backdrop != null && lensAvailable, inset, travel)
                .backdropSource(backdrop, Color.Transparent)
                .drawBehind {
                    val h = 6.dp.toPx()
                    val x0 = inset.toPx()
                    val x1 = size.width - x0
                    val y = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f)
                    drawRoundRect(inactive, topLeft = Offset(x0, y), size = Size(x1 - x0, h), cornerRadius = r)
                    val x = x0 + (x1 - x0) * motion.position.value.coerceIn(0f, 1f)
                    if (x - x0 > 0.5f) drawRoundRect(active, topLeft = Offset(x0, y), size = Size(x - x0, h), cornerRadius = r)
                }
        )
        LiquidThumb(motion, backdrop, inset, travel)
    }
}

/**
 * 开关。轨道 64x28，滑块两端各留 2dp，行程 20dp。
 *
 * - 点一下：滑块绽开、滑到另一头、合拢；
 * - 按住拖：跟手，松手时过半就算开；
 * - 轨道的颜色跟着滑块的位置连续变化，而不是到头才换；
 * - 绽开的滑块几乎和轨道一样大，透镜里若只是一片轨道色就什么都看不出来 —— 所以给透镜看的是一条**缩小的轨道**：
 *   从一条细线长成轨道四分之三大的小胶囊，像是透过一颗水珠看见了底下的开关。
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = echo
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val motion = remember { ThumbMotion(if (checked) 1f else 0f, scope) }
    val checkedRef = rememberUpdatedState(checked)
    val changeRef = rememberUpdatedState(onCheckedChange)
    val enabledRef = rememberUpdatedState(enabled)
    val backdrop = rememberBackdrop(snapshots = false).takeIf { c.glassOn }
    val dark = c.isDark
    val on = c.accent
    val off = inactiveTrack(dark)
    val start = 22.dp
    val travelDp = 20.dp

    // 外部改了状态（点了整行、或被别处改了）：绽开、滑过去、合拢
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (!motion.held && abs(motion.position.targetValue - target) > 0.001f) motion.travel(target)
    }

    Box(
        modifier
            .size(64.dp, 40.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.38f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledRef.value) return@awaitEachGesture
                    val travel = travelDp.toPx()
                    val from = motion.position.value
                    motion.down()
                    var dragging = false
                    var tapped = false
                    var lastTime = down.uptimeMillis
                    var lastF = from
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging || !change.isConsumed) {
                                tapped = !dragging
                                change.consume()
                            }
                            break
                        }
                        val d = change.position - down.position
                        if (!dragging) {
                            if (change.isConsumed || abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) break
                            if (abs(d.x) > viewConfiguration.touchSlop) dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            val f = (from + d.x / travel).coerceIn(0f, 1f)
                            val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L) / 1000f
                            motion.follow(f, (f - lastF) / dt)
                            lastTime = change.uptimeMillis
                            lastF = f
                        }
                    }
                    val was = checkedRef.value
                    val now = when {
                        dragging -> motion.position.value >= 0.5f
                        tapped -> !was
                        else -> was
                    }
                    motion.up(if (now) 1f else 0f)
                    if (now != was) {
                        Haptics.tick(view)
                        changeRef.value(now)
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!enabled) disabled()
                onClick { onCheckedChange(!checked); true }
            }
    ) {
        val travel: Density.(Float) -> Float = remember { { travelDp.toPx() } }
        // 屏幕上的轨道（滑块绽开后，它底下那一段挖掉）
        Spacer(
            Modifier
                .matchParentSize()
                .cutOutUnder(motion, backdrop != null && lensAvailable, start, travel)
                .drawBehind {
                    val h = 28.dp.toPx()
                    drawRoundRect(
                        lerp(off, on, motion.position.value.coerceIn(0f, 1f)),
                        topLeft = Offset(0f, (size.height - h) / 2f), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2f)
                    )
                }
        )
        // 只录给透镜看、不上屏的那一份：一条缩小的轨道
        Spacer(
            Modifier
                .matchParentSize()
                .backdropSource(backdrop, Color.Transparent, visible = false)
                .drawBehind {
                    val p = motion.press.value.coerceIn(0f, 1f)
                    val h = 28.dp.toPx()
                    scale(2f / 3f + (0.75f - 2f / 3f) * p, 0.75f * p, pivot = center) {
                        drawRoundRect(
                            lerp(off, on, motion.position.value.coerceIn(0f, 1f)),
                            topLeft = Offset(0f, (size.height - h) / 2f), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2f)
                        )
                    }
                }
        )
        LiquidThumb(motion, backdrop, start, travel)
    }
}
