package app.echoread.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.echoread.core.ColorStyle
import app.echoread.ui.motion.EchoMotion
import app.echoread.ui.motion.PressScale
import app.echoread.ui.motion.echoPress

/**
 * 配色选择：种子色 × 风格，与原生安卓「壁纸与个性化」同构。
 *
 * 两个刻意的设计决定，都来自实测而非偏好：
 *
 * 1. **风格色卡必须画多个颜色，不能只画主色。**
 *    深色模式下算法会把 primary 统一拉到高明度档，实测「标准/鲜明/彩虹」三种风格的 primary
 *    完全相同（都是 #B5C4FF），只有容器色与次、三色不同。只画主色的话，深色下会出现
 *    三个一模一样的选项。这条有测试守着（ColorSchemeTest.styleSwatchNeedsMoreThanPrimary）。
 *
 * 2. **色卡用真实生成的配色渲染，而不是画一个种子色圆点。**
 *    用户要选的是「整套配色长什么样」，不是「种子色是什么颜色」——
 *    种子色经过算法后可能面目全非（#7C9BFF 是亮蓝紫，生成的 primary 是深靛蓝 #4C5C92）。
 */

/** 可选的种子色。覆盖色相环，第一个是品牌色 */
val SEED_COLORS: List<Pair<String, Color>> = listOf(
    "默认" to Color(0xFF7C9BFF),
    "赤陶" to Color(0xFFB33B15),
    "苔绿" to Color(0xFF63A002),
    "紫罗兰" to Color(0xFF8C4190),
    "海青" to Color(0xFF007FAC),
    "琥珀" to Color(0xFFE8B931),
    "玫红" to Color(0xFFC2185B),
    "石板" to Color(0xFF4A6572)
)

/**
 * 四分色卡：左上 primary、右上 secondary、左下 tertiary、右下 primaryContainer。
 * 这四个角色合起来能区分全部可选风格（已由测试断言）。
 */
@Composable
fun PaletteSwatch(
    seed: Color,
    style: ColorStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    onClick: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val scheme = rememberGeneratedScheme(seed, style, dark, 0f)
    val ringWidth by animateFloatAsState(
        if (selected) 3f else 0f,
        animationSpec = EchoMotion.Instant.spec(),
        label = "swatchRing"
    )
    val ring = echo.accent
    val border = echo.border
    Box(
        modifier
            .size(size)
            .echoPress(pressedScale = PressScale.Chip, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size)) {
            val d = this.size.minDimension
            val inset = 4.dp.toPx()
            val r = (d - inset * 2) / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            fun quad(color: Color, start: Float) {
                drawArc(
                    color = color, startAngle = start, sweepAngle = 90f, useCenter = true,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2)
                )
            }
            quad(scheme.primary, 180f)            // 左上
            quad(scheme.secondary, 270f)          // 右上
            quad(scheme.primaryContainer, 90f)    // 左下
            quad(scheme.tertiary, 0f)             // 右下
            // 未选中时描一圈细边，避免浅色卡片贴在浅色底上没有边界
            drawCircle(
                color = if (ringWidth > 0f) ring else border,
                radius = r + if (ringWidth > 0f) inset * 0.6f else 0.5f,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = if (ringWidth > 0f) ringWidth.dp.toPx() else 1.dp.toPx()
                )
            )
        }
    }
}

/** 单色圆点，用于种子色一排（种子色本身就是要选的东西，不需要四分） */
@Composable
fun SeedDot(
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val ringWidth by animateFloatAsState(if (selected) 3f else 0f, EchoMotion.Instant.spec(), label = "seedRing")
    val ring = echo.accent
    val border = echo.border
    Box(modifier.size(48.dp).echoPress(pressedScale = PressScale.Chip, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(48.dp)) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = size.minDimension / 2f - 5.dp.toPx()
            drawCircle(color, radius = r, center = Offset(cx, cy))
            drawCircle(
                color = if (ringWidth > 0f) ring else border,
                radius = r + 4.dp.toPx() * if (ringWidth > 0f) 1f else 0.15f,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = if (ringWidth > 0f) ringWidth.dp.toPx() else 1.dp.toPx()
                )
            )
        }
    }
}

/** 一排横向滚动的选项，带标签 */
@Composable
fun LabeledSwatchRow(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit
) {
    // 内缩放在滚动容器**里面**（首尾各加一个 Spacer），最后一项才能完整滚进视野。
    // 放在外面等于把可视窗口缩窄，末项会被永久裁掉一半。
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Spacer(Modifier.width(contentPadding - 6.dp))
        content()
        Spacer(Modifier.width(contentPadding - 6.dp))
    }
}

@Composable
fun SwatchLabel(text: String, selected: Boolean) {
    Text(
        text,
        color = if (selected) echo.accent else echo.text3,
        style = if (selected) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 2.dp)
    )
}
