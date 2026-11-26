package app.echoread.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import app.echoread.core.PlayerState
import app.echoread.core.Range
import app.echoread.tts.EngineSnapshot
import app.echoread.ui.ReaderTheme
import app.echoread.ui.motion.MotionDriver
import kotlin.math.abs

/**
 * 页面画布里的内容：三槽位的书页，加上跨章 / 重排版时淡出的旧页。直接摆进调用方的 Box（自己不另起布局节点）。
 *
 * 朗读高亮要读引擎快照 —— 快照每段都要变好几次，放在这里读，变化只重组这几页，不牵动整个阅读页。
 */
@Composable
internal fun ReaderPages(
    bookId: String,
    snapshot: State<EngineSnapshot>,
    pager: ReaderPager,
    window: ChapterWindow,
    driver: MotionDriver,
    verticalPaging: Boolean,
    theme: ReaderTheme,
    skeletonLine: Float,
) {
    val snap = snapshot.value
    val engineChapter = if (snap.isOn(bookId)) snap.chapterIndex else -1
    val activeSeg: Range? = snap.segmentOn(bookId)
    val synthesizing = snap.bookId == bookId && snap.state != PlayerState.IDLE && snap.synthesizing

    fun segFor(ref: PageRef): Range? = if (ref.chapter == engineChapter) activeSeg else null

    val anchorPages = window.pagesOf(pager.anchor.chapter)
    if (anchorPages == null) {
        PageSkeleton(theme.text, skeletonLine, Modifier.fillMaxSize())
    } else {
        // 三槽位：上一页 / 当前页 / 下一页，各自一个 RenderNode，位移只写 graphicsLayer。
        // 静止时左右两页 alpha=0，HWUI 直接跳过，正文只画一份。
        for (slot in -1..1) {
            val ref = (if (slot == 0) pager.anchor else window.resolve(pager.anchor, slot)) ?: continue
            val pg = window.pagesOf(ref.chapter) ?: continue
            if (ref.page >= pg.pageCount) continue
            key(ref.chapter, ref.page) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val v = driver.value
                            // 两个轴都显式赋值：换方向时另一轴必须归零，不能依赖图层属性的复位时机
                            val off = slot - v
                            translationX = if (verticalPaging) 0f else off * size.width
                            translationY = if (verticalPaging) off * size.height else 0f
                            alpha = when {
                                slot == 0 -> if (pager.outgoing != null) pager.fade.value else 1f
                                abs(v) > 0.0005f -> 1f
                                else -> 0f
                            }
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                ) {
                    PageCanvas(pg, ref.page, segFor(ref), synthesizing, theme, Modifier.fillMaxSize())
                }
            }
        }
    }
    // 跨章 / 重排版时淡出的旧页：新页排好之前屏幕上一直有内容
    pager.outgoing?.let { old ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - pager.fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        ) {
            PageCanvas(old.first, old.second.coerceIn(0, old.first.pageCount - 1), null, false, theme, Modifier.fillMaxSize())
        }
    }
}

/** 文字层录制指纹：只在页 / 章 / 文字色 / 画布尺寸变化时重录，draw 期比较不分配 */
private class PageRecordKey {
    private var pages: ChapterPages? = null
    private var page = -1
    private var color = Color.Unspecified
    private var size = Size.Zero
    fun matches(p: ChapterPages, pg: Int, c: Color, s: Size) = pages === p && page == pg && color == c && size == s
    fun set(p: ChapterPages, pg: Int, c: Color, s: Size) { pages = p; page = pg; color = c; size = s }
}

/**
 * 单页绘制：高亮按行绘制（跳过段间空白行，行尾以可见文字为界，不重排文本），正文文字走「Picture 缓存」——
 * 整章 drawText 只在 (页, 章, 文字色, 尺寸) 变化时录制一次进 [GraphicsLayer]（HWUI RenderNode 显示列表），
 * 之后每段高亮切换、每帧拖动都只是「重放显示列表 + 几个圆角矩形」，不再重新遍历整章段落生成绘制指令。
 * 这就是 iOS CALayer 的 shouldRasterize 思路，只是保留矢量显示列表而非位图，无采样模糊、零显存。
 */
@Composable
private fun PageCanvas(pages: ChapterPages, page: Int, active: Range?, synthesizing: Boolean, theme: ReaderTheme, modifier: Modifier) {
    val top = pages.pageTop(page)
    val range = pages.pages[page]
    val layout = pages.layout
    // 高亮矩形连内缩量一起算好：draw lambda 里零分配
    val hlRects = remember(pages, page, active) {
        if (active == null) emptyList() else {
            val rs = pages.toRendered(active.start)
            val re = pages.toRendered(active.end) // 章节 end 为开区间，映射后仍为开区间
            val out = ArrayList<Rect>()
            val firstLine = maxOf(layout.getLineForOffset(rs), range.first)
            val lastLine = minOf(layout.getLineForOffset(maxOf(re - 1, rs)), range.last)
            for (line in firstLine..lastLine) {
                val ls = layout.getLineStart(line)
                val le = layout.getLineEnd(line, visibleEnd = true)
                if (le <= ls) continue // 段间占位 / 空白行
                val s = maxOf(rs, ls)
                val e = minOf(re, le)
                if (s >= e) continue
                // 起点用字符的真实水平位置（首行缩进不涂色）；终点到行尾时取行右边界
                val left = layout.getHorizontalPosition(s, usePrimaryDirection = true)
                val right = if (e >= le) layout.getLineRight(line) else layout.getHorizontalPosition(e, usePrimaryDirection = true)
                if (right > left) out.add(Rect(left - 2f, layout.getLineTop(line) + 2f, right + 2f, layout.getLineBottom(line) - 2f))
            }
            out
        }
    }
    // 合成中不再用 infiniteRepeatable：60fps 常驻动画在听书场景（动辄数小时）纯属耗电，
    // 且 .value 在组合期读会让整页每帧重组、每帧重新提交整章 drawText。改为静态压暗。
    val hlColor = theme.hl.copy(alpha = (theme.hl.alpha * if (synthesizing) 1.0f else 1.6f).coerceAtMost(1f))
    val bottom = layout.getLineBottom(range.last)
    val textColor = theme.text
    val textLayer = rememberGraphicsLayer()
    val recorded = remember(textLayer) { PageRecordKey() }
    Spacer(
        modifier.clipToBounds().drawBehind {
            // 整章布局只画本页：裁剪到「本页最后一行底边」，下一页的行绝不漏出（画布余量只留白）
            val clipBottom = minOf(bottom - top, size.height)
            if (!recorded.matches(pages, page, textColor, size)) {
                textLayer.record(this, layoutDirection, size.toIntSize()) {
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = clipBottom) {
                        translate(top = -top) { drawText(layout, color = textColor) }
                    }
                }
                recorded.set(pages, page, textColor, size)
            }
            if (hlRects.isNotEmpty()) {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = clipBottom) {
                    translate(top = -top) {
                        for (r in hlRects) {
                            drawRoundRect(hlColor, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(6f, 6f))
                        }
                    }
                }
            }
            drawLayer(textLayer)
        }
    )
}

/** 首次进入（没有任何旧页可留）时的骨架：从「页面消失了」变成「页面正在成形」，绝不居中转圈 */
@Composable
private fun PageSkeleton(textColor: Color, lineHeightPx: Float, modifier: Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(300, easing = LinearEasing)) }
    Spacer(
        modifier.drawBehind {
            val lh = lineHeightPx.coerceAtLeast(20f)
            var y = lh * 0.8f
            var i = 0
            while (y + lh * 0.5f < size.height && i < 30) {
                val w = size.width * (if (i % 6 == 5) 0.52f else 0.97f)
                drawRoundRect(
                    textColor,
                    topLeft = Offset(0f, y),
                    size = Size(w, lh * 0.46f),
                    cornerRadius = CornerRadius(3f, 3f),
                    alpha = a.value * 0.10f
                )
                y += lh
                i++
            }
        }
    )
}

/** 2dp 不定进度线：位置只在 draw lambda 里读，不触发重组；仅在排版进行中存在 */
@Composable
internal fun ThinProgressLine(color: Color, modifier: Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            p.snapTo(0f)
            p.animateTo(1f, tween(1100, easing = LinearEasing))
        }
    }
    Spacer(
        modifier.height(2.dp).drawBehind {
            val w = size.width * 0.32f
            drawRect(color, topLeft = Offset((size.width + w) * p.value - w, 0f), size = Size(w, size.height), alpha = 0.9f)
        }
    )
}
