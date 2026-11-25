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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
 * 液态玻璃（0.5.0）—— 自己实现，不依赖第三方库。
 *
 * 参考对象（BiliPai、FlClash）的玻璃都要 Android 13：低于它，它们直接退回不透明底色，
 * 而这款应用的主力测试机是 Android 11。所以这里按系统能力分三档，**每一档都有真的模糊**：
 *
 * | 系统 | 背板怎么来 | 模糊 |
 * |---|---|---|
 * | Android 13+ | 内容录进 GraphicsLayer，实时重放 | RenderEffect 高斯模糊 + 提饱和 + 边缘折射（AGSL 着色器） |
 * | Android 12L | 同上 | RenderEffect 高斯模糊 + 提饱和 |
 * | Android 12 及以下 | 把内容缩小成一张位图，CPU 上做盒式模糊，约每半秒刷新一次 | 位图放大铺在玻璃后面 |
 *
 * 低档之所以成立：玻璃在这款应用里只用在**盖住内容的浮层**上（底部面板、提示条、悬浮药丸），
 * 浮层出现时底下的内容几乎是静止的（面板有遮罩挡住交互；朗读高亮几秒才动一次），
 * 一张半秒前的模糊快照与实时模糊肉眼分不出来，而每帧的代价只是画一张 150 像素宽的小图。
 *
 * 材质本身与系统无关的部分 —— 着色、高光描边、连续的大圆角 —— 三档完全一致。
 */

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

    /** 低档：缩小 + 模糊后的快照，以及缩小倍数 */
    internal var frost by mutableStateOf<ImageBitmap?>(null)
    internal var frostScale = 1f
}

/** 浮层从这里取背板；为 null（或用户关掉了玻璃）时退回不透明底色 */
val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 实时模糊可用。RenderEffect 是 Android 12（API 31）加的，但这里从 12L（API 32）才启用：
 * API 31 上「给一个不断重录的图层挂 RenderEffect」在部分机型上有驱动问题（同类开源库也是从 32 起默认开启），
 * 而快照档在 31 上同样成立，没有理由去赌。
 */
internal val liveBlur: Boolean get() = Build.VERSION.SDK_INT >= 32

/** 边缘折射可用（AGSL RuntimeShader，Android 13+） */
internal val lens: Boolean get() = Build.VERSION.SDK_INT >= 33

@Composable
fun rememberBackdrop(): Backdrop {
    val backdrop = remember { Backdrop() }
    backdrop.layer = rememberGraphicsLayer()
    backdrop.density = LocalDensity.current
    if (!liveBlur) {
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
fun Modifier.backdropSource(backdrop: Backdrop?, fill: Color): Modifier {
    if (backdrop == null) return this.drawBehind { drawRect(fill) }
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

/** 玻璃材质的参数。取值见 Theme.kt 里按明暗给出的两套 */
@Immutable
data class GlassStyle(
    /** 盖在模糊背板上的着色。越大越不透，文字越稳；面板取 0.7 上下，小药丸可以更薄 */
    val tint: Color,
    /** 没有背板可采样（或用户关掉了玻璃）时的不透明底色 */
    val fallback: Color,
    val blur: Dp = 24.dp,
    /** 饱和度提升：玻璃后面的颜色比原画面更艳一点，这是「玻璃」与「一层灰」的分别 */
    val saturation: Float = 1.6f,
    /** 高光描边：沿对角线从亮到暗再回亮，模拟来自左上方的光 */
    val rimHi: Color = Color.Transparent,
    val rimLo: Color = Color.Transparent,
    val rimWidth: Dp = 1.dp,
    /** 录制背板时缩小几倍再模糊（只影响实时档的 GPU 开销）：大面板 2，小药丸 1 */
    val inputScale: Int = 2,
    /**
     * 边缘折射（Android 13+）：玻璃有厚度，边缘那一圈会把后面的画面「掰」进来。
     * [lensDepth] 是这一圈的宽度，[lensAmount] 是最边上的像素被挪动的距离；0 = 不做折射。
     */
    val lensDepth: Dp = 0.dp,
    val lensAmount: Dp = 0.dp
)

/**
 * 画一块玻璃作为背景。
 *
 * @param offset 这块玻璃被 `graphicsLayer` 之类**绘制期变换**挪了多少（面板的滑入位移）。
 *   布局位置由本修饰符自己量；绘制期的位移布局不知道，必须由调用方告知，背板才能与底下的真实画面对齐。
 */
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle,
    backdrop: Backdrop?,
    offset: (() -> Offset)? = null
): Modifier = composed {
    val blurLayer = if (backdrop != null && liveBlur) rememberGraphicsLayer() else null
    val lensCache = remember { LensCache() }
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
    // 背板要晚一两帧才到（低档要等快照回读）：先画不透明底色，到了再淡成玻璃，而不是「啪」地一下变透明
    val available = backdrop != null && backdrop.recorded && (liveBlur || backdrop.frost != null)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(available) {
        if (available) reveal.animateTo(1f, tween(200, easing = LinearEasing)) else reveal.snapTo(0f)
    }
    this
        .then(if (backdrop != null) Modifier.onGloballyPositioned { position = it.positionInRoot() } else Modifier)
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = outlinePath(outline)
            val src = backdrop?.layer
            val t = reveal.value
            // 还没完全显出来之前，底下垫着不透明底色：任何时刻都不会出现一块「透明的面板」
            if (t < 0.999f) drawPath(path, style.fallback)
            if (t > 0.001f && backdrop != null && src != null && backdrop.recorded) {
                val shift = offset?.invoke() ?: Offset.Zero
                val at = position - backdrop.origin + shift
                clipPath(path) {
                    if (blurLayer != null && Build.VERSION.SDK_INT >= 32) {
                        blurLayer.alpha = t
                        drawLiveBlur(blurLayer, src, at, style, outline, lensCache)
                    } else {
                        val img = backdrop.frost
                        if (img != null) {
                            val k = backdrop.frostScale
                            translate(-at.x, -at.y) {
                                scale(k, k, pivot = Offset.Zero) {
                                    drawImage(img, dstOffset = IntOffset.Zero, alpha = t, filterQuality = FilterQuality.Medium, colorFilter = saturate)
                                }
                            }
                        }
                    }
                }
                drawPath(path, style.tint, alpha = t)
            }
            drawContent()
            if (style.rimWidth.value > 0f && (style.rimHi.alpha > 0f || style.rimLo.alpha > 0f)) {
                val w = style.rimWidth.toPx()
                drawPath(
                    path,
                    Brush.linearGradient(
                        0f to style.rimHi,
                        0.32f to style.rimLo,
                        0.68f to style.rimLo,
                        1f to style.rimHi.copy(alpha = style.rimHi.alpha * 0.55f),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.55f, size.height)
                    ),
                    style = Stroke(w)
                )
            }
        }
}

/** 实时档：把背板在自己的位置上录进一个带模糊效果的小图层，再画出来 */
@RequiresApi(32)
private fun DrawScope.drawLiveBlur(blurLayer: GraphicsLayer, src: GraphicsLayer, at: Offset, style: GlassStyle, outline: Outline, cache: LensCache) {
    val radius = style.blur.toPx()
    val k = style.inputScale.coerceIn(1, 4).toFloat()
    // 四周各多录一圈：模糊核与折射在边缘都要取到玻璃外面的真实画面，而不是把边缘像素拉长
    val pad = maxOf(radius, style.lensAmount.toPx())
    val w = ceil((size.width + pad * 2) / k).toInt().coerceAtLeast(1)
    val h = ceil((size.height + pad * 2) / k).toInt().coerceAtLeast(1)
    val corner = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        else -> 0f
    }
    blurLayer.renderEffect = cache.effect(
        blur = radius / k, saturation = style.saturation,
        originX = pad / k, originY = pad / k, width = size.width / k, height = size.height / k,
        corner = minOf(corner, size.minDimension / 2f) / k,
        depth = style.lensDepth.toPx() / k, amount = style.lensAmount.toPx() / k
    )
    blurLayer.record(this, layoutDirection, IntSize(w, h)) {
        scale(1f / k, 1f / k, pivot = Offset.Zero) {
            translate(pad - at.x, pad - at.y) { drawLayer(src) }
        }
    }
    translate(-pad, -pad) {
        scale(k, k, pivot = Offset.Zero) { drawLayer(blurLayer) }
    }
}

/**
 * 折射着色器（AGSL）。玻璃是一块圆角矩形的凸透镜：离边缘 [depth] 以内的像素，
 * 沿「指向外侧的法线」去取更靠外的画面，位移量按圆弧剖面从 0 增长到 [amount] ——
 * 于是玻璃边缘那一圈能看到被压进来的、玻璃外面的内容，这就是「液态」的来源。
 * 形状用圆角矩形的有向距离函数表示，法线取它的梯度。
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 extent;
uniform float corner;
uniform float depth;
uniform float amount;

float sdf(float2 p) {
    float2 q = abs(p) - extent * 0.5 + corner;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
}

half4 main(float2 xy) {
    float2 p = xy - (origin + extent * 0.5);
    float d = sdf(p);
    if (d >= 0.0 || d < -depth) { return content.eval(xy); }
    float t = 1.0 + d / depth;
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 g = float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)), sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0)));
    float2 n = g / max(length(g), 0.0001);
    return content.eval(xy + n * bend * amount);
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

    @RequiresApi(32)
    fun effect(
        blur: Float, saturation: Float,
        originX: Float, originY: Float, width: Float, height: Float,
        corner: Float, depth: Float, amount: Float
    ): androidx.compose.ui.graphics.RenderEffect {
        val k = floatArrayOf(blur, saturation, originX, originY, width, height, corner, depth, amount)
        cached?.let { if (key?.contentEquals(k) == true) return it }
        val r = blur.coerceAtLeast(0.5f)
        val blurred = android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
        var out = android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix), blurred)
        if (Build.VERSION.SDK_INT >= 33 && depth > 0.5f && amount > 0.5f) {
            out = withLens(out, originX, originY, width, height, corner, depth, amount)
        }
        return out.asComposeRenderEffect().also {
            key = k
            cached = it
        }
    }

    @RequiresApi(33)
    private fun withLens(
        inner: android.graphics.RenderEffect,
        originX: Float, originY: Float, width: Float, height: Float, corner: Float, depth: Float, amount: Float
    ): android.graphics.RenderEffect = try {
        val sh = shader ?: android.graphics.RuntimeShader(LENS_AGSL).also { shader = it }
        sh.setFloatUniform("origin", originX, originY)
        sh.setFloatUniform("extent", width, height)
        sh.setFloatUniform("corner", corner)
        // 折射带不能比短边的一半还宽，否则两侧的位移会在中间打架
        sh.setFloatUniform("depth", minOf(depth, minOf(width, height) / 2f))
        sh.setFloatUniform("amount", amount)
        android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content"), inner)
    } catch (_: Throwable) {
        // 着色器在这台设备上编不过：退回只有模糊的玻璃
        inner
    }
}

private fun DrawScope.outlinePath(outline: Outline): Path = when (outline) {
    is Outline.Generic -> outline.path
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
}

/** 玻璃浮层外圈的柔和投影：只画在形状外面，不会从半透明的玻璃底下透出一团脏影 */
fun Modifier.glassShadow(shape: Shape, color: Color, blur: Dp = 18.dp, dy: Dp = 6.dp): Modifier = drawWithContent {
    // 硬件画布上给图形画 shadowLayer 需要 API 28；更低的系统上就不画投影
    if (color.alpha > 0f && Build.VERSION.SDK_INT >= 28) {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = outlinePath(outline).asAndroidPath()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // 填充色必须不透明（透明的画笔会被整个跳过）；填充本身被下面的 clipOut 挖掉，只剩投影
            this.color = android.graphics.Color.BLACK
            setShadowLayer(blur.toPx(), 0f, dy.toPx(), color.toArgb())
        }
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    drawContent()
}
