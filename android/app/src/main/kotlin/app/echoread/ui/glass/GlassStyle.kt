package app.echoread.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一块玻璃的材质（会采样背后的画面）。字段与取值的含义按 FlClash（prism_ui）液态玻璃的行为规格，独立重写：
 *
 * 画面从背后取来 → 轻轻模糊（[blur]）→ 在边缘斜面里被位移（[refraction]、[bevel]）→ 红蓝分开（[dispersion]）→
 * 提饱和、提亮（[saturation]、[brightness]）→ 盖一层着色（[tint]，背景与着色明暗差太多时自动加浓，[contrast]）→
 * 上下两条棱上的高光（rim*）→ 最外 0.67dp 一圈暗线（hairline*）。没有内阴影、没有磨砂噪点。
 *
 * 长度一律是 dp。取值见 GlassRecipes.kt 里按用途给出的几套（常规按钮、着色按钮、横幅、透镜）。
 */
@Immutable
data class GlassStyle(
    /** 着色：颜色 + 浓度（alpha 就是盖上去的比例） */
    val tint: Color,
    /** 没有背板可采样（关了玻璃、或者被遮住）时的不透明底色 */
    val fallback: Color,
    /** 背景与着色的亮度差超过 0.3 时着色加浓的程度；正数管「背景比着色暗」（浅色界面），负数管「背景更亮」（深色界面） */
    val contrast: Float = 0f,
    /** 采样时的模糊（高斯的 σ） */
    val blur: Dp = 0.dp,
    /** 再混进多少一层重度模糊（[hazeBlur]）的画面：横幅这类要托住一行字、又想透出背后形状的件用 */
    val haze: Float = 0f,
    val hazeBlur: Dp = 0.dp,
    /** 边缘的位移幅度：正数 = 普通玻璃（往里取样，斜面先放大、最外一圈压扁翻转）；负数 = 鼓起的透镜（往外取样） */
    val refraction: Dp = 60.dp,
    /** 斜面宽 */
    val bevel: Dp = 20.dp,
    val saturation: Float = 1.5f,
    val brightness: Float = 1f,
    /** 高光强度、宽度，以及它拖出去的长尾占比、只亮上下两条的程度、按加色叠上去的占比 */
    val rim: Float = 0.6f,
    val rimWidth: Dp = 1.3.dp,
    val rimTail: Float = 0f,
    val rimFocus: Float = 0f,
    val rimAdded: Float = 0f,
    /** 透镜两端暗一点 */
    val endShade: Float = 0f,
    /** 顶边、底边处红蓝通道分开的距离（左右两侧取六成） */
    val dispersion: Dp = 0.dp,
    /** 越靠边越模糊（透镜） */
    val rimBlur: Dp = 0.dp,
    /** 暗线：上下两条与左右两侧各自的浓度 */
    val hairline: Float = 0f,
    val hairlineSide: Float = hairline,
) {
    /**
     * 材质「显形」到 [t]（0..1）：玻璃是一点点弯出来的，不是淡进来的。
     * 位移、色散、高光、暗线、着色浓度与模糊乘上 t，饱和度与亮度从 1 往目标走；斜面宽与高光宽不变。
     */
    fun materialized(t: Float): GlassStyle {
        if (t >= 0.999f) return this
        val k = t.coerceIn(0f, 1f)
        return copy(
            tint = tint.copy(alpha = tint.alpha * k),
            contrast = contrast * k,
            blur = blur * k,
            haze = haze * k,
            refraction = refraction * k,
            saturation = 1f + (saturation - 1f) * k,
            brightness = 1f + (brightness - 1f) * k,
            rim = rim * k,
            endShade = endShade * k,
            dispersion = dispersion * k,
            rimBlur = rimBlur * k,
            hairline = hairline * k,
            hairlineSide = hairlineSide * k,
        )
    }
}

/**
 * 不采样背后画面的「玻璃面」：卡片、面板、次级按钮。半透明的填充、棱上一线高光，可选一条暗发丝线与顶部反光。
 * 它们背后没有值得折射的东西（纯色的页面或面板），所以不走玻璃材质，也就没有那一份开销。
 */
@Immutable
data class SurfaceStyle(
    val fill: Color,
    /** 高光描边：沿对角线从亮到暗再回亮（光从左上来） */
    val rimHi: Color = Color.Transparent,
    val rimLo: Color = Color.Transparent,
    val rimWidth: Dp = 1.dp,
    /** 顶部的一抹反光（白色的浓度；0 = 没有） */
    val sheen: Float = 0f,
    /** 压在高光外侧的暗发丝线：浅色界面上白色的高光落在白底上等于没有，轮廓要靠它 */
    val edge: Color = Color.Transparent,
)
