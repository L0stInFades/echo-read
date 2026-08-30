package app.echoread.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.Thr
import app.echoread.ui.motion.driveVertically
import app.echoread.ui.motion.echoPress
import app.echoread.ui.motion.echoTap
import app.echoread.ui.motion.preemptable
import app.echoread.ui.motion.settleTarget
import kotlinx.coroutines.launch

/** 颜色不做弹簧：色彩空间上过冲没有意义，短 tween 更省（一屏 20 个 Chip 曾经是 60 条并发弹簧） */
private val colorSpec = tween<Color>(Dur.Short, easing = Ease.Standard)

/* ---------- 卡片 / 分组 ---------- */

/** Harmony 风格大圆角卡片分组 */
@Composable
fun EchoCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = echo
    Column(
        modifier
            .fillMaxWidth()
            .background(color ?: c.card, RoundedCornerShape(radius))
            .border(1.dp, c.border, RoundedCornerShape(radius))
            .padding(padding),
        content = content
    )
}

/** 分组标题（One UI 的小节标签） */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = echo
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.text3, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/* ---------- 按钮 ---------- */

/** 圆形图标按钮（按压回弹，无水波纹）。命中区恒为 [size]，缩放只发生在其内部。 */
@Composable
fun IconButtonEcho(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = echo.text2,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.3f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .echoPress(
                enabled = enabled,
                pressedScale = PressScale.Icon,
                pressedAlpha = 0.6f,
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 极光渐变主按钮（胶囊） */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    fontSize: Int = 15,
    onClick: () -> Unit
) {
    val brush = rememberAurora()
    Row(
        modifier
            .height(height)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.6f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .echoPress(enabled = enabled, pressedScale = PressScale.Button, onClick = onClick)
            .background(brush, CircleShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 次级胶囊按钮 */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = echo.text2, height: Dp = 44.dp, onClick: () -> Unit) {
    val c = echo
    Box(
        modifier
            .height(height)
            .echoPress(pressedScale = PressScale.Button, onClick = onClick)
            .border(1.dp, c.border, CircleShape)
            .background(c.card, CircleShape)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 选项胶囊（选中：强调色描边 + 淡底） */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color? = null,
    onClick: () -> Unit
) {
    val c = echo
    val bg by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, colorSpec, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.accent else c.text2, colorSpec, label = "chipFg")
    val border by animateColorAsState(if (selected) c.accent else c.border, colorSpec, label = "chipBorder")
    Row(
        modifier
            .echoPress(pressedScale = PressScale.Chip, onClick = onClick)
            .border(1.dp, border, CircleShape)
            .background(bg, CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(3.dp))
            Text(trailing, color = trailingColor ?: fg.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

/** 两栏/多栏大选项卡（引擎选择、字体选择） */
@Composable
fun OptionTile(
    title: String,
    subtitle: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = TextStyle(),
    onClick: () -> Unit
) {
    val c = echo
    val bg by animateColorAsState(if (selected) c.accentSoft else c.cardAlt, colorSpec, label = "tileBg")
    val border by animateColorAsState(if (selected) c.accent else Color.Transparent, colorSpec, label = "tileBorder")
    Column(
        modifier
            .echoPress(pressedScale = PressScale.Tile, onClick = onClick)
            .background(bg, RoundedCornerShape(Radius.md))
            .border(1.dp, border, RoundedCornerShape(Radius.md))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = titleStyle)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = c.text2, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

/* ---------- 输入 ---------- */

/** 输入框文字样式：显式行高、去字体内边距、行内居中 —— 光标高度与基线在任何系统字体下都对齐 */
fun fieldTextStyle(color: Color, sizeSp: Int): TextStyle = TextStyle(
    color = color,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.45f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

@Composable
fun EchoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = echo
    Column(modifier) {
        if (label != null) {
            Text(label, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.cardAlt, RoundedCornerShape(Radius.md))
                .border(1.dp, c.border, RoundedCornerShape(Radius.md))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val fieldStyle = fieldTextStyle(c.text, 14)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).let { if (singleLine) it.height(24.dp) else it },
                singleLine = singleLine,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().let { if (singleLine) it.height(24.dp) else it }, contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = fieldStyle.copy(color = c.text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
            )
            trailing?.invoke()
        }
    }
}

@Composable
fun EchoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    val c = echo
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.border,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

/** 细进度条（渐变）：进度值只在 draw lambda 里读，不再用 `fillMaxWidth(fraction)` 承载动画（那是每帧重测量） */
@Composable
fun GradientBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = echo.border) {
    val target = progress.coerceIn(0f, 1f)
    val p = remember { Animatable(target, Thr.FRAC) }
    LaunchedEffect(target) { p.animateTo(target, EchoMotion.Gentle.float()) }
    val brush = rememberAurora()
    Spacer(
        modifier
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(track, cornerRadius = r)
                val w = size.width * p.value
                if (w > 0.5f) drawRoundRect(brush, size = Size(w, size.height), cornerRadius = r)
            }
    )
}

/* ---------- 底部弹层（半模态，整体由驱动器驱动，可拖拽关闭） ---------- */

/**
 * 打开 / 关闭 / 拖拽是**同一条呈现值**，因此：
 * - 下拉一半松手不再「先跳回顶部再滑下去」（旧实现 `drag = 0f` 瞬时归零 + tween 出场）；
 * - 遮罩 alpha 由同一个 `driver.value` 派生（`drawBehind`），物理上不可能与弹层脱节；
 * - 关闭动画中再点开，从当前位置连续回到展开位；
 * - 内容滚到顶再下拉，经 nestedScroll 交给弹层。
 */
@Composable
fun BoxScope.EchoSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    title: String,
    maxHeightFraction: Float = 0.82f,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = echo
    val driver = remember { MotionDriver(0f) }
    val scope = rememberCoroutineScope()
    val dismissRef = rememberUpdatedState(onDismiss)
    // open 是普通参数：长期存活的 snapshotFlow 闭包必须通过 State 读它，否则永远看到首次组合的值
    val openRef = rememberUpdatedState(open)
    var mounted by remember { mutableStateOf(false) }

    // open 只是「目标」，动画永远从当前呈现值出发。
    // 这里刻意不包 preemptable：被手指抢占时就该跳过 mounted = false（用户正抓着它），
    // 收尾交给下面的对账 effect。
    LaunchedEffect(open) {
        if (open) {
            mounted = true
            driver.animateTo(1f, spec = EchoMotion.Emphasized.float())
        } else if (mounted) {
            driver.animateTo(0f, spec = EchoMotion.Emphasized.float())
            mounted = false
        }
    }

    // 对账：宿主说已关闭、手指也松开了，就必须收敛到「落到底 → 卸载」。
    // 没有它的话，「关闭动画播到一半被手指抓住」会让 LaunchedEffect(open) 随之取消，
    // 而 open 已经是 false、不会再触发一次 —— 弹层就永远卡在挂载态（一层看不见却拦点击的遮罩）。
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(openRef.value, driver.isDragging, driver.isSettling) }
            .collect { (want, dragging, settling) ->
                if (want || dragging || settling) return@collect
                if (driver.value > 0.001f) preemptable { driver.animateTo(0f, spec = EchoMotion.Emphasized.float()) }
                else mounted = false
            }
    }

    // 松手落点：逃逸速度 → 投影 → 阈值，与翻页共用同一套判定
    val settle: (Float) -> Unit = remember {
        { velocityPxPerSec: Float ->
            val unit = if (driver.unitPx > 0f) driver.unitPx else 1f
            val target = settleTarget(driver.value, velocityPxPerSec / unit, listOf(0f, 1f))
            scope.launch {
                preemptable { driver.animateTo(target, velocityPxPerSec, EchoMotion.Emphasized.float()) }
                if (target == 0f && driver.value < 0.01f) dismissRef.value()
            }
        }
    }

    val nested = remember {
        object : NestedScrollConnection {
            // 内容区拖动走这条通道时，子滚动容器松手上报的速度常为 0（内容本身没滚动），
            // 自己用位移累积估计速度，松手结算才有「甩一下就关」的手感
            // 自估速度：最近 120ms 内的位移/时间（Compose 的 VelocityTracker 对手工喂入的样本不可靠）
            private val samples = ArrayDeque<Pair<Long, Float>>()
            private var travel = 0f
            private var tracking = false

            private fun feed(deltaPx: Float) {
                val now = System.currentTimeMillis()
                if (!tracking) {
                    samples.clear()
                    travel = 0f
                    tracking = true
                }
                travel += deltaPx
                samples.addLast(now to travel)
                while (samples.size > 1 && now - samples.first().first > 120) samples.removeFirst()
            }

            private fun ownVelocity(): Float {
                if (samples.size < 2) return 0f
                val now = System.currentTimeMillis()
                val (t1, x1) = samples.last()
                if (now - t1 > 150) return 0f // 松手前手指已停住
                val (t0, x0) = samples.first()
                val dt = (t1 - t0).coerceAtLeast(1)
                return (x1 - x0) * 1000f / dt
            }

            // 弹层被下拉过：先把它推回去，再让内容滚动
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && driver.value < 1f) {
                    val consumed = -driver.dispatchRawDeltaPx(-available.y, 0f..1f)
                    feed(consumed)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            // 内容已滚到顶仍在下拉 → 交给弹层
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) {
                    val took = -driver.dispatchRawDeltaPx(-available.y, 0f..1f)
                    feed(took)
                    return Offset(0f, took)
                }
                return Offset.Zero
            }

            // onPreFling 与 onPostFling 会各来一次：一次手势只结算一次，否则第二次（速度 0）会抢占掉第一次的关闭动画
            private fun release(available: Velocity): Velocity {
                if (!tracking) return Velocity.Zero
                val ownV = ownVelocity()
                tracking = false
                if (driver.value < 1f && !driver.isDragging && !driver.isSettling) {
                    // 优先用子容器上报的速度，为 0 时退回自估
                    val v = if (available.y != 0f) available.y else ownV
                    settle(-v)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity = release(available)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = release(available)
        }
    }

    if (!mounted) return
    BackHandler(enabled = open) { onDismiss() }

    val shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
    Box(
        Modifier
            .matchParentSize()
            .zIndex(40f)
            .drawBehind { drawRect(Color.Black, alpha = 0.5f * driver.value.coerceIn(0f, 1f)) }
            .echoTap(onClick = onDismiss)
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .zIndex(50f)
            .fillMaxWidth()
            .fillMaxHeight(maxHeightFraction)
            .onSizeChanged { if (it.height > 0) driver.unitPx = it.height.toFloat() }
            .graphicsLayer { translationY = (1f - driver.value.coerceIn(-0.05f, 1.3f)) * size.height }
            .nestedScroll(nested)
            .driveVertically(driver, enabled = { true }, bounds = { 0f..1f }, onSettle = settle)
            .background(c.card, shape)
            .border(1.dp, c.border, shape)
            .imePadding()
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).background(c.text3.copy(alpha = 0.5f), CircleShape))
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButtonEcho(EchoIcons.Close, "关闭", size = 36.dp, iconSize = 18.dp, onClick = onDismiss)
            }
        }
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .let { if (scrollable) it.verticalScroll(scroll) else it }
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            content = content
        )
    }
}
