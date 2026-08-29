package app.echoread.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt

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

/** 圆形图标按钮（按压回弹，无水波纹） */
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
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .background(background, CircleShape)
            .bounceClick(enabled = enabled, pressedScale = 0.88f, onClick = onClick),
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
    Row(
        modifier
            .height(height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .background(Aurora, CircleShape)
            .bounceClick(enabled = enabled, onClick = onClick)
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
            .border(1.dp, c.border, CircleShape)
            .background(c.card, CircleShape)
            .bounceClick(onClick = onClick)
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
    val bg by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, Motion.colorSpring, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.accent else c.text2, Motion.colorSpring, label = "chipFg")
    val border by animateColorAsState(if (selected) c.accent else c.border, Motion.colorSpring, label = "chipBorder")
    Row(
        modifier
            .border(1.dp, border, CircleShape)
            .background(bg, CircleShape)
            .bounceClick(pressedScale = 0.93f, onClick = onClick)
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
    val bg by animateColorAsState(if (selected) c.accentSoft else c.cardAlt, Motion.colorSpring, label = "tileBg")
    val border by animateColorAsState(if (selected) c.accent else Color.Transparent, Motion.colorSpring, label = "tileBorder")
    Column(
        modifier
            .background(bg, RoundedCornerShape(Radius.md))
            .border(1.dp, border, RoundedCornerShape(Radius.md))
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
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

/** 细进度条（渐变） */
@Composable
fun GradientBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = echo.border) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.soft, label = "bar")
    Box(modifier.height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Aurora, CircleShape))
    }
}

/* ---------- 底部弹层（半模态，弹簧上浮，可拖拽关闭） ---------- */

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
    BackHandler(enabled = open) { onDismiss() }
    AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.fadeIn(Motion.spring),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        modifier = Modifier.matchParentSize().zIndex(40f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .bounceClick(pressedScale = 1f, onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = Motion.sheetEnter,
        exit = Motion.sheetExit,
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(50f)
    ) {
        var drag by remember { mutableFloatStateOf(0f) }
        val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) }
        val shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
        Column(
            Modifier
                .offset { IntOffset(0, drag.roundToInt()) }
                .fillMaxWidth()
                .fillMaxHeight(maxHeightFraction)
                .background(c.card, shape)
                .border(1.dp, c.border, shape)
                .imePadding()
        ) {
            // 拖拽把手与标题区：下拉超过阈值即关闭（ColorOS 手势）
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (drag > 140f || velocity > 1800f) onDismiss()
                            drag = 0f
                        }
                    )
                    .padding(top = 8.dp)
            ) {
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
}

/** 透明点击拦截层（用于遮罩） */
@Composable
fun Scrim(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).bounceClick(pressedScale = 1f, onClick = onClick))
    }
}

@Suppress("unused")
private val unusedInteraction = MutableInteractionSource::class
@Suppress("unused")
private val unusedBrush = Brush::class
