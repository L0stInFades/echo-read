package app.echoread.ui

import app.echoread.core.ReaderSettings
import app.echoread.core.ColorStyle
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.ui.motion.EchoMotionScheme

/**
 * 设计语言（0.2.0-exp）：**Material 3 Expressive**。
 *
 * 配色不再沿用网页版的手挑「暗夜极光」，而是用 Google 自己的色彩算法生成：
 * 以 #7C9BFF 为 seed、SchemeTonalSpot 变体、contrast 0.0，跑 material-color-utilities
 * （com.google.android.material:material:1.14.0 内附）产出完整 48 角色调色板，并用独立实现交叉校验。
 * TonalSpot 只保留 seed 的**色相**（H=273.2）而丢弃其彩度与明度，因此品牌辨识度还在，
 * 每一个具体色值却都是 Google 算法的产物 —— 这正是「配色采用谷歌的建议」的字面落实。
 *
 * 副产品：TonalSpot 把 tertiary 放在 hue+60（H=333.2，玫红）。旧极光渐变是「靛→紫→粉」，
 * 而 primary→tertiary 天然复现了同一个手势，却无需任何手挑色（见 [rememberAurora]）。
 * 顺带修掉一个真实缺陷：旧极光三个色标在浅色底上只有 2.15~2.61:1，大标题实际不满足 WCAG；
 * 新渐变两端同色调（浅色 T40 / 深色 T80），全程 6.1:1 / 10.9:1 且亮度恒定。
 */
@Immutable
data class EchoColors(
    val canvas: Color,
    val card: Color,
    val cardAlt: Color,
    /**
     * 弹层/大面板的底色，比 [card] 低一档。
     * 必须比卡片低：分组卡片画在弹层上，两者同色就等于卡片不存在 ——
     * 实测深色下只差一档只有 1.05:1，差两档（Low↔High）才有 1.20:1，能看出边界。
     */
    val sheet: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    /** 品牌渐变/强调色**之上**的内容色。深色模式下 primary 是 T80 的浅紫，写死 Color.White 只有 1.7:1 */
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val isDark: Boolean
)

/* ---------------- M3 色板（seed #7C9BFF · SchemeTonalSpot · 2021 spec） ---------------- */

/* ---------------- M3 色板 ----------------
 *
 * 这里原本写死了浅色/深色两套 48 角色常量（约 110 行）。0.2.2 起改为运行时生成：
 * 见 ColorSchemes.kt —— 跑 Google 的 material-color-utilities，按种子色 × 风格现算。
 * 已用契约测试锁住：默认种子色 #7C9BFF + 标准风格必须逐位复现原来那套人工核验过的色板
 * （见 ColorSchemeTest.defaultPaletteIsUnchanged）。
 */



/**
 * 由 M3 色板派生出全应用在用的 10 个语义色。
 *
 * 保留 [EchoColors] 与 `echo` 访问器不动，是为了让约 160 处 `c.xxx` 调用点一行都不用改，
 * 就整体换到 M3 角色上（顺便自动获得动态取色支持）。
 *
 * 层级遵循 M3 的「色调化表面」而非旧的 elevation 叠色：页面 = surface，
 * 卡片 = surfaceContainer，卡片内的行/输入框 = surfaceContainerHigh。
 * 注意浅色模式因此发生了方向性变化：M3 里容器比页面**更暗**，而旧设计是灰底白卡片。
 *
 * text3 没有干净的 M3 对应角色（规范里表面上只定义了 onSurface / onSurfaceVariant 两级）。
 * 取 `outline` 是最接近的真实角色：浅色 4.27:1、深色 5.85:1，稳过 3:1 的非文本/大字门槛，
 * 且远优于旧实现（onSurface @0.35~0.40 ≈ 2.3:1）。
 */
fun echoColorsFrom(s: ColorScheme, dark: Boolean) = EchoColors(
    canvas = s.surface,
    card = s.surfaceContainer,
    cardAlt = s.surfaceContainerHigh,
    sheet = s.surfaceContainerLow,
    border = s.outlineVariant,
    text = s.onSurface,
    text2 = s.onSurfaceVariant,
    text3 = s.outline,
    accent = s.primary,
    onAccent = s.onPrimary,
    accentSoft = s.primaryContainer,
    danger = s.error,
    isDark = dark
)

/**
 * 默认值只在「主题尚未提供」的极短窗口里被读到（例如预览）。
 * 用默认种子色现算一份深色方案即可，不必为此保留一整套写死的常量。
 */
val LocalEchoColors = staticCompositionLocalOf {
    echoColorsFrom(
        dynamicSchemeOf(Color(ReaderSettings.DEFAULT_SEED), ColorStyle.TONAL_SPOT, dark = true).toColorScheme(),
        true
    )
}

/**
 * 品牌渐变：primary → tertiary。
 *
 * 配色可由用户任意更换后，这个选择需要有据可依。实测七种可选风格下两端的色相距离
 * （种子 #7C9BFF、深色）：
 *   primary→tertiary   标准 40° / 鲜明 61° / 活泼 101° / 淡雅 24° / 彩虹 59° / 缤纷 51° / 单色 0°
 *   primary→secondary  标准  1° / 鲜明  9° / 活泼  95° / 淡雅  9° / 彩虹  0° / 缤纷  0° / 单色 0°
 *
 * 取 tertiary：七种里有六种落在 24~61°，是和谐的邻近／类比配色；只有「活泼」到 101°，
 * 而那个变体（EXPRESSIVE）本来就是为强色彩关系设计的，用户选它就是要这个效果。
 * 换成 secondary 的话，六种风格的两端色相差只有 0~9°，渐变会直接看不出来。
 *
 * 注意：**实心控件不要用它**。播放键曾经用这个渐变，在「苔绿 + 活泼」下会变成红到绿，
 * 已改为纯色 primary 容器（见 ReaderScreen.PlayButton）。渐变留给文字/大面积品牌元素。
 */
fun auroraBrush(primary: Color, tertiary: Color): Brush = Brush.linearGradient(listOf(primary, tertiary))

@Composable
fun rememberAurora(): Brush {
    val c = echo
    val tertiary = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    return remember(c.accent, tertiary) { auroraBrush(c.accent, tertiary) }
}

/* ---------------- 形状 ---------------- */

/**
 * M3 Expressive 形状阶梯。原有 xl=28 / lg=20 恰好命中 extraLarge 与 largeIncreased；
 * md 由 14 上调到 16（large）—— Expressive 的方向是更圆，往下取 12 会比现状更扁平。
 * 原 sm=10 全项目零引用，替换为规范值 8。
 */
object Radius {
    val xxl: Dp = 48.dp // extraExtraLarge
    val xl: Dp = 28.dp  // extraLarge
    val lgPlus: Dp = 24.dp // largeIncreased
    val lg: Dp = 20.dp  // large
    val md: Dp = 16.dp  // medium
    val mdMinus: Dp = 12.dp // 卡片之下、chip 之上的那一档；原来缺这一级，于是出现了 9/10/14dp 这类随手值
    val sm: Dp = 8.dp   // small
    val xs: Dp = 4.dp   // extraSmall
}

/**
 * 供落进来的 M3 组件使用，与上面的 Radius 保持同一套语言（alpha18 的 Shapes 有 8 个槽位）。
 *
 * 注意 `large` 与 `largeIncreased` 必须是两个不同的值：M3 的组件靠这一级差别表达层级
 * （例如 ButtonGroup 的 connected 形状会在 shape / checkedShape 之间取两档），
 * 塌成同一个值等于把这条表达通道关掉了。
 */
val EchoShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    largeIncreased = RoundedCornerShape(Radius.lgPlus),
    extraLarge = RoundedCornerShape(Radius.xl),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(Radius.xxl)
)

/* ---------------- 字体 ---------------- */

/**
 * M3 的字号阶梯，按中文排版做两处修正：
 * 1. **字距归零**。规范里 0.1~0.5sp 的正字距是照着 Roboto 的拉丁字形边距调的；
 *    汉字是全角方块，本身自带留白，再加字距会显得松散断裂。
 * 2. **行高上调**。M3 正文行高比是 1.43~1.50，而汉字纵向填满字身框，1.5 在中文里偏挤，
 *    UI 正文取 1.6~1.75。（书籍正文的行高由阅读设置单独控制，不走这里。）
 *
 * **15 个 Emphasized 样式必须一并显式传入。** alpha18 的 `Typography` 同时存在 30 参与
 * 15 参（legacy）两个构造器：只命名基础样式时，重载解析会选中 15 参那个，于是所有
 * `*Emphasized` 静默退回 M3 默认值 —— 上面两条中文修正在它们身上一条都不生效，
 * 而 Expressive 恰恰要求用 Emphasized 承担「强调」这件事。
 *
 * Emphasized 的差别只在字重（M3 自己的做法）：display/headline/body 由 Normal→Bold，
 * title 由 Medium→Bold，label 由 Medium→ExtraBold。字号与行高保持一致，
 * 这样同一段文字在普通与强调之间切换不会引起重排。
 */
private val ZeroSpacing = 0.sp
val EchoTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, lineHeight = 68.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    displayLargeEmphasized = TextStyle(fontSize = 57.sp, lineHeight = 68.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    displayMedium = TextStyle(fontSize = 45.sp, lineHeight = 56.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    displayMediumEmphasized = TextStyle(fontSize = 45.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    displaySmallEmphasized = TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    headlineLargeEmphasized = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    headlineMediumEmphasized = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    headlineSmallEmphasized = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    titleLargeEmphasized = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    titleMediumEmphasized = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    titleSmallEmphasized = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    bodyLargeEmphasized = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    bodyMediumEmphasized = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = ZeroSpacing),
    bodySmallEmphasized = TextStyle(fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = ZeroSpacing),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    labelLargeEmphasized = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = ZeroSpacing),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    labelMediumEmphasized = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = ZeroSpacing),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = ZeroSpacing),
    labelSmallEmphasized = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = ZeroSpacing)
)

/* ---------------- 阅读器配色（刻意独立于 M3） ---------------- */

/**
 * 五套阅读主题是**手工调校的常量**，不从 M3 角色派生，理由有三：
 * 1. 优化目标不同。M3 的 onSurface/surface 追求 UI 极限可读性（14~16:1），
 *    长文阅读则刻意退到 8~12:1；把书页接到 onSurface 上只会更刺眼。
 * 2. 动态取色会渗进正文。壁纸是橙色时，书页不该跟着变橙 —— 正文是内容，不是外壳。
 * 3.「纸墨」与「护眼」本就是纸张模拟，任何色调调色板都生不出这两个色。
 *
 * 但原来的取值有两个实测缺陷，已一并修掉：
 * - `dim`（页脚、章号等次要文字）五套全部不满足 WCAG AA：实测 3.25 / 2.03 / 2.48 / 2.23 / 2.25，
 *   提高 alpha 后依次为 4.95 / 4.75 / 4.75 / 4.76 / 4.79，全部达标。
 * - 「纸墨」的朗读高亮文字 3.93:1 不达标（高亮标的是正在朗读的句子，属正文级别），
 *   hlText 由 #8A5A00 改为 #5C3A00，实测 6.75:1。
 *
 * 另外三套非纸张模拟主题（暗夜/纯黑/明亮）的高亮改用新调色板的 primary 系色，
 * 让朗读高亮也带上新的品牌识别；纸墨与护眼保留原本congruent 的琥珀/绿色。
 */
@Immutable
data class ReaderTheme(
    val id: String,
    val label: String,
    val bg: Color,
    val text: Color,
    val dim: Color,
    val hl: Color,
    val hlText: Color,
    /**
     * 阅读器外壳（底栏状态行、进度条、睡眠倒计时）的强调色。
     *
     * **不能用 app 的 `echo.accent`**：应用配色跟随系统深浅色，而阅读主题是独立的用户选择，
     * 二者会错配。最常见的一种：浅色系统 + 默认的「暗夜」阅读主题 —— 深蓝的 app accent 落在
     * 深色底栏上只有 2.8:1，「正在朗读」的进度条几乎看不见。
     */
    val accent: Color,
    val isDark: Boolean
)

val READER_THEMES = listOf(
    // accent 一列（倒数第二个）是底栏强调色，取值保证在该主题对应的底栏底色上都 ≥ 6:1
    ReaderTheme("dark", "暗夜", Color(0xFF0B0E14), Color(0xFFC9CDD8), Color(0xFFC9CDD8).copy(alpha = 0.60f), Color(0xFF344479).copy(alpha = 0.55f), Color(0xFFDBE1FF), Color(0xFFB5C4FF), true),
    ReaderTheme("ink", "纯黑", Color(0xFF000000), Color(0xFF9AA0AE), Color(0xFF9AA0AE).copy(alpha = 0.75f), Color(0xFF344479).copy(alpha = 0.60f), Color(0xFFDBE1FF), Color(0xFFB5C4FF), true),
    ReaderTheme("light", "明亮", Color(0xFFF7F5F0), Color(0xFF35322C), Color(0xFF35322C).copy(alpha = 0.70f), Color(0xFFB5C4FF).copy(alpha = 0.55f), Color(0xFF1C2D61), Color(0xFF4C5C92), false),
    ReaderTheme("paper", "纸墨", Color(0xFFF2E8D5), Color(0xFF4A3F2F), Color(0xFF4A3F2F).copy(alpha = 0.78f), Color(0xFFB07C2C).copy(alpha = 0.22f), Color(0xFF5C3A00), Color(0xFF5C3A00), false),
    ReaderTheme("eye", "护眼", Color(0xFFDCE8DD), Color(0xFF2F4234), Color(0xFF2F4234).copy(alpha = 0.78f), Color(0xFF2E7D32).copy(alpha = 0.20f), Color(0xFF1B5E20), Color(0xFF1B5E20), false)
)

/**
 * 警示色（实验版角标、自愈重试提示）。固定的琥珀 #FBBF24 在浅色卡片上只有 1.44:1，
 * 按明暗给两个值：深色仍用琥珀，浅色换成同色相的深琥珀（在 surfaceContainer 上 5.1:1）。
 */
fun warningColor(dark: Boolean): Color = if (dark) Color(0xFFFBBF24) else Color(0xFF8A5A00)

/**
 * 错误色（阅读器底栏的失败状态行、进度条）。与 [warningColor] 同理，不能用 app 的 `echo.danger`：
 * 应用配色跟随系统深浅，而阅读主题是独立选择，浅色系统 + 暗夜主题会把浅红落在深底上。
 * 两个取值都在对应底栏底色上 ≥ 4.5:1。
 */
fun dangerColor(dark: Boolean): Color = if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)

fun readerThemeOf(id: String): ReaderTheme = READER_THEMES.firstOrNull { it.id == id } ?: READER_THEMES[0]

/* ---------------- 主题入口 ---------------- */

/**
 * 主题入口。配色的取得顺序与原生安卓一致：
 *
 * 1. **动态取色**（[dynamic] 且 Android 12+）：系统已从壁纸算好，直接取平台资源；
 * 2. **按种子色现算**：跑 Google 的 material-color-utilities，按 [seed] × [style] × [contrast]
 *    铺开全部 48 个角色 —— 这与系统内部做的事是同一套算法，只是种子色由用户选。
 *
 * 不再保留写死的浅色/深色两套常量：写死意味着只能有一套配色，
 * 而「可切换的配色系列」要的正是同一算法在不同种子与风格下的产物。
 */
@Composable
fun EchoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,
    seed: Color = Color(ReaderSettings.DEFAULT_SEED),
    style: ColorStyle = ColorStyle.TONAL_SPOT,
    contrast: Float = 0f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDynamic = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // dynamicXxxColorScheme() 每次调用都要解析约 48 个平台颜色资源，必须 remember
    val dynamicScheme = remember(dark, useDynamic, context) {
        if (useDynamic) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }
    val generated = rememberGeneratedScheme(seed, style, dark, contrast)
    val scheme = dynamicScheme ?: generated
    val colors = remember(scheme, dark) { echoColorsFrom(scheme, dark) }
    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = EchoMotionScheme,
            shapes = EchoShapes,
            typography = EchoTypography,
            content = content
        )
    }
}

val echo: EchoColors
    @Composable get() = LocalEchoColors.current
