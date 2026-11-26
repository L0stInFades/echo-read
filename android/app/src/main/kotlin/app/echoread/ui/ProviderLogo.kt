package app.echoread.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 服务商标志（0.7.0）：目录里的一段路径数据（[app.echoread.core.ProviderSpec.logo]）→ 一枚矢量图标。
 *
 * 标志写在目录里那一家自己的条目上，接入一家服务商仍然只改一个文件；整份目录的标志约 13 KB 文本，
 * 不带图片资源，也不引入 SVG 库或图标库 —— 解析用的是 Compose 自带的路径解析器。
 */
internal object ProviderLogos {
    /** 解析一次就留着：服务列表滚动时条目反复进出组合，不该每次重新解析几 KB 的路径 */
    private val cache = HashMap<String, ImageVector?>()

    /** 画不出来（空串、路径数据有错）返回 null，由调用方退回首字母头像 */
    fun vector(logo: String): ImageVector? {
        if (logo.isBlank()) return null
        return synchronized(cache) { cache.getOrPut(logo) { runCatching { build(logo) }.getOrNull() } }
    }

    /** `[不透明度@]路径|[不透明度@]路径…` → 24×24 视口、奇偶填充的矢量图。深浅层次靠每条路径自己的不透明度 */
    fun build(logo: String): ImageVector {
        val b = ImageVector.Builder(name = "logo", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (part in logo.split('|')) {
            val nodes = PathParser().parsePathString(part.substringAfter('@')).toNodes()
            require(nodes.isNotEmpty()) { "empty path" }
            b.addPath(
                pathData = nodes,
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
                fillAlpha = if ('@' in part) part.substringBefore('@').toFloat().coerceIn(0f, 1f) else 1f
            )
        }
        return b.build()
    }
}

/**
 * 品牌色的圆角方块上放标志：与首字母头像（[Monogram]）同一个外形，只是里面换成了图形。
 * [scale] 是 LobeHub 给圆形头像定的比例；圆角方块的四条直边离标志更近，所以统一再收一成。
 */
@Composable
internal fun LogoTile(logo: ImageVector, tile: Color, scale: Float, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(tile, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        // 装饰性：服务的名字就写在头像旁边，读屏不必再念一遍
        Icon(
            logo, null,
            tint = if (tile.luminanceApprox() > 0.6f) Color(0xFF111111) else Color.White,
            modifier = Modifier.size(size * (scale.coerceIn(0.2f, 1f) * 0.9f))
        )
    }
}
