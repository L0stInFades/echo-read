package app.echoread.ui.glass

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 液态玻璃 —— 自己实现，不依赖第三方库。
 *
 * 这里的「玻璃」是一枚**透镜**，不是毛玻璃：几乎不模糊（至多 4dp），整块同一个薄着色，后面的内容透过它清清楚楚，
 * 只在边缘那一圈斜面里被掰弯、压扁、甚至翻转（光学见 [Bezel]），棱上一线反光。
 * 0.5.x 以及 0.6.0 的前几版做的是「模糊 + 着色」，再后来是「近乎不透的中部 + 透明的宽边」——
 * 前者是毛玻璃，后者在真机上看是「一块板子垫在一块更大的玻璃上」，两层是分开的。都不是液态玻璃。
 *
 * 参考对象（BiliPai、FlClash）的玻璃都要 Android 13：低于它，它们直接退回不透明底色，
 * 而这款应用的主力测试机是 Android 11。所以这里按系统能力分三档：
 *
 * | 系统 | 背板怎么来 | 折射 |
 * |---|---|---|
 * | Android 13+ | 内容录进 GraphicsLayer，实时重放 | AGSL 着色器逐像素算，带色散 |
 * | Android 10 ~ 12L | 同上 | 用画布变换拼出同一条位移曲线（见 [refracted]），实时 |
 * | Android 8 ~ 9 | 把内容缩小成一张位图，约每半秒刷新一次 | 同上，但画面是一张柔化过的快照 |
 *
 * 中间一档是这台 Android 11 测试机上的画法，只用到图层本身的能力（重放、裁剪、缩放、平移），不需要任何 Android 12 的接口。
 * 最低一档沿用 0.5.x 的快照（那两个系统版本上没法验证「同一个图层画两次」时系统会不会自动刷新），所以那里仍是磨砂的。
 */

internal enum class GlassTier { SHADER, REPLAY, SNAPSHOT }

/** 这台设备画得出清晰的透镜（Android 10 起）。画不出时，会褪成透镜的控件应当保持实心 */
val lensAvailable: Boolean get() = glassTier != GlassTier.SNAPSHOT


/**
 * 这台设备用哪一档。两个调试开关（`adb shell setprop log.tag.EchoGlassReplay DEBUG` / `EchoGlassSnap`）
 * 可以在新系统上强制走低档，方便在模拟器上核对三档的观感。
 */
internal val glassTier: GlassTier by lazy {
    val sdk = Build.VERSION.SDK_INT
    when {
        android.util.Log.isLoggable("EchoGlassSnap", android.util.Log.DEBUG) -> GlassTier.SNAPSHOT
        sdk >= 33 && !android.util.Log.isLoggable("EchoGlassReplay", android.util.Log.DEBUG) -> GlassTier.SHADER
        sdk >= 29 -> GlassTier.REPLAY
        else -> GlassTier.SNAPSHOT
    }
}

/** 一块被玻璃采样的背板：由 [backdropSource] 喂内容，由 [glass] 取用 */
@Stable
class Backdrop internal constructor() {
    internal var layer: GraphicsLayer? = null
    internal var density: Density = Density(1f)

    /** 背板内容的尺寸与它在根坐标系里的原点 */
    internal var size: IntSize = IntSize.Zero
    internal var origin: Offset = Offset.Zero

    /** 正在取用这块背板的玻璃数：没有玻璃可见时背板不录制，零额外开销 */
    internal var users by mutableIntStateOf(0)

    /** 录制过至少一次：玻璃要等它才有东西可采样（快照状态，翻转时取用方会重画） */
    internal var recorded by mutableStateOf(false)

    /** 背板自带不透明的底色（整屏的页面）还是透明底（控件内部只录了一条轨道）。透明底时折射的每一片不能互相覆盖 */
    internal var opaque = true

    /** 低档：缩小 + 模糊后的快照，以及缩小倍数 */
    internal var frost by mutableStateOf<ImageBitmap?>(null)
    internal var frostScale = 1f
}

/** 浮层从这里取背板；为 null（或用户关掉了玻璃）时退回不透明底色 */
val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * @param snapshots 最低一档（Android 8 ~ 9）要不要为这块背板拍快照。只给控件内部的小透镜
 *   （分段控件的滑块）采样的背板传 false：那一档上它反正画不了清晰的折射，不必为它每半秒回读一次。
 */
@Composable
fun rememberBackdrop(snapshots: Boolean = true): Backdrop {
    val backdrop = remember { Backdrop() }
    backdrop.layer = rememberGraphicsLayer()
    backdrop.density = LocalDensity.current
    if (snapshots && glassTier == GlassTier.SNAPSHOT) {
        val small = rememberGraphicsLayer()
        LaunchedEffect(backdrop, small) {
            snapshotFlow { backdrop.users > 0 }.collectLatest { active ->
                if (!active) {
                    backdrop.frost = null
                    return@collectLatest
                }
                // 第一张尽快：等背板录完这一帧就拍；之后低频刷新，跟上朗读高亮与自动翻页
                while (true) {
                    if (!backdrop.recorded) {
                        awaitFrame()
                        continue
                    }
                    runCatching { snapshot(backdrop, small) }
                    delay(SNAPSHOT_INTERVAL_MS)
                }
            }
        }
    }
    return backdrop
}

private const val SNAPSHOT_INTERVAL_MS = 480L

/** 快照的目标宽度（像素）。再小模糊后会出色带，再大只是白花 CPU：模糊之后本来就没有细节 */
private const val SNAPSHOT_WIDTH = 150

/** 低档的模糊强度（dp，按屏幕尺度）：一张快照供所有玻璃共用，取面板用的那一档 */
private val SNAPSHOT_BLUR = 26.dp

private suspend fun snapshot(backdrop: Backdrop, small: GraphicsLayer) {
    val src = backdrop.layer ?: return
    val size = backdrop.size
    if (size.width <= 0 || size.height <= 0) return
    val k = ceil(size.width / SNAPSHOT_WIDTH.toFloat()).coerceAtLeast(1f)
    val w = ceil(size.width / k).toInt().coerceAtLeast(1)
    val h = ceil(size.height / k).toInt().coerceAtLeast(1)
    small.record(backdrop.density, LayoutDirection.Ltr, IntSize(w, h)) {
        scale(1f / k, 1f / k, pivot = Offset.Zero) { drawLayer(src) }
    }
    // 硬件位图 → 软件位图 → 像素数组：缩小之后只有几万个像素，回读与模糊都在毫秒级
    val hardware = small.toImageBitmap().asAndroidBitmap()
    val radius = (with(backdrop.density) { SNAPSHOT_BLUR.toPx() } / k / 2.4f).roundToInt().coerceAtLeast(1)
    val out = withContext(Dispatchers.Default) {
        val soft = hardware.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
        val px = IntArray(soft.width * soft.height)
        soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        boxBlur(px, soft.width, soft.height, radius)
        soft.setPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        if (android.util.Log.isLoggable("EchoGlass", android.util.Log.DEBUG)) {
            val opaque = px.count { (it ushr 24) > 8 }
            android.util.Log.d("EchoGlass", "snapshot ${soft.width}x${soft.height} k=$k r=$radius opaque=$opaque/${px.size} mid=${Integer.toHexString(px[px.size / 2])} hw=${hardware.config}")
        }
        soft
    } ?: return
    backdrop.frostScale = k
    backdrop.frost = out.asImageBitmap()
}

/** 三遍盒式模糊 ≈ 高斯模糊。就地修改 [px]（ARGB，逐通道累加，边缘取最近像素） */
internal fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3) {
    if (radius < 1 || w < 2 || h < 2) return
    val tmp = IntArray(px.size)
    repeat(passes) {
        blurLine(px, tmp, w, h, radius, horizontal = true)
        blurLine(tmp, px, w, h, radius, horizontal = false)
    }
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        var a = 0
        var red = 0
        var g = 0
        var b = 0
        for (i in -r..r) {
            val p = src[base + i.coerceIn(0, len - 1) * step]
            a += p ushr 24
            red += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until len) {
            dst[base + i * step] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
            val pOut = src[base + (i - r).coerceIn(0, len - 1) * step]
            val pIn = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
            a += (pIn ushr 24) - (pOut ushr 24)
            red += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
            g += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
            b += (pIn and 0xFF) - (pOut and 0xFF)
        }
    }
}

/**
 * 把这棵子树的画面交给 [backdrop]。必须是玻璃的**兄弟**而不是祖先：
 * 玻璃画的是这份录像，录像里若又包含玻璃，就是一个自己画自己的环。
 *
 * @param fill 这块画面的不透明底色，由本修饰符负责画（代替调用方的 `.background()`）。
 *   录像必须自带底色：页面底色若画在更外层，录下来的就是「透明底上的几行字」，
 *   模糊之后几乎全透明，玻璃后面看到的仍是没有模糊的原画面（真机上实际出现过）。
 */
fun Modifier.backdropSource(backdrop: Backdrop?, fill: Color, visible: Boolean = true): Modifier {
    // visible = false：只录不画。给「透镜里要看到的东西与屏幕上画的不一样」的场合（开关的滑块里是一条缩小的轨道）
    if (!visible) {
        if (backdrop == null) return this.drawWithContent { }
        backdrop.opaque = false
        return this
            .onGloballyPositioned {
                backdrop.origin = it.positionInRoot()
                backdrop.size = it.size
            }
            .drawWithContent {
                val layer = backdrop.layer
                if (layer == null || backdrop.users == 0) {
                    if (backdrop.recorded) backdrop.recorded = false
                    return@drawWithContent
                }
                layer.record(IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                    this@drawWithContent.drawContent()
                }
                if (!backdrop.recorded) backdrop.recorded = true
            }
    }
    if (backdrop == null) return this.drawBehind { drawRect(fill) }
    backdrop.opaque = fill.alpha >= 0.999f
    return this
        .onGloballyPositioned {
            backdrop.origin = it.positionInRoot()
            backdrop.size = it.size
        }
        .drawWithContent {
            val layer = backdrop.layer
            // 读 users（快照状态）：第一块玻璃出现时这里会被重新调用，开始录制
            if (layer == null || backdrop.users == 0) {
                if (backdrop.recorded) backdrop.recorded = false
                drawRect(fill)
                drawContent()
                return@drawWithContent
            }
            // 录一次、画一次：录像里引用的是子节点各自的 RenderNode，之后子节点自己重绘
            // （朗读高亮、列表滚动）不需要再走到这里，重放录像时拿到的就是最新画面
            // 注意用的是 DrawScope 上的 record 扩展：它把当前画布临时换成图层的画布，
            // 外层的 drawContent() 才会画进图层里（带 density 参数的那个重载不会换画布）
            layer.record(IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                drawRect(fill)
                this@drawWithContent.drawContent()
            }
            drawLayer(layer)
            if (!backdrop.recorded) backdrop.recorded = true
        }
}

/** 玻璃材质的参数。取值见 Theme.kt 里按明暗给出的几套 */
@Immutable
data class GlassStyle(
    /**
     * 整块玻璃的着色，从边到心同一个浓度。小透镜很薄（一成多），带一行字的药丸五成上下，
     * 放大段文字的面板九成 —— 浓淡只表示「这块玻璃有多厚的颜色」，不分区：分了区看上去就是两层。
     */
    val tint: Color,
    /** 没有背板可采样（或用户关掉了玻璃）时的不透明底色 */
    val fallback: Color,
    /**
     * 模糊。默认不模糊（纯透镜）。参考实现的悬浮条用 4dp —— 只是把后面小字的锐边抹掉一点，画面仍然辨得清，远不到毛玻璃。
     * Android 8 ~ 9 的快照永远是柔化过的，不认这个值。
     */
    val blur: Dp = 0.dp,
    /** 饱和度。玻璃后面的颜色比原画面艳一点（参考实现取 1.5）；不模糊的纯透镜在 Android 10 ~ 12L 上不认这个值 */
    val saturation: Float = 1f,
    /** 高光描边：沿对角线从亮到暗再回亮，模拟来自左上方的光 */
    val rimHi: Color = Color.Transparent,
    val rimLo: Color = Color.Transparent,
    val rimWidth: Dp = 1.dp,
    /** 录制背板时缩小几倍再模糊（只影响 Android 13+ 的 GPU 开销）：大面板 2~3，小药丸 1 */
    val inputScale: Int = 2,
    /**
     * 边缘折射。玻璃的边缘是一圈弧形的斜面（bezel）：[lensDepth] 是斜面的宽度，[lensAmount] 是玻璃的**厚度**。
     * 光线垂直射入，在斜面上按斯涅尔定律折一次（折射率 1.5），穿过玻璃落到背后的画面上时已经横向偏了一段 ——
     * 那一段就是这个像素的位移，方向垂直于边缘、指向玻璃内侧（见 [Bezel]）。
     * 位移集中在最外面三成：边缘处约为厚度的 0.45 倍，往里很快衰减到 0；厚度大于斜面宽时，边缘会出现翻转的镜像。
     * 0 = 不做折射。
     */
    val lensDepth: Dp = 0.dp,
    val lensAmount: Dp = 0.dp,
    /** 色散（Android 13+）：红蓝两个通道的折射量各偏这么多，边缘出现一线彩边。0 = 没有 */
    val dispersion: Float = 0f,
    /** 顶部的一抹反光（白色的透明度；0 = 没有） */
    val sheen: Float = 0f,
    /**
     * 压在高光外侧的一条暗发丝线。浅色界面上白色的高光落在白底上等于没有，
     * 玻璃的轮廓要靠这条线（和投影）来交代；深色界面上它把高光衬得更亮。
     */
    val edge: Color = Color.Transparent,
    /** 边缘向内晕开的一圈暗影（悬起的透镜才有：它让玻璃看起来有厚度、鼓起来）。黑色的透明度 */
    val innerShade: Float = 0f
)

/**
 * 手指下的那团光：按住玻璃时，光从触点晕开、跟着手指走，松手后散掉。
 * 由 [glassLight] 喂位置，由 [glass] / [glassSurface] 画。
 */
@Stable
class GlassLight {
    internal var at by mutableStateOf(Offset.Unspecified)
    internal val power = Animatable(0f)
}

/** 追踪手指位置给 [GlassLight]。只观察、不消费事件：点击与拖拽照常交给别的修饰符 */
fun Modifier.glassLight(light: GlassLight?): Modifier = if (light == null) this else pointerInput(light) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            light.at = down.position
            launch { light.power.animateTo(1f, tween(110, easing = LinearEasing)) }
            do {
                val event = awaitPointerEvent()
                event.changes.firstOrNull { it.id == down.id }?.let { light.at = it.position }
            } while (event.changes.any { it.pressed })
            launch { light.power.animateTo(0f, tween(420, easing = LinearEasing)) }
        }
    }
}

/**
 * 画一块玻璃作为背景。
 *
 * 它仍是 composed（不像 [glassSurface] 那样是绘制节点）：它持有随组合释放的离屏图层、要给背板登记取用者、
 * 显影动画由组合期的状态驱动。搬成节点会改动这几样的创建、释放与开始的时机，而玻璃的观感是在真机上逐帧调出来的。
 *
 * @param offset 这块玻璃被 `graphicsLayer` 之类**绘制期变换**挪了多少（面板的滑入位移）。
 *   布局位置由本修饰符自己量；绘制期的位移布局不知道，必须由调用方告知，背板才能与底下的真实画面对齐。
 */
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle,
    backdrop: Backdrop?,
    light: GlassLight? = null,
    /**
     * 0..1 的「透镜力度」，在绘制期读：折射量、高光与内侧暗影都乘上它。
     * 给会「悬起来」的滑块用 —— 静止时它是一块实心的色块（力度 0，什么都不画），被手指按住或移动途中才变成一枚透镜。
     */
    power: (() -> Float)? = null,
    offset: (() -> Offset)? = null
): Modifier = composed {
    val blurLayer = if (backdrop != null && glassTier == GlassTier.SHADER) rememberGraphicsLayer() else null
    // 中档：玻璃自己那一小块背板的离屏副本。折射要把同一块画面重复画上百次，重放整棵绘制树太贵，贴一张小纹理几乎不要钱
    val lensLayer = if (backdrop != null && glassTier == GlassTier.REPLAY) rememberGraphicsLayer() else null
    val lensCache = remember { LensCache() }
    val geo = remember { GlassGeometry() }
    var position by remember { mutableStateOf(Offset.Zero) }
    if (backdrop != null) {
        DisposableEffect(backdrop) {
            backdrop.users++
            onDispose { backdrop.users-- }
        }
    }
    val saturate = remember(style.saturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(style.saturation) })
    }
    // 背板要晚一两帧才到（最低一档要等快照回读）：先画不透明底色，到了再淡成玻璃，而不是「啪」地一下变透明
    val available = backdrop != null && backdrop.recorded && (glassTier != GlassTier.SNAPSHOT || backdrop.frost != null)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(available) {
        if (available) reveal.animateTo(1f, tween(200, easing = LinearEasing)) else reveal.snapTo(0f)
    }
    this
        .then(if (backdrop != null) Modifier.onGloballyPositioned { position = it.positionInRoot() } else Modifier)
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = geo.outline.of(outline)
            val rr = roundRectOf(outline)
            val src = backdrop?.layer
            val t = reveal.value
            val pw = power?.invoke()?.coerceIn(0f, 1f) ?: 1f
            if (power != null && pw < 0.004f) {
                drawContent()
                return@drawWithContent
            }
            val through = t > 0.001f && backdrop != null && src != null && backdrop.recorded
            // 还没完全显出来之前，底下垫着不透明底色：任何时刻都不会出现一块「透明的面板」
            if ((!through || t < 0.999f) && power == null) drawPath(path, style.fallback)
            if (through) {
                val shift = offset?.invoke() ?: Offset.Zero
                val at = position - backdrop!!.origin + shift
                clipPath(path) {
                    when (glassTier) {
                        GlassTier.SHADER -> if (blurLayer != null && Build.VERSION.SDK_INT >= 33) {
                            blurLayer.alpha = t
                            drawLens(blurLayer, src!!, at, style, outline, lensCache, pw, backdrop.opaque)
                        }
                        GlassTier.REPLAY -> {
                            // 清晰的画面从哪里来：
                            // - 小件（药丸、按钮、滑块）：先把自己正下方那一小块录进离屏图层。折射要把这一块画几十次，贴小纹理最省；
                            // - 大面板：直接重放背板。斜面被切成的每一段都很窄，段外的绘制指令在提交前就被剔掉了，
                            //   用不着为它再开一张整屏大小的纹理（面板滑动时还得每帧重画）
                            val small = lensLayer != null && size.width * size.height < 0.3f * backdrop.size.width * backdrop.size.height
                            // 轻微的柔化（参考实现的 4dp）不需要模糊滤镜：把那一小块缩小着录下来，再放大贴回去 ——
                            // 缩小时每个像素是一小片画面的平均，放大时是双线性插值，合起来相当于几个像素的高斯核
                            val k = if (small) geo.recordLens(lensLayer!!, src!!, at, size, style, this) else 1f
                            val pane: DrawScope.() -> Unit = when {
                                !small -> { { translate(-at.x, -at.y) { drawLayer(src!!) } } }
                                k > 1f -> { { scale(k, k, pivot = Offset.Zero) { drawLayer(lensLayer!!) } } }
                                else -> { { drawLayer(lensLayer!!) } }
                            }
                            // 大面板不做折射：没有着色器时，折射意味着把整屏内容重放几十遍 —— 在 Adreno 610 上实测面板滑动掉到 15 帧。
                            // 面板的颜色有九成浓，那一圈折射本来也只透得出一成，换不来这个代价
                            if (small) refracted(rr, style, geo, pw, backdrop.opaque, pane) else pane()
                            if (t < 0.999f && power == null) drawRect(style.fallback, alpha = 1f - t)
                        }
                        GlassTier.SNAPSHOT -> {
                            val img = backdrop.frost
                            if (img != null) {
                                val k = backdrop.frostScale
                                val frost: DrawScope.() -> Unit = {
                                    translate(-at.x, -at.y) {
                                        scale(k, k, pivot = Offset.Zero) {
                                            drawImage(img, dstOffset = IntOffset.Zero, filterQuality = FilterQuality.Medium, colorFilter = saturate)
                                        }
                                    }
                                }
                                refracted(rr, style, geo, pw, true, frost)
                                if (t < 0.999f && power == null) drawRect(style.fallback, alpha = 1f - t)
                            }
                        }
                    }
                }
                if (style.tint.alpha > 0f) drawPath(path, style.tint, alpha = t)
            }
            drawGloss(path, style, geo, light, if (through) t else 0f)
            drawInnerShade(path, style, pw)
            drawContent()
            drawRim(path, style, geo, pw)
        }
}

/**
 * 不采样背板的玻璃：只有着色、反光与描边。用在**已经位于玻璃或平整底色之上**的控件
 * （面板里的卡片、按钮、书架上的卡片）—— 那里背后没有可折射的内容，再采样一次只是白花 GPU。
 *
 * 它只画、不持有图层也不起协程，所以是一个绘制节点（几何缓存跟着节点走），不经过组合：
 * 宿主重组时参数没变就不重画。
 */
fun Modifier.glassSurface(shape: Shape, style: GlassStyle, light: GlassLight? = null): Modifier =
    this then GlassSurfaceElement(shape, style, light)

private data class GlassSurfaceElement(val shape: Shape, val style: GlassStyle, val light: GlassLight?) :
    ModifierNodeElement<GlassSurfaceNode>() {
    override fun create() = GlassSurfaceNode(shape, style, light)
    override fun update(node: GlassSurfaceNode) {
        node.shape = shape
        node.style = style
        node.light = light
    }
}

private class GlassSurfaceNode(var shape: Shape, var style: GlassStyle, var light: GlassLight?) : Modifier.Node(), DrawModifierNode {
    private val geo = GlassGeometry()

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = geo.outline.of(outline)
        if (style.tint.alpha > 0f) drawPath(path, style.tint)
        drawGloss(path, style, geo, light, 1f)
        drawContent()
        drawRim(path, style, geo, 1f)
    }
}

/**
 * 玻璃边缘的光学。原理来自 kube.io 的《Liquid Glass in the Browser》：
 *
 * - 斜面的剖面是一条「超椭圆」弧：`h(x) = (1 - (1 - x)^4)^(1/4)`，x 从 0（最外沿）到 1（斜面结束、表面变平）。
 *   比圆弧更饱满，往平面的过渡更柔 —— 苹果偏爱的就是这种形状；
 * - 光线垂直于画面射入，在斜面上只折射一次：`sin θ2 = sin θ1 / n`，n = 1.5（玻璃），θ1 是入射角，即斜面在该处的倾角；
 * - 折射后的光线偏离竖直方向 (θ1 - θ2)，再往下走过玻璃在该处的厚度，落点横向偏了 `厚度 · h(x) · tan(θ1 - θ2)`；
 * - 凸的斜面把光线折向玻璃**内侧**，所以每个像素看到的是更靠里的画面，位移方向垂直于边缘。
 *
 * 这条曲线的特点：位移集中在斜面最外面三成（边缘处约 0.45 倍厚度），往里很快掉到 0 ——
 * 中间的画面一丝不动，只有贴边那一圈被狠狠地压扁、甚至翻转。这正是「一块厚玻璃」而不是「一层毛玻璃」的样子。
 */
internal object Bezel {
    const val REFRACTIVE_INDEX = 1.5f

    /** 斜面上 [x]（0 外沿 → 1 内端）处的横向位移（像素）。[width] 斜面宽、[thickness] 玻璃厚，同为像素 */
    fun displacement(x: Float, width: Float, thickness: Float): Float {
        if (width <= 0f || thickness <= 0f) return 0f
        val k = 1f - x.coerceIn(0f, 1f)
        val k4 = k * k * k * k
        val base = (1f - k4).coerceAtLeast(1e-5f)
        val height = Math.pow(base.toDouble(), 0.25).toFloat()
        val slope = thickness / width * k * k * k * Math.pow(base.toDouble(), -0.75).toFloat()
        val incident = kotlin.math.atan(slope)
        val refracted = kotlin.math.asin(kotlin.math.sin(incident) / REFRACTIVE_INDEX)
        return thickness * height * kotlin.math.tan(incident - refracted)
    }
}

/** 直边最多分几段（每段内位移线性变化，段与段之间连续）；圆角最多切几片（每片一个位移） */
private const val MAX_SEGMENTS = 6
private const val MAX_SLICES = 8
private const val SLICE_STEP_PX = 1.5f
private const val LUT = 96

/** 内缩路径的缓存槽：每片两条（外沿、内沿），最后一个留给玻璃中部 */
private const val INTERIOR_SLOT = MAX_SLICES * 2

/**
 * 没有着色器的系统上，把 [Bezel] 的位移曲线化成画布能做的变换（见 [refracted]）。两套划分：
 *
 * - **直边**：位移只沿一个方向，一段之内让它线性变化 —— 那正好是一次「缩放 + 平移」，段与段在交界处严丝合缝，
 *   没有任何台阶。所以直边只需要很少几段（[MAX_SEGMENTS]）就能贴住整条曲线；
 * - **圆角**：位移沿半径方向，画布只能做「以圆心为轴的缩放」，一片之内的位移只能取一个值，片与片之间有小台阶，
 *   因此切得细一些（[MAX_SLICES]）。圆角面积小、画面又被压得很扁，台阶并不显眼。
 *
 * 直边按「位移每下降同样多切一刀」来分：位移变化剧烈的外沿分得密，几乎不动的内侧一段带过；圆角按宽度均分。
 * 段数要省，是因为每多一段就多几次绘制调用 —— 在 Adreno 610 上实测，按像素逐片平移（每块玻璃两百多次绘制）
 * 会把书架滚动拖到每帧 35ms；现在每块玻璃四五十次。
 */
private class LensSlices {
    /** 直边：分界处的内缩量与位移（各 segments + 1 个） */
    var segments = 0
        private set
    val edgeAt = FloatArray(MAX_SEGMENTS + 1)
    val edgeShift = FloatArray(MAX_SEGMENTS + 1)

    /** 圆角：每片的内缩范围与位移 */
    var count = 0
        private set
    val inset = FloatArray(MAX_SLICES + 1)
    val shift = FloatArray(MAX_SLICES)

    private var depthKey = -1f
    private var thicknessKey = -1f
    private val envelope = FloatArray(LUT)

    fun update(depth: Float, thickness: Float) {
        if (depth == depthKey && thickness == thicknessKey) return
        depthKey = depth
        thicknessKey = thickness
        // 从里往外取包络：曲线在最外沿不到 3% 的地方有一个回落到 0 的尖角（那里玻璃厚度为 0），这里当它是平的
        var peak = 0f
        for (i in LUT - 1 downTo 0) {
            peak = maxOf(peak, Bezel.displacement(i / (LUT - 1f), depth, thickness))
            envelope[i] = peak
        }
        segments = cut(depth, peak, ceil(peak / 6f).toInt().coerceIn(3, MAX_SEGMENTS), edgeAt, minWidth = 0.75f)
        for (j in 0..segments) edgeShift[j] = if (j == segments) 0f else sample(edgeAt[j] / depth)
        // 圆角按宽度均分：每片的缩放倍数取决于「位移 / 到圆心的距离」，越靠里圆心越近，等位移地分反而会在内侧留下一大片台阶
        count = ceil(depth / SLICE_STEP_PX).toInt().coerceIn(2, MAX_SLICES)
        for (j in 0..count) inset[j] = depth * j / count
        for (j in 0 until count) shift[j] = sample((inset[j] + inset[j + 1]) / 2f / depth)
    }

    /** 按位移等差地下刀，写进 [out]，返回分成了几份。挨得太近（不足 [minWidth] 像素）的刀并掉 */
    private fun cut(depth: Float, peak: Float, wanted: Int, out: FloatArray, minWidth: Float): Int {
        var n = 0
        var index = 0
        out[0] = 0f
        for (j in 1 until wanted) {
            val target = peak * (1f - j.toFloat() / wanted)
            while (index < LUT - 1 && envelope[index] > target) index++
            val at = depth * index / (LUT - 1f)
            if (at - out[n] < minWidth || at >= depth - minWidth) continue
            n++
            out[n] = at
        }
        n++
        out[n] = depth
        return n
    }

    private fun sample(x: Float): Float {
        val f = (x * (LUT - 1)).coerceIn(0f, LUT - 1f)
        val i = f.toInt().coerceAtMost(LUT - 2)
        return envelope[i] + (envelope[i + 1] - envelope[i]) * (f - i)
    }
}

/**
 * 不用着色器的边缘折射（Android 12L 及以下）。着色器版逐像素沿法线取样；这里用画布变换拼出同一条位移曲线：
 *
 * - 直边分成几段，每段是一次「缩放 + 平移」（缩放可以为负：位移降得比位置升得快的地方，画面是翻转的 ——
 *   厚玻璃边上那一圈倒像就是这么来的）；
 * - 圆角切成一圈圈薄片，每片是以角的圆心为轴的一次缩放。
 *
 * [pane] 把背板画到玻璃自己的坐标系里（不带任何位移）；斜面里的每一段、每一片都是它套上一个变换再画一次。
 */
private fun DrawScope.refracted(rr: RoundRect?, style: GlassStyle, geo: GlassGeometry, power: Float, opaque: Boolean, pane: DrawScope.() -> Unit) {
    val w = size.width
    val h = size.height
    val r = if (rr == null) 0f else rr.topLeftCornerRadius.x.coerceAtMost(size.minDimension / 2f)
    // 斜面不超过圆角半径：再宽的话角上两条边的斜面会交叠
    val depth = minOf(style.lensDepth.toPx(), size.minDimension / 2f, if (r > 0.5f) r else Float.MAX_VALUE)
    val thickness = style.lensAmount.toPx() * power
    if (rr == null || depth < 1f || thickness < 0.5f) {
        pane()
        return
    }
    val s = geo.slices
    s.update(depth, thickness)
    val straightW = w - 2f * r
    val straightH = h - 2f * r

    // 直边：逐段线性
    for (j in 0 until s.segments) {
        val d0 = s.edgeAt[j]
        val d1 = s.edgeAt[j + 1]
        val v0 = s.edgeShift[j]
        // 这一段里「背板走过的距离 / 屏幕上走过的距离」；为负即翻转
        var m = 1f + (s.edgeShift[j + 1] - v0) / (d1 - d0)
        if (abs(m) < 0.04f) m = if (m < 0f) -0.04f else 0.04f
        val k = 1f / m
        if (straightW > 0.5f) {
            clipRect(r, d0, w - r, d1) {
                translate(0f, d0) { scale(1f, k, pivot = Offset.Zero) { translate(0f, -(d0 + v0)) { pane() } } }
            }
            clipRect(r, h - d1, w - r, h - d0) {
                translate(0f, h - d0) { scale(1f, k, pivot = Offset.Zero) { translate(0f, -(h - d0 - v0)) { pane() } } }
            }
        }
        if (straightH > 0.5f) {
            clipRect(d0, r, d1, h - r) {
                translate(d0, 0f) { scale(k, 1f, pivot = Offset.Zero) { translate(-(d0 + v0), 0f) { pane() } } }
            }
            clipRect(w - d1, r, w - d0, h - r) {
                translate(w - d0, 0f) { scale(k, 1f, pivot = Offset.Zero) { translate(-(w - d0 - v0), 0f) { pane() } } }
            }
        }
    }

    // 圆角：逐片缩放。胶囊两端的上下两个角共用一个圆心，合成一次画；正圆四个角合成一次
    if (r > 0.5f) {
        val capsuleX = straightH <= 0.5f
        val capsuleY = straightW <= 0.5f
        for (j in 0 until s.count) {
            val d0 = s.inset[j]
            // 这一片中线到圆心的距离，以及它该去取的那一圈的半径（可以为负：越过圆心，画面翻转）
            val rho = (r - (d0 + s.inset[j + 1]) / 2f).coerceAtLeast(0.5f)
            var from = rho - s.shift[j]
            if (abs(from) < rho * 0.05f) from = if (from < 0f) -rho * 0.05f else rho * 0.05f
            val k = rho / from
            val corners: DrawScope.() -> Unit = {
                when {
                    capsuleX && capsuleY -> scale(k, k, pivot = Offset(w / 2f, h / 2f)) { pane() }
                    capsuleX -> {
                        clipRect(0f, 0f, r, h) { scale(k, k, pivot = Offset(r, h / 2f)) { pane() } }
                        clipRect(w - r, 0f, w, h) { scale(k, k, pivot = Offset(w - r, h / 2f)) { pane() } }
                    }
                    capsuleY -> {
                        clipRect(0f, 0f, w, r) { scale(k, k, pivot = Offset(w / 2f, r)) { pane() } }
                        clipRect(0f, h - r, w, h) { scale(k, k, pivot = Offset(w / 2f, h - r)) { pane() } }
                    }
                    else -> {
                        clipRect(0f, 0f, r, r) { scale(k, k, pivot = Offset(r, r)) { pane() } }
                        clipRect(w - r, 0f, w, r) { scale(k, k, pivot = Offset(w - r, r)) { pane() } }
                        clipRect(w - r, h - r, w, h) { scale(k, k, pivot = Offset(w - r, h - r)) { pane() } }
                        clipRect(0f, h - r, r, h) { scale(k, k, pivot = Offset(r, h - r)) { pane() } }
                    }
                }
            }
            // 外沿往外多留半个像素：相邻两片的抗锯齿边叠在一起正好补满，圈与圈之间不留细缝
            clipPath(geo.inset(2 * j, rr, (d0 - 0.5f).coerceAtLeast(0f))) {
                if (opaque) {
                    // 背板不透明：由外到内画，后画的自然盖住先画的内侧
                    corners()
                } else {
                    // 背板是透明底的（滑块透镜下面只有一条轨道）：透明的地方盖不住前一片，每片只能画在自己那一圈里
                    clipPath(geo.inset(2 * j + 1, rr, s.inset[j + 1]), ClipOp.Difference) { corners() }
                }
            }
        }
    }
    // 斜面以内：没有位移的玻璃中部
    clipPath(geo.inset(INTERIOR_SLOT, rr, depth)) { pane() }
}

/** 玻璃表面的光：顶部的反光带，与手指下的那团光 */
private fun DrawScope.drawGloss(path: Path, style: GlassStyle, geo: GlassGeometry, light: GlassLight?, alpha: Float) {
    val lit = light != null && light.power.value > 0.01f && light.at.isSpecified
    if (style.sheen <= 0f && !lit) return
    clipPath(path) {
        if (style.sheen > 0f && alpha > 0.01f) {
            val h = minOf(size.height * 0.62f, 56.dp.toPx())
            drawRect(geo.sheen(h, style.sheen), size = Size(size.width, h), alpha = alpha)
        }
        if (lit) {
            // 手指下的光（数值取自参考实现）：整块先亮一点，触点周围再叠一团，半径是短边的 1.5 倍，加色混合
            val p = light!!.power.value
            val r = maxOf(size.minDimension * 1.5f, 40.dp.toPx())
            val c = light.at
            drawRect(Color.White, alpha = 0.08f * p, blendMode = BlendMode.Plus)
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.16f * p),
                    0.5f to Color.White.copy(alpha = 0.16f * p),
                    1f to Color.Transparent,
                    center = c, radius = r
                ),
                radius = r, center = c, blendMode = BlendMode.Plus
            )
        }
    }
}

/** 悬起的透镜内侧的一圈暗影：三道由宽到窄的描边叠出柔和的衰减 */
private fun DrawScope.drawInnerShade(path: Path, style: GlassStyle, power: Float) {
    val a = style.innerShade * power
    if (a <= 0.004f) return
    // 窄窄的一圈：宽了就成了画在里面的第二道边
    val w = 1.25.dp.toPx()
    clipPath(path) {
        drawPath(path, Color.Black, alpha = a * 0.34f, style = Stroke(w * 4f))
        drawPath(path, Color.Black, alpha = a * 0.33f, style = Stroke(w * 2f))
        drawPath(path, Color.Black, alpha = a * 0.33f, style = Stroke(w))
    }
}

/**
 * 棱上的高光。一块真的玻璃没有「描边」—— 它的轮廓是折射自己画出来的，棱上只有一线反光：
 * 左上角最亮，沿两条邻边暗下去，到右上、左下两个角几乎没有，右下角再亮回来一些
 * （光从左上来，在对角的棱上又折出来一次；参考实现里是两盏方向光，各按法线与光向夹角的平方取强度）。
 *
 * 0.6.0 的第一版在这里叠了三层（暗线、亮线、往里晕开的一圈光），再加上斜面上的着色渐变，
 * 边上看起来是一圈糊的白框，像毛玻璃的包边。现在只留一条不到 1dp 的亮线，加色混合；
 * 浅色界面上另压一条极淡的暗发丝线，否则白底上的白玻璃没有轮廓。
 */
private fun DrawScope.drawRim(path: Path, style: GlassStyle, geo: GlassGeometry, power: Float) {
    if (style.rimWidth.value <= 0f) return
    val w = style.rimWidth.toPx()
    if (style.edge.alpha > 0f) drawPath(path, style.edge, style = Stroke(maxOf(1f, w * 0.5f)), alpha = power)
    if (style.rimHi.alpha > 0f || style.rimLo.alpha > 0f) {
        val brush = geo.rim(size, style.rimHi, style.rimLo)
        clipPath(path) { drawPath(path, brush, style = Stroke(w * 1.7f), alpha = power, blendMode = BlendMode.Plus) }
    }
}

/** 每块玻璃自己的几何缓存：外形与内缩路径、渐变只在尺寸变化时重建，面板滑动的每一帧都不分配 */
private class GlassGeometry {
    val outline = OutlinePath()
    private val paths = arrayOfNulls<Path>(INTERIOR_SLOT + 1)
    private val keys = arrayOfNulls<FloatArray>(INTERIOR_SLOT + 1)
    val slices = LensSlices()
    private var lensAt = Offset.Unspecified
    private var lensSize = Size.Unspecified
    private var lensSrc: GraphicsLayer? = null

    private var lensBlur = -1f
    private var lensScale = 1f
    private var rimKey: Size = Size.Unspecified
    private var rimHi = Color.Unspecified
    private var rimLo = Color.Unspecified
    private var rimBrush: Brush? = null
    private var sheenKey = -1f
    private var sheenAlpha = -1f
    private var sheenBrush: Brush? = null

    /**
     * 把玻璃正下方那一块背板录进离屏图层 [layer]。只在位置、尺寸或模糊量变了才重录；
     * 背板的内容变了不用管 —— 图层引用的是背板的 RenderNode，系统发现它被画了第二次会自己把这一层重新栅格化。
     *
     * 不模糊时是一比一的清晰副本，返回 1；要柔化时按 1/n 录（n 随模糊量取 2 ~ 8）并提饱和度，返回 n ——
     * 调用方把它放大 n 倍贴回去。
     */
    fun recordLens(layer: GraphicsLayer, src: GraphicsLayer, at: Offset, size: Size, style: GlassStyle, scope: DrawScope): Float {
        val blur = with(scope) { style.blur.toPx() }
        if (lensSrc === src && lensAt == at && lensSize == size && lensBlur == blur) return lensScale
        lensSrc = src
        lensAt = at
        lensSize = size
        lensBlur = blur
        layer.compositingStrategy = CompositingStrategy.Offscreen
        val n = if (blur < 1f) 1 else (blur / 2f).roundToInt().coerceIn(2, 8)
        layer.colorFilter = if (n > 1 && abs(style.saturation - 1f) > 0.01f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(style.saturation) }) else null
        layer.record(scope, scope.layoutDirection, IntSize(ceil(size.width / n).toInt().coerceAtLeast(1), ceil(size.height / n).toInt().coerceAtLeast(1))) {
            scale(1f / n, 1f / n, pivot = Offset.Zero) { translate(-at.x, -at.y) { drawLayer(src) } }
        }
        lensScale = n.toFloat()
        return lensScale
    }

    fun inset(slot: Int, rr: RoundRect, by: Float): Path {
        val k = keys[slot]
        val tl = rr.topLeftCornerRadius.x
        val br = rr.bottomRightCornerRadius.x
        if (k != null && k[0] == rr.width && k[1] == rr.height && k[2] == by && k[3] == tl && k[4] == br) return paths[slot]!!
        val p = paths[slot] ?: Path().also { paths[slot] = it }
        p.rewind()
        fun shrink(c: CornerRadius) = CornerRadius((c.x - by).coerceAtLeast(0f), (c.y - by).coerceAtLeast(0f))
        p.addRoundRect(
            RoundRect(
                rr.left + by, rr.top + by, rr.right - by, rr.bottom - by,
                shrink(rr.topLeftCornerRadius), shrink(rr.topRightCornerRadius),
                shrink(rr.bottomRightCornerRadius), shrink(rr.bottomLeftCornerRadius)
            )
        )
        keys[slot] = floatArrayOf(rr.width, rr.height, by, tl, br)
        return p
    }

    fun rim(size: Size, hi: Color, lo: Color): Brush {
        rimBrush?.let { if (rimKey == size && rimHi == hi && rimLo == lo) return it }
        return Brush.linearGradient(
            0f to hi,
            0.5f to lo,
            1f to hi.copy(alpha = hi.alpha * 0.55f),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        ).also {
            rimBrush = it
            rimKey = size
            rimHi = hi
            rimLo = lo
        }
    }

    fun sheen(height: Float, alpha: Float): Brush {
        sheenBrush?.let { if (sheenKey == height && sheenAlpha == alpha) return it }
        return Brush.verticalGradient(
            0f to Color.White.copy(alpha = alpha),
            0.45f to Color.White.copy(alpha = alpha * 0.28f),
            1f to Color.Transparent,
            startY = 0f, endY = height
        ).also {
            sheenBrush = it
            sheenKey = height
            sheenAlpha = alpha
        }
    }
}

/** 形状是（圆角）矩形时给出它，折射与斜面按它内缩；任意路径的形状不做这两样 */
private fun DrawScope.roundRectOf(outline: Outline): RoundRect? = when (outline) {
    is Outline.Rounded -> outline.roundRect
    is Outline.Rectangle -> RoundRect(outline.rect, CornerRadius.Zero)
    is Outline.Generic -> null
}

/** Android 13+：把背板在自己的位置上录进一个带折射效果的小图层，再画出来 */
@RequiresApi(33)
private fun DrawScope.drawLens(layer: GraphicsLayer, src: GraphicsLayer, at: Offset, style: GlassStyle, outline: Outline, cache: LensCache, power: Float, opaque: Boolean) {
    val radius = style.blur.toPx()
    val k = (if (radius < 1f) 1 else style.inputScale.coerceIn(1, 4)).toFloat()
    // 有模糊时四周各多录一圈：模糊核在边缘要取到玻璃外面的真实画面（折射向内取样，不需要这一圈）
    val pad = radius
    val w = ceil((size.width + pad * 2) / k).toInt().coerceAtLeast(1)
    val h = ceil((size.height + pad * 2) / k).toInt().coerceAtLeast(1)
    val corner = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        else -> 0f
    }
    layer.renderEffect = cache.effect(
        blur = radius / k, saturation = style.saturation,
        originX = pad / k, originY = pad / k, width = size.width / k, height = size.height / k,
        corner = minOf(corner, size.minDimension / 2f) / k,
        depth = style.lensDepth.toPx() / k, amount = style.lensAmount.toPx() * power / k,
        dispersion = style.dispersion, opaque = opaque
    )
    layer.record(this, layoutDirection, IntSize(w, h)) {
        scale(1f / k, 1f / k, pivot = Offset.Zero) {
            translate(pad - at.x, pad - at.y) { drawLayer(src) }
        }
    }
    translate(-pad, -pad) {
        scale(k, k, pivot = Offset.Zero) { drawLayer(layer) }
    }
}

/**
 * 折射着色器（AGSL）：[Bezel] 的逐像素版本。
 * 形状用圆角矩形的有向距离函数表示（距离给出像素在斜面上的位置 x，梯度给出法线）；
 * 位移按斯涅尔定律现算，逆着法线（朝玻璃内侧）去取样。
 * 色散：红、蓝两个通道各用略大、略小的位移再取一次，边缘就有了一线彩边（真玻璃的折射率随波长变）。
 *
 * 取样拿到的是预乘过的颜色，任何一个分量都不能超过 alpha。背板是透明底时（滑块透镜下面只有一条轨道），
 * 红蓝两路可能取到比中心更不透明的像素：拼出来的颜色分量大于 alpha，叠到底下时会多出一圈发亮的彩边。
 * 这时把红蓝夹到中心的 alpha 以内（premulClamp = 1）。不透明的背板上每个像素 alpha 都是 1，
 * 不会越界，premulClamp = 0，走的仍是原来那几步运算，画面与以前逐像素相同。
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 extent;
uniform float corner;
uniform float depth;
uniform float thickness;
uniform float dispersion;
uniform float premulClamp;

float sdf(float2 p) {
    float2 q = abs(p) - extent * 0.5 + corner;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
}

half4 main(float2 xy) {
    float2 p = xy - (origin + extent * 0.5);
    float d = sdf(p);
    if (d >= 0.0 || d <= -depth) { return content.eval(xy); }
    float k = 1.0 + d / depth;
    float k4 = k * k * k * k;
    float base = max(1.0 - k4, 0.00001);
    float height = pow(base, 0.25);
    float slope = thickness / depth * k * k * k * pow(base, -0.75);
    float incident = atan(slope);
    float refracted = asin(sin(incident) / 1.5);
    float shift = thickness * height * tan(incident - refracted);
    float2 g = float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)), sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0)));
    float2 n = g / max(length(g), 0.0001);
    float2 off = -n * shift;
    half4 c = content.eval(xy + off);
    if (dispersion <= 0.0) { return c; }
    half r = content.eval(xy + off * (1.0 + dispersion * 0.25)).r;
    half b = content.eval(xy + off * (1.0 - dispersion * 0.25)).b;
    if (premulClamp > 0.5) {
        r = min(r, c.a);
        b = min(b, c.a);
    }
    return half4(r, c.g, b, c.a);
}
"""

/**
 * 每块玻璃自己的效果缓存：RenderEffect 在创建时就把着色器的 uniform 定死了，
 * 所以尺寸不变时复用同一个效果（面板滑动的每一帧都走到这里），尺寸变了才重建。
 */
private class LensCache {
    private var key: FloatArray? = null
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null
    private var shader: android.graphics.RuntimeShader? = null

    @RequiresApi(33)
    fun effect(
        blur: Float, saturation: Float,
        originX: Float, originY: Float, width: Float, height: Float,
        corner: Float, depth: Float, amount: Float, dispersion: Float, opaque: Boolean
    ): androidx.compose.ui.graphics.RenderEffect {
        val premulClamp = if (opaque) 0f else 1f
        val k = floatArrayOf(blur, saturation, originX, originY, width, height, corner, depth, amount, dispersion, premulClamp)
        cached?.let { if (key?.contentEquals(k) == true) return it }
        // 效果链按需拼：透镜默认既不模糊也不改饱和度，那就只有折射这一环
        var out: android.graphics.RenderEffect? = null
        if (blur >= 0.5f) out = android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)
        if (abs(saturation - 1f) > 0.01f) {
            val filter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(saturation) })
            out = if (out == null) android.graphics.RenderEffect.createColorFilterEffect(filter)
            else android.graphics.RenderEffect.createColorFilterEffect(filter, out)
        }
        if (depth > 0.5f && amount > 0.5f) {
            // 斜面不超过圆角半径，与低档的画法一致
            val bezel = minOf(depth, minOf(width, height) / 2f, if (corner > 0.5f) corner else Float.MAX_VALUE)
            out = withLens(out, originX, originY, width, height, corner, bezel, amount, dispersion, premulClamp)
        }
        // 什么都不需要时也得给图层一个效果：恒等的颜色矩阵
        val effect = out ?: android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix()))
        return effect.asComposeRenderEffect().also {
            key = k
            cached = it
        }
    }

    @RequiresApi(33)
    private fun withLens(
        inner: android.graphics.RenderEffect?,
        originX: Float, originY: Float, width: Float, height: Float, corner: Float, depth: Float, amount: Float, dispersion: Float,
        premulClamp: Float
    ): android.graphics.RenderEffect? = try {
        val sh = shader ?: android.graphics.RuntimeShader(LENS_AGSL).also { shader = it }
        sh.setFloatUniform("origin", originX, originY)
        sh.setFloatUniform("extent", width, height)
        sh.setFloatUniform("corner", corner)
        sh.setFloatUniform("depth", depth)
        sh.setFloatUniform("thickness", amount)
        sh.setFloatUniform("dispersion", dispersion)
        sh.setFloatUniform("premulClamp", premulClamp)
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
        if (inner == null) lens else android.graphics.RenderEffect.createChainEffect(lens, inner)
    } catch (_: Throwable) {
        // 着色器在这台设备上编不过：退回没有折射的玻璃
        inner
    }
}

/**
 * 外形路径的缓存。外形本身每次绘制照常向形状现要（它可能读快照状态，读了才会被观察到），
 * 只有外形真的变了（尺寸、圆角）才重建路径；以前每画一帧就新建一条 Path，背后是一条原生 SkPath。
 * 画布记录的是路径的副本，之后改写这一条不影响已经录下的帧。
 */
private class OutlinePath {
    private val path = Path()
    private var key: Any? = null

    fun of(outline: Outline): Path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> {
            if (key != outline.roundRect) {
                path.rewind()
                path.addRoundRect(outline.roundRect)
                key = outline.roundRect
            }
            path
        }
        is Outline.Rectangle -> {
            if (key != outline.rect) {
                path.rewind()
                path.addRect(outline.rect)
                key = outline.rect
            }
            path
        }
    }
}

/**
 * 玻璃浮层外圈的投影：只画在形状外面，不会从半透明的玻璃底下透出一团脏影。
 * 两层 —— 一层又大又淡的环境影把它托离画面，一层贴身的接触影交代它的边（数值的量级取自参考实现）。
 */
fun Modifier.glassShadow(shape: Shape, color: Color, blur: Dp = 12.dp, dy: Dp = 4.dp): Modifier =
    this then GlassShadowElement(shape, color, blur, dy)

private data class GlassShadowElement(val shape: Shape, val color: Color, val blur: Dp, val dy: Dp) :
    ModifierNodeElement<GlassShadowNode>() {
    override fun create() = GlassShadowNode(shape, color, blur, dy)
    override fun update(node: GlassShadowNode) {
        node.shape = shape
        node.color = color
        node.blur = blur
        node.dy = dy
    }
}

/** 投影的绘制节点：外形路径与画笔跟着节点走，每帧只改画笔上的投影参数，不再每帧新建 */
private class GlassShadowNode(var shape: Shape, var color: Color, var blur: Dp, var dy: Dp) : Modifier.Node(), DrawModifierNode {
    private val outline = OutlinePath()
    private var paint: android.graphics.Paint? = null

    override fun ContentDrawScope.draw() {
        // 硬件画布上给图形画 shadowLayer 需要 API 28；更低的系统上就不画投影
        if (color.alpha > 0f && Build.VERSION.SDK_INT >= 28) {
            val path = outline.of(shape.createOutline(size, layoutDirection, this)).asAndroidPath()
            val p = paint ?: android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // 填充色必须不透明（透明的画笔会被整个跳过）；填充本身被下面的 clipOut 挖掉，只剩投影
                this.color = android.graphics.Color.BLACK
            }.also { paint = it }
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            canvas.clipOutPath(path)
            p.setShadowLayer(blur.toPx(), 0f, dy.toPx(), color.toArgb())
            canvas.drawPath(path, p)
            p.setShadowLayer(3.dp.toPx(), 0f, 1.dp.toPx(), color.copy(alpha = color.alpha * 0.5f).toArgb())
            canvas.drawPath(path, p)
            canvas.restoreToCount(save)
        }
        drawContent()
    }
}
