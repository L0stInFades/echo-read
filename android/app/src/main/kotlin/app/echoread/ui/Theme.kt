package app.echoread.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计语言：HarmonyOS(ArkUI) 的大圆角卡片分组 + One UI 的大标题下沉与拇指可达 + ColorOS 的弹簧动效。
 * 品牌色沿用网页版「暗夜极光」渐变；浅色/深色跟随系统。
 */
@Immutable
data class EchoColors(
    val canvas: Color,
    val card: Color,
    val cardAlt: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val isDark: Boolean
)

val DarkColors = EchoColors(
    canvas = Color(0xFF0A0B0F),
    card = Color(0xFF15171E),
    cardAlt = Color(0xFF1E212B),
    border = Color.White.copy(alpha = 0.08f),
    text = Color(0xFFE9EBF1),
    text2 = Color(0xFFE9EBF1).copy(alpha = 0.6f),
    text3 = Color(0xFFE9EBF1).copy(alpha = 0.38f),
    accent = Color(0xFF7C9BFF),
    accentSoft = Color(0xFF7C9BFF).copy(alpha = 0.18f),
    danger = Color(0xFFFF6B7A),
    isDark = true
)

val LightColors = EchoColors(
    canvas = Color(0xFFF2F3F7),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFF4F5F9),
    border = Color.Black.copy(alpha = 0.06f),
    text = Color(0xFF15171E),
    text2 = Color(0xFF15171E).copy(alpha = 0.58f),
    text3 = Color(0xFF15171E).copy(alpha = 0.4f),
    accent = Color(0xFF5B7CFF),
    accentSoft = Color(0xFF5B7CFF).copy(alpha = 0.14f),
    danger = Color(0xFFE5484D),
    isDark = false
)

/** 极光渐变色标：主按钮、进度、品牌字 */
val AuroraColors: List<Color> = listOf(Color(0xFF7C9BFF), Color(0xFFB47CFF), Color(0xFFFF7CB8))

/**
 * 每个使用点各拿一份实例 —— `ShaderBrush` 内部按「上次创建时的尺寸」缓存 shader，
 * 全局单例被 36sp 标题 / 胶囊按钮 / 2dp 进度条轮流命中时缓存反复失效，每帧重建 LinearGradientShader。
 */
fun auroraBrush(): Brush = Brush.linearGradient(AuroraColors)

@Composable
fun rememberAurora(): Brush = remember { auroraBrush() }

val LocalEchoColors = staticCompositionLocalOf { DarkColors }

object Radius {
    /** Harmony / One UI 的大卡片圆角 */
    val xl: Dp = 28.dp
    val lg: Dp = 20.dp
    val md: Dp = 14.dp
    val sm: Dp = 10.dp
}

/** 阅读器配色主题（与网页版五套一致） */
@Immutable
data class ReaderTheme(
    val id: String,
    val label: String,
    val bg: Color,
    val text: Color,
    val dim: Color,
    val hl: Color,
    val hlText: Color,
    val isDark: Boolean
)

val READER_THEMES = listOf(
    ReaderTheme("dark", "暗夜", Color(0xFF0B0E14), Color(0xFFC9CDD8), Color(0xFFC9CDD8).copy(alpha = 0.45f), Color(0xFF7C9BFF).copy(alpha = 0.22f), Color.White, true),
    ReaderTheme("ink", "纯黑", Color(0xFF000000), Color(0xFF9AA0AE), Color(0xFF9AA0AE).copy(alpha = 0.4f), Color(0xFF7C9BFF).copy(alpha = 0.25f), Color(0xFFDFE6FF), true),
    ReaderTheme("light", "明亮", Color(0xFFF7F5F0), Color(0xFF35322C), Color(0xFF35322C).copy(alpha = 0.45f), Color(0xFF4263EB).copy(alpha = 0.16f), Color(0xFF1B3BD8), false),
    ReaderTheme("paper", "纸墨", Color(0xFFF2E8D5), Color(0xFF4A3F2F), Color(0xFF4A3F2F).copy(alpha = 0.45f), Color(0xFFB07C2C).copy(alpha = 0.22f), Color(0xFF8A5A00), false),
    ReaderTheme("eye", "护眼", Color(0xFFDCE8DD), Color(0xFF2F4234), Color(0xFF2F4234).copy(alpha = 0.45f), Color(0xFF2E7D32).copy(alpha = 0.2f), Color(0xFF1B5E20), false)
)

fun readerThemeOf(id: String): ReaderTheme = READER_THEMES.firstOrNull { it.id == id } ?: READER_THEMES[0]

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color.White, background = colors.canvas, onBackground = colors.text,
            surface = colors.card, onSurface = colors.text, surfaceVariant = colors.cardAlt, onSurfaceVariant = colors.text2,
            outline = colors.border, error = colors.danger
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White, background = colors.canvas, onBackground = colors.text,
            surface = colors.card, onSurface = colors.text, surfaceVariant = colors.cardAlt, onSurfaceVariant = colors.text2,
            outline = colors.border, error = colors.danger
        )
    }
    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val echo: EchoColors
    @Composable get() = LocalEchoColors.current
