package app.echoread.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 线性图标集：与网页版 SVG 路径同源（24×24 viewBox），描边 1.8 圆头 */
object EchoIcons {
    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    private fun fill(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) b.addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Back: ImageVector by lazy { stroke("back", 2f, "m15 18-6-6 6-6") }
    val ChevronRight: ImageVector by lazy { stroke("chevronRight", 2f, "m9 18 6-6-6-6") }
    val Toc: ImageVector by lazy { stroke("toc", 1.8f, "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01") }
    val TextStyle: ImageVector by lazy { stroke("textStyle", 1.8f, "M4 7V5h16v2M9 20h6M12 5v15") }
    val Waves: ImageVector by lazy { stroke("waves", 1.8f, "M12 3v18M7 8v8M17 8v8M2 11v2M22 11v2") }
    val Moon: ImageVector by lazy { stroke("moon", 1.8f, "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z") }
    val Plus: ImageVector by lazy { stroke("plus", 2.2f, "M12 5v14M5 12h14") }
    val Close: ImageVector by lazy { stroke("close", 2f, "M18 6 6 18M6 6l12 12") }
    val Search: ImageVector by lazy { stroke("search", 2f, "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0z", "m20 20-3-3") }
    val Help: ImageVector by lazy { stroke("help", 1.8f, "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2-3 4", "M12 17h.01") }
    val Key: ImageVector by lazy { stroke("key", 2f, "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4") }
    val Book: ImageVector by lazy { stroke("book", 1.6f, "M4 19.5A2.5 2.5 0 0 1 6.5 17H20", "M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z") }
    val Check: ImageVector by lazy { stroke("check", 2.2f, "m5 12 5 5L20 7") }
    val Trash: ImageVector by lazy { stroke("trash", 1.8f, "M3 6h18", "M8 6V4h8v2", "M19 6l-1 14H6L5 6", "M10 11v6M14 11v6") }
    val Settings: ImageVector by lazy {
        stroke(
            "settings", 1.8f,
            "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
        )
    }
    val Play: ImageVector by lazy { fill("play", "M8 5.5v13l11-6.5z") }
    val Pause: ImageVector by lazy { fill("pause", "M6 5h4v14H6zM14 5h4v14h-4z") }
    val SkipPrev: ImageVector by lazy { fill("skipPrev", "M6 6h2v12H6zm3.5 6 8.5 6V6z") }
    val SkipNext: ImageVector by lazy { fill("skipNext", "M16 6h2v12h-2zM6 18l8.5-6L6 6z") }
    val PlaySmall: ImageVector by lazy { fill("playSmall", "M8 5v14l11-7z") }
}
