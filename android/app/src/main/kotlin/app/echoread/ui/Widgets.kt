package app.echoread.ui

import app.echoread.core.i18n.S
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.echoread.ui.glass.glass
import app.echoread.ui.glass.glassLight
import app.echoread.ui.glass.glassShadow
import app.echoread.ui.glass.glassSurface
import app.echoread.ui.motion.liquidPress
import app.echoread.ui.motion.rememberGlassLight
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.Haptics
import app.echoread.ui.motion.MotionDriver
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.Thr
import app.echoread.ui.motion.driveVertically
import app.echoread.ui.motion.echoPress
import app.echoread.ui.motion.echoTap
import app.echoread.ui.motion.preemptable
import app.echoread.ui.motion.settleTarget
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 颜色不做弹簧：色彩空间上过冲没有意义，短 tween 更省（一屏 20 个 Chip 曾经是 60 条并发弹簧） */
private val colorSpec = tween<Color>(Dur.Short, easing = Ease.Standard)

/* ---------- 卡片 / 分组 ---------- */

/**
 * 分组卡片。0.5.0 起不再描边：层级改由「玻璃上的半透明填充」表达（见 [EchoColors.fill]），
 * 一屏里十几条 1dp 的边框线是 0.4.x 看起来「很碎」的主要来源。
 */
@Composable
fun EchoCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.lgPlus,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = echo
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(radius), c.surfaceGlass(color ?: c.fill))
            .padding(padding),
        content = content
    )
}

/** 小节标题：中性色、与组内文字左缘对齐 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = echo
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, color = c.text2, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        trailing?.invoke(this)
    }
}

/* ---------- 按钮 ---------- */

/**
 * 图标按钮。
 *
 * **绘制尺寸与触控尺寸是两件事**：[size] 只决定画多大（阅读器底栏为了密度用 36dp，
 * 更新卡片的关闭用 30dp），而指针命中区始终不小于 [touchSize]。
 * Material 的可访问性下限是 48dp —— 低于它的目标在真机上明显难点中，
 * 尤其是「忽略这一版更新」这种一旦点偏就要重新等下一次提示的操作。
 * 实现上把 `.size(size)` 的可视圆放在内层，外层单独撑到 48dp 并承载点击。
 */
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
    touchSize: Dp = 48.dp,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(maxOf(size, touchSize))
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
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(size).background(background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * 悬浮的玻璃按钮组：一枚胶囊里并排几个图标（只有一个时就是一个圆）。
 * 真玻璃 —— 采样 [backdrop]，底下的内容滚过时在它边缘被掰弯；每个图标各自有按压反馈，整枚胶囊共用一团触点光。
 */
@Composable
fun GlassIconGroup(
    backdrop: app.echoread.ui.glass.Backdrop?,
    modifier: Modifier = Modifier,
    style: app.echoread.ui.glass.GlassStyle = echo.clearGlass,
    shadow: Color = Color.Black.copy(alpha = if (echo.isDark) 0.20f else 0.10f),
    height: Dp = 44.dp,
    content: @Composable RowScope.() -> Unit
) {
    val light = rememberGlassLight()
    Row(
        modifier
            .height(height)
            .glassShadow(CircleShape, shadow)
            .glass(CircleShape, style, backdrop, light = light)
            .glassLight(light)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** [GlassIconGroup] 里的一个图标：绘制 40dp，触控区由胶囊的高度撑到 44dp */
@Composable
fun GlassIcon(icon: ImageVector, contentDescription: String?, tint: Color = echo.text, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.3f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .liquidPress(enabled = enabled, lift = 6.dp, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 主按钮：品牌渐变胶囊，上缘一道高光（与玻璃件同一套光照） */
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
    // 渐变两端就是 primary/tertiary，其上的内容色必须取 onPrimary。
    // 写死 Color.White 在深色模式下是 1.7:1（primary 是 T80 的浅紫），按钮文字几乎读不出来。
    val onBrush = echo.onAccent
    val light = rememberGlassLight()
    Row(
        modifier
            .height(height)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.5f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // 玻璃件的按压：鼓起来、被手指带偏一点、松手弹回，手指下有一团光（规则见 Liquid）
            .liquidPress(enabled = enabled, light = light, onClick = onClick)
            .background(brush, CircleShape)
            .glassSurface(CircleShape, echo.glossGlass, light)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = onBrush, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text, color = onBrush, fontSize = fontSize.sp, style = MaterialTheme.typography.labelLargeEmphasized,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 次级按钮：胶囊、柔和的填充底，不描边 */
@Composable
fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = echo.text,
    height: Dp = 44.dp,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = echo
    val light = rememberGlassLight()
    Row(
        modifier
            .height(height)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.4f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .liquidPress(enabled = enabled, light = light, onClick = onClick)
            .glassSurface(CircleShape, c.surfaceGlass(c.fillStrong), light)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** 行内文字按钮（「获取 API Key」「清空」这类次要动作） */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = echo.accent, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.echoPress(pressedScale = PressScale.Chip, pressedAlpha = 0.6f, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        if (icon != null) {
            Spacer(Modifier.width(3.dp))
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        }
    }
}

/** 筛选 / 选择标签（选中：强调色淡底 + 强调色文字） */
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
    val bg by animateColorAsState(if (selected) c.accentSoft else c.fillStrong, colorSpec, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.accent else c.text2, colorSpec, label = "chipFg")
    // 形状承担语义：**胶囊 = 动作，10dp = 筛选/选择，22dp 以上 = 容器**
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .echoPress(pressedScale = PressScale.Chip, onClick = onClick)
            .background(bg, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text, color = fg, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Text(trailing, color = trailingColor ?: fg.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** 小标签（「免费」「旗舰」这类一两个词的属性） */
@Composable
fun Badge(text: String, color: Color = echo.text2, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
        modifier = modifier.background(color.copy(alpha = 0.13f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/* ---------- 输入 ---------- */

/** 输入框文字样式：显式行高、去字体内边距、行内居中 —— 光标高度与基线在任何系统字体下都对齐 */
fun fieldTextStyle(color: Color, sizeSp: Int, mono: Boolean = false): TextStyle = TextStyle(
    color = color,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.45f).sp,
    fontFamily = if (mono) FontFamily.Monospace else null,
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
    /** 等宽字体：Key、地址、ID 这类逐字符核对的内容 */
    mono: Boolean = false,
    leadingIcon: ImageVector? = null,
    minLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = echo
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val ring by animateColorAsState(if (focused) c.accent else Color.Transparent, colorSpec, label = "fieldRing")
    val shape = RoundedCornerShape(14.dp)
    Column(modifier) {
        if (label != null) {
            Text(label, color = c.text2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.fillStrong, shape)
                // 只有聚焦时才有描边（1.5dp 强调色）：平时是一块安静的底，焦点一来位置立刻可辨
                .border(1.5.dp, ring, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = c.text3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(9.dp))
            }
            val fieldStyle = fieldTextStyle(c.text, 14, mono)
            val lineBox = if (singleLine) Modifier.height(24.dp) else Modifier.heightIn(min = (minLines * 21).dp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).then(lineBox).onFocusChanged { focused = it.isFocused },
                singleLine = singleLine,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
                visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().then(lineBox), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                        if (value.isEmpty()) Text(placeholder, style = fieldStyle.copy(color = c.text3), maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
            )
            if (password && value.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (revealed) S.providers.hide else S.providers.show,
                    color = c.accent, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.echoPress(pressedScale = PressScale.Chip, pressedAlpha = 0.6f) { revealed = !revealed }
                )
            }
            trailing?.invoke()
        }
    }
}

/** 搜索框：胶囊形，带放大镜与清除 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, background: Color? = null) {
    val c = echo
    Row(
        modifier
            .fillMaxWidth()
            .background(background ?: c.fillStrong, CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(EchoIcons.Search, null, tint = c.text3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        // 固定行高 + 去掉字体内边距：自定义系统字体（如手写体）下光标与文字基线才能对齐
        val fieldStyle = fieldTextStyle(c.text, 14)
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = fieldStyle, cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).height(24.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = fieldStyle.copy(color = c.text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            IconButtonEcho(EchoIcons.Close, S.common.clear, size = 22.dp, iconSize = 12.dp, touchSize = 28.dp, tint = c.text2, background = c.fillStrong) { onValueChange("") }
        }
    }
}

/** 细进度条（渐变）：进度值只在 draw lambda 里读，不再用 `fillMaxWidth(fraction)` 承载动画（那是每帧重测量） */
@Composable
fun GradientBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = echo.fillStrong) {
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

/* ---------- 底部面板（悬浮的玻璃板，整体由驱动器驱动，可拖拽关闭） ---------- */

/** 面板四角的圆角。面板四周留边悬浮，圆角与屏幕本身的圆角同心 */
val SheetShape = RoundedCornerShape(32.dp)

/** 面板距屏幕边缘的留白 */
private val SheetMargin = 8.dp

/**
 * 打开 / 关闭 / 拖拽是**同一条呈现值**，因此：
 * - 下拉一半松手不再「先跳回顶部再滑下去」（旧实现 `drag = 0f` 瞬时归零 + tween 出场）；
 * - 遮罩 alpha 由同一个 `driver.value` 派生（`drawBehind`），物理上不可能与面板脱节；
 * - 关闭动画中再点开，从当前位置连续回到展开位；
 * - 内容滚到顶再下拉，经 nestedScroll 交给面板。
 *
 * 0.5.0 的两处变化：
 * - **外形**：面板四周留 8dp 悬浮、四角同圆。它是实心的（玻璃留给悬浮件，理由见 [sheetSurface]），棱上一线反光；
 * - **面板内导航**：传入 [onBack] 时标题左侧出现返回箭头，系统返回键先退一页再关面板。
 *   以前「模型」「音色」各开一张新面板叠在设置面板上面，两层遮罩、两个抓手；现在是同一张面板里的几页。
 *
 * @param fillHeight 固定撑到 [maxHeightFraction]：带页面导航的面板各页高度不同，面板跟着跳会很晕
 */
@Composable
fun BoxScope.EchoSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    title: String,
    maxHeightFraction: Float = 0.82f,
    scrollable: Boolean = true,
    onBack: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
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
            driver.animateTo(1f, spec = app.echoread.ui.motion.Liquid.Sheet)
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
    val hostView = LocalView.current
    val settle: (Float) -> Unit = remember {
        { velocityPxPerSec: Float ->
            val unit = if (driver.unitPx > 0f) driver.unitPx else 1f
            val target = settleTarget(driver.value, velocityPxPerSec / unit, listOf(0f, 1f))
            scope.launch {
                preemptable { driver.animateTo(target, velocityPxPerSec, EchoMotion.Emphasized.float()) }
                // 只有手势结算走这里：吸附到位的瞬间给一次轻触感，程序化开关不震
                if (abs(driver.value - target) < 0.01f) Haptics.tick(hostView)
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
    BackHandler(enabled = open) { if (onBack != null) onBack() else onDismiss() }

    val scrim = c.scrim
    val marginPx = with(LocalDensity.current) { SheetMargin.toPx() }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction
    Box(
        Modifier
            .matchParentSize()
            .zIndex(40f)
            .drawBehind { drawRect(scrim, alpha = driver.value.coerceIn(0f, 1f)) }
            .echoTap(onClick = onDismiss)
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .zIndex(50f)
            // 键盘弹出时整块面板让到键盘上方（留白一并保留）
            .imePadding()
            .padding(start = SheetMargin, end = SheetMargin, bottom = SheetMargin)
            // 宽度封顶：折叠屏展开态与平板上，一张横贯整个窗口的面板既难读也不符合 M3 的容器观。
            // 640dp 是 BottomSheetDefaults.SheetMaxWidth 的取值。
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            // 「最多这么高」而不是「就是这么高」：内容少的面板不撑出一大片空白
            .then(if (fillHeight) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight))
            // 一个单位 = 面板高 + 下方留白：收起时整块滑出屏幕，不留一条边
            .onSizeChanged { if (it.height > 0) driver.unitPx = it.height + marginPx }
            .graphicsLayer {
                translationY = (1f - driver.value.coerceIn(-0.05f, 1.3f)) * (size.height + marginPx)
                shape = SheetShape
                clip = true
            }
            .nestedScroll(nested)
            .driveVertically(driver, enabled = { true }, bounds = { 0f..1f }, onSettle = settle)
            .glassSurface(SheetShape, c.sheetSurface)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 抓手不只是装饰：给它可点区与「关闭」语义，
            // 否则它看起来可交互、实际却只是一根画上去的线，读屏也完全读不到。
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(22.dp)
                    .width(72.dp)
                    .echoPress(pressedScale = PressScale.Chip, onClickLabel = S.common.close, onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.width(34.dp).height(4.dp).background(c.text3.copy(alpha = 0.45f), CircleShape))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else 20.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButtonEcho(EchoIcons.Back, S.reader.back, size = 34.dp, iconSize = 19.dp, tint = c.text, touchSize = 44.dp, onClick = onBack)
                    Spacer(Modifier.width(2.dp))
                }
                // 换页时标题交叉淡入，不跳
                AnimatedContent(
                    targetState = title,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(tween(Dur.Medium, easing = Ease.Linear)).togetherWith(fadeOut(tween(Dur.Micro, easing = Ease.Linear)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    label = "sheetTitle"
                ) { t ->
                    Text(
                        t, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
                actions?.invoke(this)
                IconButtonEcho(EchoIcons.Close, S.common.close, size = 30.dp, iconSize = 15.dp, tint = c.text2, background = c.fillStrong, touchSize = 44.dp, onClick = onDismiss)
            }
        }
        // 不用可保存的滚动状态：面板关掉再打开应当回到顶部，而不是停在上次滚到的地方
        val scroll = remember { androidx.compose.foundation.ScrollState(0) }
        Column(
            Modifier
                .weight(1f, fill = fillHeight)
                .fillMaxWidth()
                .let { if (scrollable) it.verticalScroll(scroll) else it }
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            content = content
        )
    }
}

/**
 * 面板内的页面切换：进更深一层时新页从右侧滑入，返回时反向。
 * 位移用与面板、根导航同一条弹簧；透明度是短的线性淡入（位移可被打断，淡入淡出不值得）。
 *
 * @param depthOf 页面的层级深度：决定这次切换算「前进」还是「返回」
 */
@Composable
fun <T> SheetPages(
    page: T,
    depthOf: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val slide = remember { EchoMotion.Emphasized.spec(androidx.compose.ui.unit.IntOffset(1, 1)) }
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val dir = if (depthOf(targetState) >= depthOf(initialState)) 1 else -1
            (slideInHorizontally(slide) { dir * it / 3 } + fadeIn(tween(Dur.Medium, delayMillis = 30, easing = Ease.Linear)))
                .togetherWith(slideOutHorizontally(slide) { -dir * it / 5 } + fadeOut(tween(Dur.Micro, easing = Ease.Linear)))
                .using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "sheetPages"
    ) { content(it) }
}
