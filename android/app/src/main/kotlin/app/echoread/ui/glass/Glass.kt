package app.echoread.ui.glass

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 液态玻璃 —— 自己实现，不依赖第三方库。
 *
 * 0.8.0 起按 FlClash（prism_ui）液态玻璃的做法整个重写（照着它的行为与数值仿写，没有搬它的代码）。
 * 一块玻璃是一枚透镜：背后的画面几乎不模糊（按钮 2.5dp，透镜 0），提饱和后透过来；
 * 只在边缘一圈斜面里被位移，棱上下两条亮线，最外 0.67dp 一圈暗线。材质参数见 [GlassStyle]。
 *
 * 参考对象的玻璃都要能跑运行时着色器（Android 13）。这款应用的主力测试机之一是 Android 11，所以按系统能力分三档：
 *
 * | 系统 | 背板怎么来 | 画法 |
 * |---|---|---|
 * | Android 13+ | 内容录进 GraphicsLayer，实时重放 | 一个着色器逐像素做完整份材质（见 MaterialShader.kt） |
 * | Android 10 ~ 12L | 同上 | 斜面位移用画布变换拼出（见 [refracted]）；着色、高光、暗线按规格里「没有着色器」时的画法 |
 * | Android 8 ~ 9 | 把内容缩小成一张位图，约每半秒刷新一次 | 同上，但画面是一张柔化过的快照 |
 *
 * 鼓起的透镜（refraction < 0，滑块与分段控件的选中透镜）在没有着色器的两档上不做位移：
 * 透镜里的内容另有一份放大的拷贝画在上面（见 Segmented.kt），观感主要靠它。
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

    /** 背板自带不透明的底色（整屏的页面）还是透明底（控件内部只录了一条轨道）。透明底时斜面的每一片不能互相覆盖 */
    internal var opaque = true

    /** 低档：缩小 + 模糊后的快照，以及缩小倍数 */
    internal var frost by mutableStateOf<ImageBitmap?>(null)
    internal var frostScale = 1f
}

/** 浮层从这里取背板；为 null（或用户关掉了玻璃）时退回不透明底色 */
val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * @param snapshots 最低一档（Android 8 ~ 9）要不要为这块背板拍快照。只给控件内部的小透镜
 *   （分段控件、滑块）采样的背板传 false：那一档上它反正画不了清晰的透镜，不必为它每半秒回读一次。
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

/** 低档的模糊强度（dp，按屏幕尺度）：一张快照供所有玻璃共用 */
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
 *   录像必须自带底色：页面底色若画在更外层，录下来的就是「透明底上的几行字」，玻璃后面看到的仍是原画面。
 */
fun Modifier.backdropSource(backdrop: Backdrop?, fill: Color, visible: Boolean = true, show: (() -> Boolean)? = null): Modifier {
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
            // （朗读高亮、列表滚动）不需要再走到这里，重放录像时拿到的就是最新画面。
            // 用的是 DrawScope 上的 record 扩展：它把当前画布临时换成图层的画布，外层的 drawContent() 才会画进图层里
            layer.record(IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                drawRect(fill)
                this@drawWithContent.drawContent()
            }
            // show = false：照录不画（这一刻由采样它的那块玻璃代画 —— 分段透镜里被掰弯的那份字）
            if (show?.invoke() != false) drawLayer(layer)
            if (!backdrop.recorded) backdrop.recorded = true
        }
}

/**
 * 手指下的那团光：按住玻璃时，光从触点晕开、跟着手指走，松手后一边散开一边淡掉。
 * 由 elasticPress 喂位置与强弱，由 [glass] / [glassSurface] 画。
 */
@Stable
class GlassLight {
    internal var at by mutableStateOf(Offset.Unspecified)
    internal val power = Animatable(0f)

    internal companion object {
        /** 亮起 120 ms、熄灭 360 ms，都是 easeOut */
        private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
        val On = tween<Float>(120, easing = EaseOut)
        val Off = tween<Float>(360, easing = EaseOut)
    }
}

/**
 * 画一块玻璃作为背景。
 *
 * 它是 composed（不是绘制节点）：它持有随组合释放的离屏图层、要给背板登记取用者、显影动画由组合期的状态驱动。
 *
 * @param power 0..1 的「显形」程度，在绘制期读：材质按 [GlassStyle.materialized] 一点点弯出来。
 *   给悬起才变成透镜的滑块用 —— 为 0 时什么都不画。
 * @param offset 这块玻璃被 `graphicsLayer` 之类**绘制期变换**挪了多少（面板的滑入位移、按压时被手指拉过去的那一段）。
 *   布局位置由本修饰符自己量；绘制期的位移布局不知道，必须由调用方告知，背板才能与底下的真实画面对齐。
 * @param scale 同理：绘制期绕玻璃中心的缩放（按压时整块鼓起），参数是玻璃自己的尺寸。
 *   玻璃跟着变大，背后的画面不该跟着放大 —— 取样时按它反过来缩回去。
 */
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle,
    backdrop: Backdrop?,
    light: GlassLight? = null,
    power: (() -> Float)? = null,
    offset: (() -> Offset)? = null,
    scale: ((Size) -> Offset)? = null
): Modifier = composed {
    val shaderLayer = if (backdrop != null && glassTier == GlassTier.SHADER) rememberGraphicsLayer() else null
    // 中档：玻璃自己那一小块背板的离屏副本。斜面要把同一块画面重复画几十次，重放整棵绘制树太贵，贴一张小纹理几乎不要钱
    val lensLayer = if (backdrop != null && glassTier == GlassTier.REPLAY) rememberGraphicsLayer() else null
    val material = remember { if (Build.VERSION.SDK_INT >= 33) MaterialHolder() else null }
    val geo = remember { GlassGeometry() }
    var position by remember { mutableStateOf(Offset.Zero) }
    if (backdrop != null) {
        DisposableEffect(backdrop) {
            backdrop.users++
            onDispose { backdrop.users-- }
        }
    }
    // 背板要晚一两帧才到（最低一档要等快照回读）：先画不透明底色，到了再淡成玻璃，而不是「啪」地一下变透明
    val available = backdrop != null && backdrop.recorded && (glassTier != GlassTier.SNAPSHOT || backdrop.frost != null)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(available) {
        if (available) reveal.animateTo(1f, tween(200, easing = LinearEasing)) else reveal.snapTo(0f)
    }
    this
        .then(
            if (backdrop == null) Modifier else Modifier.onGloballyPositioned {
                // 根坐标里的位置算上了外层图层的变换（按压鼓起、面板滑动）：这里要的是静止时的位置，把变换反算回去
                val half = Offset(it.size.width / 2f, it.size.height / 2f)
                val sc = scale?.invoke(Size(it.size.width.toFloat(), it.size.height.toFloat())) ?: Unscaled
                position = it.positionInRoot() - (offset?.invoke() ?: Offset.Zero) - Offset(half.x * (1f - sc.x), half.y * (1f - sc.y))
            }
        )
        .drawWithContent {
            val pw = power?.invoke()?.coerceIn(0f, 1f) ?: 1f
            if (power != null && pw < 0.004f) {
                drawContent()
                return@drawWithContent
            }
            val s = if (power != null) style.materialized(pw) else style
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = geo.outline.of(outline)
            val rr = roundRectOf(outline)
            val src = backdrop?.layer
            val t = reveal.value
            val through = t > 0.001f && backdrop != null && src != null && backdrop.recorded
            // 还没完全显出来之前，底下垫着不透明底色：任何时刻都不会出现一块「透明的面板」
            if ((!through || t < 0.999f) && power == null) drawPath(path, style.fallback)
            var edges = false
            if (through) {
                val at = position - backdrop!!.origin + (offset?.invoke() ?: Offset.Zero)
                val sc = scale?.invoke(size) ?: Unscaled
                when (glassTier) {
                    GlassTier.SHADER -> {
                        val holder = material!!
                        clipPath(path) { holder.draw(this, shaderLayer!!, src!!, at, sc, s, rr, t) }
                        edges = holder.failed
                        if (edges) drawPath(path, s.tint, alpha = t)
                    }
                    GlassTier.REPLAY -> {
                        clipPath(path) {
                            // 小件（药丸、按钮、滑块）把自己正下方那一小块录进离屏图层，斜面要把这一块画几十次，贴小纹理最省；
                            // 大面板直接重放背板，也不做斜面（没有着色器时，那意味着把整屏内容重放几十遍）
                            val small = size.width * size.height < 0.3f * backdrop.size.width * backdrop.size.height
                            val k = if (small) geo.recordLens(lensLayer!!, src!!, at, sc, size, s, this) else 1f
                            val pane: DrawScope.() -> Unit = when {
                                !small -> { { sampled(at, sc, size.center) { drawLayer(src!!) } } }
                                k > 1f -> { { scale(k, k, pivot = Offset.Zero) { drawLayer(lensLayer!!) } } }
                                else -> { { drawLayer(lensLayer!!) } }
                            }
                            // 混了重度模糊的件（横幅）不拼斜面：几段线性的折射叠在一片模糊上只剩条纹，规格里没有着色器时本来也不折射
                            if (small && s.haze < 0.3f) refracted(rr, s, geo, opaque = backdrop.opaque, pane) else pane()
                            if (t < 0.999f && power == null) drawRect(style.fallback, alpha = 1f - t)
                        }
                        drawPath(path, s.tint, alpha = t)
                        edges = true
                    }
                    GlassTier.SNAPSHOT -> {
                        val img = backdrop.frost
                        if (img != null) {
                            val k = backdrop.frostScale
                            val center = size.center
                            val frost: DrawScope.() -> Unit = {
                                sampled(at, sc, center) {
                                    scale(k, k, pivot = Offset.Zero) {
                                        drawImage(img, dstOffset = IntOffset.Zero, filterQuality = FilterQuality.Medium, colorFilter = geo.colorOf(s))
                                    }
                                }
                            }
                            clipPath(path) {
                                if (s.haze < 0.3f) refracted(rr, s, geo, opaque = true, frost) else frost()
                                if (t < 0.999f && power == null) drawRect(style.fallback, alpha = 1f - t)
                            }
                            drawPath(path, s.tint, alpha = t)
                            edges = true
                        }
                    }
                }
            }
            // 没有着色器的两档：棱与暗线按规格里的「描边」画法补上
            if (edges) drawGlassEdges(path, s, geo, t)
            drawGlow(path, light)
            drawContent()
        }
}

/**
 * 不采样背板的玻璃面：填充、反光与描边。用在**已经位于玻璃或平整底色之上**的件（面板、卡片、按钮）——
 * 那里背后没有可折射的内容，再采样一次只是白花 GPU。
 *
 * 它只画、不持有图层也不起协程，所以是一个绘制节点（几何缓存跟着节点走），不经过组合。
 */
fun Modifier.glassSurface(shape: Shape, style: SurfaceStyle, light: GlassLight? = null): Modifier =
    this then GlassSurfaceElement(shape, style, light)

private data class GlassSurfaceElement(val shape: Shape, val style: SurfaceStyle, val light: GlassLight?) :
    ModifierNodeElement<GlassSurfaceNode>() {
    override fun create() = GlassSurfaceNode(shape, style, light)
    override fun update(node: GlassSurfaceNode) {
        node.shape = shape
        node.style = style
        node.light = light
    }
}

private class GlassSurfaceNode(var shape: Shape, var style: SurfaceStyle, var light: GlassLight?) : Modifier.Node(), DrawModifierNode {
    private val geo = GlassGeometry()

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = geo.outline.of(outline)
        val s = style
        if (s.fill.alpha > 0f) drawPath(path, s.fill)
        if (s.sheen > 0f) {
            clipPath(path) {
                val h = minOf(size.height * 0.62f, 56.dp.toPx())
                drawRect(geo.sheen(h, s.sheen), size = Size(size.width, h))
            }
        }
        drawGlow(path, light)
        drawContent()
        if (s.rimWidth.value > 0f) {
            val w = s.rimWidth.toPx()
            if (s.edge.alpha > 0f) drawPath(path, s.edge, style = Stroke(maxOf(1f, w * 0.5f)))
            if (s.rimHi.alpha > 0f || s.rimLo.alpha > 0f) {
                val brush = geo.rim(size, s.rimHi, s.rimLo, s.rimHi.copy(alpha = s.rimHi.alpha * 0.55f))
                clipPath(path) { drawPath(path, brush, style = Stroke(w * 1.7f), blendMode = BlendMode.Plus) }
            }
        }
    }
}

/**
 * 普通玻璃斜面上的位移（规格里的圆弧斜面）：离边 [d]、斜面宽 [bevel]、最外沿的位移 [depth]（同为像素），
 * 朝玻璃内侧。斜面与平面相接处斜率为 0（看不出接缝），越往外越陡；
 * 最外沿读到的是 depth 那么深的地方，所以最外一圈是被压扁、翻转的画面，再往里一段是放大的。
 */
internal object Optics {
    fun inward(d: Float, bevel: Float, depth: Float): Float {
        if (bevel <= 0f || depth <= 0f || d >= bevel) return 0f
        val u = 1f - (d / bevel).coerceIn(0f, 1f)
        return depth * (1f - sqrt((1f - u * u).coerceAtLeast(0f)))
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
 * 没有着色器的系统上，把 [Optics] 的位移曲线化成画布能做的变换（见 [refracted]）。两套划分：
 *
 * - **直边**：位移只沿一个方向，一段之内让它线性变化 —— 那正好是一次「缩放 + 平移」，段与段在交界处严丝合缝；
 * - **圆角**：位移沿半径方向，画布只能做「以圆心为轴的缩放」，一片之内只能取一个值，因此切得细一些。
 *
 * 直边按「位移每下降同样多切一刀」来分：位移变化剧烈的外沿分得密，几乎不动的内侧一段带过。
 * 段数要省，是因为每多一段就多几次绘制调用（Adreno 610 上实测：每块玻璃两百多次绘制会把书架滚动拖到每帧 35ms）。
 */
private class LensSlices {
    var segments = 0
        private set
    val edgeAt = FloatArray(MAX_SEGMENTS + 1)
    val edgeShift = FloatArray(MAX_SEGMENTS + 1)

    var count = 0
        private set
    val inset = FloatArray(MAX_SLICES + 1)
    val shift = FloatArray(MAX_SLICES)

    private var bevelKey = -1f
    private var depthKey = -1f
    private val curve = FloatArray(LUT)

    fun update(bevel: Float, depth: Float) {
        if (bevel == bevelKey && depth == depthKey) return
        bevelKey = bevel
        depthKey = depth
        for (i in 0 until LUT) curve[i] = Optics.inward(bevel * i / (LUT - 1f), bevel, depth)
        val peak = curve[0]
        segments = cut(bevel, peak, ceil(peak / 6f).toInt().coerceIn(3, MAX_SEGMENTS), minWidth = 0.75f)
        for (j in 0..segments) edgeShift[j] = if (j == segments) 0f else sample(edgeAt[j] / bevel)
        // 圆角按宽度均分：每片的缩放倍数取决于「位移 / 到圆心的距离」，越靠里圆心越近，等位移地分反而会在内侧留下一大片台阶
        count = ceil(bevel / SLICE_STEP_PX).toInt().coerceIn(2, MAX_SLICES)
        for (j in 0..count) inset[j] = bevel * j / count
        for (j in 0 until count) shift[j] = sample((inset[j] + inset[j + 1]) / 2f / bevel)
    }

    /** 按位移等差地下刀，写进 edgeAt，返回分成了几份。挨得太近（不足 [minWidth] 像素）的刀并掉 */
    private fun cut(bevel: Float, peak: Float, wanted: Int, minWidth: Float): Int {
        var n = 0
        var index = 0
        edgeAt[0] = 0f
        for (j in 1 until wanted) {
            val target = peak * (1f - j.toFloat() / wanted)
            while (index < LUT - 1 && curve[index] > target) index++
            val at = bevel * index / (LUT - 1f)
            if (at - edgeAt[n] < minWidth || at >= bevel - minWidth) continue
            n++
            edgeAt[n] = at
        }
        n++
        edgeAt[n] = bevel
        return n
    }

    private fun sample(x: Float): Float {
        val f = (x * (LUT - 1)).coerceIn(0f, LUT - 1f)
        val i = f.toInt().coerceAtMost(LUT - 2)
        return curve[i] + (curve[i + 1] - curve[i]) * (f - i)
    }
}

/**
 * 不用着色器的斜面位移（Android 12L 及以下）。着色器版逐像素沿法线取样；这里用画布变换拼出同一条位移曲线：
 *
 * - 直边分成几段，每段是一次「缩放 + 平移」（缩放可以为负：位移降得比位置升得快的地方，画面是翻转的）；
 * - 圆角切成一圈圈薄片，每片是以角的圆心为轴的一次缩放。
 *
 * [pane] 把背板画到玻璃自己的坐标系里（不带任何位移）；斜面里的每一段、每一片都是它套上一个变换再画一次。
 * 鼓起的透镜（refraction < 0）在这一档不做位移。
 */
private fun DrawScope.refracted(rr: RoundRect?, style: GlassStyle, geo: GlassGeometry, opaque: Boolean, pane: DrawScope.() -> Unit) {
    val w = size.width
    val h = size.height
    val m = size.minDimension / 2f
    val r = if (rr == null) 0f else rr.topLeftCornerRadius.x.coerceAtMost(m)
    // 规格：斜面宽不超过半短边的一半，最外沿的位移不超过半短边；这里再让斜面不超过圆角半径（否则角上两条边的斜面会交叠）
    val bevel = minOf(style.bevel.toPx(), m / 2f, if (r > 0.5f) r else Float.MAX_VALUE)
    val depth = minOf(style.refraction.toPx(), m)
    if (rr == null || bevel < 1f || depth < 0.5f) {
        pane()
        return
    }
    val s = geo.slices
    s.update(bevel, depth)
    val straightW = w - 2f * r
    val straightH = h - 2f * r

    for (j in 0 until s.segments) {
        val d0 = s.edgeAt[j]
        val d1 = s.edgeAt[j + 1]
        val v0 = s.edgeShift[j]
        // 这一段里「背板走过的距离 / 屏幕上走过的距离」；为负即翻转
        var slope = 1f + (s.edgeShift[j + 1] - v0) / (d1 - d0)
        if (abs(slope) < 0.04f) slope = if (slope < 0f) -0.04f else 0.04f
        val k = 1f / slope
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
    clipPath(geo.inset(INTERIOR_SLOT, rr, bevel)) { pane() }
}

/**
 * 没有着色器时玻璃的棱与暗线（规格里的描边画法）：
 * 棱 = 宽 rimWidth 的白色描边，沿对角线从 rim 到 0.15·rim 再回到 0.5·rim；暗线 = 0.67dp 的黑色描边，
 * 浓度取上下与两侧的平均再乘 0.3。都画在形状内侧。
 */
private fun DrawScope.drawGlassEdges(path: Path, style: GlassStyle, geo: GlassGeometry, alpha: Float) {
    clipPath(path) {
        if (style.rim > 0.004f) {
            val w = style.rimWidth.toPx()
            val hi = Color.White.copy(alpha = style.rim.coerceAtMost(1f))
            val brush = geo.rim(size, hi, hi.copy(alpha = hi.alpha * 0.15f), hi.copy(alpha = hi.alpha * 0.5f))
            drawPath(path, brush, style = Stroke(w * 2f), alpha = alpha)
        }
        val line = (style.hairline + style.hairlineSide) / 2f * 0.3f
        if (line > 0.002f) drawPath(path, Color.Black, alpha = line * alpha, style = Stroke(0.67.dp.toPx() * 2f))
    }
}

/**
 * 手指下的光（规格里的按压光）：白光加在玻璃上，强度 0.16，按高斯分布从触点往外衰减，
 * 半径是高的 1/√2；松手后一边淡掉一边散开到四倍远。
 */
private fun DrawScope.drawGlow(path: Path, light: GlassLight?) {
    if (light == null || !light.at.isSpecified) return
    val p = light.power.value
    if (p < 0.01f) return
    val fading = light.power.targetValue < 0.5f
    val reach = size.height / sqrt(2f) * (if (fading) 1f + 3f * (1f - p) else 1f)
    val peak = 0.16f * p
    clipPath(path) {
        // 高斯 e^(−r²/reach²) 在 0..2·reach 上取几档
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = peak),
                0.15f to Color.White.copy(alpha = peak * 0.914f),
                0.3f to Color.White.copy(alpha = peak * 0.698f),
                0.45f to Color.White.copy(alpha = peak * 0.445f),
                0.6f to Color.White.copy(alpha = peak * 0.237f),
                0.75f to Color.White.copy(alpha = peak * 0.105f),
                1f to Color.Transparent,
                center = light.at, radius = 2f * reach
            ),
            radius = 2f * reach, center = light.at, blendMode = BlendMode.Plus
        )
    }
}

private val Unscaled = Offset(1f, 1f)

/**
 * 在玻璃自己的坐标里画背板：玻璃左上角对着背板上的 [at]；玻璃若绕中心 [center] 缩放了 [sc] 倍（按压鼓起），
 * 背板反过来缩回去 —— 玻璃变大了，透过它看到的画面仍与底下严丝合缝，不跟着放大。
 */
private inline fun DrawScope.sampled(at: Offset, sc: Offset, center: Offset, block: DrawScope.() -> Unit) {
    if (sc == Unscaled) {
        translate(-at.x, -at.y, block)
        return
    }
    translate(center.x, center.y) {
        scale(1f / sc.x, 1f / sc.y, pivot = Offset.Zero) {
            translate(-(at.x + center.x), -(at.y + center.y), block)
        }
    }
}

/** 每块玻璃自己的几何缓存：外形与内缩路径、渐变只在尺寸变化时重建，面板滑动的每一帧都不分配 */
private class GlassGeometry {
    val outline = OutlinePath()
    private val paths = arrayOfNulls<Path>(INTERIOR_SLOT + 1)
    private val keys = arrayOfNulls<FloatArray>(INTERIOR_SLOT + 1)
    val slices = LensSlices()
    private var lensAt = Offset.Unspecified
    private var lensSc = Offset.Unspecified
    private var lensSize = Size.Unspecified
    private var lensSrc: GraphicsLayer? = null
    private var lensBlur = -1f
    private var lensScale = 1f
    private var lensColor: ColorFilter? = null
    private var colorKey = Offset.Unspecified
    private var rimKey: Size = Size.Unspecified
    private var rimStops: Triple<Color, Color, Color>? = null
    private var rimBrush: Brush? = null
    private var sheenKey = -1f
    private var sheenAlpha = -1f
    private var sheenBrush: Brush? = null

    /** 饱和度与亮度合成一个颜色矩阵（都是 1 时为 null） */
    fun colorOf(style: GlassStyle): ColorFilter? {
        val key = Offset(style.saturation, style.brightness)
        if (key == colorKey) return lensColor
        colorKey = key
        lensColor = if (abs(style.saturation - 1f) < 0.01f && abs(style.brightness - 1f) < 0.01f) null
        else ColorFilter.colorMatrix(ColorMatrix().apply {
            setToSaturation(style.saturation)
            timesAssign(ColorMatrix().apply { setToScale(style.brightness, style.brightness, style.brightness, 1f) })
        })
        return lensColor
    }

    /**
     * 把玻璃正下方那一块背板录进离屏图层 [layer]。只在位置、尺寸或模糊量变了才重录；
     * 背板的内容变了不用管 —— 图层引用的是背板的 RenderNode，系统发现它被画了第二次会自己把这一层重新栅格化。
     *
     * 不模糊时是一比一的清晰副本，返回 1；要柔化时按 1/n 录（n 随模糊量取 2 ~ 8），返回 n ——
     * 调用方把它放大 n 倍贴回去（缩小时每个像素是一小片画面的平均，放大时双线性插值，合起来相当于几个像素的高斯核）。
     * 重度模糊混入（haze）在这一档上并成整块的柔化：没有第二张图可混。
     */
    fun recordLens(layer: GraphicsLayer, src: GraphicsLayer, at: Offset, sc: Offset, size: Size, style: GlassStyle, scope: DrawScope): Float {
        val sigma = with(scope) { (if (style.haze > 0.3f) maxOf(style.blur, style.hazeBlur) else style.blur).toPx() }
        val color = colorOf(style)
        if (lensSrc === src && lensAt == at && lensSc == sc && lensSize == size && lensBlur == sigma && layer.colorFilter == color) return lensScale
        lensSrc = src
        lensAt = at
        lensSc = sc
        lensSize = size
        lensBlur = sigma
        layer.compositingStrategy = CompositingStrategy.Offscreen
        val n = if (sigma < 1f) 1 else (sigma / 2f).roundToInt().coerceIn(2, 8)
        layer.colorFilter = color
        val center = size.center
        layer.record(scope, scope.layoutDirection, IntSize(ceil(size.width / n).toInt().coerceAtLeast(1), ceil(size.height / n).toInt().coerceAtLeast(1))) {
            scale(1f / n, 1f / n, pivot = Offset.Zero) { sampled(at, sc, center) { drawLayer(src) } }
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

    /** 沿对角线（左上 → 右下）的三段渐变：棱上的光从左上来 */
    fun rim(size: Size, start: Color, middle: Color, end: Color): Brush {
        val stops = Triple(start, middle, end)
        rimBrush?.let { if (rimKey == size && rimStops == stops) return it }
        return Brush.linearGradient(
            0f to start, 0.5f to middle, 1f to end,
            start = Offset(0f, 0f), end = Offset(size.width, size.height)
        ).also {
            rimBrush = it
            rimKey = size
            rimStops = stops
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

/** 形状是（圆角）矩形时给出它，斜面按它内缩；任意路径的形状不做斜面 */
private fun roundRectOf(outline: Outline): RoundRect? = when (outline) {
    is Outline.Rounded -> outline.roundRect
    is Outline.Rectangle -> RoundRect(outline.rect, CornerRadius.Zero)
    is Outline.Generic -> null
}

/** Android 13+：材质效果与它的离屏图层。不在 13 以下的系统上实例化 */
private class MaterialHolder {
    private var effect: Any? = null
    val failed: Boolean get() = Build.VERSION.SDK_INT >= 33 && (effect as? MaterialEffect)?.failed == true

    /** 把背板在玻璃位置上的那一块（连同四周一圈）录进 [layer]，套上材质效果画出来；调用方已裁到玻璃的外形 */
    fun draw(scope: DrawScope, layer: GraphicsLayer, src: GraphicsLayer, at: Offset, sc: Offset, style: GlassStyle, rr: RoundRect?, alpha: Float) {
        if (Build.VERSION.SDK_INT < 33) return
        drawMaterial(scope, layer, src, at, sc, style, rr, alpha)
    }

    @RequiresApi(33)
    private fun drawMaterial(scope: DrawScope, layer: GraphicsLayer, src: GraphicsLayer, at: Offset, sc: Offset, style: GlassStyle, rr: RoundRect?, alpha: Float) = with(scope) {
        val fx = (effect as? MaterialEffect) ?: MaterialEffect().also { effect = it }
        val pad = ceil(MaterialEffect.padOf(style, density))
        val corner = (rr?.topLeftCornerRadius?.x ?: 0f).coerceAtMost(size.minDimension / 2f)
        layer.renderEffect = fx.of(style, density, size.width, size.height, corner, pad)
        layer.alpha = alpha
        val center = size.center
        layer.record(this, layoutDirection, IntSize(ceil(size.width + pad * 2f).toInt(), ceil(size.height + pad * 2f).toInt())) {
            translate(pad, pad) { sampled(at, sc, center) { drawLayer(src) } }
        }
        translate(-pad, -pad) { drawLayer(layer) }
    }
}

/**
 * 外形路径的缓存。外形本身每次绘制照常向形状现要（它可能读快照状态，读了才会被观察到），
 * 只有外形真的变了（尺寸、圆角）才重建路径。画布记录的是路径的副本，之后改写这一条不影响已经录下的帧。
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
 * 两层 —— 一层又大又淡的环境影把它托离画面，一层贴身的接触影交代它的边。
 *
 * @param alpha 绘制期读的浓度系数（悬起的透镜的影子跟着悬起程度浓淡）
 */
fun Modifier.glassShadow(shape: Shape, color: Color, blur: Dp = 12.dp, dy: Dp = 4.dp, alpha: (() -> Float)? = null): Modifier =
    this then GlassShadowElement(shape, color, blur, dy, alpha)

private data class GlassShadowElement(val shape: Shape, val color: Color, val blur: Dp, val dy: Dp, val alpha: (() -> Float)?) :
    ModifierNodeElement<GlassShadowNode>() {
    override fun create() = GlassShadowNode(shape, color, blur, dy, alpha)
    override fun update(node: GlassShadowNode) {
        node.shape = shape
        node.color = color
        node.blur = blur
        node.dy = dy
        node.alpha = alpha
    }
}

/** 投影的绘制节点：外形路径与画笔跟着节点走，每帧只改画笔上的投影参数，不再每帧新建 */
private class GlassShadowNode(var shape: Shape, var color: Color, var blur: Dp, var dy: Dp, var alpha: (() -> Float)?) : Modifier.Node(), DrawModifierNode {
    private val outline = OutlinePath()
    private var paint: android.graphics.Paint? = null

    override fun ContentDrawScope.draw() {
        val a = color.alpha * (alpha?.invoke()?.coerceIn(0f, 1f) ?: 1f)
        // 硬件画布上给图形画 shadowLayer 需要 API 28；更低的系统上就不画投影
        if (a > 0.002f && Build.VERSION.SDK_INT >= 28) {
            val path = outline.of(shape.createOutline(size, layoutDirection, this)).asAndroidPath()
            val p = paint ?: android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // 填充色必须不透明（透明的画笔会被整个跳过）；填充本身被下面的 clipOut 挖掉，只剩投影
                this.color = android.graphics.Color.BLACK
            }.also { paint = it }
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            canvas.clipOutPath(path)
            p.setShadowLayer(blur.toPx(), 0f, dy.toPx(), color.copy(alpha = a).toArgb())
            canvas.drawPath(path, p)
            p.setShadowLayer(3.dp.toPx(), 0f, 1.dp.toPx(), color.copy(alpha = a * 0.5f).toArgb())
            canvas.drawPath(path, p)
            canvas.restoreToCount(save)
        }
        drawContent()
    }
}
