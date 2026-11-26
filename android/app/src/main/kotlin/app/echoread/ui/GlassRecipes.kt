package app.echoread.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.echoread.ui.glass.GlassStyle
import app.echoread.ui.glass.SurfaceStyle
import com.materialkolor.hct.Hct

/*
 * 按用途给出的几种玻璃。数值取自 FlClash（prism_ui）液态玻璃的行为规格（那一套是对着 iOS 量出来的），独立重写：
 *
 * - 常规（regular）：浮在内容上的图标按钮、按钮组、状态药丸。浅色是近白、深色是「卡片色提亮 7 个色调」的着色，
 *   2.5dp 的轻模糊，饱和度拉到 2 以上，背后的内容与着色明暗差太多时着色自动加浓，图标始终站得住；
 * - 着色（accent）：导入按钮，0.7.1 的材质（强调色六成浓）配这一版的边；
 * - 横幅（banner）：提示条。28% 清晰 + 72% 重度模糊（15.6dp），托住一行字，又透出背后的形状与颜色；
 * - 透镜（lens）：分段控件、滑块悬起时的那一颗。不着色、不模糊，边缘往外取样（鼓起的透镜），红蓝分开。
 *
 * 面板与卡片不采样背后的画面，用的是下面的 [SurfaceStyle]（实心或半透明填充 + 棱上一线光）。
 */

/** 这套配色下的几种玻璃：随配色一起算好，组合期取用不再分配 */
@Immutable
class GlassStyles(val regular: GlassStyle, val accent: GlassStyle, val pill: GlassStyle, val banner: GlassStyle, val lens: GlassStyle, val lensContent: GlassStyle)

/** 深色玻璃的着色：颜色在 HCT 里提亮 [tones] 个色调，色相与彩度不变 */
private fun lifted(color: Color, tones: Double): Color {
    val h = Hct.fromInt(color.toArgb())
    return Color(Hct.from(h.hue, h.chroma, (h.tone + tones).coerceIn(0.0, 100.0)).toInt())
}

internal fun glassStylesFor(s: ColorScheme, dark: Boolean): GlassStyles = GlassStyles(
    regular = regularGlass(dark, s.surfaceContainer, s.surfaceContainerHigh),
    // 导入按钮：0.7.1 的材质（用户点名要的）—— 强调色只盖六成，后面的书与页面透得出来；饱和度 1.5，
    // 约 2.5dp 的轻模糊，边缘一圈厚玻璃的折射（与 0.7.1 的位移量相当）。边沿用这一版的：上下两条细高光 + 最外一圈暗线
    accent = GlassStyle(
        tint = s.primary.copy(alpha = 0.62f), fallback = s.primary,
        blur = 2.5.dp, saturation = 1.5f,
        refraction = 19.dp, bevel = 18.dp,
        rim = if (dark) 0.14f else 0.2f, rimWidth = 1.2.dp, rimTail = 0.17f, rimFocus = 1f, rimAdded = 0.38f,
        hairline = if (dark) 0f else 0.34f, hairlineSide = if (dark) 0.73f else 0.5f
    ),
    // 书架小标题那枚药丸：0.7.1 的材质（用户点名要的）—— 容器色提一点亮度、不到五成浓，2.5dp 轻模糊，饱和度 1.5，
    // 边缘一圈折射（与 0.7.1 的位移量相当）。边沿用这一版的（与常规玻璃同一套高光与暗线）
    pill = GlassStyle(
        tint = androidx.compose.ui.graphics.lerp(s.surfaceContainer, if (dark) Color.Black else Color.White, 0.22f).copy(alpha = 0.47f),
        fallback = s.surfaceContainerHigh,
        blur = 2.5.dp, saturation = 1.5f,
        refraction = 17.dp, bevel = 16.dp,
        rim = 0.28f, rimWidth = 1.2.dp, rimTail = 0.17f, rimFocus = 1f, rimAdded = 0.38f,
        hairline = if (dark) 0f else 0.25f, hairlineSide = 0.75f
    ),
    banner = GlassStyle(
        tint = if (dark) lifted(s.surfaceContainer, 18.0).copy(alpha = 0.42f) else Color(0xFFF9F9F9).copy(alpha = 0.525f),
        fallback = s.surfaceContainerHigh,
        blur = 0.dp, haze = 0.72f, hazeBlur = 15.6.dp,
        saturation = if (dark) 1.925f else 2.1f,
        rim = 0.28f, rimWidth = 1.2.dp, rimTail = 0.17f, rimFocus = 1f, rimAdded = 0.38f,
        hairline = if (dark) 0f else 0.25f, hairlineSide = 0.75f
    ),
    lens = lensGlass(dark),
    // 透镜里那份放大的字再被一块清玻璃掰一下（FlClash 的做法）：往里取样 14dp、斜面 11.2dp —— 字经过透镜边缘时被压扁、弯折。
    // 它的边缘模糊（2.5dp）与色散（3.6dp）在这么小的字上糊成一团，这里收到很小，字弯而不糊；深色也不提亮（会把强调色提成白字）
    lensContent = GlassStyle(
        tint = Color.Transparent, fallback = Color.Transparent,
        refraction = 14.dp, bevel = 11.2.dp, saturation = 1f,
        rim = 0f, dispersion = 1.2.dp, rimBlur = 0.5.dp
    )
)

/**
 * 常规玻璃。[card] 是这块玻璃所在页面的卡片色（深色时着色由它提亮 7 个色调得来），[fallback] 是关掉玻璃时的底色。
 * 阅读器用阅读主题自己的颜色调用它：阅读主题独立于系统深浅色。
 */
fun regularGlass(dark: Boolean, card: Color, fallback: Color): GlassStyle =
    if (dark) GlassStyle(
        tint = lifted(card, 7.0).copy(alpha = 0.62f), fallback = fallback,
        contrast = -0.4f, blur = 2.5.dp, saturation = 2.84f,
        rim = 0.28f, rimWidth = 1.2.dp, rimTail = 0.17f, rimFocus = 1f, rimAdded = 0.38f,
        hairline = 0f, hairlineSide = 0.75f
    ) else GlassStyle(
        tint = Color(0xFFFDFDFD).copy(alpha = 0.541f), fallback = fallback,
        contrast = 0.4f, blur = 2.5.dp, saturation = 2.08f,
        rim = 0.28f, rimWidth = 1.2.dp, rimTail = 0.17f, rimFocus = 1f, rimAdded = 0.38f,
        hairline = 0.25f, hairlineSide = 0.75f
    )

/** 悬起的选中透镜：鼓起（往外取样 4.3dp，斜面 5.5dp），红蓝分开 6.5dp，边缘追加一点模糊，两端略暗 */
fun lensGlass(dark: Boolean): GlassStyle = GlassStyle(
    tint = Color.Transparent, fallback = Color.Transparent,
    refraction = (-4.3).dp, bevel = 5.5.dp, saturation = 1f, brightness = if (dark) 1.33f else 1f,
    rim = if (dark) 0.3f else 0.6f, rimWidth = 1.1.dp,
    endShade = 0.045f, dispersion = 6.5.dp, rimBlur = 1.5.dp, hairline = 0.42f
)

/**
 * 面板：**实心**的面板色，没有描边。面板上是成段的文字，参考对象的面板与对话框都是实心的，玻璃留给悬浮件。
 * FlClash 的面板只按圆角裁一刀、铺满底色：一块面靠明度与底下拉开，从来不靠边框。
 * 0.7.1 以前这里绕了一圈 1dp 的高光描边，深色下是一条又亮又匀的灰线（「光很僵」），去掉了。
 * 试过的三种「玻璃面板」都不成立：模糊 + 着色是毛玻璃；中部不透、四周一圈清，看上去是两层；
 * 整块九成浓的清玻璃，透过来的一成只在文字后面添鬼影，而 Android 11 上面板滑动从每帧 19ms 掉到 32ms。
 */
val EchoColors.sheetSurface: SurfaceStyle
    get() = SurfaceStyle(fill = sheet)

/** 卡片、次级按钮：半透明的填充 + 一条很淡的高光描边。背后没有值得折射的东西，不采样 */
fun EchoColors.surfaceGlass(color: Color = fill): SurfaceStyle =
    SurfaceStyle(
        fill = color,
        rimHi = if (!glassOn) Color.Transparent else if (isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.95f),
        rimLo = if (!glassOn) Color.Transparent else if (isDark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.25f),
        rimWidth = 0.7.dp
    )

/** 品牌渐变按钮上的那层光：不填充，只有顶部反光与高光描边 */
val EchoColors.glossGlass: SurfaceStyle
    get() = SurfaceStyle(
        fill = Color.Transparent,
        rimHi = Color.White.copy(alpha = if (isDark) 0.55f else 0.75f),
        rimLo = Color.White.copy(alpha = 0.10f),
        rimWidth = 1.dp,
        sheen = if (isDark) 0.08f else 0.12f
    )
