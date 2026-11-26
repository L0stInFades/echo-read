package app.echoread.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.echoread.core.ColorStyle
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/**
 * 配色生成：跑 Google 自己的 material-color-utilities，按「种子色 × 风格 × 明暗 × 对比度」
 * 现算出全部 48 个 M3 角色。
 *
 * 为什么是运行时算而不是把色值写死：
 * 原生安卓的取色就是这么做的 —— 系统从壁纸提取种子色，再用同一套算法按所选风格铺开整个色板。
 * 写死意味着只能有一套；而「可切换的配色系列」要的正是同一套算法在不同种子/风格下的产物。
 * 这个库是 Google 那份 material-color-utilities 的 Kotlin 移植，纯 Kotlin、不含 Compose 依赖。
 *
 * 规范版本用库的默认值 SPEC_2025：这是 material-color-utilities 现行的色彩规范，
 * 相比 2021 版把容器色与表面层级拉得更开，深色模式下的对比更稳。
 */

/** 生成一个 M3 方案。[contrast] 对应系统「对比度」设置：0 = 标准，1 = 最高 */
fun dynamicSchemeOf(seed: Color, style: ColorStyle, dark: Boolean, contrast: Float = 0f): DynamicScheme {
    // 种子色只贡献色相与彩度，各角色的明度由算法按对比度重新分配
    val hct = Hct.fromInt(seed.toArgb())
    val c = contrast.coerceIn(-1f, 1f).toDouble()
    return when (style) {
        ColorStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, c)
        ColorStyle.VIBRANT -> SchemeVibrant(hct, dark, c)
        ColorStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, c)
        ColorStyle.NEUTRAL -> SchemeNeutral(hct, dark, c)
        ColorStyle.MONOCHROME -> SchemeMonochrome(hct, dark, c)
        ColorStyle.FIDELITY -> SchemeFidelity(hct, dark, c)
        ColorStyle.CONTENT -> SchemeContent(hct, dark, c)
        ColorStyle.RAINBOW -> SchemeRainbow(hct, dark, c)
        ColorStyle.FRUIT_SALAD -> SchemeFruitSalad(hct, dark, c)
    }
}

/** DynamicScheme → Compose 的 ColorScheme。48 个角色逐一映射，无一处取默认值 */
fun DynamicScheme.toColorScheme(): ColorScheme = ColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(surfaceTint),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant)
)

/**
 * 记住一份生成的配色。生成一次要跑 48 次 HCT 解算，
 * 因此按 (种子, 风格, 明暗, 对比度) 缓存，参数不变绝不重算。
 */
@Composable
fun rememberGeneratedScheme(seed: Color, style: ColorStyle, dark: Boolean, contrast: Float): ColorScheme =
    remember(seed, style, dark, contrast) { dynamicSchemeOf(seed, style, dark, contrast).toColorScheme() }

/**
 * 纯黑变体（只用于深色方案）。页面底色与最低一级容器变成 #000000 —— OLED 屏上这些像素是关着的；
 * 其余几级容器各自减去原页面底色的明度（M3 深色里是 T6），色相与彩度不动。
 * 这样卡片、面板仍然一级一级地浮在黑底上，而且还带着这套配色自己的色相，不会变成一堆中性灰。
 * 强调色、文字色完全不变（做法与参考对象 FlClash 一致；BiliPai 用的是一组固定的中性灰阶）。
 */
fun ColorScheme.toPureBlack(): ColorScheme {
    val base = Hct.fromInt(surface.toArgb()).tone
    fun drop(c: Color): Color {
        val h = Hct.fromInt(c.toArgb())
        return Color(Hct.from(h.hue, h.chroma, (h.tone - base).coerceAtLeast(0.0)).toInt())
    }
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = drop(surfaceContainerLow),
        surfaceContainer = drop(surfaceContainer),
        surfaceContainerHigh = drop(surfaceContainerHigh),
        surfaceContainerHighest = drop(surfaceContainerHighest),
        surfaceBright = drop(surfaceBright)
    )
}
