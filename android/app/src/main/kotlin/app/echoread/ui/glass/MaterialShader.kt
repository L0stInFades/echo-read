package app.echoread.ui.glass

import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.max

/**
 * 玻璃材质的着色器（AGSL，Android 13+）。逐像素做完 [GlassStyle] 文档里的那一串：
 * 外形的距离场与法线 → 斜面位移 → 边缘的追加模糊 → 色散 → 饱和度与亮度 → 透镜两端的暗角 →
 * 着色（背景与着色明暗差太大时加浓）→ 上下两条棱的高光 → 最外 0.67dp 的暗线。
 *
 * 公式按 FlClash（prism_ui）液态玻璃的行为规格独立写成；坐标与长度都是像素（[pxPerDp] 把规格里的 dp 常数换过来）。
 * 内容（content）是已经模糊过、混好重度模糊的背板，透明底也成立：高光与暗线按自己的覆盖率叠上去，
 * 所以分段控件的透镜伸出轨道的那一截，棱照样看得见。
 */
private const val MATERIAL_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 extent;
uniform float corner;
uniform float pxPerDp;
uniform float refraction;
uniform float bevel;
uniform float dispersion;
uniform float rimBlur;
uniform float saturation;
uniform float brightness;
uniform float endShade;
uniform float4 tint;
uniform float contrast;
uniform float probe;
uniform float rim;
uniform float rimWidth;
uniform float rimTail;
uniform float rimFocus;
uniform float rimAdded;
uniform float hairline;
uniform float hairlineSide;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float4 frost(float2 at, float sigma) {
    if (sigma < 0.75) { return float4(content.eval(at)); }
    float radius = (sigma - 0.5) * 2.0785;
    float4 acc = float4(0.0);
    float total = 0.0;
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float r = radius * sqrt((fi + 0.5) / 12.0);
        float a = fi * 2.39996;
        float w = exp(-r * r / (2.0 * sigma * sigma));
        acc += float4(content.eval(at + r * float2(cos(a), sin(a)))) * w;
        total += w;
    }
    return acc / total;
}

float4 probeAt(float2 p) {
    if (probe <= 0.0) { return float4(content.eval(p)); }
    return 0.25 * (float4(content.eval(p + float2(probe, probe))) + float4(content.eval(p + float2(-probe, probe)))
        + float4(content.eval(p + float2(probe, -probe))) + float4(content.eval(p + float2(-probe, -probe))));
}

float clash() {
    float4 s = probeAt(origin + extent * float2(0.5, 0.5)) + probeAt(origin + extent * float2(0.2, 0.3))
        + probeAt(origin + extent * float2(0.8, 0.3)) + probeAt(origin + extent * float2(0.2, 0.7))
        + probeAt(origin + extent * float2(0.8, 0.7));
    s *= 0.2;
    float lb = luma(s.rgb / max(s.a, 0.0001));
    return clamp((sign(contrast) * (luma(tint.rgb) - lb) - 0.3) / 0.5, 0.0, 1.0);
}

half4 main(float2 xy) {
    float2 h = extent * 0.5;
    float2 c = xy - origin - h;
    float m = min(h.x, h.y);
    float r = min(corner, m);
    float2 q = abs(c) - h + r;
    float d = max(-(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r), 0.0);
    float2 sg = float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));

    float b = max(min(bevel, m * 0.5), 0.001);
    float u = 1.0 - clamp(d / b, 0.0, 1.0);
    float2 shift = refraction < 0.0
        ? -refraction * smoothstep(0.0, b, d) * (1.0 - smoothstep(b, m, d)) * n
        : -min(refraction, m) * (1.0 - sqrt(max(1.0 - u * u, 0.0))) * n;
    float2 at = xy + shift;
    float sigma = rimBlur * u * u;
    float4 src = frost(at, sigma);
    float near = 10.0 * pxPerDp;
    if (dispersion > 0.0 && d < near) {
        float2 v = float2(n.y, n.x) * (dispersion * 0.5)
            * (0.6 + 0.4 * abs(n.y) * max(1.0 - d / (7.0 * pxPerDp), 0.0))
            * (1.0 - smoothstep(7.0 * pxPerDp, near, d));
        float4 red = frost(at + v, sigma);
        float4 blue = frost(at - v, sigma);
        // 分出去的通道只从「有东西」的地方取色：透明底的背板（控件里只录了一条轨道）边缘不会拆出一圈红蓝。
        // 不透明的背板上每个取样的 alpha 都是 1，结果与直接拿红、蓝两个取样相同
        float3 mid = src.rgb / max(src.a, 0.0001);
        float rr = mix(mid.r, red.r / max(red.a, 0.0001), red.a);
        float bb = mix(mid.b, blue.b / max(blue.a, 0.0001), blue.a);
        src = float4(float3(rr, mid.g, bb) * src.a, src.a);
    }

    float alpha = src.a;
    float3 col = src.rgb / max(alpha, 0.0001);
    col = brightness * mix(float3(luma(col)), col, saturation);
    if (endShade > 0.0) { col *= 1.0 - endShade * abs(n.x) * exp(-d / (3.0 * pxPerDp)); }
    float cover = tint.a;
    if (contrast != 0.0) { cover += (1.0 - tint.a) * clash() * abs(contrast); }
    float3 g = col * alpha * (1.0 - cover) + tint.rgb * cover;
    float a = alpha * (1.0 - cover) + cover;

    float line = 0.67 * pxPerDp;
    float w = max(d - line, 0.0);
    float ny = abs(n.y);
    float facing = refraction < 0.0 ? smoothstep(0.65, 0.95, ny) : mix(ny * ny, 0.5 * (ny * ny * ny + pow(ny, 19.0)), rimFocus);
    float band = clamp(1.0 - w / max(rimWidth, 0.001), 0.0, 1.0);
    float tail = clamp(1.0 - w / (4.5 * pxPerDp), 0.0, 1.0);
    float s = min(rim * facing * mix(band, tail, rimTail), 1.0);
    g = g * (1.0 - (1.0 - rimAdded) * s) + s;
    a = a + s * (1.0 - a);

    // 暗线是整块玻璃的事（有一边不为 0 就画），不是逐像素的：上下两条浓度为 0 的地方，最外这一圈露出的是背后本来的画面。
    // 以前按逐像素的浓度判断，圆形按钮正上、正下那一列像素浓度恰好是 0，高光留在了最外沿，成了两个亮点
    float k = mix(hairline, hairlineSide, pow(abs(n.x), 3.0));
    if (hairline > 0.0 || hairlineSide > 0.0) {
        float l = clamp(line - d + 0.5, 0.0, 1.0);
        float4 under = float4(content.eval(xy));
        float3 dark = max(under.rgb - 0.298 * under.a, 0.22 * under.rgb);
        g = mix(g, mix(under.rgb, dark, k), l);
        a = mix(a, under.a + 0.298 * k * (1.0 - under.a), l);
    }
    return half4(min(g, float3(a)), a);
}
"""

/**
 * 每块玻璃自己的效果缓存。RenderEffect 一创建就定死了着色器的 uniform：参数没变时复用（面板滑动的每一帧都走到这里），
 * 变了才重建。悬起中的透镜每帧换参数，那几百毫秒里每帧重建一次。
 */
@RequiresApi(33)
internal class MaterialEffect {
    private var key: FloatArray? = null
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null
    private var shader: RuntimeShader? = null

    /** 着色器在这台设备上编不过：之后只给模糊，由调用方补画着色与描边 */
    var failed = false
        private set

    /**
     * @param pad 背板四周多录的一圈（像素），着色器里的坐标从这一圈的左上角算起
     */
    fun of(style: GlassStyle, density: Float, width: Float, height: Float, corner: Float, pad: Float): androidx.compose.ui.graphics.RenderEffect {
        val t = style.tint
        val k = floatArrayOf(
            density, width, height, corner, pad,
            t.red, t.green, t.blue, t.alpha, style.contrast, style.blur.value, style.haze, style.hazeBlur.value,
            style.refraction.value, style.bevel.value, style.saturation, style.brightness,
            style.rim, style.rimWidth.value, style.rimTail, style.rimFocus, style.rimAdded,
            style.endShade, style.dispersion.value, style.rimBlur.value, style.hairline, style.hairlineSide
        )
        cached?.let { if (key?.contentEquals(k) == true) return it }
        val blur = style.blur.value * density
        val sharp = if (blur >= 0.75f) RenderEffect.createBlurEffect(radiusOf(blur), radiusOf(blur), Shader.TileMode.CLAMP) else null
        var input = sharp
        if (style.haze > 0.005f && style.hazeBlur.value > 0f) {
            // 重度模糊的那一层按 haze 的比例盖在清晰的那一层上：0.28 清晰 + 0.72 模糊
            val far = RenderEffect.createBlurEffect(radiusOf(style.hazeBlur.value * density), radiusOf(style.hazeBlur.value * density), Shader.TileMode.CLAMP)
            val faded = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, style.haze) }), far)
            input = RenderEffect.createBlendModeEffect(sharp ?: RenderEffect.createOffsetEffect(0f, 0f), faded, BlendMode.SRC_OVER)
        }
        val material = if (failed) null else try {
            val sh = shader ?: RuntimeShader(MATERIAL_AGSL).also { shader = it }
            val px = density
            val probe = if (style.haze <= 0.005f && style.blur.value in 0.01f..7.99f) blur else 0f
            sh.setFloatUniform("origin", pad, pad)
            sh.setFloatUniform("extent", width, height)
            sh.setFloatUniform("corner", corner)
            sh.setFloatUniform("pxPerDp", px)
            sh.setFloatUniform("refraction", style.refraction.value * px)
            sh.setFloatUniform("bevel", style.bevel.value * px)
            sh.setFloatUniform("dispersion", style.dispersion.value * px)
            sh.setFloatUniform("rimBlur", style.rimBlur.value * px)
            sh.setFloatUniform("saturation", style.saturation)
            sh.setFloatUniform("brightness", style.brightness)
            sh.setFloatUniform("endShade", style.endShade)
            sh.setFloatUniform("tint", t.red, t.green, t.blue, t.alpha)
            sh.setFloatUniform("contrast", style.contrast)
            sh.setFloatUniform("probe", probe)
            sh.setFloatUniform("rim", style.rim)
            sh.setFloatUniform("rimWidth", style.rimWidth.value * px)
            sh.setFloatUniform("rimTail", style.rimTail)
            sh.setFloatUniform("rimFocus", style.rimFocus)
            sh.setFloatUniform("rimAdded", style.rimAdded)
            sh.setFloatUniform("hairline", style.hairline)
            sh.setFloatUniform("hairlineSide", style.hairlineSide)
            RenderEffect.createRuntimeShaderEffect(sh, "content")
        } catch (_: Throwable) {
            failed = true
            null
        }
        val out = when {
            material != null && input != null -> RenderEffect.createChainEffect(material, input)
            material != null -> material
            input != null -> input
            // 什么都不需要时也得给图层一个效果：恒等的颜色矩阵
            else -> RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix()))
        }
        return out.asComposeRenderEffect().also {
            key = k
            cached = it
        }
    }

    companion object {
        /** 采样时要读到玻璃外面多远（像素）：模糊核的三个 σ、透镜往外取样的那一段、色散，再留 2dp */
        fun padOf(style: GlassStyle, density: Float): Float {
            val reach = 3f * (style.blur.value + style.rimBlur.value) + max(0f, -style.refraction.value) + style.dispersion.value + 2f
            val haze = if (style.haze > 0.005f) 3f * style.hazeBlur.value + 2f else 0f
            return max(reach, haze) * density
        }

        /** RenderEffect 的模糊参数是「半径」，系统按 σ = 0.57735·r + 0.5 换算；这里反过来，让 [sigma] 就是高斯的 σ */
        private fun radiusOf(sigma: Float): Float = max((sigma - 0.5f) / 0.57735f, 0.01f)
    }
}
